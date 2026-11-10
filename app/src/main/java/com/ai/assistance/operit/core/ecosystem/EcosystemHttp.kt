package com.ai.assistance.operit.core.ecosystem

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*

class ProviderHttpException(val status: Int, val retryAfterMs: Long = 0) : IOException("Provider HTTP $status")
data class WireResponse(val body: String, val contentType: String = "application/json", val headers: Map<String,String> = emptyMap())
fun interface JsonTransport { suspend fun post(url: String, headers: Map<String, String>, body: JsonObject): WireResponse }

/** Existing OkHttp supplies TLS, cancellation and total/idle deadlines; no private retry stack. */
class OkHttpJsonTransport(private val client: OkHttpClient = sharedClient) : JsonTransport {
    override suspend fun post(url: String, headers: Map<String, String>, body: JsonObject): WireResponse = suspendCancellableCoroutine { continuation ->
        val payload = if (headers["Content-Type"] == "application/x-www-form-urlencoded") {
            FormBody.Builder().apply { body.forEach { (key,value) -> add(key,value.jsonPrimitive.content) } }.build()
        } else body.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(payload)
        headers.forEach { (key, value) -> request.header(key, value) }
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try { response.use {
                    if (!it.isSuccessful) throw ProviderHttpException(it.code, retryAfter(it.header("Retry-After")))
                    val source = it.body?.source() ?: throw IOException("Empty response")
                    val buffer = okio.Buffer()
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer, 8192)
                        if (count == -1L) break
                        total += count
                        if (total > 2_000_000) throw IOException("Response size exceeded")
                    }
                    val media = it.header("Content-Type").orEmpty().substringAfter("charset=", "UTF-8").trim().trimEnd(';')
                    val charset = runCatching { java.nio.charset.Charset.forName(media) }.getOrDefault(Charsets.UTF_8)
                    if (continuation.isActive) continuation.resume(WireResponse(buffer.readString(charset), it.header("Content-Type").orEmpty(), it.headers.names().associateWith { name -> it.header(name).orEmpty() }))
                } } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long {
            value ?: return 0
            value.toLongOrNull()?.let { return it.coerceIn(0, 86400) * 1000 }
            return runCatching { (java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceIn(0, 86_400_000) }.getOrDefault(0)
        }
    }
}

/** SSE framing accepts multiline data and CRLF. Never treats an interrupted stream as success. */
object WireEvents {
    fun data(body: String): List<JsonObject> {
        val normalized = body.replace("\r\n", "\n")
        if (!normalized.lineSequence().any { it.startsWith("data:") }) return runCatching { listOf(Json.parseToJsonElement(body).jsonObject) }.getOrElse { listOf(buildJsonObject { put("error", "invalid JSON response") }) }
        return normalized.split(Regex("\n\n+")).mapNotNull { frame ->
            val payload = frame.lineSequence().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
            if (payload.isBlank() || payload == "[DONE]") null else runCatching {Json.parseToJsonElement(payload).jsonObject}.getOrElse {buildJsonObject {put("error",payload)}}
        }
    }
}
