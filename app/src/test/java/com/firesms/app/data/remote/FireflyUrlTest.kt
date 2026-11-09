package com.firesms.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun `assumes https when the scheme is missing`() {
        assertEquals(
            "https://192.168.1.10:8080/api/v1/about",
            apiUrl("192.168.1.10:8080", "about")
        )
    }

    @Test
    fun `keeps an explicit http scheme`() {
        assertEquals(
            "http://192.168.1.10:8080/api/v1/about",
            apiUrl("http://192.168.1.10:8080", "about")
        )
    }
}

class NormalizeFireflyUrlTest {

    @Test
    fun `adds https to an address without a scheme`() {
        assertEquals("https://firefly.example.com", normalizeFireflyUrl("firefly.example.com"))
    }

    @Test
    fun `keeps an explicit scheme in any case`() {
        assertEquals("http://10.0.0.2:8080", normalizeFireflyUrl("http://10.0.0.2:8080"))
        assertEquals("HTTPS://firefly.example.com", normalizeFireflyUrl("HTTPS://firefly.example.com"))
    }

    @Test
    fun `trims whitespace and trailing slashes`() {
        assertEquals("https://example.com/firefly", normalizeFireflyUrl("  example.com/firefly//\n"))
    }

    @Test
    fun `leaves a blank address blank`() {
        assertEquals("", normalizeFireflyUrl("   "))
    }

    @Test
    fun `flags only explicit http as cleartext`() {
        assertTrue(isCleartextUrl("http://192.168.1.10"))
        assertTrue(isCleartextUrl(" HTTP://nas.local "))
        assertFalse(isCleartextUrl("https://firefly.example.com"))
        assertFalse(isCleartextUrl("firefly.example.com"))
        assertFalse(isCleartextUrl(""))
    }
}

class ConnectionCheckClassificationTest {

    private fun failure(result: ConnectionCheckResult): String =
        assertIs<ConnectionCheckResult.Failure>(result).message

    @Test
    fun `json from the api is a success`() {
        assertEquals(
            ConnectionCheckResult.Success,
            classifyConnectionResponse(200, "application/vnd.api+json", "/api/v1/about")
        )
        assertEquals(
            ConnectionCheckResult.Success,
            classifyConnectionResponse(200, "application/json; charset=utf-8", "/firefly/api/v1/about")
        )
    }

    @Test
    fun `rejected token`() {
        assertTrue(failure(classifyConnectionResponse(401, "application/json", "/api/v1/about"))
            .startsWith("Access token was rejected"))
        assertTrue(failure(classifyConnectionResponse(403, "text/html", "/api/v1/about"))
            .startsWith("Access token was rejected"))
    }

    @Test
    fun `missing api`() {
        assertEquals(MESSAGE_API_NOT_FOUND, failure(classifyConnectionResponse(404, "text/html", "/api/v1/about")))
    }

    @Test
    fun `html page instead of the api`() {
        assertEquals(MESSAGE_WEB_PAGE, failure(classifyConnectionResponse(200, "text/html; charset=UTF-8", "/login")))
        assertEquals(MESSAGE_WEB_PAGE, failure(classifyConnectionResponse(200, "text/html", "/api/v1/about")))
        assertEquals(MESSAGE_WEB_PAGE, failure(classifyConnectionResponse(200, "application/json", "/login")))
    }

    @Test
    fun `server error`() {
        assertTrue(failure(classifyConnectionResponse(502, "application/json", "/api/v1/about")).contains("HTTP 502"))
    }

    @Test
    fun `timeouts and unreachable servers`() {
        assertEquals(MESSAGE_TIMEOUT, failure(classifyConnectionError(java.net.SocketTimeoutException("timeout"))))
        assertEquals(MESSAGE_UNREACHABLE, failure(classifyConnectionError(java.net.UnknownHostException("x"))))
        assertEquals(
            MESSAGE_UNREACHABLE,
            failure(classifyConnectionError(java.io.IOException("wrapped", java.net.ConnectException("refused"))))
        )
        assertTrue(failure(classifyConnectionError(java.net.UnknownHostException("x"))).startsWith("Couldn't reach the server"))
    }

    @Test
    fun `certificate problems`() {
        assertEquals(
            MESSAGE_CERTIFICATE,
            failure(classifyConnectionError(javax.net.ssl.SSLHandshakeException("PKIX path building failed")))
        )
        assertEquals(
            MESSAGE_CERTIFICATE,
            failure(classifyConnectionError(java.security.cert.CertificateExpiredException("expired")))
        )
    }

    @Test
    fun `https spoken to a plain http server`() {
        assertEquals(
            MESSAGE_NOT_HTTPS,
            failure(classifyConnectionError(javax.net.ssl.SSLException("Unrecognized SSL message, plaintext connection?")))
        )
    }

    @Test
    fun `other errors fall back to the network message`() {
        assertEquals("something odd", failure(classifyConnectionError(IllegalStateException("something odd"))))
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
