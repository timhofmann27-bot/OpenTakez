package io.github.timhofmann27.opentakez.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The state-changing queries only act on rows that can still move (queued or running), so a late
 * "cancelled" can never overwrite "done".
 */
@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity)

    @Update
    suspend fun update(entity: DownloadEntity): Int

    /** Everything the Downloads screen shows: waiting, running, failed, cancelled. Newest first. */
    @Query(
        "SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING', 'FAILED', 'CANCELLED') " +
            "ORDER BY createdAt DESC",
    )
    fun observeActive(): Flow<List<DownloadEntity>>

    /** The History screen: finished jobs, newest first. */
    @Query(
        "SELECT * FROM downloads WHERE status = 'DONE' ORDER BY COALESCE(finishedAt, createdAt) DESC",
    )
    fun observeDone(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeById(id: String): Flow<DownloadEntity?>

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE status = 'DONE'")
    suspend fun clearDone()

    @Query(
        "UPDATE downloads SET status = 'RUNNING', errorCode = NULL, errorMessage = NULL, " +
            "finishedAt = NULL WHERE id = :id AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markRunning(id: String): Int

    @Query(
        "UPDATE downloads SET status = 'DONE', files = :files, skipped = :skipped, " +
            "finishedAt = :finishedAt, errorCode = NULL, errorMessage = NULL " +
            "WHERE id = :id AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markDone(id: String, files: List<String>, skipped: Int, finishedAt: Long): Int

    @Query(
        "UPDATE downloads SET status = 'FAILED', errorCode = :code, errorMessage = :message, " +
            "files = :files, finishedAt = :finishedAt WHERE id = :id AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markFailed(
        id: String,
        code: String,
        message: String?,
        files: List<String>,
        finishedAt: Long,
    ): Int

    @Query(
        "UPDATE downloads SET status = 'CANCELLED', finishedAt = :finishedAt " +
            "WHERE id = :id AND status IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markCancelled(id: String, finishedAt: Long): Int

    /** Failed remote jobs reattach and keep saved files; cancelled jobs start fresh. */
    @Query(
        "UPDATE downloads SET status = 'QUEUED', errorCode = NULL, errorMessage = NULL, " +
            "finishedAt = NULL, skipped = 0, " +
            "serverJobId = CASE WHEN status = 'CANCELLED' THEN NULL ELSE serverJobId END, " +
            "files = CASE WHEN status = 'CANCELLED' THEN '[]' ELSE files END " +
            "WHERE id = :id AND status IN ('FAILED', 'CANCELLED')",
    )
    suspend fun requeue(id: String): Int

    @Query("UPDATE downloads SET serverJobId = :serverJobId WHERE id = :id AND status = 'RUNNING'")
    suspend fun setServerJobId(id: String, serverJobId: String?)
}
