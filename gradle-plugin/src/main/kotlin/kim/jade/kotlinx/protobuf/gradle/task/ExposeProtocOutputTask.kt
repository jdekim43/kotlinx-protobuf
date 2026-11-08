package kim.jade.kotlinx.protobuf.gradle.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * Mirrors a protoc output into a directory AGP owns.
 *
 * AGP only tracks the producer of a generated source directory registered through
 * `addGeneratedSourceDirectory`, which takes a task and one of its `DirectoryProperty` outputs. The protoc
 * task has no such property per output — every output is a subdirectory of one `protocOutputDirectory`,
 * so handing that over would make the Kotlin and plugin outputs Android Java sources too. And whether a
 * variant includes the source set at all is only known after configuration, when the Kotlin hierarchy is
 * complete, which a task's file input can wait for and a registration made in `onVariants` cannot.
 *
 * A directory added through the legacy `android.sourceSets` DSL loses the producer instead, so tasks such
 * as KSP read it before protoc ran.
 */
@DisableCachingByDefault(because = "Copying files is cheaper than caching them")
abstract class ExposeProtocOutputTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    protected abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun expose() {
        fileSystem.sync {
            it.from(source)
            it.into(outputDirectory)
        }
    }
}
