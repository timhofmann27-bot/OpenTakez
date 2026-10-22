package io.github.timhofmann27.opentakez.core

import io.github.timhofmann27.opentakez.core.model.Engine
import java.net.URI
import java.net.URISyntaxException

object RoutingPolicy {
    // Same patterns as the gallery-dl providers in backend/app/providers/__init__.py.
    private val tiktokPhoto = Regex("""^/@[^/]+/photo/\d+""")
    private val instagramPost = Regex("""^/(?:[^/]+/)?p/[^/]+/?$""")

    /**
     * Photos need gallery-dl, which only the user's server has. Without a server this still says
     * DEVICE: the device engine then reports `EngineError.NeedsServer` when resolving.
     */
    fun defaultEngine(url: String, serverConfigured: Boolean): Engine =
        if (serverConfigured && isPhotoLink(url)) Engine.SERVER else Engine.DEVICE

    fun isPhotoLink(url: String): Boolean {
        val path = pathOf(url) ?: return false
        return when {
            Platforms.hostMatches(url, listOf("tiktok.com")) -> tiktokPhoto.containsMatchIn(path)
            Platforms.hostMatches(url, listOf("instagram.com")) -> instagramPost.containsMatchIn(path)
            else -> false
        }
    }

    private fun pathOf(url: String): String? = try {
        URI(url).rawPath
    } catch (_: URISyntaxException) {
        null
    }
}
