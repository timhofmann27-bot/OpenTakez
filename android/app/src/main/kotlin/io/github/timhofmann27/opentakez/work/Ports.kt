package io.github.timhofmann27.opentakez.work

import android.os.StatFs
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.engine.MediaEngine
import java.io.File

/**
 * Gives the work layer an engine without depending on the engine implementations. Suspending so
 * the server engine can read its URL and token from settings first.
 */
fun interface EngineProvider {
    suspend fun engineFor(engine: Engine): MediaEngine
}

/** Updates yt-dlp and returns the new version, or null when it was already current. */
fun interface YtDlpUpdater {
    suspend fun update(): String?
}

fun interface FreeSpaceChecker {
    fun freeBytes(dir: File): Long
}

/** [StatFs] on the directory's nearest existing parent. */
class StatFsFreeSpaceChecker : FreeSpaceChecker {
    override fun freeBytes(dir: File): Long {
        var existing: File? = dir
        while (existing != null && !existing.exists()) existing = existing.parentFile
        return StatFs((existing ?: dir).path).availableBytes
    }
}

/** Space a job needs: twice the expected size (download plus merge/convert), or 300 MB if unknown. */
object StorageRequirement {
    const val UNKNOWN_SIZE_BYTES = 300L * 1024 * 1024

    fun requiredBytes(expectedBytes: Long?): Long =
        if (expectedBytes != null && expectedBytes > 0) expectedBytes * 2 else UNKNOWN_SIZE_BYTES
}

/** Creates the per-job scratch directory (`cacheDir/jobs/<id>`), empty. */
fun interface WorkDirFactory {
    fun create(jobId: String): File
}

class CacheWorkDirFactory(private val cacheDir: File) : WorkDirFactory {
    override fun create(jobId: String): File {
        val dir = File(File(cacheDir, "jobs"), jobId)
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }
}

/** User-visible notification texts; keeps resources out of the logic classes. */
interface Texts {
    val channelName: String
    val cancel: String
    fun done(title: String): String
    fun failed(title: String): String
}
