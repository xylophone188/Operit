package com.ai.assistance.operit.core.ecosystem

import kotlinx.serialization.Serializable

@Serializable enum class ProviderCapability { CHAT, STREAMING, TOOLS, RESPONSES, SSE, LOCAL, REMOTE }
@Serializable data class AuthProfile(val id: String, val label: String, val secretRef: String? = null, val organizationId: String? = null, val projectId: String? = null)
@Serializable data class ModelDescriptor(val id: String, val displayName: String = id, val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CHAT), val contextWindow: Int = 0)
@Serializable enum class AdapterMode { OPENAI_REST, CODEX_RESPONSES, AGY_REST, AGY_CLI, AGY_ACP, AGY_MCP, AGY_DIRECT }
@Serializable data class ProviderDescriptor(
    val id: String, val displayName: String, val baseUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val models: List<ModelDescriptor> = emptyList(),
    val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CHAT),
    val authProfiles: List<AuthProfile> = emptyList(),
    val enabled: Boolean = true, val priority: Int = 0,
    val mode: AdapterMode = AdapterMode.OPENAI_REST,
    val existingConfigId: String? = null,
)
@Serializable enum class WorkFunction { PLANNING, RESEARCH, CODING, EXECUTION, VERIFICATION, REVIEW, SUMMARIZATION, EVOLUTION }
@Serializable data class ComboMember(val providerId: String, val modelId: String, val weight: Double = 1.0, val timeoutMs: Long = 15_000, val maxRetries: Int = 0)
@Serializable data class ComboDefinition(val id: String, val members: List<ComboMember>, val autoScore: Boolean = true, val totalTimeoutMs: Long = 60_000)
@Serializable data class ProviderRegistrySnapshot(
    val providers: List<ProviderDescriptor> = emptyList(),
    val bindings: Map<WorkFunction, String> = emptyMap(),
    val combos: List<ComboDefinition> = emptyList(),
    val revision: Long = 0,
)
data class RouteRequest(val function: WorkFunction, val requiredCapabilities: Set<ProviderCapability> = setOf(ProviderCapability.CHAT), val preferredModel: String? = null)
data class RouteDecision(val providerId: String, val modelId: String, val score: Double, val reason: String)
data class HealthState(val successes: Long = 0, val failures: Long = 0, val consecutiveFailures: Int = 0, val openedUntilMs: Long = 0, val latencyMs: Long = 0) { fun isOpen(nowMs: Long) = openedUntilMs > nowMs }
data class ModelRequest(val prompt: String, val function: WorkFunction, val maxOutputTokens: Int = 1024)
data class ModelResponse(val text: String, val providerId: String, val modelId: String, val latencyMs: Long, val outputTokens: Long = 0)
fun interface ModelInvoker { suspend fun invoke(member: ComboMember, request: ModelRequest): Result<ModelResponse> }
interface ProviderStateStore { fun read(): ProviderRegistrySnapshot; fun write(snapshot: ProviderRegistrySnapshot) }
/** Bind ciphertext namespace to the exact endpoint, preventing ref reuse after reconfiguration. */
fun credentialKey(provider:ProviderDescriptor,profile:AuthProfile):String {
    require(profile.secretRef!=null)
    val origin=java.security.MessageDigest.getInstance("SHA-256").digest(provider.baseUrl.toByteArray()).joinToString(""){"%02x".format(it)}
    return profile.secretRef+":"+origin
}
interface SecretStore { fun put(ref: String, value: String); fun get(ref: String): String?; fun remove(ref: String) }
interface ToolBroker { suspend fun webSearch(query: String): ToolResult; suspend fun webFetch(url: String): ToolResult }
data class ToolResult(val ok: Boolean, val content: String, val source: String? = null)

/** Do not absorb parent cancellation as provider failure. */
suspend fun <T> attempt(block: suspend () -> T): Result<T> = try { Result.success(block()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
