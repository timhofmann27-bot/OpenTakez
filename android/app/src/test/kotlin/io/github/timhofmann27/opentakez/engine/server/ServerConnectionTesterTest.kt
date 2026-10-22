package io.github.timhofmann27.opentakez.engine.server

import io.github.timhofmann27.opentakez.core.model.ServerConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ServerConnectionTesterTest {
    private val server = FakeServer()
    private val tester = ServerConnectionTester(allowCleartextForTests = true)

    @After fun tearDown() = server.close()

    private fun health(version: String) =
        json(200, """{"status":"ok","version":"$version","yt_dlp":"2026.1.1","auth_required":true}""")

    @Test fun `a current server with an accepted token is Connected`() = runTest {
        server.on("GET", "/api/health", health("1.2.3"))
        server.on("GET", "/api/jobs/token-check", json(404, """{"detail":"Job not found"}"""))

        assertEquals(ConnectionResult.Connected("1.2.3"), tester.test(server.config))

        server.requests.forEach {
            assertEquals("Bearer $TOKEN", it.headers["Authorization"])
            assertFalse(it.url.toString().contains(TOKEN))
        }
    }

    @Test fun `release candidates of 1_0_0 are accepted`() = runTest {
        server.on("GET", "/api/health", health("1.0.0-rc.2"))
        server.on("GET", "/api/jobs/token-check", status(404))

        assertEquals(ConnectionResult.Connected("1.0.0-rc.2"), tester.test(server.config))
    }

    @Test fun `a rejected token is TokenRejected`() = runTest {
        server.on("GET", "/api/health", health("1.0.0"))
        server.on("GET", "/api/jobs/token-check", json(401, """{"detail":"Invalid token"}"""))

        assertEquals(ConnectionResult.TokenRejected, tester.test(server.config))
    }

    @Test fun `servers older than 1_0_0 are TooOld and the token is not tried`() = runTest {
        server.on("GET", "/api/health", health("0.9.5"))

        assertEquals(ConnectionResult.TooOld("0.9.5"), tester.test(server.config))
        assertEquals(0, server.calls("GET", "/api/jobs/token-check"))
    }

    @Test fun `things that are not OpenTakez are NotOpenTakez`() = runTest {
        val answers = listOf(
            json(200, "<html>hello</html>"),
            json(200, """{"status":"ok"}"""),
            json(200, """{"status":"ok","version":"banana"}"""),
            json(200, """{"status":"error","version":"1.0.0"}"""),
            json(200, "[]"),
            status(404),
            status(500),
        )
        for (answer in answers) {
            server.on("GET", "/api/health", answer)
            assertEquals(ConnectionResult.NotOpenTakez, tester.test(server.config))
        }
    }

    @Test fun `no answer is Unreachable`() = runTest {
        val config = server.config
        server.shutdown()

        assertEquals(ConnectionResult.Unreachable, tester.test(config))
    }

    @Test fun `a server that dies after the health check is Unreachable`() = runTest {
        server.on("GET", "/api/health", health("1.0.0"))
        server.on("GET", "/api/jobs/token-check", status(503))

        assertEquals(ConnectionResult.Unreachable, tester.test(server.config))
    }

    @Test fun `cleartext addresses and garbage are Unreachable by default`() = runTest {
        val strict = ServerConnectionTester()

        assertEquals(ConnectionResult.Unreachable, strict.test(server.config))
        assertEquals(ConnectionResult.Unreachable, strict.test(ServerConfig("not a url", TOKEN)))
        assertEquals(0, server.requests.size)
    }

    @Test fun `a token that cannot be sent is TokenRejected`() = runTest {
        server.on("GET", "/api/health", health("1.0.0"))

        val result = tester.test(ServerConfig(server.baseUrl, "bad\ntoken"))

        assertEquals(ConnectionResult.TokenRejected, result)
    }
}
