package io.github.timhofmann27.opentakez.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * App settings in DataStore. The token is stored only as ciphertext; it is never logged and no
 * exception here carries it.
 */
class SettingsRepository(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    /** Empty means no server configured. */
    val serverUrl: Flow<String> = store.data.map { it[SERVER_URL] ?: "" }

    /** The decrypted token; empty when none is set or it can no longer be decrypted. */
    val token: Flow<String> = store.data.map { prefs ->
        val encoded = prefs[TOKEN]
        if (encoded.isNullOrEmpty()) "" else runCatching { cipher.decrypt(encoded) }.getOrDefault("")
    }

    val defaultEngine: Flow<Engine> = store.data.map { prefs ->
        prefs[DEFAULT_ENGINE]?.let { name -> Engine.entries.firstOrNull { it.name == name } } ?: Engine.DEVICE
    }

    /** Empty follows the system language; otherwise the explicit app locale. */
    val language: Flow<String> = store.data.map { it[LANGUAGE] ?: "" }

    /** Epoch millis of the last yt-dlp update check, or null if never. */
    val lastYtDlpCheck: Flow<Long?> = store.data.map { it[LAST_YTDLP_CHECK] }

    val firstRunDone: Flow<Boolean> = store.data.map { it[FIRST_RUN_DONE] ?: false }

    suspend fun setServerUrl(url: String) {
        val normalized = normalizeServerUrl(url) ?: throw IllegalArgumentException("Expected an HTTPS server URL")
        store.edit { it[SERVER_URL] = normalized }
    }

    /** An empty token removes the stored one. */
    suspend fun setToken(token: String) {
        val encrypted = if (token.isEmpty()) null else cipher.encrypt(token)
        store.edit { prefs ->
            if (encrypted == null) prefs.remove(TOKEN) else prefs[TOKEN] = encrypted
        }
    }

    suspend fun setDefaultEngine(engine: Engine) {
        store.edit { it[DEFAULT_ENGINE] = engine.name }
    }

    suspend fun setLanguage(tag: String) {
        require(tag == "" || tag == "en" || tag == "de") { "Unsupported app language" }
        store.edit { it[LANGUAGE] = tag }
    }

    suspend fun setLastYtDlpCheck(millis: Long) {
        store.edit { it[LAST_YTDLP_CHECK] = millis }
    }

    suspend fun setFirstRunDone(done: Boolean) {
        store.edit { it[FIRST_RUN_DONE] = done }
    }

    /** The server to talk to, or null when URL or token is missing. */
    suspend fun serverConfig(): ServerConfig? {
        val url = serverUrl.first()
        val token = token.first()
        return if (url.isEmpty() || token.isEmpty()) null else ServerConfig(url, token)
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("serverUrl")
        val TOKEN = stringPreferencesKey("tokenEncrypted")
        val DEFAULT_ENGINE = stringPreferencesKey("defaultEngine")
        val LANGUAGE = stringPreferencesKey("language")
        val LAST_YTDLP_CHECK = longPreferencesKey("lastYtDlpCheck")
        val FIRST_RUN_DONE = booleanPreferencesKey("firstRunDone")
    }
}

/**
 * `https://dl.example.com/` -> `https://dl.example.com`. Returns null for anything that is not an
 * https URL (the native app does not allow cleartext, contract section 13); empty stays empty.
 */
fun normalizeServerUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrEmpty() ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.port > 65535
    ) return null
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    return "https://${uri.host.lowercase()}$port${uri.rawPath.orEmpty()}".trimEnd('/')
}

/** Adapter for the engine's yt-dlp update store; the integrator wraps it (see the report). */
class YtDlpUpdateStoreAdapter(
    private val read: suspend () -> Long?,
    private val write: suspend (Long) -> Unit,
) {
    suspend fun lastUpdateMillis(): Long? = read()

    suspend fun setLastUpdateMillis(millis: Long) = write(millis)

    companion object {
        fun from(settings: SettingsRepository) = YtDlpUpdateStoreAdapter(
            read = { settings.lastYtDlpCheck.first() },
            write = { settings.setLastYtDlpCheck(it) },
        )
    }
}
