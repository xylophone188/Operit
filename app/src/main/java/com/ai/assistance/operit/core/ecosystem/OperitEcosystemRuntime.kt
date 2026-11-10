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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.json.JSONObject

class AndroidProviderStateStore(context: Context) : ProviderStateStore {
    private val file = AtomicFile(File(context.noBackupFilesDir,"ecosystem/registry.json").apply { parentFile?.mkdirs() })
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override fun read(): ProviderRegistrySnapshot = try {json.decodeFromString(file.readFully().toString(Charsets.UTF_8))} catch(_:java.io.FileNotFoundException) {ProviderRegistrySnapshot()} catch(_:Exception) { ProviderRegistrySnapshot() }
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
    private val approvedPrompt:String? = null,
) : ModelInvoker {
    override suspend fun invoke(member: ComboMember, request: ModelRequest): Result<ModelResponse> = attempt {
        val p = snapshot.providers.single { it.id == member.providerId && it.enabled }
        require(p.models.any { it.id == member.modelId })
        if (p.existingConfigId == null || p.mode in setOf(AdapterMode.AGY_CLI, AdapterMode.AGY_ACP, AdapterMode.AGY_MCP, AdapterMode.AGY_DIRECT)) {
            val native = NativeAdapterInvoker(ProviderRegistry(object : ProviderStateStore {
                override fun read() = snapshot
                override fun write(snapshot: ProviderRegistrySnapshot) = error("Frozen task registry")
            }), secretStore, command=AndroidControlledCommand(context))
            return@attempt native.invoke(member, request).getOrThrow()
        }
        val profile = p.activeAuthProfileId?.let {id -> p.authProfiles.single {it.id==id}} ?: p.authProfiles.firstOrNull()
        val base = frozenConfigs[p.existingConfigId] ?: error("configured Operit profile is missing from task snapshot")
        val config = base.copy(apiEndpoint=base.apiEndpoint,modelName=member.modelId,
            apiKey=base.apiKey,
            customHeaders=JSONObject(base.customHeaders).apply {
                profile?.organizationId?.let { put("OpenAI-Organization",it) }
                profile?.projectId?.let { put("OpenAI-Project",it) }
            }.toString())
        val service = AIServiceFactory.createService(config,configs,context)
        val started = android.os.SystemClock.elapsedRealtime()
        val history=listOfNotNull(approvedPrompt?.let {PromptTurn(PromptTurnKind.SYSTEM,it)},PromptTurn(PromptTurnKind.USER,request.prompt))
        try {
            val result = StringBuilder()
            service.sendMessage(context,chatHistory=history,modelParameters=listOf(com.ai.assistance.operit.data.model.ModelParameter("max_tokens","max_tokens","max_tokens",defaultValue=request.maxOutputTokens,currentValue=request.maxOutputTokens,isEnabled=true,valueType=com.ai.assistance.operit.data.model.ParameterValueType.INT)),stream=true)
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
        private val sharedToolCache=TtlLruCache<String,ToolResult>()
        private val sharedToolLimiter=TokenBucket()
        @Volatile private var sharedRegistry:ProviderRegistry?=null
    }
    val registry = sharedRegistry ?: synchronized(OperitEcosystemRuntime::class.java) {
        sharedRegistry ?: ProviderRegistry(AndroidProviderStateStore(app)).also {sharedRegistry=it}
    }
    val secrets: SecretStore = AndroidKeystoreSecretStore(app)
    val proposals = AndroidProposalRepository(app)
    val evolution = EvolutionRuntime { proposals.current(it) }
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val dreamStore = FileDreamStore(File(app.noBackupFilesDir, "ecosystem/dream.json"))
    val services = EcosystemServicesStore(app)
    private val dreamEngine = DreamEngine(dreamStore, services.read().openViking?.let { c -> OpenVikingClient(c, { secrets.get(c.secretRef) }) })

    private val currentRecall = kotlinx.coroutines.sync.Mutex()
    private suspend fun recallFor(request: ModelRequest): String = withTimeoutOrNull(1500) {
        currentRecall.withLock {
            val config = services.read().openViking ?: return@withLock ""
            runCatching { OpenVikingClient(config, { secrets.get(config.secretRef) }).recall(request.prompt) }.getOrDefault("")
        }
    }.orEmpty()
    init { DreamWorker.schedule(app) }
    fun dream(config: OpenVikingConfig? = services.read().openViking): DreamEngine {
        config?.let { c -> dreamEngine.remote = OpenVikingClient(c, { secrets.get(c.secretRef) }) }
        return dreamEngine
    }
    fun tools(): ToolBroker = services.read().tools?.let { config -> GatewayToolBroker(config,OperitToolBroker(app),secret={config.secretRef?.let {secrets.get(it)}},cache=sharedToolCache,limiter=sharedToolLimiter) } ?: OperitToolBroker(app)
    fun health(providerId:String):HealthState = sharedHealth[providerId] ?: HealthState()
    suspend fun run(request: ModelRequest, verify: Boolean=true, review: Boolean=true): Result<WorkflowResult> = attempt {
        val snapshot = evolution.effective(registry.snapshot())
        val selected = snapshot.combos.singleOrNull { it.id == snapshot.bindings[request.function] } ?: error("no combo bound to ${request.function}")
        val enabled = selected.copy(members=selected.members.filter { m -> snapshot.providers.any { it.id==m.providerId && it.enabled } })
        require(enabled.members.isNotEmpty()) { "all combo providers disabled" }
        val manager = ModelConfigManager(app)
        val roles = snapshot.bindings.mapValues { (_,id)->snapshot.combos.single {it.id==id} }
        val used = (listOf(enabled)+roles.filterKeys {it in setOf(WorkFunction.VERIFICATION,WorkFunction.REVIEW)}.values).flatMap {it.members}.map {it.providerId}.toSet()
        val frozen = snapshot.providers.filter {it.id in used}.mapNotNull { p -> p.existingConfigId?.let { id -> id to (manager.getModelConfig(id) ?: error("missing config")) } }.toMap()
        val values = snapshot.providers.filter {it.id in used}.flatMap { p->p.authProfiles.filter {it.secretRef!=null}.map {credentialKey(p,it)} }.associateWith {secrets.get(it)}
        val oauthRefs=snapshot.providers.filter {it.id in used}.flatMap {it.authProfiles}.mapNotNull {it.clientSecretRef}
        val allValues=values+oauthRefs.associateWith {secrets.get(it)}
        val frozenSecrets=object:SecretStore {
            override fun get(ref:String)=allValues[ref]
            override fun put(ref:String,value:String)=error("immutable task credentials")
            override fun remove(ref:String)=error("immutable task credentials")
        }
        val engine = ComboEngine(OperitModelInvoker(app,snapshot,frozenSecrets,manager,frozen),states=sharedHealth,
            capabilities = { member -> snapshot.providers.single { it.id==member.providerId }.models.single { it.id==member.modelId }.capabilities })
        val memoryContext=recallFor(request).orEmpty()
        val effective=evolution.prompt(request).let {if(memoryContext.isBlank()) it else it.copy(prompt=memoryContext+"\n\n"+it.prompt)}
        try {
            AgentWorkflowEngine(engine,maxBudget=24,limits=evolution.budget()).run(effective,enabled,verify,review,roles).getOrThrow().also { result ->
                background.launch { dream().consolidate(DreamInput(sessionSummaries=listOf(result.primary.text), reviews=result.auxiliaryFindings.values.toList())) }
            }
        } catch(e:kotlinx.coroutines.CancellationException) {throw e} catch(e:Exception) {
            background.launch {dream().consolidate(DreamInput(toolFailures=listOf("${request.function}: ${e.javaClass.simpleName}")))}
            throw e
        }
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
