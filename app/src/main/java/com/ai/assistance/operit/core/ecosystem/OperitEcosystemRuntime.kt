package com.ai.assistance.operit.core.ecosystem

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.api.chat.llmprovider.AIServiceFactory
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

class AndroidProviderStateStore(context: Context) : ProviderStateStore {
    private val file = AtomicFile(File(context.noBackupFilesDir,"ecosystem/registry.json").apply { parentFile?.mkdirs() })
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Synchronized override fun read(): ProviderRegistrySnapshot = if (!file.baseFile.exists()) ProviderRegistrySnapshot() else json.decodeFromString(file.readFully().toString(Charsets.UTF_8))
    @Synchronized override fun write(snapshot: ProviderRegistrySnapshot) {
        val output = file.startWrite()
        try { output.write(json.encodeToString(snapshot).toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
}

/** Existing provider implementation is the protocol boundary; no duplicated SSE/OAuth stack. */
class OperitModelInvoker(
    private val context: Context,
    private val snapshot: ProviderRegistrySnapshot,
    private val secretStore: SecretStore,
    private val configs: ModelConfigManager = ModelConfigManager(context),
    private val frozenConfigs: Map<String, ModelConfigData> = emptyMap(),
) : ModelInvoker {
    override suspend fun invoke(member: ComboMember, request: ModelRequest): Result<ModelResponse> = attempt {
        val p = snapshot.providers.single { it.id == member.providerId && it.enabled }
        require(p.models.any { it.id == member.modelId })
        require(p.mode in setOf(AdapterMode.OPENAI_REST,AdapterMode.AGY_REST,AdapterMode.CODEX_RESPONSES)) {
            "${p.mode} requires the PC stdio/direct gateway; not a phone-native transport"
        }
        val profile = p.authProfiles.firstOrNull()
        val key = profile?.takeIf {it.secretRef!=null}?.let { secretStore.get(credentialKey(p,it)) ?: error("NEEDS_CREDENTIALS") }.orEmpty()
        val baseUrl=p.baseUrl.trimEnd('/')
        val endpoint = baseUrl + when {
            baseUrl.endsWith("/chat/completions") || baseUrl.endsWith("/responses") -> ""
            p.mode == AdapterMode.CODEX_RESPONSES -> "/responses"
            else -> "/chat/completions"
        }
        val providerType = if (p.mode == AdapterMode.CODEX_RESPONSES) ApiProviderType.OPENAI_RESPONSES else ApiProviderType.OPENAI_GENERIC
        val base = p.existingConfigId?.let { frozenConfigs[it] ?: error("configured Operit profile is missing from task snapshot") }
            ?: ModelConfigData(id="ecosystem-${p.id}",name=p.displayName,apiProviderType=providerType,apiProviderTypeId=providerType.name)
        val config = base.copy(apiEndpoint=if(p.existingConfigId!=null) base.apiEndpoint else endpoint,modelName=member.modelId,
            apiKey=if(p.existingConfigId==null) key else base.apiKey,
            customHeaders=(if(p.existingConfigId!=null) JSONObject(base.customHeaders) else JSONObject(p.headers)).apply {
                profile?.organizationId?.let { put("OpenAI-Organization",it) }
                profile?.projectId?.let { put("OpenAI-Project",it) }
            }.toString())
        val service = AIServiceFactory.createService(config,configs,context)
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val result = StringBuilder()
            service.sendMessage(context,chatHistory=listOf(PromptTurn(PromptTurnKind.USER,request.prompt)),modelParameters=listOf(com.ai.assistance.operit.data.model.ModelParameter("max_tokens","max_tokens","max_tokens",defaultValue=request.maxOutputTokens,currentValue=request.maxOutputTokens,isEnabled=true,valueType=com.ai.assistance.operit.data.model.ParameterValueType.INT)),stream=true)
                .collect { result.append(it) }
            require(result.isNotBlank()) { "empty upstream stream" }
            ModelResponse(result.toString(),p.id,member.modelId,android.os.SystemClock.elapsedRealtime()-started,service.outputTokenCount)
        } finally { service.cancelStreaming(); service.release() }
    }
}

class OperitEcosystemRuntime(context: Context) {
    private val app = context.applicationContext
    companion object {
        private val sharedHealth=java.util.concurrent.ConcurrentHashMap<String,HealthState>()
        @Volatile private var sharedRegistry:ProviderRegistry?=null
    }
    val registry = sharedRegistry ?: synchronized(OperitEcosystemRuntime::class.java) {
        sharedRegistry ?: ProviderRegistry(AndroidProviderStateStore(app)).also {sharedRegistry=it}
    }
    val secrets: SecretStore = AndroidKeystoreSecretStore(app)
    suspend fun run(request: ModelRequest, verify: Boolean=true, review: Boolean=true): Result<WorkflowResult> = attempt {
        val snapshot = registry.snapshot()
        val selected = snapshot.combos.singleOrNull { it.id == snapshot.bindings[request.function] } ?: error("no combo bound to ${request.function}")
        val enabled = selected.copy(members=selected.members.filter { m -> snapshot.providers.any { it.id==m.providerId && it.enabled } })
        require(enabled.members.isNotEmpty()) { "all combo providers disabled" }
        val manager = ModelConfigManager(app)
        val roles = snapshot.bindings.mapValues { (_,id)->snapshot.combos.single {it.id==id} }
        val used = (listOf(enabled)+roles.filterKeys {it in setOf(WorkFunction.VERIFICATION,WorkFunction.REVIEW)}.values).flatMap {it.members}.map {it.providerId}.toSet()
        val frozen = snapshot.providers.filter {it.id in used}.mapNotNull { p -> p.existingConfigId?.let { id -> id to (manager.getModelConfig(id) ?: error("missing config")) } }.toMap()
        val values = snapshot.providers.filter {it.id in used}.flatMap { p->p.authProfiles.filter {it.secretRef!=null}.map {credentialKey(p,it)} }.associateWith {secrets.get(it)}
        val frozenSecrets=object:SecretStore {
            override fun get(ref:String)=values[ref]
            override fun put(ref:String,value:String)=error("immutable task credentials")
            override fun remove(ref:String)=error("immutable task credentials")
        }
        AgentWorkflowEngine(ComboEngine(OperitModelInvoker(app,snapshot,frozenSecrets,manager,frozen),states=sharedHealth),maxBudget=24).run(request,enabled,verify,review,roles).getOrThrow()
    }
    suspend fun test(provider: ProviderDescriptor): Result<ModelResponse> = attempt {
        ProviderRegistry.validate(ProviderRegistrySnapshot(providers=listOf(provider)))
        require(registry.find(provider.id)==provider) {"connectivity test requires the exact persisted provider snapshot"}
        val snapshot = registry.snapshot().copy(providers=listOf(provider.copy(enabled=true)))
        val manager=ModelConfigManager(app)
        val frozen=provider.existingConfigId?.let { id -> mapOf(id to (manager.getModelConfig(id) ?: error("missing config"))) }.orEmpty()
        OperitModelInvoker(app,snapshot,secrets,manager,frozen).invoke(ComboMember(provider.id,provider.models.first().id),ModelRequest("Reply OK only",WorkFunction.VERIFICATION,16)).getOrThrow()
    }
}
