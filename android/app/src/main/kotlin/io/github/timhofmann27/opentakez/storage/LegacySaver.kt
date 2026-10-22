package io.github.timhofmann27.opentakez.storage

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The runtime permission is missing; asking for it is the UI's job. */
class StoragePermissionException :
    IOException("Storage permission (WRITE_EXTERNAL_STORAGE) is not granted")

/**
 * API 26-28: writes into the public folders directly. The collaborators are passed in so the
 * logic runs on the JVM: [publicDir] maps `Movies` etc. to a directory, [scan] tells the media
 * scanner and returns the URI it assigned (or null).
 */
class LegacySaver(
    private val hasPermission: () -> Boolean,
    private val publicDir: (String) -> File,
    private val scan: suspend (File, String) -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileSaver {

    override suspend fun save(file: File, displayName: String): SavedFile = withContext(io) {
        if (!hasPermission()) throw StoragePermissionException()
        val requested = sanitizeFileName(displayName)
        val location = SaveLocation.forFile(requested)
        val folder = File(publicDir(location.directory), SaveLocation.APP_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) {
            throw IOException("Cannot create ${location.relativePath}")
        }
        val name = uniqueName(requested) { File(folder, it).exists() }
        val target = File(folder, name)
        try {
            file.inputStream().use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        val uri = try {
            scan(target, location.mimeType) ?: throw IOException("Media scanner did not index $name")
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        SavedFile(uri, name)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
