package com.ai.assistance.operit.core.ecosystem

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable data class SearchHit(val title: String, val url: String, val snippet: String, val score: Double = 0.0, val citations: List<String> = emptyList())
@Serializable data class ToolGatewayConfig(val url: String = "http://127.0.0.1:20128", val searchPath: String = "/v1/search", val fetchPath: String = "/v1/web/fetch", val provider: String = "auto", val secretRef: String? = null)

class TtlLruCache<K, V>(private val capacity: Int = 64, private val ttlMs: Long = 60_000, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry<V>(val value: V, val expiresAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    init { require(capacity > 0 && ttlMs > 0) }
    @Synchronized fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (clock() >= entry.expiresAt) { entries.remove(key); return null }
        return entry.value
    }
    @Synchronized fun put(key: K, value: V) {
        entries[key] = Entry(value, clock() + ttlMs)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
}
class TokenBucket(private val capacity: Double = 10.0, private val perSecond: Double = 2.0, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var tokens = capacity
    private var previous = clock()
    init { require(capacity >= 1 && perSecond > 0) }
    @Synchronized fun acquire(): Boolean {
        val now = clock()
        tokens = (tokens + (now - previous).coerceAtLeast(0) * perSecond / 1000).coerceAtMost(capacity)
        previous = now
        if (tokens < 1) return false
        tokens--
        return true
    }
}

/** Remote tool authority is limited to public URLs; auth never follows returned citations. */
class GatewayToolBroker(private val config: ToolGatewayConfig, private val local: ToolBroker,
                        private val transport: JsonTransport = OkHttpJsonTransport(), private val secret: () -> String? = { null },
                        private val cache: TtlLruCache<String, ToolResult> = TtlLruCache(), private val limiter: TokenBucket = TokenBucket()) : ToolBroker {
    private fun headers() = secret()?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
    override suspend fun webSearch(query: String): ToolResult = search(query, 5)
    suspend fun search(query: String, maxResults: Int): ToolResult {
        require(query.isNotBlank() && query.length <= 4000 && maxResults in 1..20)
        val key = "${config.url}:${config.searchPath}:${config.secretRef}:search:$maxResults:${config.provider}:$query"
        cache.get(key)?.let { return it }
        if (!limiter.acquire()) return ToolResult(false, "Tool request rate limit exceeded")
        val result = fallback({
            val response = transport.post(config.url.trimEnd('/') + config.searchPath, headers(), buildJsonObject {
                put("query", query); put("maxResults", maxResults); put("provider", config.provider)
            })
            val root = Json.parseToJsonElement(response.body).jsonObject
            val hits = (root["results"] ?: root["data"]?.jsonObject?.get("results"))?.jsonArray ?: error("Gateway search missing results")
            val normalized = hits.take(maxResults).map { value ->
                val item = value.jsonObject
                val url = item["url"]?.jsonPrimitive?.content ?: error("Search URL missing")
                PublicWebTarget.validateUrl(url.toHttpUrl())
                SearchHit(item["title"]?.jsonPrimitive?.content.orEmpty(), url, item["snippet"]?.jsonPrimitive?.content.orEmpty(),
                    item["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0, item["citations"]?.jsonArray.orEmpty().map { (it as? JsonPrimitive)?.content ?: it.toString() })
            }
            ToolResult(true, "UNTRUSTED_WEB_DATA:\n" + Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SearchHit.serializer()), normalized), config.url)
        }, { local.webSearch(query) })
        if (result.ok) cache.put(key, result)
        return result
    }
    override suspend fun webFetch(url: String): ToolResult {
        PublicWebTarget.validateUrl(url.toHttpUrl())
        val key = "${config.url}:${config.fetchPath}:${config.secretRef}:fetch:$url"
        cache.get(key)?.let { return it }
        if (!limiter.acquire()) return ToolResult(false, "Tool request rate limit exceeded")
        val result = fallback({
            val response = transport.post(config.url.trimEnd('/') + config.fetchPath, headers(), buildJsonObject { put("url", url); put("mode", "reader") })
            val root = Json.parseToJsonElement(response.body).jsonObject
            require(root["ok"]?.jsonPrimitive?.booleanOrNull != false)
            val text = (root["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.content
                ?: (root["content"] as? JsonPrimitive)?.contentOrNull ?: root["text"]?.jsonPrimitive?.content
                ?: error("Gateway fetch missing content")
            ToolResult(true, "UNTRUSTED_WEB_DATA:\n" + text.take(100_000), url)
        }, { local.webFetch(url) })
        if (result.ok) cache.put(key, result)
        return result
    }
    private suspend fun fallback(remote: suspend () -> ToolResult, direct: suspend () -> ToolResult): ToolResult = try {
        remote().let { if (it.ok) it else direct() }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { direct() }
}
