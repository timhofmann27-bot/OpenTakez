package io.github.timhofmann27.opentakez.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import io.github.timhofmann27.opentakez.core.model.JobProgress
import java.util.UUID

/**
 * Notification texts only ever contain the job title: no URL, no token, no error detail.
 * Posting needs POST_NOTIFICATIONS on Android 13+, which is optional: without it nothing is
 * shown and nothing crashes.
 */
class Notifications(context: Context, private val texts: Texts) {
    private val app = context.applicationContext

    fun ensureChannel() {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, texts.channelName, NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** The ongoing notification of a running worker, with a Cancel action for [workerId]. */
    @SuppressLint("InlinedApi") // the type is ignored below API 29
    fun foregroundInfo(rowId: String, title: String, progress: JobProgress?, workerId: UUID): ForegroundInfo {
        ensureChannel()
        val cancel = WorkManager.getInstance(app).createCancelPendingIntent(workerId)
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, texts.cancel, cancel)
        val fraction = progress?.fraction
        if (fraction != null) {
            builder.setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return ForegroundInfo(
            progressId(rowId),
            builder.build(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    fun showDone(rowId: String, title: String) = post(
        resultId(rowId),
        build(android.R.drawable.stat_sys_download_done, texts.done(title)),
    )

    fun showFailed(rowId: String, title: String) = post(
        resultId(rowId),
        build(android.R.drawable.stat_notify_error, texts.failed(title)),
    )

    private fun build(icon: Int, text: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    @SuppressLint("MissingPermission") // checked in canPost()
    private fun post(id: Int, notification: Notification) {
        if (!canPost()) return
        try {
            NotificationManagerCompat.from(app).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: downloads work without it.
        }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        return PendingIntent.getActivity(app, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val PROGRESS_MAX = 100

        /** Distinct ids so the result stays after the ongoing notification is removed. */
        fun progressId(rowId: String): Int = rowId.hashCode() * 2
        fun resultId(rowId: String): Int = rowId.hashCode() * 2 + 1
    }
}

/**
 * Texts from the string resources the UI track adds (`notif_channel_downloads`, `notif_done`,
 * `notif_failed`, `job_cancel`). Looked up by name with an English fallback, so this module
 * builds and works before those resources exist.
 */
class ResourceTexts(private val context: Context) : Texts {
    override val channelName: String get() = string("notif_channel_downloads", "Downloads")
    override val cancel: String get() = string("job_cancel", "Cancel")
    override fun done(title: String): String = string("notif_done", "Saved: {title}").replace("{title}", title)
    override fun failed(title: String): String = string("notif_failed", "Failed: {title}").replace("{title}", title)

    @SuppressLint("DiscouragedApi")
    private fun string(name: String, fallback: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        return if (id != 0) context.getString(id) else fallback
    }
}
