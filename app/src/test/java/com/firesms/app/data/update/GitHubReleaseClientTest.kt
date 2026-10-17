package com.firesms.app.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubReleaseClientTest {
    private fun payload(tag: String = "v1.10.0", draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"html_url":"https://evil.example/apk","name":"Extra ignored field"}"""

    @Test
    fun `returns newer stable release with a trusted derived page url`() {
        val release = assertNotNull(decodeLatestReleaseResponse(200, payload(), "1.9.0"))
        assertEquals("1.10.0", release.version)
        assertEquals("https://github.com/PepeByte/FireSMS/releases/tag/v1.10.0", release.pageUrl)
    }

    @Test
    fun `does not offer equal older or metadata only versions`() {
        listOf("v1.2.3", "v1.2.2", "1.2.3+new-build", "0.99.99").forEach {
            assertNull(decodeLatestReleaseResponse(200, payload(it), "1.2.3"), it)
        }
    }

    @Test
    fun `supports unprefixed tags`() {
        assertEquals(
            AppRelease("2.0.0", "https://github.com/PepeByte/FireSMS/releases/tag/2.0.0"),
            decodeLatestReleaseResponse(200, payload("2.0.0"), "1.0.0")
        )
    }

    @Test
    fun `ignores drafts and prereleases even with stable looking tags`() {
        assertNull(decodeLatestReleaseResponse(200, payload(draft = true), "1.0.0"))
        assertNull(decodeLatestReleaseResponse(200, payload(prerelease = true), "1.0.0"))
        assertNull(decodeLatestReleaseResponse(200, payload("v2.0.0-beta", prerelease = true), "1.0.0"))
    }

    @Test
    fun `no published stable release is not an error`() {
        assertNull(decodeLatestReleaseResponse(404, "Not Found", "1.0.0"))
    }

    @Test
    fun `rate limits and other http failures do not masquerade as up to date`() {
        listOf(403, 429, 301, 401, 500, 503, 204).forEach { status ->
            val error = assertFailsWith<UpdateCheckException> {
                decodeLatestReleaseResponse(status, "", "1.0.0")
            }
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `invalid payloads or missing stable flags are errors`() {
        listOf("not json", "{}", """{"tag_name":"v2.0.0"}""", """{"tag_name":null,"draft":false,"prerelease":false}""").forEach {
            assertFailsWith<UpdateCheckException> { decodeLatestReleaseResponse(200, it, "1.0.0") }
        }
    }

    @Test
    fun `unsupported or unsafe latest tags are errors rather than no update`() {
        listOf("v2.0.0-beta", "latest", "2.0.0/evil", "2.0.0?redirect=evil", "02.0.0").forEach {
            assertFailsWith<UpdateCheckException> { decodeLatestReleaseResponse(200, payload(it), "1.0.0") }
        }
    }

    @Test
    fun `unsupported installed version is an explicit error`() {
        assertFailsWith<UpdateCheckException> {
            decodeLatestReleaseResponse(200, payload(), "development")
        }
    }
}
