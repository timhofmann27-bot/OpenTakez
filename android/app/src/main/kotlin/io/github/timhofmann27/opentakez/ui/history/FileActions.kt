package io.github.timhofmann27.opentakez.ui.history

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.timhofmann27.opentakez.core.model.MediaKind
import io.github.timhofmann27.opentakez.ui.ports.HistoryEntry

/** Hands saved files to other apps. Both return false when nothing could handle the request. */
interface FileActions {
    fun open(entry: HistoryEntry): Boolean
    fun share(entry: HistoryEntry, chooserTitle: String): Boolean
}

class AndroidFileActions(private val context: Context) : FileActions {
    override fun open(entry: HistoryEntry): Boolean {
        val uri = entry.files.firstOrNull()?.let(Uri::parse) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeFor(uri, entry.kind))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent)
    }

    override fun share(entry: HistoryEntry, chooserTitle: String): Boolean {
        val uris = entry.files.map(Uri::parse)
        if (uris.isEmpty()) return false
        val mime = uris.map { mimeFor(it, entry.kind) }.distinct().singleOrNull() ?: "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The clip is what carries the read grant to the chosen app.
        send.clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        return launch(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun mimeFor(uri: Uri, kind: MediaKind): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: when (kind) {
                MediaKind.VIDEO -> "video/*"
                MediaKind.AUDIO -> "audio/*"
                MediaKind.IMAGE -> "image/*"
            }

    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
