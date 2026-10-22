package io.github.timhofmann27.opentakez.engine.device

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The only class that touches youtubedl-android. Not unit tested; verified on a device. */
class YoutubeDlAndroidRunner(context: Context) : YtDlpRunner {
    private val appContext = context.applicationContext

    override suspend fun init() = withContext(Dispatchers.IO) {
        YoutubeDL.getInstance().init(appContext)
        FFmpeg.getInstance().init(appContext)
    }

    override suspend fun version(): String? = withContext(Dispatchers.IO) {
        YoutubeDL.getInstance().version(appContext)
    }

    override suspend fun update(): UpdateOutcome = withContext(Dispatchers.IO) {
        val status = YoutubeDL.getInstance()
            .updateYoutubeDL(appContext, YoutubeDL.UpdateChannel._STABLE)
        if (status == YoutubeDL.UpdateStatus.DONE) UpdateOutcome.UPDATED else UpdateOutcome.ALREADY_LATEST
    }

    override suspend fun execute(
        args: List<String>,
        processId: String,
        redirectErrorStream: Boolean,
        onLine: (String) -> Unit,
    ): RunResult = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(emptyList()).addCommands(args)
        try {
            val response = YoutubeDL.getInstance()
                .execute(request, processId, redirectErrorStream) { _, _, line -> onLine(line) }
            RunResult(response.exitCode, response.out, response.err)
        } catch (e: YoutubeDL.CanceledException) {
            throw CancellationException("yt-dlp process cancelled").apply { initCause(e) }
        } catch (e: YoutubeDLException) {
            // With a separated stderr stream, non-zero process exits carry yt-dlp's explanation.
            RunResult(1, "", e.message.orEmpty())
        }
    }

    override fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }
}
