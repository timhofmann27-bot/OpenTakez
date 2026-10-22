package io.github.timhofmann27.opentakez.engine.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test fun `accepts and normalizes https addresses`() {
        val cases = mapOf(
            "https://takez.example.com" to "https://takez.example.com",
            "  https://takez.example.com  " to "https://takez.example.com",
            "https://TAKEZ.Example.COM" to "https://takez.example.com",
            "HTTPS://takez.example.com" to "https://takez.example.com",
            "https://takez.example.com/" to "https://takez.example.com",
            "https://takez.example.com///" to "https://takez.example.com",
            "https://takez.example.com/api" to "https://takez.example.com",
            "https://takez.example.com/api/" to "https://takez.example.com",
            "https://takez.example.com:8443" to "https://takez.example.com:8443",
            "https://takez.example.com:443" to "https://takez.example.com",
            "https://example.com/takez" to "https://example.com/takez",
            "https://example.com/takez/api" to "https://example.com/takez",
            "https://192.168.1.5:8000" to "https://192.168.1.5:8000",
            "https://[::1]:8000/" to "https://[::1]:8000",
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, ServerUrl.normalize(input)) }
    }

    @Test fun `rejects everything else`() {
        val bad = listOf(
            "",
            "   ",
            "takez.example.com",
            "http://takez.example.com",
            "ftp://takez.example.com",
            "javascript:alert(1)",
            "https://",
            "https:///path",
            "https://user@takez.example.com",
            "https://user:pass@takez.example.com",
            "https://@takez.example.com",
            "https://takez.example.com?token=abc",
            "https://takez.example.com/?",
            "https://takez.example.com#frag",
            "https://takez.example.com/api?x=1",
            "https://take z.example.com",
            "https://takez.example.com:99999",
        )
        bad.forEach { assertNull(it, ServerUrl.normalize(it)) }
    }

    @Test fun `http is only accepted through the test-only overload`() {
        assertNull(ServerUrl.normalize("http://localhost:8000"))
        assertEquals("http://localhost:8000", ServerUrl.normalize("http://localhost:8000/", allowCleartext = true))
    }
}
