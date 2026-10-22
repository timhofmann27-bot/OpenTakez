package io.github.timhofmann27.opentakez.engine.server

import io.github.timhofmann27.opentakez.core.model.ServerConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

const val TOKEN = "s3cret-tok3n-value"

/** A scripted server: routes are "METHOD /path"; anything else answers 404. */
class FakeServer : AutoCloseable {
    private val server = MockWebServer()
    private val routes = HashMap<String, (RecordedRequest) -> MockResponse>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val route = routes["${request.method} ${request.url.encodedPath}"]
                return route?.invoke(request) ?: json(404, """{"detail":"Not found"}""")
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')
    val config get() = ServerConfig(baseUrl, TOKEN)

    fun on(method: String, path: String, handler: (RecordedRequest) -> MockResponse) {
        routes["$method $path"] = handler
    }

    fun on(method: String, path: String, response: MockResponse) = on(method, path) { response }

    /** Answers the n-th call with the n-th response, repeating the last one. */
    fun onSequence(method: String, path: String, vararg responses: MockResponse) {
        var calls = 0
        on(method, path) { responses[minOf(calls++, responses.lastIndex)] }
    }

    fun calls(method: String, path: String) =
        requests.count { it.method == method && it.url.encodedPath == path }

    /** Waits (in real time) until a request shows up; the engine works on other threads. */
    fun awaitRequest(method: String, path: String, timeoutMs: Long = 5_000): RecordedRequest? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            requests.firstOrNull { it.method == method && it.url.encodedPath == path }?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    fun shutdown() = server.close()

    override fun close() = server.close()
}

fun json(code: Int, body: String) =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

fun status(code: Int, vararg headers: Pair<String, String>) =
    MockResponse.Builder().code(code).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

fun jobJson(
    status: String,
    id: String = "job1",
    platform: String = "tiktok",
    progress: String? = null,
    files: List<String> = emptyList(),
    skipped: Int = 0,
    error: String? = null,
    errorCode: String? = null,
): String = buildString {
    append("""{"id":"$id","url":"https://x","platform":"$platform","option":"video-best","status":"$status"""")
    append(""","progress":${progress ?: "null"}""")
    append(""","files":[${files.joinToString(",") { "\"" + it.replace("\\", "\\\\") + "\"" }}],"sizes":[],"skipped":$skipped""")
    append(""","error":${error?.let { "\"$it\"" } ?: "null"},"error_code":${errorCode?.let { "\"$it\"" } ?: "null"}""")
    append(""","created_at":"2026-01-01T00:00:00Z","unknown_future_field":1}""")
}

/** Server-sent events: one `data:` line per job state, with a comment line like keep-alives. */
fun sse(vararg jobs: String): MockResponse = MockResponse.Builder()
    .code(200)
    .addHeader("Content-Type", "text/event-stream")
    .body(": hello\n\n" + jobs.joinToString("") { "data: $it\n\n" })
    .build()

fun fileBody(content: String) = MockResponse.Builder().code(200).body(content).build()

/** Runs [block] and returns the exception of type [T] it throws; fails if it throws nothing else. */
inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.simpleName} but got $e", e)
    }
    throw AssertionError("Expected ${T::class.simpleName} but nothing was thrown")
}
