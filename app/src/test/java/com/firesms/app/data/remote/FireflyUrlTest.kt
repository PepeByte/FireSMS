package com.firesms.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FireflyUrlTest {

    @Test
    fun `builds api url from a plain server address`() {
        assertEquals(
            "https://firefly.example.com/api/v1/about",
            apiUrl("https://firefly.example.com", "about")
        )
    }

    @Test
    fun `tolerates a trailing slash`() {
        assertEquals(
            "https://firefly.example.com/api/v1/transactions",
            apiUrl("https://firefly.example.com/", "transactions")
        )
    }

    @Test
    fun `tolerates whitespace pasted with the address`() {
        assertEquals(
            "https://firefly.example.com/api/v1/about",
            apiUrl("  https://firefly.example.com \n", "about")
        )
    }

    @Test
    fun `strips a pasted api suffix instead of doubling it`() {
        assertEquals(
            "https://firefly.example.com/api/v1/about",
            apiUrl("https://firefly.example.com/api", "about")
        )
        assertEquals(
            "https://firefly.example.com/api/v1/about",
            apiUrl("https://firefly.example.com/api/v1", "about")
        )
    }

    @Test
    fun `keeps a subpath installation`() {
        assertEquals(
            "https://example.com/firefly/api/v1/about",
            apiUrl("https://example.com/firefly", "about")
        )
    }

    @Test
    fun `keeps a subpath installation when api is pasted`() {
        assertEquals(
            "https://example.com/firefly/api/v1/about",
            apiUrl("https://example.com/firefly/api/v1", "about")
        )
        assertEquals(
            "https://example.com/firefly/api/v1/about",
            apiUrl("https://example.com/firefly/api", "about")
        )
    }

    @Test
    fun `collapses repeated trailing slashes`() {
        assertEquals(
            "https://firefly.example.com/api/v1/about",
            apiUrl("https://firefly.example.com///", "about")
        )
    }

    @Test
    fun `assumes http when the scheme is missing`() {
        assertEquals(
            "http://192.168.1.10:8080/api/v1/about",
            apiUrl("192.168.1.10:8080", "about")
        )
    }
}

class NetworkErrorMessageTest {

    @Test
    fun `explains an unknown host`() {
        val message = networkErrorMessage(java.net.UnknownHostException("firefly.example.com"))
        assertTrue(message.contains("Can't find that server"), message)
    }

    @Test
    fun `explains a refused connection`() {
        val message = networkErrorMessage(java.net.ConnectException("Connection refused"))
        assertTrue(message.contains("Can't reach that server"), message)
    }

    @Test
    fun `explains a socket timeout without leaking stack details`() {
        val message = networkErrorMessage(
            java.net.SocketTimeoutException("Socket timeout has expired [url=https://x, socket_timeout=unknown]")
        )
        assertTrue(message.contains("took too long"), message)
        assertTrue(!message.contains("socket_timeout"), message)
    }

    @Test
    fun `explains an untrusted certificate`() {
        val message = networkErrorMessage(
            javax.net.ssl.SSLHandshakeException("PKIX path building failed")
        )
        assertTrue(message.contains("certificate"), message)
    }

    @Test
    fun `falls back to the original message`() {
        assertEquals("something odd", networkErrorMessage(IllegalStateException("something odd")))
    }
}
