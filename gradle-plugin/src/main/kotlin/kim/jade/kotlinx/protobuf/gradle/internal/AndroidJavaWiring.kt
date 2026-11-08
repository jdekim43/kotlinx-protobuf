package kim.jade.kotlinx.protobuf.gradle.internal

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Component
import com.android.build.api.variant.HasUnitTest
import kim.jade.kotlinx.protobuf.gradle.task.ExposeProtocOutputTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/**
 * Feeds Java protoc output to AGP's compilations.
 *
 * Kept apart from the plugin class so AGP's API is only loaded in projects that apply AGP. Compiled
 * against the 8.x API, which `com.android.library`, `com.android.application` and
 * `com.android.kotlin.multiplatform.library` all expose through [AndroidComponentsExtension], in 8.x and
 * 9.x alike.
 */
internal object AndroidJavaWiring {

    /**
     * @param wired whether the output goes to Java at all; final by the time AGP creates variants.
     * @param includedInMain whether the Android main compilation includes the proto source set. Not final
     *   when AGP creates variants, so it is only read lazily.
     * @param warnWithoutJava whether a component that compiles no Java deserves a warning.
     */
    fun attach(
        project: Project,
        description: String,
        taskNamePrefix: String,
        directory: Provider<Directory>,
        wired: () -> Boolean,
        includedInMain: () -> Boolean,
        includedInUnitTest: () -> Boolean,
        warnWithoutJava: () -> Boolean,
    ) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().all()) { variant ->
            if (!wired()) return@onVariants

            fun expose(component: Component, included: () -> Boolean) {
                val java = component.sources.java
                if (java == null) {
                    // The KMP library plugin compiles no Java until the build calls withJava().
                    if (warnWithoutJava()) project.whenConfigured {
                        if (included()) project.logger.warn(
                            "kotlinx-protobuf: $description is Java, but the Android '${component.name}' " +
                                "compilation has no Java sources. Call withJava() on the Android target to " +
                                "compile it."
                        )
                    }
                    return
                }

                val task = project.tasks.register(
                    "$taskNamePrefix${component.name.replaceFirstChar { it.uppercase() }}",
                    ExposeProtocOutputTask::class.java,
                ) { it.source.from(project.onlyWhen(included, directory)) }
                java.addGeneratedSourceDirectory(task, ExposeProtocOutputTask::outputDirectory)
            }

            expose(variant, includedInMain)
            (variant as? HasUnitTest)?.unitTest?.let { expose(it, includedInUnitTest) }
        }
    }
}
