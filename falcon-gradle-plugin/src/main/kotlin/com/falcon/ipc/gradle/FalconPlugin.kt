package com.falcon.ipc.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Gradle plugin that injects `<queries>` entries for the configured Falcon peer packages
 * into the merged AndroidManifest, so integrators don't hand-maintain `<queries>`.
 *
 * Apply in an Android application module:
 * ```
 * plugins {
 *     id("com.android.application")
 *     id("com.falcon.ipc.falcon-gradle")
 * }
 * falcon { peerPackages("com.oem.nav", "com.oem.media") }
 * ```
 */
class FalconPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("falcon", FalconExtension::class.java)

        val androidComponents =
            project.extensions.findByType(AndroidComponentsExtension::class.java)
        if (androidComponents == null) {
            project.logger.warn(
                "Falcon: com.falcon.ipc.falcon-gradle applied without the Android Application " +
                    "plugin — <queries> injection is disabled."
            )
            return
        }

        androidComponents.onVariants { variant ->
            val taskName = "falconInjectQueries" + variant.name.replaceFirstChar { it.uppercase() }
            val task = project.tasks.register(taskName, FalconQueriesManifestTask::class.java) {
                it.peerPackages.set(ext.peerPackages)
            }
            variant.artifacts
                .use(task)
                .wiredWithFiles(
                    FalconQueriesManifestTask::mergedManifest,
                    FalconQueriesManifestTask::updatedManifest
                )
                .toTransform(SingleArtifact.MERGED_MANIFEST)
        }
    }
}

/** Transforms MERGED_MANIFEST by injecting peer-package `<queries>` entries. */
abstract class FalconQueriesManifestTask : DefaultTask() {

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val peerPackages: SetProperty<String>

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    @TaskAction
    fun transform() {
        val xml = mergedManifest.get().asFile.readText()
        val out = FalconManifestInjector.inject(xml, peerPackages.get())
        updatedManifest.get().asFile.writeText(out)
    }
}
