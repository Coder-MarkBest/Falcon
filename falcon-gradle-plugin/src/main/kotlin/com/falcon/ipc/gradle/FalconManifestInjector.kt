package com.falcon.ipc.gradle

/**
 * Pure manifest transformation: inject `<package android:name="…"/>` entries for the
 * configured Falcon peer packages into a merged AndroidManifest's `<queries>` element.
 *
 * Kept side-effect-free so it can be unit-tested without a Gradle/AGP build.
 */
object FalconManifestInjector {

    /**
     * Returns [manifestXml] with the peer [packages] declared under `<queries>`:
     * - if a `<queries>` element already exists, the `<package>` entries are added inside it
     *   (skipping any already present);
     * - otherwise a new `<queries>` block is inserted before `</manifest>`.
     *
     * Returns the input unchanged when [packages] is empty or no `</manifest>` is found.
     */
    fun inject(manifestXml: String, packages: Collection<String>): String {
        if (packages.isEmpty()) return manifestXml
        val wanted = packages.toSortedSet()
        // Don't re-add packages already declared anywhere in the manifest.
        val missing = wanted.filterNot { manifestXml.contains("android:name=\"$it\"") }
        if (missing.isEmpty()) return manifestXml

        val entries = missing.joinToString("\n") { "    <package android:name=\"$it\" />" }

        val openIdx = manifestXml.indexOf("<queries>")
        if (openIdx >= 0) {
            val insertAt = openIdx + "<queries>".length
            return manifestXml.substring(0, insertAt) + "\n" + entries + manifestXml.substring(insertAt)
        }

        val closeIdx = manifestXml.lastIndexOf("</manifest>")
        if (closeIdx < 0) return manifestXml
        val block = "<queries>\n$entries\n</queries>\n"
        return manifestXml.substring(0, closeIdx) + block + manifestXml.substring(closeIdx)
    }
}
