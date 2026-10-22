package io.github.timhofmann27.opentakez.engine.server

import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.ServerConfig
import okhttp3.OkHttpClient

sealed interface ConnectionResult {
    data class Connected(val version: String) : ConnectionResult

    /** No answer: wrong address, no internet, TLS failure, or the server is down. */
    data object Unreachable : ConnectionResult

    data object TokenRejected : ConnectionResult

    /** An OpenTakez server below [ServerConnectionTester.MIN_VERSION]. */
    data class TooOld(val version: String) : ConnectionResult

    /** Something answered, but it does not look like OpenTakez. */
    data object NotOpenTakez : ConnectionResult
}

/** The "Test connection" button of the settings screen. */
class ServerConnectionTester(
    private val client: OkHttpClient = OkHttpClient(),
    private val allowCleartextForTests: Boolean = false,
) {

    suspend fun test(config: ServerConfig): ConnectionResult {
        val http = ServerHttp(config, client, allowCleartextForTests)
        return try {
            val version = health(http) ?: return ConnectionResult.NotOpenTakez
            val parsed = parseVersion(version) ?: return ConnectionResult.NotOpenTakez
            if (isOlder(parsed, MIN_VERSION)) return ConnectionResult.TooOld(version)
            tokenCheck(http, version)
        } catch (_: EngineError.Unauthorized) {
            ConnectionResult.TokenRejected
        } catch (_: EngineError) {
            ConnectionResult.Unreachable
        }
    }

    /** The server's version, or null if the answer is not an OpenTakez health report. */
    private suspend fun health(http: ServerHttp): String? {
        http.sendRaw(http.request("GET", "api", "health")).use { exchange ->
            if (exchange.code != 200) return null
            val body = exchange.read { it.string() }
            val health = try {
                ServerJson.decodeFromString(HealthDto.serializer(), body)
            } catch (_: kotlinx.serialization.SerializationException) {
                return null
            }
            return health.version?.takeIf { health.status == "ok" }
        }
    }

    /** A job id that does not exist: 404 means the token passed, 401 means it did not. */
    private suspend fun tokenCheck(http: ServerHttp, version: String): ConnectionResult =
        http.sendRaw(http.request("GET", "api", "jobs", "token-check")).use { exchange ->
            when (exchange.code) {
                404 -> ConnectionResult.Connected(version)
                401 -> ConnectionResult.TokenRejected
                else -> ConnectionResult.Unreachable
            }
        }

    /**
     * Major, minor, patch. A pre-release suffix is ignored, so 1.0.0-rc.1 counts as 1.0.0: the
     * release candidates of the app and the server ship together.
     */
    private fun parseVersion(text: String): List<Int>? {
        val match = VERSION.matchEntire(text.trim()) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    private fun isOlder(version: List<Int>, minimum: List<Int>): Boolean {
        for ((a, b) in version.zip(minimum)) if (a != b) return a < b
        return false
    }

    companion object {
        val MIN_VERSION = listOf(1, 0, 0)
        private val VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")
    }
}
