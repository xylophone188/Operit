package com.ai.assistance.operit.core.ecosystem

import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

fun interface ControlledCommand { suspend fun execute(executable: String, arguments: List<String>, stdin: String): String }

/** No shell interpolation, arbitrary executable, or prompt-as-option injection. Termux is explicit opt-in. */
class TermuxCommand(private val binDirectory: () -> java.io.File = { java.io.File(requireNotNull(System.getenv("PREFIX")) {"Termux PREFIX or package directory required"},"bin") }) : ControlledCommand {
    override suspend fun execute(executable: String, arguments: List<String>, stdin: String): String = withContext(Dispatchers.IO) {
        require(executable in setOf("ask-agy", "agy", "agy-acp", "agy-mcp"))
        val path = java.io.File(binDirectory(),executable)
        require(path.canExecute()) { "Termux executable inaccessible; configure HTTP PC bridge" }
        val process = ProcessBuilder(listOf(path.absolutePath) + arguments).redirectErrorStream(true).start()
        try {
            require(stdin.toByteArray().size <= 256_000) { "CLI input too large" }
            withContext(Dispatchers.IO) { process.outputStream.bufferedWriter().use { it.write(stdin) } }
            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val deadline = System.nanoTime() + 60_000_000_000L
            val buffer = CharArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                require(System.nanoTime() < deadline) { "CLI deadline exceeded" }
                val n = withContext(Dispatchers.IO) { reader.read(buffer) }
                if (n < 0) break
                require(output.length + n <= 1_000_000) { "CLI output too large" }
                output.append(buffer, 0, n)
            }
            check(process.waitFor() == 0) { "CLI process failed" }
            output.toString()
        } finally { process.destroy() }
    }
}

