package io.github.timhofmann27.opentakez.core

/** Same rule as `extract_url` in backend/app/urls.py. */
object UrlExtractor {
    // (?U): Python's \s also matches Unicode spaces such as the no-break space.
    private val urlRegex = Regex("""(?U)https?://[^\s<>"']+""")
    private val trailing = charArrayOf('.', ',', ';', ':', '!', '?', ')')

    /** The first http(s) link in [text], without trailing punctuation, or null. */
    fun extract(text: String): String? = urlRegex.find(text)?.value?.trimEnd(*trailing)
}
