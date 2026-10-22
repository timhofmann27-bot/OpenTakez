package io.github.timhofmann27.opentakez.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Picks the saver for the running Android version. */
class AndroidFileSaver(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
) : FileSaver {
    @SuppressLint("NewApi") // guarded by sdkInt, which lint cannot see
    private val delegate: FileSaver =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            MediaStoreSaver(context.applicationContext.contentResolver)
        } else {
            legacy(context.applicationContext)
        }

    override suspend fun save(file: File, displayName: String): SavedFile =
        delegate.save(file, displayName)

    private fun legacy(app: Context) = LegacySaver(
        hasPermission = {
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        },
        publicDir = { name -> Environment.getExternalStoragePublicDirectory(publicDirType(name)) },
        scan = { file, mime ->
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(app, arrayOf(file.path), arrayOf(mime)) { _, uri ->
                    cont.resume(uri?.toString())
                }
            }
        },
    )

    private fun publicDirType(name: String): String = when (name) {
        "Movies" -> Environment.DIRECTORY_MOVIES
        "Music" -> Environment.DIRECTORY_MUSIC
        "Pictures" -> Environment.DIRECTORY_PICTURES
        else -> Environment.DIRECTORY_DOWNLOADS
    }
}
