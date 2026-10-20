package com.firesms.app.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StableVersionTest {
    private fun version(value: String) = assertNotNull(StableVersion.parse(value))

    @Test
    fun `compares each numeric component rather than text`() {
        assertTrue(version("1.10.0") > version("1.9.99"))
        assertTrue(version("2.0.0") > version("1.99.99"))
        assertTrue(version("1.0.10") > version("1.0.9"))
        assertTrue(version("1.0.1") < version("1.1.0"))
    }

    @Test
    fun `accepts optional v and ignores build metadata for precedence`() {
        assertEquals(version("1.2.3"), version("v1.2.3"))
        assertEquals(version("1.2.3+build.7"), version("v1.2.3+other-build"))
    }

    @Test
    fun `handles versions beyond primitive integer range`() {
        assertTrue(version("99999999999999999999999.0.0") > version("2147483647.99.99"))
    }

    @Test
    fun `rejects prereleases malformed versions and unsafe tag characters`() {
        listOf(
            "1.2.3-beta.1", "v1.2.3-rc1", "1.2", "1.2.3.4", "01.2.3", "1.02.3",
            "1.2.03", "V1.2.3", "release-1.2.3", " 1.2.3", "1.2.3\n", "1.2.3+",
            "1.2.3+build..1", "1.2.3/evil", "1.2.3?x", "1.2.3#x", "1.2.3%2F", ""
        ).forEach { assertNull(StableVersion.parse(it), it) }
    }
}
