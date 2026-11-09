package com.firesms.app.data.local

import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyFireflyUrlMigrationTest {

    @Test
    fun `pins a scheme-less address to http as older versions assumed`() {
        assertEquals("http://192.168.1.10:8080", migrateLegacyFireflyUrl("192.168.1.10:8080"))
        assertEquals("http://firefly.example.com", migrateLegacyFireflyUrl(" firefly.example.com "))
    }

    @Test
    fun `leaves addresses with a scheme alone`() {
        assertEquals("https://firefly.example.com", migrateLegacyFireflyUrl("https://firefly.example.com"))
        assertEquals("http://nas.local", migrateLegacyFireflyUrl("http://nas.local"))
    }

    @Test
    fun `leaves an empty address empty`() {
        assertEquals("", migrateLegacyFireflyUrl(""))
    }
}
