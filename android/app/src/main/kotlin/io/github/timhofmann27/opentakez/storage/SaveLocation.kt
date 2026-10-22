package io.github.timhofmann27.opentakez.storage

enum class MediaCategory { VIDEO, AUDIO, IMAGE, OTHER }

/**
 * Where a finished file belongs in shared storage. [directory] is the public top-level folder
 * (`Movies`, `Music`, `Pictures`, `Download`); every file lands in its `OpenTakez` subfolder.
 */
data class SaveLocation(
    val category: MediaCategory,
    val directory: String,
    val mimeType: String,
) {
    /** `Movies/OpenTakez`, no trailing slash. */
    val relativePath: String get() = "$directory/$APP_FOLDER"

    companion object {
        const val APP_FOLDER = "OpenTakez"
        private const val FALLBACK_MIME = "application/octet-stream"

        private val video = mapOf(
            "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska",
            "webm" to "video/webm", "mov" to "video/quicktime", "avi" to "video/x-msvideo",
            "3gp" to "video/3gpp", "flv" to "video/x-flv", "ts" to "video/mp2t",
        )
        private val audio = mapOf(
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
            "opus" to "audio/opus", "ogg" to "audio/ogg", "oga" to "audio/ogg",
            "wav" to "audio/wav", "flac" to "audio/flac", "weba" to "audio/webm",
            "mka" to "audio/x-matroska",
        )
        private val image = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "webp" to "image/webp", "gif" to "image/gif", "heic" to "image/heic",
            "avif" to "image/avif", "bmp" to "image/bmp",
        )

        /** Decided by the extension only; yt-dlp and the server always give files proper ones. */
        fun forFile(name: String): SaveLocation {
            val ext = name.substringAfterLast('.', "").lowercase()
            video[ext]?.let { return SaveLocation(MediaCategory.VIDEO, "Movies", it) }
            audio[ext]?.let { return SaveLocation(MediaCategory.AUDIO, "Music", it) }
            image[ext]?.let { return SaveLocation(MediaCategory.IMAGE, "Pictures", it) }
            return SaveLocation(MediaCategory.OTHER, "Download", FALLBACK_MIME)
        }
    }
}

/** Keeps a display name from escaping its folder; titles can contain anything. */
fun sanitizeFileName(name: String): String {
    val cleaned = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
        .joinToString("")
        .trim()
        .trimStart('.')
    return cleaned.ifEmpty { "download" }
}

/** `a.mp4` -> `a (1).mp4` -> `a (2).mp4` ... until [exists] says the name is free. */
fun uniqueName(name: String, exists: (String) -> Boolean): String {
    if (!exists(name)) return name
    val dot = name.lastIndexOf('.')
    val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    var n = 1
    while (true) {
        val candidate = "$stem ($n)$ext"
        if (!exists(candidate)) return candidate
        n++
    }
}
