package io.github.timhofmann27.opentakez.storage

import java.io.File

/** A file in shared storage, e.g. `content://media/external/audio/media/42`. */
data class SavedFile(val uri: String, val displayName: String)

/**
 * Copies a finished download into the device's shared folders. The folder follows the file's
 * type: videos to Movies/OpenTakez, audio to Music/OpenTakez, images to Pictures/OpenTakez,
 * anything else to Download/OpenTakez.
 */
interface FileSaver {
    suspend fun save(file: File, displayName: String): SavedFile
}
