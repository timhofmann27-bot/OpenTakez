package io.github.timhofmann27.opentakez.core

import java.net.URI
import java.net.URISyntaxException

/** The five platforms of backend/app/urls.py and backend/app/providers/__init__.py. */
object Platforms {
    class Info(val name: String, val domains: List<String>, val thumbnailHosts: List<String>)

    private val tiktokCdn = listOf(
        "tiktokcdn.com",
        "tiktokcdn-us.com",
        "tiktokcdn-eu.com",
        "tiktokv.com",
        "tiktokv.us",
        "byteimg.com",
        "ibyteimg.com",
    )
    private val metaCdn = listOf("cdninstagram.com", "fbcdn.net")

    // vm./vt.tiktok.com and on.soundcloud.com are subdomains, so they match without an entry.
    private val registry = listOf(
        Info("youtube", listOf("youtube.com", "youtu.be", "youtube-nocookie.com"), listOf("ytimg.com", "ggpht.com")),
        Info("tiktok", listOf("tiktok.com"), tiktokCdn),
        Info("instagram", listOf("instagram.com"), metaCdn),
        Info("facebook", listOf("facebook.com", "fb.watch", "fb.com"), metaCdn),
        Info("soundcloud", listOf("soundcloud.com"), listOf("sndcdn.com")),
    )

    val names: List<String> = registry.map { it.name }

    fun info(platform: String): Info? = registry.firstOrNull { it.name == platform }

    /** The platform name for [url], or null if the host is not one of the five. */
    fun platformOf(url: String): String? =
        registry.firstOrNull { hostMatches(url, it.domains) }?.name

    /**
     * True if [url] is http(s) on one of [domains] or their subdomains. Rejects ports and other
     * schemes, like `host_matches` on the server.
     */
    fun hostMatches(url: String, domains: Iterable<String>): Boolean {
        val host = hostOf(url) ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    /** [url] if it is https on the CDN of [platform], else null. */
    fun trustedThumbnail(url: String?, platform: String): String? =
        trusted(url, info(platform)?.thumbnailHosts ?: emptyList())

    /** [url] if it is https on the CDN of any known platform, else null. */
    fun trustedThumbnail(url: String?): String? =
        trusted(url, registry.flatMap { it.thumbnailHosts })

    private fun trusted(url: String?, hosts: List<String>): String? {
        if (url.isNullOrEmpty() || !url.startsWith("https://")) return null
        return url.takeIf { hostMatches(it, hosts) }
    }

    /** Lower-case host without trailing dot, or null if [url] is not plain http(s) on the default port. */
    internal fun hostOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.port != -1) return null
        return uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    }
}