class NativeAdapterInvoker(
    private val registry: ProviderRegistry, private val secrets: SecretStore,
    private val http: JsonTransport = OkHttpJsonTransport(), private val command: ControlledCommand = TermuxCommand(),
) : ModelInvoker {
    override suspend fun invoke(member: ComboMember, request: ModelRequest): Result<ModelResponse> = attempt {
        val started = System.nanoTime()
        val provider = requireNotNull(registry.find(member.providerId))
        require(provider.enabled && provider.models.any {it.id==member.modelId})
        if(provider.locality==ExecutionLocality.TERMUX_CLI) require(provider.mode in setOf(AdapterMode.AGY_CLI,AdapterMode.AGY_ACP,AdapterMode.AGY_MCP))
        val profile = provider.activeAuthProfileId?.let { id -> provider.authProfiles.single {it.id==id} } ?: provider.authProfiles.firstOrNull()
        val token = profile?.secretRef?.let { secrets.get(credentialKey(provider, profile)) ?: error("NEEDS_CREDENTIALS") }
        val headers = provider.headers + profile?.customHeaders.orEmpty() + buildMap {
            token?.takeIf {profile?.type!=AuthType.OAUTH_REFRESH}?.let { put("Authorization", "Bearer $it") }
            profile?.organizationId?.let { put("OpenAI-Organization", it) }
            profile?.projectId?.let { put("OpenAI-Project", it) }
        }
        val base = provider.baseUrl.trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/responses")
        val result = withTimeout(member.timeoutMs) {
            when (provider.mode) {
                AdapterMode.OPENAI_REST, AdapterMode.AGY_REST -> chat(http.post("$base/chat/completions", headers, buildJsonObject {
                    put("model", member.modelId); put("stream", true); put("max_tokens", request.maxOutputTokens)
                    putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", request.prompt) }) }
                }))
                AdapterMode.CODEX_RESPONSES -> {
                    val auth = headers + (token?.let { accountId(it) }?.let { mapOf("ChatGPT-Account-Id" to it) } ?: emptyMap())
                    responses(http.post("$base/responses", auth, responsesBody(member.modelId, request)))
                }
                AdapterMode.AGY_DIRECT -> {
                    require(profile?.type == AuthType.OAUTH_REFRESH && !token.isNullOrBlank()) { "OAuth refresh profile required" }
                    require(java.net.URI(profile.tokenUrl).scheme=="https") {"OAuth token endpoint must use TLS"}
                    val refresh = http.post(profile.tokenUrl, mapOf("Content-Type" to "application/x-www-form-urlencoded"), buildJsonObject {
                        put("grant_type", "refresh_token"); put("refresh_token", token); put("client_id", profile.clientId)
                        profile.clientSecretRef?.let { secrets.get(it) }?.let { put("client_secret", it) }
                    })
                    val access = Json.parseToJsonElement(refresh.body).jsonObject["access_token"]?.jsonPrimitive?.contentOrNull
                    require(!access.isNullOrBlank()) { "OAuth response lacks access_token" }
                    cloud(http.post("$base/v1internal:streamGenerateContent?alt=sse", headers + ("Authorization" to "Bearer $access"), cloudBody(member.modelId, request, profile.projectId)))
                }
                AdapterMode.AGY_MCP -> if(provider.locality==ExecutionLocality.TERMUX_CLI) {
                    require(provider.bridgeTool in setOf("ask_antigravity","ask_gemini_flash","ask_claude_sonnet"))
                    val input=listOf(
                        buildJsonObject {put("jsonrpc","2.0");put("id",0);put("method","initialize");putJsonObject("params"){put("protocolVersion","2025-03-26");putJsonObject("capabilities"){};putJsonObject("clientInfo"){put("name","operit");put("version","1")}}},
                        buildJsonObject {put("jsonrpc","2.0");put("method","notifications/initialized")},
                        buildJsonObject {put("jsonrpc","2.0");put("id",1);put("method","tools/call");putJsonObject("params"){put("name",provider.bridgeTool);putJsonObject("arguments"){put("prompt",request.prompt);put("model",member.modelId)}}}
                    ).joinToString("\n",postfix="\n")
                    val events=command.execute("agy-mcp",emptyList(),input).lineSequence().mapNotNull {runCatching {Json.parseToJsonElement(it).jsonObject}.getOrNull()}.toList()
                    val result=events.lastOrNull {it["id"]==JsonPrimitive(1)} ?: error("Missing stdio MCP result")
                    check(result["error"]==null)
                    mcpText(result["result"]!!.jsonObject)
                } else mcp(base, headers, provider.bridgeTool, member.modelId, request)
                AdapterMode.AGY_ACP -> if (provider.locality == ExecutionLocality.TERMUX_CLI) {
                    command.execute("agy-acp", listOf("-m", member.modelId), request.prompt)
                } else acp(base, headers, request, member.modelId)
                AdapterMode.AGY_CLI -> {
                    require(provider.locality == ExecutionLocality.TERMUX_CLI)
                    val args = if (provider.cliExecutable == "agy") listOf("-p", request.prompt, "--model", member.modelId)
                        else listOf("-m", member.modelId)
                    val raw = command.execute(provider.cliExecutable, args, if (provider.cliExecutable == "ask-agy") request.prompt else "")
                    if (raw.trimStart().startsWith("{")) runCatching { mcpText(Json.parseToJsonElement(raw).jsonObject["result"]!!.jsonObject) }.getOrElse { raw } else raw
                }
            }
        }
        require(result.isNotBlank()) { "Adapter produced empty output" }
        ModelResponse(result, provider.id, member.modelId, (System.nanoTime() - started) / 1_000_000)
    }
    private suspend fun rpc(base: String, headers: Map<String, String>, id: Int, method: String, params: JsonObject): JsonElement {
        val wire = http.post(base, headers, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        val value = WireEvents.data(wire.body).lastOrNull { it["id"] == JsonPrimitive(id) } ?: error("Missing JSON-RPC response $id")
        check(value["error"] == null) { "JSON-RPC error" }
        return value["result"] ?: error("Missing JSON-RPC result")
    }

    private suspend fun mcp(base: String, headers: Map<String, String>, tool: String, model: String, request: ModelRequest): String {
        require(tool in setOf("ask_antigravity", "ask_gemini_flash", "ask_claude_sonnet"))
        val init = http.post(base, headers + ("Accept" to "application/json, text/event-stream"), buildJsonObject {
            put("jsonrpc","2.0"); put("id",0); put("method","initialize")
            putJsonObject("params") { put("protocolVersion","2025-03-26"); putJsonObject("capabilities") {}; putJsonObject("clientInfo") {put("name","operit");put("version","1")} }
        })
        val initialized=WireEvents.data(init.body).singleOrNull {it["id"]==JsonPrimitive(0)} ?: error("Missing MCP initialization")
        check(initialized["error"]==null && initialized["result"]!=null) {"MCP initialization failed"}
        val sessionHeaders=headers + mapOf("Accept" to "application/json, text/event-stream", "MCP-Protocol-Version" to "2025-03-26") + init.headers.entries.firstOrNull {it.key.equals("Mcp-Session-Id",true)}?.let {mapOf("Mcp-Session-Id" to it.value)}.orEmpty()
        http.post(base,sessionHeaders,buildJsonObject {put("jsonrpc","2.0");put("method","notifications/initialized")})
        val result = rpc(base, sessionHeaders, 1, "tools/call", buildJsonObject {
            put("name", tool); putJsonObject("arguments") { put("prompt", request.prompt); put("model", model) }
        }).jsonObject
        return mcpText(result)
    }
    private fun mcpText(result:JsonObject):String {
        check(result["isError"]?.jsonPrimitive?.booleanOrNull != true) { "MCP tool failed" }
        return result["content"]?.jsonArray.orEmpty().joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
    }
    private suspend fun acp(base: String, headers: Map<String, String>, request: ModelRequest, model: String): String {
        rpc(base, headers, 1, "initialize", buildJsonObject { put("protocolVersion", "1"); putJsonObject("clientCapabilities") {} })
        val session = rpc(base, headers, 2, "session/new", buildJsonObject { put("cwd", "."); put("model",model); putJsonArray("mcpServers") {} }).jsonObject["sessionId"]?.jsonPrimitive?.content ?: error("Missing ACP session")
        // HTTP bridge aggregates ACP notifications and terminal session/prompt result.
        val wire = http.post(base, headers, buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 3); put("method", "session/prompt")
            putJsonObject("params") { put("sessionId", session); putJsonArray("prompt") { add(buildJsonObject { put("type", "text"); put("text", request.prompt) }) } }
        })
        val events = WireEvents.data(wire.body)
        val final = events.lastOrNull { it["id"] == JsonPrimitive(3) } ?: error("Interrupted ACP stream")
        check(final["error"] == null) { "ACP prompt failed" }
        val updates = events.filter { it["method"]?.jsonPrimitive?.contentOrNull == "session/update" }.joinToString("") {
            it["params"]?.jsonObject?.get("update")?.jsonObject?.get("content")?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        }
        return updates.ifBlank { final["result"]?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty() }
    }
    companion object {
        fun accountId(jwt: String): String? = runCatching {
            val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8)).jsonObject
            (payload["https://api.openai.com/auth"] as? JsonObject)?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
                ?: payload["chatgpt_account_id"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        fun responsesBody(model: String, request: ModelRequest) = buildJsonObject {
            put("model", model); put("instructions", "Perform the requested ${request.function.name.lowercase()} task.")
            put("input", request.prompt); put("stream", true); put("max_output_tokens", request.maxOutputTokens)
        }
        fun cloudBody(model: String, request: ModelRequest, project: String?) = buildJsonObject {
            put("project", project ?: "aicode-consumers"); put("model", model); put("requestId", "req_${UUID.randomUUID()}")
            putJsonObject("request") {
                putJsonArray("contents") { add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", request.prompt) }) } }) }
                putJsonObject("generationConfig") { put("maxOutputTokens", request.maxOutputTokens) }
            }
        }
        fun responses(wire: WireResponse): String {
            val events = WireEvents.data(wire.body)
            check(events.none { it["type"]?.jsonPrimitive?.contentOrNull in setOf("error", "response.failed", "response.incomplete") }) { "Responses failed" }
            check(events.any { it["type"]?.jsonPrimitive?.contentOrNull == "response.completed" || it["status"]?.jsonPrimitive?.contentOrNull == "completed" }) { "Interrupted Responses stream" }
            val deltas = events.filter { it["type"]?.jsonPrimitive?.contentOrNull == "response.output_text.delta" }.joinToString("") { it["delta"]?.jsonPrimitive?.content.orEmpty() }
            return deltas.ifBlank {
                val response = events.last()["response"]?.jsonObject ?: events.last()
                response["output"]?.jsonArray.orEmpty().flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            }
        }
        fun chat(wire: WireResponse): String {
            val events = WireEvents.data(wire.body)
            check(events.none { it["error"] != null }) { "Chat stream error" }
            val choices = events.flatMap { it["choices"]?.jsonArray.orEmpty() }.map { it.jsonObject }
            check(choices.any { it["finish_reason"]?.jsonPrimitive?.contentOrNull != null }) { "Interrupted chat stream" }
            return choices.joinToString("") { (it["delta"] ?: it["message"])?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty() }
        }
        fun cloud(wire: WireResponse): String {
            val events = WireEvents.data(wire.body)
            check(events.none { it["error"] != null }) { "CloudCode stream error" }
            val candidates = events.flatMap { (it["response"]?.jsonObject ?: it)["candidates"]?.jsonArray.orEmpty() }.map { it.jsonObject }
            check(candidates.any { it["finishReason"]?.jsonPrimitive?.contentOrNull == "STOP" }) { "Interrupted CloudCode stream" }
            return candidates.flatMap { it["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty() }.filter { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true }.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        }
    }
}
