package io.github.timhofmann27.opentakez.ui.ports

/**
 * Pulls the link out of shared text, like `backend/app/urls.py::extract_url`. The integrator may
 * replace this with `core/UrlExtractor`; the rule is the same.
 */
object SharedLink {
    private val LINK = Regex("""https?://[^\s<>"']+""")
    private const val TRAILING = ".,;:!?)"

    /** The first link in [text], else in [subject]; null if neither has one. */
    fun from(text: String?, subject: String?): String? =
        extract(text) ?: extract(subject)

    fun extract(text: String?): String? {
        val match = text?.let { LINK.find(it) } ?: return null
        return match.value.trimEnd { it in TRAILING }.ifEmpty { null }
    }
}

/** A link (or the lack of one) that arrived through the share sheet. [id] makes repeats distinct. */
data class ShareEvent(val id: Long, val link: String?)

/** Hands a share from `MainActivity` to the UI, and survives until the UI has taken it. */
class ShareInbox {
    private val state = kotlinx.coroutines.flow.MutableStateFlow<ShareEvent?>(null)
    private var counter = 0L

    val events: kotlinx.coroutines.flow.StateFlow<ShareEvent?> get() = state

    fun offer(link: String?) {
        state.value = ShareEvent(++counter, link)
    }

    /** Marks [event] as handled; a newer event is kept. */
    fun consume(event: ShareEvent) {
        state.compareAndSet(event, null)
    }
}
