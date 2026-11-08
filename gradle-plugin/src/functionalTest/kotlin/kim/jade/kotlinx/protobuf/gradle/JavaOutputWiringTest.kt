package kim.jade.kotlinx.protobuf.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Where Java protoc output ends up: which compilations get it, and what the build is told when none can.
 *
 * The Android cases need an SDK, so they only run where ANDROID_HOME points at one — as it does on the
 * GitHub-hosted Ubuntu runners.
 */
class JavaOutputWiringTest : StringSpec({

    val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")

    /** A Kotlin file that only compiles if kotlinc can see the classes protoc-gen-java emitted. */
    val usesJavaClasses = """
        package demo

        fun hello(): ByteArray = demo.v1.Greeter.HelloRequest.newBuilder().setName("x").build().toByteArray()
    """.trimIndent()

    fun TestProject.withGoogleRepository() = write(
        "settings.gradle.kts",
        """
        pluginManagement {
            repositories {
                mavenLocal()
                google()
                mavenCentral()
                gradlePluginPortal()
            }
        }
        dependencyResolutionManagement {
            repositories {
                mavenLocal()
                google()
                mavenCentral()
            }
        }
        rootProject.name = "test-project"
        """.trimIndent(),
    )

    fun TestProject.withAndroidSdk() {
        withGoogleRepository()
        write("local.properties", "sdk.dir=${androidSdk!!.replace("\\", "\\\\")}")
    }

    fun multiplatformProject(targets: String, javaBuiltin: String = """builtin("java")"""): TestProject =
        TestProject(tempdir()).apply {
            write(
                "build.gradle.kts",
                """
                import kim.jade.kotlinx.protobuf.gradle.proto

                plugins {
                    kotlin("multiplatform") version "2.4.10"
                    id("kim.jade.kotlinx-protobuf") version "$pluginVersion"
                }
                kotlin {
                    jvmToolchain(17)
                    $targets

                    sourceSets {
                        commonMain {
                            proto {
                                $javaBuiltin
                            }
                        }
                    }
                }
                """.trimIndent(),
            )
            write("src/commonMain/proto/demo/v1/greeter.proto", TestProject.GREETER_PROTO)
        }

    "honours the wireTo a builtin block sets" {
        // builtin("java") { … } creates the spec before running the block, and the wiring used to be
        // decided on creation — with the JAVA convention, whatever the block said.
        val project = TestProject(tempdir()).apply {
            write(
                "build.gradle.kts",
                """
                import kim.jade.kotlinx.protobuf.gradle.proto

                plugins {
                    kotlin("jvm") version "2.4.10"
                    id("kim.jade.kotlinx-protobuf") version "$pluginVersion"
                }
                kotlin { jvmToolchain(17) }
                dependencies {
                    implementation("com.google.protobuf:protobuf-java:4.35.1")
                }
                kotlin.sourceSets.named("main") {
                    proto {
                        builtin("java") { wireToNothing() }
                    }
                }
                """.trimIndent(),
            )
            write("src/main/proto/demo/v1/greeter.proto", TestProject.GREETER_PROTO)
        }

        // generateProto by name: with the output wired nowhere, nothing in `build` depends on it any more.
        project.build("generateProto", "build")

        project.file("build/generated/sources/kotlinx-protobuf-protoc/main/java/demo/v1/Greeter.java").shouldExist()
        project.file("build/classes/java/main/demo/v1/Greeter.class").shouldNotExist()
    }

    "compiles commonMain's Java output for the JVM target and keeps it away from JS" {
        val project = multiplatformProject(
            """
            jvm()
                js { nodejs() }

                sourceSets.jvmMain.dependencies {
                    implementation("com.google.protobuf:protobuf-java:4.35.1")
                }
            """.trimIndent(),
        )
        project.write("src/jvmMain/kotlin/demo/Hello.kt", usesJavaClasses)

        val result = project.build("jvmJar", "compileKotlinJs", "--configuration-cache")

        project.file("build/classes/java/jvmMain/demo/v1/Greeter.class").shouldExist()
        result.output shouldNotContain "kotlinx-protobuf:"
        // And a second run reuses the cache: every decision the wiring makes survives serialization.
        project.build("jvmJar", "compileKotlinJs", "--configuration-cache")
            .output shouldContain "Reusing configuration cache"
    }

    "warns about an explicit Java output nothing can compile, unless told not to wire it" {
        val jsOnly = multiplatformProject("js { nodejs() }", javaBuiltin = """builtin("java") { wireToJava() }""")

        jsOnly.build("generateProto").output shouldContain
            "the 'java' protoc output for 'commonMain' is Java, but no compilation of this project can compile Java"

        val declined = multiplatformProject("js { nodejs() }", javaBuiltin = """builtin("java") { wireToNothing() }""")

        declined.build("generateProto").output shouldNotContain "kotlinx-protobuf:"
    }

    "compiles commonMain's Java output for androidTarget()".config(enabled = androidSdk != null) {
        val project = TestProject(tempdir()).apply {
            // AGP 8 uses a Gradle internal that 9.6 removed, and AGP 9 no longer takes androidTarget().
            gradleVersion = "9.5.1"
            withAndroidSdk()
            write(
                "build.gradle.kts",
                """
                import kim.jade.kotlinx.protobuf.gradle.proto

                plugins {
                    kotlin("multiplatform") version "2.4.10"
                    id("com.android.library") version "8.13.2"
                    id("kim.jade.kotlinx-protobuf") version "$pluginVersion"
                }
                kotlin {
                    jvmToolchain(17)
                    androidTarget()
                    jvm()

                    sourceSets {
                        commonMain {
                            proto {
                                builtin("java") { option("lite") }
                            }
                            dependencies {
                                implementation("com.google.protobuf:protobuf-javalite:4.35.1")
                            }
                        }
                    }
                }
                android {
                    namespace = "demo"
                    compileSdk = 35
                    defaultConfig { minSdk = 24 }
                }
                """.trimIndent(),
            )
            write("src/commonMain/proto/demo/v1/greeter.proto", TestProject.GREETER_PROTO)
            write("src/androidMain/kotlin/demo/Hello.kt", usesJavaClasses)
            write("src/jvmMain/kotlin/demo/Hello.kt", usesJavaClasses)
        }

        // Gradle fails any task that reads the generated directory without depending on its producer, so
        // these passing also shows the generate task is wired in, not merely lucky with ordering.
        val result = project.build("compileDebugJavaWithJavac", "compileReleaseJavaWithJavac", "jvmJar")

        project.file(
            "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/demo/v1/Greeter.class"
        ).shouldExist()
        project.file("build/classes/java/jvmMain/demo/v1/Greeter.class").shouldExist()
        result.output shouldNotContain "kotlinx-protobuf:"
    }

    "compiles commonMain's Java output under the KMP Android library plugin".config(enabled = androidSdk != null) {
        fun project(withJava: Boolean) = TestProject(tempdir()).apply {
            withAndroidSdk()
            write(
                "build.gradle.kts",
                """
                import kim.jade.kotlinx.protobuf.gradle.proto

                plugins {
                    kotlin("multiplatform") version "2.4.10"
                    id("com.android.kotlin.multiplatform.library") version "9.3.1"
                    id("kim.jade.kotlinx-protobuf") version "$pluginVersion"
                }
                kotlin {
                    jvmToolchain(17)
                    android {
                        namespace = "demo"
                        compileSdk = 35
                        minSdk = 24
                        ${if (withJava) "withJava()" else ""}
                    }

                    sourceSets {
                        commonMain {
                            proto {
                                builtin("java") {
                                    option("lite")
                                    wireToJava()
                                }
                            }
                            dependencies {
                                implementation("com.google.protobuf:protobuf-javalite:4.35.1")
                            }
                        }
                    }
                }
                """.trimIndent(),
            )
            write("src/commonMain/proto/demo/v1/greeter.proto", TestProject.GREETER_PROTO)
        }

        // Without withJava() the plugin compiles no Java at all, which is worth saying out loud.
        project(withJava = false).build("help").output shouldContain "Call withJava() on the Android target"

        val withJava = project(withJava = true)
        withJava.write("src/androidMain/kotlin/demo/Hello.kt", usesJavaClasses)

        val result = withJava.build("assemble")

        result.output shouldNotContain "kotlinx-protobuf:"
        withJava.file("build/intermediates").walkTopDown()
            .any { it.path.endsWith("demo/v1/Greeter.class") } shouldBe true
    }
})
