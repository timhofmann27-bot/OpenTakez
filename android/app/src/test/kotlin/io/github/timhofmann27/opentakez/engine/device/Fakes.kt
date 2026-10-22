package io.github.timhofmann27.opentakez.engine.device

import io.github.timhofmann27.opentakez.core.model.EngineError

typealias RunHandler = suspend (args: List<String>, processId: String, onLine: (String) -> Unit) -> RunResult

class FakeYtDlpRunner : YtDlpRunner {
    class Call(val args: List<String>, val processId: String, val redirectErrorStream: Boolean)

    var versionValue: String? = "2025.01.01"
    var initError: Throwable? = null
    var updateOutcome = UpdateOutcome.UPDATED
    var updateError: Throwable? = null
    var updateAction: suspend () -> Unit = {}
    var handler: RunHandler = { _, _, _ -> RunResult(0, "", "") }

    var initCalls = 0
    var updateCalls = 0
    val calls = mutableListOf<Call>()
    val cancelled = mutableListOf<String>()

    /** Everything in call order, to assert "update before resolve". */
    val log = mutableListOf<String>()

    override suspend fun init() {
        initCalls++
        initError?.let { throw it }
    }

    override suspend fun version(): String? = versionValue

    override suspend fun update(): UpdateOutcome {
        updateCalls++
        log += "update"
        updateAction()
        updateError?.let { throw it }
        if (updateOutcome == UpdateOutcome.UPDATED) versionValue = "2026.10.21"
        return updateOutcome
    }

    override suspend fun execute(
        args: List<String>,
        processId: String,
        redirectErrorStream: Boolean,
        onLine: (String) -> Unit,
    ): RunResult {
        calls += Call(args, processId, redirectErrorStream)
        log += "execute"
        return handler(args, processId, onLine)
    }

    override fun cancel(processId: String) {
        cancelled += processId
    }
}

class FakeUpdateStore(var millis: Long? = null) : YtDlpUpdateStore {
    override suspend fun lastUpdateMillis(): Long? = millis

    override suspend fun setLastUpdateMillis(millis: Long) {
        this.millis = millis
    }
}

fun fixture(name: String): String =
    checkNotNull(FakeYtDlpRunner::class.java.getResource("/device/$name")) { "missing fixture $name" }
        .readText()

fun stdout(json: String) = RunResult(0, json, "")

fun failure(stderr: String, exitCode: Int = 1) = RunResult(exitCode, "", stderr)

/** Runs [block] and returns the EngineError it throws, failing if it throws something else or nothing. */
suspend fun engineError(block: suspend () -> Unit): EngineError {
    try {
        block()
    } catch (e: EngineError) {
        return e
    }
    throw AssertionError("expected an EngineError")
}
