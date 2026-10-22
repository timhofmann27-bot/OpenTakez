package io.github.timhofmann27.opentakez.engine.server

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    /**
     * The canonical base address of an OpenTakez server, or null if [input] is not acceptable.
     * Only `https://` is allowed, and nothing that could carry a secret or change the target:
     * no user info, query, or fragment. A trailing `/api` is dropped because users paste it.
     */
    fun normalize(input: String): String? = normalize(input, allowCleartext = false)

    /** [allowCleartext] exists so tests can talk to MockWebServer; the app never sets it. */
    internal fun normalize(input: String, allowCleartext: Boolean): String? {
        val text = input.trim()
        val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "https" && !(allowCleartext && scheme == "http")) return null
        // OkHttp reads a backslash as a slash and skips extra slashes, so refuse what it would fix up.
        if ('?' in text || '#' in text || '\\' in text) return null
        val authority = text.substringAfter("://").substringBefore('/')
        if (authority.isEmpty() || '@' in authority) return null

        val url = text.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null

        val path = url.encodedPath.trimEnd('/').removeSuffix("/api").trimEnd('/')
        return url.newBuilder().encodedPath(path.ifEmpty { "/" }).build().toString().trimEnd('/')
    }
}
