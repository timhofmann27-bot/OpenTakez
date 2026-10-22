package io.github.timhofmann27.opentakez.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.timhofmann27.opentakez.core.model.DownloadOption
import io.github.timhofmann27.opentakez.core.model.Engine
import io.github.timhofmann27.opentakez.core.model.MediaKind

/** The names are stored in SQLite and used in DAO queries; do not rename them. */
enum class DownloadStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** One row per download job (contract section 7). Live progress is not stored. */
@Entity(
    tableName = "downloads",
    indices = [Index("status"), Index("createdAt")],
)
data class DownloadEntity(
    /** A random UUID; also the WorkManager input and the work directory name. */
    @PrimaryKey val id: String,
    val url: String,
    val platform: String,
    val kind: MediaKind,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val option: DownloadOption,
    val engine: Engine,
    val serverJobId: String?,
    val status: DownloadStatus,
    /** URIs of the saved files (`content://...`), in download order. */
    val files: List<String>,
    val skipped: Int,
    /** Stable machine-readable code, see [ErrorCodes]. */
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val finishedAt: Long?,
)
