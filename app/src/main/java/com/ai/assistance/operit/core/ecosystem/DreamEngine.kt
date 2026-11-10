package com.ai.assistance.operit.core.ecosystem

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class MemoryKind { EXPERIENCE, PATTERN, ENTITY, TOOL }
@Serializable data class DreamInput(val sessionSummaries: List<String> = emptyList(), val toolFailures: List<String> = emptyList(), val reviews: List<String> = emptyList())
@Serializable data class DreamMemory(val id: String, val kind: MemoryKind, val text: String, val createdAt: Long, val expiresAt: Long, val synced: Boolean = false)
@Serializable data class DreamState(val memories: List<DreamMemory> = emptyList(), val lastRunAt: Long = 0, val lastError: String? = null)
@Serializable data class OpenVikingConfig(val url: String = "http://127.0.0.1:1933", val secretRef: String = "openviking", val account: String = "default", val user: String = "operit", val rootUri: String = "viking://user/memories/operit")
interface DreamStore { fun read(): DreamState; fun write(state: DreamState) }

class FileDreamStore(private val file: File) : DreamStore {
    private val json = Json { ignoreUnknownKeys = true }
    override fun read(): DreamState = synchronized(lock) {
        if (!file.exists()) return@synchronized DreamState()
        runCatching { json.decodeFromString(DreamState.serializer(), file.readText()) }.getOrElse {
            val backup = File(file.parentFile, file.name + ".pending")
            if (backup.exists()) json.decodeFromString(DreamState.serializer(), backup.readText()) else DreamState()
        }
    }
    override fun write(state: DreamState) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".pending")
        java.io.FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(DreamState.serializer(), state).toByteArray()); output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Cannot commit Dream memory store" }
    }
    companion object { private val lock = Any() }
}

class OpenVikingClient(private val config: OpenVikingConfig, private val secret: () -> String?, private val transport: JsonTransport = OkHttpJsonTransport()) {
    private fun headers() = buildMap {
        put("X-OpenViking-Account", config.account); put("X-OpenViking-User", config.user)
        secret()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }
    suspend fun recall(query: String, limit: Int = 5): String {
        val response = transport.post(config.url.trimEnd('/') + "/api/v1/search/search", headers(), buildJsonObject {
            put("query", query); put("limit", limit.coerceIn(1, 20)); put("target_uri", config.rootUri)
        })
        val data = Json.parseToJsonElement(response.body).jsonObject
        val status = data["status"]?.jsonPrimitive?.contentOrNull
        check(status == null || status == "ok" || status == "success") { "OpenViking search rejected: $status" }
        // Remote memory is untrusted context, never system instructions. Escape closing delimiters.
        val content = (data["result"] ?: data).toString().take(24_000).replace("<", "\\u003c").replace(">", "\\u003e")
        return "<openviking-context untrusted=\"true\">\n$content\n</openviking-context>"
    }
    suspend fun sync(memory: DreamMemory) {
        suspend fun write(mode: String): WireResponse = transport.post(config.url.trimEnd('/') + "/api/v1/content/write", headers(), buildJsonObject {
            put("uri", "${config.rootUri}/${memory.kind.name.lowercase()}/${memory.id}.md")
            put("content", memory.text); put("mode", mode)
        })
        val response = try { write("create") } catch(e: ProviderHttpException) {
            if(e.status != 409) throw e
            write("replace") // Stable content-derived URI: overwrite is idempotent after lost acknowledgements.
        }
        val writeStatus = Json.parseToJsonElement(response.body).jsonObject["status"]?.jsonPrimitive?.contentOrNull
        check(writeStatus == null || writeStatus == "ok" || writeStatus == "success") { "OpenViking write rejected: $writeStatus" }
    }
}

/** Offline-first bounded outbox; deterministic content IDs make retries idempotent remotely. */
class DreamEngine(private val store: DreamStore, @Volatile var remote: OpenVikingClient?, private val clock: () -> Long = System::currentTimeMillis) {
    companion object { private val mutex = Mutex() }
    fun state(): DreamState = store.read()
    suspend fun consolidate(input: DreamInput): DreamState = withContext(Dispatchers.IO) { mutex.withLock {
        require(input.sessionSummaries.size + input.toolFailures.size + input.reviews.size <= 100) {"Dream input batch too large"}
        val now = clock()
        val existing = store.read().memories.filter { it.expiresAt > now }.associateBy { it.id }.toMutableMap()
        val candidates = input.sessionSummaries.map { MemoryKind.EXPERIENCE to it } +
            input.toolFailures.map { MemoryKind.TOOL to it } + input.reviews.map { MemoryKind.PATTERN to it } +
            input.sessionSummaries.filter { Regex("(?i)entity:|实体[:：]").containsMatchIn(it) }.map { MemoryKind.ENTITY to it }
        for ((kind, raw) in candidates) {
            val text = raw.trim().replace(Regex("\\s+"), " ").take(4096)
            if (text.isBlank()) continue
            val id = MessageDigest.getInstance("SHA-256").digest("$kind:$text".toByteArray()).joinToString("") { "%02x".format(it) }
            existing.putIfAbsent(id, DreamMemory(id, kind, text, now, now + 30L * 24 * 60 * 60 * 1000))
        }
        val result = DreamState(existing.values.sortedByDescending { it.createdAt }.take(1000), now)
        store.write(result)
        result
    } }
    suspend fun syncPending(): DreamState = withContext(Dispatchers.IO) { mutex.withLock {
        var state = store.read()
        val client = remote ?: return@withLock state
        for (memory in state.memories.filter { !it.synced }.take(25)) {
            try {
                client.sync(memory)
                state = state.copy(memories = state.memories.map { if (it.id == memory.id) it.copy(synced = true) else it }, lastError = null)
                store.write(state)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                state = state.copy(lastError = e.javaClass.simpleName)
                store.write(state)
                break // Fast fail once; no main-chat wait or blind retry loop.
            }
        }
        state
    } }
}
