package com.ai.assistance.operit.core.ecosystem

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RoundTwoFailureContractsTest {
    private val request=ModelRequest("task",WorkFunction.EXECUTION,10)
    private val member=ComboMember("p","m",maxRetries=3)
    private val combo=ComboDefinition("c",listOf(member))

    @Test fun retryBudgetIsChargedBeforeEveryAdmission() = runBlocking {
        var calls=0
        val engine=ComboEngine(ModelInvoker {_,_->calls++;Result.failure(IOException("fail"))},breakerThreshold=100)
        val result=runCatching {engine.execute(combo,request,BudgetController(WorkflowBudget(maxSteps=2))).getOrThrow()}
        assertTrue(result.exceptionOrNull() is BudgetExceeded);assertEquals(2,calls)
    }
    @Test fun tokenBudgetRejectsBeforeProviderInvocation() = runBlocking {
        var calls=0
        val engine=ComboEngine(ModelInvoker {_,_->calls++;Result.success(ModelResponse("ok","p","m",0))})
        assertTrue(runCatching {engine.execute(combo,request,BudgetController(WorkflowBudget(maxTokens=5))).getOrThrow()}.exceptionOrNull() is BudgetExceeded)
        assertEquals(0,calls)
    }
    @Test fun workflowTotalDeadlineIncludesParallelReview() = runBlocking {
        val engine=ComboEngine(ModelInvoker {_,r->if(r.function!=WorkFunction.EXECUTION) delay(200);Result.success(ModelResponse("ok","p","m",0))})
        val result=runCatching {AgentWorkflowEngine(engine,limits=WorkflowBudget(maxWallClockMs=50)).run(request,combo.copy(members=listOf(member.copy(maxRetries=0)))).getOrThrow()}
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }
    @Test fun structured429DoesNotTriggerFromProse() {
        assertEquals(SupervisorStatus.FAILED,SupervisorContract.status(1,"{\"type\":\"error\",\"message\":\"the docs mention 429\"}"))
    }
    @Test fun ovCreateCollisionIsIdempotentReplace() = runBlocking {
        val modes=mutableListOf<String>()
        val ov=OpenVikingClient(OpenVikingConfig(),{null},JsonTransport {_,_,body->
            modes+=body["mode"]!!.jsonPrimitive.content
            if(modes.size==1)throw ProviderHttpException(409)
            WireResponse("{\"status\":\"ok\"}")
        })
        ov.sync(DreamMemory("id",MemoryKind.TOOL,"same",0,100))
        assertEquals(listOf("create","replace"),modes)
    }
    @Test fun malformedOrFailedSseNeverProducesSuccess() {
        listOf(
            "data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\"}}\n\n",
            "data: not json\n\n",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
        ).forEach {assertTrue(runCatching {NativeAdapterInvoker.responses(WireResponse(it))}.isFailure)}
        assertTrue(runCatching {NativeAdapterInvoker.cloud(WireResponse("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}"))}.isFailure)
    }
    @Test fun retryAfterNumericAndHttpDate() {
        assertEquals(5000L,OkHttpJsonTransport.retryAfter("5"))
        assertEquals(1000L,OkHttpJsonTransport.retryAfter("Thu, 01 Jan 1970 00:00:01 GMT",0))
        assertEquals(0L,OkHttpJsonTransport.retryAfter("invalid"))
    }
    @Test fun customAuthHeadersAndClientSecretsAreNotExported() {
        var snapshot=ProviderRegistrySnapshot()
        val registry=ProviderRegistry(object:ProviderStateStore {override fun read()=snapshot;override fun write(snapshotValue:ProviderRegistrySnapshot){snapshot=snapshotValue}})
        val profile=AuthProfile("a","oauth","ecosystem.p.a",customHeaders=mapOf("X-Custom" to "private-value"),clientSecretRef="ecosystem.p.a.client")
        registry.upsert(ProviderDescriptor("p","p","https://example.com/v1",models=listOf(ModelDescriptor("m")),authProfiles=listOf(profile)))
        val exported=registry.exportRedacted();assertFalse(exported.contains("private-value"));assertFalse(exported.contains("ecosystem.p.a.client"))
        assertTrue(runCatching {registry.import(Json.encodeToString(ProviderRegistrySnapshot.serializer(),snapshot))}.isFailure)
    }
    @Test fun evolutionCannotRedirectExistingCredentials() {
        val profile=AuthProfile("a","a","ecosystem.p.a")
        val p=ProviderDescriptor("p","p","https://example.com/v1",models=listOf(ModelDescriptor("m")),authProfiles=listOf(profile))
        val base=ProviderRegistrySnapshot(providers=listOf(p))
        val proposal=EvolutionProposal("id",1,"config",Json.encodeToString(ProviderRegistrySnapshot.serializer(),base.copy(providers=listOf(p.copy(baseUrl="https://evil.example/v1")))),"")
        assertTrue(runCatching {EvolutionRuntime{null}.validate(proposal,base)}.isFailure)
    }
    @Test fun routerUsesApprovedModelAndRollbackImmediately() {
        var proposal:EvolutionProposal?=null
        val p=ProviderDescriptor("p","p","https://example.com/v1",models=listOf(ModelDescriptor("wrong"),ModelDescriptor("m")))
        val base=ProviderRegistrySnapshot(providers=listOf(p),combos=listOf(ComboDefinition("c",listOf(member))))
        val registry=ProviderRegistry(object:ProviderStateStore{override fun read()=base;override fun write(snapshot:ProviderRegistrySnapshot)=Unit})
        val evolution=EvolutionRuntime{kind->if(kind=="policy")proposal else null}
        val router=FunctionalModelRouter(registry,evolution=evolution)
        assertEquals("wrong",router.route(RouteRequest(WorkFunction.EXECUTION))?.modelId)
        proposal=EvolutionProposal("policy",1,"policy","{\"bindings\":{\"EXECUTION\":\"c\"}}","")
        assertEquals("m",router.route(RouteRequest(WorkFunction.EXECUTION))?.modelId)
        proposal=null;assertEquals("wrong",router.route(RouteRequest(WorkFunction.EXECUTION))?.modelId)
    }
    @Test fun gatewayActualAlmaFetchAndCitationShapes() = runBlocking {
        var localCalls=0
        val local=object:ToolBroker {override suspend fun webSearch(query:String):ToolResult {localCalls++;return ToolResult(false,"no")};override suspend fun webFetch(url:String):ToolResult{localCalls++;return ToolResult(false,"no")}}
        val gateway=GatewayToolBroker(ToolGatewayConfig(),local,JsonTransport{url,_,_->
            if(url.endsWith("/search"))WireResponse("{\"results\":[{\"url\":\"https://example.com\",\"title\":\"t\",\"citations\":[{\"provider\":\"brave\",\"rank\":1}]}]}")
            else WireResponse("{\"content\":{\"text\":\"real reader\",\"format\":\"markdown\"}}")
        })
        assertTrue(gateway.webSearch("q").content.contains("brave"))
        assertTrue(gateway.webFetch("https://example.com").content.contains("real reader"));assertEquals(0,localCalls)
    }
    @Test fun dispatchParentCancellationPropagates() = runBlocking {
        val dispatcher=SubagentDispatcher{_,_->throw CancellationException("parent")}
        assertTrue(runCatching {dispatcher.dispatch(listOf(ExecutorSubagent()),ContextHandoff("g"),BudgetController(WorkflowBudget()))}.exceptionOrNull() is CancellationException)
    }
}
