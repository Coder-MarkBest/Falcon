package com.falcon.ipc.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FalconManifestInjectorTest {

    private val base = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:label="x" />
        </manifest>
    """.trimIndent()

    @Test
    fun `empty packages leaves manifest unchanged`() {
        assertEquals(base, FalconManifestInjector.inject(base, emptyList()))
    }

    @Test
    fun `creates a queries block with sorted package entries`() {
        val out = FalconManifestInjector.inject(base, listOf("com.b", "com.a"))
        assertTrue(out.contains("<queries>"))
        assertTrue(out.contains("<package android:name=\"com.a\" />"))
        assertTrue(out.contains("<package android:name=\"com.b\" />"))
        // sorted: com.a appears before com.b
        assertTrue(out.indexOf("com.a") < out.indexOf("com.b"))
        // inserted before </manifest>
        assertTrue(out.indexOf("<queries>") < out.lastIndexOf("</manifest>"))
    }

    @Test
    fun `adds entries inside an existing queries element`() {
        val withQueries = base.replace(
            "<application android:label=\"x\" />",
            "<queries>\n    <package android:name=\"com.existing\" />\n</queries>\n    <application android:label=\"x\" />"
        )
        val out = FalconManifestInjector.inject(withQueries, listOf("com.new"))
        // exactly one <queries> element (no duplicate block)
        assertEquals(1, Regex("<queries>").findAll(out).count())
        assertTrue(out.contains("<package android:name=\"com.new\" />"))
        assertTrue(out.contains("<package android:name=\"com.existing\" />"))
    }

    @Test
    fun `does not duplicate an already-declared package`() {
        val out = FalconManifestInjector.inject(base, listOf("com.a"))
        val out2 = FalconManifestInjector.inject(out, listOf("com.a"))
        assertEquals(1, Regex("android:name=\"com\\.a\"").findAll(out2).count())
    }
}
