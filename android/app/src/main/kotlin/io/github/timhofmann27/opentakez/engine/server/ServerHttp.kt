package io.github.timhofmann27.opentakez.engine.server

import io.github.timhofmann27.opentakez.core.model.EngineError
import io.github.timhofmann27.opentakez.core.model.ServerConfig
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody

private const val ERROR_BODY_LIMIT = 64L * 1024
private val JSON_TYPE = "application/json".toMediaType()

/**
 * The one place that builds requests, so every call carries the Bearer header and the token can
 * never end up in a URL. Errors are mapped like web/src/lib/api.ts.
 */
internal class ServerHttp(
    private val config: ServerConfig,
    client: OkHttpClient,
    private val allowCleartext: Boolean,
) {
    // A redirect can leave the configured HTTPS server or carry the Bearer token to another path.
    // The OpenTakez API does not use redirects; reject them instead of following them.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private fun baseUrl(): HttpUrl =
        ServerUrl.normalize(config.baseUrl, allowCleartext)?.toHttpUrlOrNull()
            ?: throw EngineError.Other(0, "The server address must be a valid https:// address")

    fun request(
        method: String,
        vararg segments: String,
        json: String? = null,
        accept: String? = null,
    ): Request {
        val url = baseUrl().newBuilder().apply { segments.forEach(::addPathSegment) }.build()
        val builder = Request.Builder().url(url)
        // OkHttp quotes the rejected value in its message, so a bad token must never reach it.
        try {
            builder.header("Authorization", "Bearer ${config.token}")
        } catch (_: IllegalArgumentException) {
            throw EngineError.Unauthorized()
        }
        accept?.let { builder.header("Accept", it) }
        val body = when {
            json != null -> json.toRequestBody(JSON_TYPE)
            method == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        return builder.method(method, body).build()
    }

    /** Sends the request and returns the reply whatever its status; only I/O failures throw. */
    suspend fun sendRaw(request: Request, via: OkHttpClient = client): Exchange {
        val call = via.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(request)
        val response = try {
            call.await()
        } catch (e: IOException) {
            throw ioError(e)
        }
        return Exchange(call, response)
    }

    /** Like [sendRaw], but turns any non-2xx status into the matching [EngineError]. */
    suspend fun open(request: Request, via: OkHttpClient = client): Exchange {
        val exchange = sendRaw(request, via)
        if (exchange.response.isSuccessful) return exchange
        exchange.use {
            val body = try {
                it.read { b -> b.boundedString() }
            } catch (_: EngineError) {
                ""
            }
            throw errorFor(it.code, body, it.response.header("Retry-After"))
        }
    }

    suspend fun <T> fetch(
        request: Request,
        serializer: KSerializer<T>,
        via: OkHttpClient = client,
    ): T = open(request, via).use { exchange ->
        val body = exchange.read { it.string() }
        try {
            ServerJson.decodeFromString(serializer, body)
        } catch (_: SerializationException) {
            throw EngineError.Other(exchange.code, "Unexpected answer from the server")
        }
    }
}

/** A reply whose body is read off the main thread; cancelling the coroutine cancels the call. */
internal class Exchange(private val call: Call, val response: Response) : Closeable {
    val code get() = response.code

    suspend fun <T> read(block: (ResponseBody) -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            cont.resumeWith(
                try {
                    Result.success(block(response.body))
                } catch (e: IOException) {
                    Result.failure(ioError(e))
                },
            )
        }
    }

    override fun close() = response.close()
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) =
                cont.resume(response) { _, _, _ -> response.close() }
        },
    )
}

/** Only the exception's class name is kept: OkHttp messages can quote the request. */
internal fun ioError(e: IOException): EngineError =
    if (e.message?.contains("No space left", ignoreCase = true) == true) {
        EngineError.StorageFull()
    } else {
        EngineError.Network(e.javaClass.simpleName)
    }

private fun ResponseBody.boundedString(): String {
    val source = source()
    source.request(ERROR_BODY_LIMIT)
    return source.readUtf8(minOf(source.buffer.size, ERROR_BODY_LIMIT))
}

/** FastAPI's `detail` is a string, or `{message, code}` for known failures. */
private fun parseDetail(body: String): Pair<String, String?> {
    val detail = try {
        Json.parseToJsonElement(body).jsonObject["detail"]
    } catch (_: Exception) {
        null
    }
    return when (detail) {
        is JsonPrimitive -> (detail.contentOrNull ?: "") to null
        is JsonObject -> {
            val message = (detail["message"] as? JsonPrimitive)?.contentOrNull ?: ""
            message to (detail["code"] as? JsonPrimitive)?.contentOrNull
        }
        else -> "" to null
    }
}

internal fun errorFor(status: Int, body: String, retryAfter: String?): EngineError {
    val (message, code) = parseDetail(body)
    return when (status) {
        400 -> when (message) {
            "No link found" -> EngineError.NoLink()
            "This site isn't supported" -> EngineError.Unsupported()
            else -> EngineError.Other(status, message)
        }
        401 -> EngineError.Unauthorized()
        404 -> EngineError.Lost()
        409 -> EngineError.Conflict()
        422 -> if (code == "login_required") EngineError.LoginRequired(null) else EngineError.Platform(message)
        429 -> EngineError.RateLimited(retryAfter?.trim()?.toIntOrNull()?.takeIf { it >= 0 })
        503 -> EngineError.Busy()
        else -> EngineError.Other(status, message)
    }
}
