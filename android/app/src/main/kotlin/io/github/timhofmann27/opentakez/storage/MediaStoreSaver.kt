package io.github.timhofmann27.opentakez.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** API 29+: inserts into MediaStore, so no storage permission is needed. */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreSaver(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSaver {

    override suspend fun save(file: File, displayName: String): SavedFile = withContext(io) {
        val requested = sanitizeFileName(displayName)
        val location = SaveLocation.forFile(requested)
        val collection = collectionFor(location.category)
        val name = uniqueName(requested) { exists(collection, location, it) }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, location.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, location.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open $name")
            out.use { sink ->
                file.inputStream().use { source ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, done, null, null) != 1) {
                throw IOException("MediaStore did not publish $name")
            }
        } catch (t: Throwable) {
            // A half-written pending row would stay invisible and take space forever.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        SavedFile(uri.toString(), name)
    }

    private fun collectionFor(category: MediaCategory): Uri {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when (category) {
            MediaCategory.VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            MediaCategory.AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
            MediaCategory.IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            MediaCategory.OTHER -> MediaStore.Downloads.getContentUri(volume)
        }
    }

    private fun exists(collection: Uri, location: SaveLocation, name: String): Boolean =
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, location.relativePath + "/"),
            null,
        )?.use { it.count > 0 } ?: false

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
