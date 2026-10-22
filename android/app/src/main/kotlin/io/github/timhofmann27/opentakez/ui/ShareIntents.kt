package io.github.timhofmann27.opentakez.ui

import android.content.Intent
import io.github.timhofmann27.opentakez.ui.ports.ShareInbox
import io.github.timhofmann27.opentakez.ui.ports.SharedLink

object ShareIntents {
    // A shared page can be large; a link is always near the start of what is worth reading.
    private const val MAX_TEXT = 100_000

    /**
     * Reads a `text/plain` SEND intent into [inbox]. A share without a link is still offered (with a
     * null link) so the user sees the "no link" error. Returns false for any other intent.
     */
    fun handle(intent: Intent?, inbox: ShareInbox): Boolean {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return false
        // A hostile sender can attach a broken parcel; treat that like text without a link.
        val text = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(MAX_TEXT) }.getOrNull()
        val subject = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.take(MAX_TEXT) }.getOrNull()
        inbox.offer(SharedLink.from(text, subject))
        return true
    }
}
