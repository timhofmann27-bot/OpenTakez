package io.github.timhofmann27.opentakez.engine.server

import io.github.timhofmann27.opentakez.core.model.JobProgress
import io.github.timhofmann27.opentakez.core.model.Phase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val ServerJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
internal data class ResolveRequest(val url: String)

@Serializable
internal data class JobRequest(val url: String, val option: String)

@Serializable
internal data class ProgressDto(
    val phase: String? = null,
    val fraction: Double? = null,
    val speed: Double? = null,
    val eta: Double? = null,
) {
    fun toProgress() = JobProgress(
        phase = if (phase == "processing") Phase.PROCESSING else Phase.DOWNLOADING,
        fraction = fraction?.coerceIn(0.0, 1.0),
        speedBytesPerSecond = speed,
        etaSeconds = eta?.toInt(),
    )
}

/** The server's `Job` (backend/app/jobs/manager.py); only what the app needs. */
@Serializable
internal data class JobDto(
    val id: String,
    val platform: String = "",
    val status: String,
    val progress: ProgressDto? = null,
    val files: List<String> = emptyList(),
    val skipped: Int = 0,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
) {
    val isFinished get() = status == "done" || status == "error"
}

@Serializable
internal data class HealthDto(val status: String? = null, val version: String? = null)
