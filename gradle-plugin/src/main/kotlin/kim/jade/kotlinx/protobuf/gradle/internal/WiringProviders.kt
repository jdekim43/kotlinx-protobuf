package kim.jade.kotlinx.protobuf.gradle.internal

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/**
 * Where an output that is not wired somewhere points instead.
 *
 * `srcDir` has to receive a single directory: KGP and AGP turn each entry into `fileTree(entry)`, so a
 * provider of an empty list fails. A directory that never exists is what every source set finds empty.
 */
internal fun Project.unwiredDirectory(): Provider<Directory> =
    layout.buildDirectory.dir("kotlinx-protobuf/unwired")

/**
 * [directory] while [included] holds, otherwise [unwiredDirectory]. flatMap keeps [directory]'s producer,
 * so whatever consumes the result still depends on the task that generates it.
 */
internal fun Project.onlyWhen(included: () -> Boolean, directory: Provider<Directory>): Provider<Directory> =
    provider(included).flatMap { if (it) directory else unwiredDirectory() }

/**
 * Runs [action] once every project is configured: the DSL, AGP's variants and the Kotlin source set
 * hierarchy — which KGP fills in after AGP has created its variants — are all final by then.
 *
 * Not Project#afterEvaluate: the outputs are created from inside lazy configuration blocks such as
 * `sourceSets.named("main") { … }`, where Gradle forbids that call.
 */
internal fun Project.whenConfigured(action: () -> Unit) {
    try {
        gradle.projectsEvaluated { action() }
    } catch (_: RuntimeException) {
        // Only reached for an output created after configuration finished, and the callers only warn.
    }
}
