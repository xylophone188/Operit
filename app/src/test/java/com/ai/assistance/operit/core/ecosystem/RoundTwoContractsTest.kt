package com.ai.assistance.operit.core.ecosystem

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RoundTwoContractsTest {
    private class Store(var value: ProviderRegistrySnapshot = ProviderRegistrySnapshot()) : ProviderStateStore {
        override fun read() = value
        override fun write(snapshot: ProviderRegistrySnapshot) { value = snapshot }
    }
    private class Secrets : SecretStore {
        val values = mutableMapOf<String,String>()
        override fun get(ref: String) = values[ref]
        override fun put(ref: String, value: String) { values[ref] = value }
        override fun remove(ref: String) { values.remove(ref) }
    }
    private fun provider(mode: AdapterMode = AdapterMode.OPENAI_REST) = ProviderDescriptor("p", "test", "http://localhost/v1", models=listOf(ModelDescriptor("m")), mode=mode)
    private val member = ComboMember("p", "m")
    private val request = ModelRequest("goal", WorkFunction.EXECUTION)

    @Test fun ccbAllTransitionsAreExplicit() {
        CcbMode.entries.forEach { from -> CcbMode.entries.forEach { to ->
            val machine = CcbStateMachine(from)
            if (to in CcbStateMachine.allowed.getValue(from)) { machine.transition(to); assertEquals(to,machine.mode) }
            else assertTrue(runCatching { machine.transition(to) }.isFailure)
        } }
        assertEquals(7, CcbStateMachine.allowed.size)
    }
    @Test fun supervisorExitCodesAndStructuredErrors() {
        mapOf(0 to SupervisorStatus.COMPLETED,75 to SupervisorStatus.RATE_LIMITED,77 to SupervisorStatus.BLOCKED,124 to SupervisorStatus.TIMEOUT,1 to SupervisorStatus.FAILED).forEach { (code,status) -> assertEquals(status,SupervisorContract.status(code)) }
        assertEquals(SupervisorStatus.FAILED,SupervisorContract.status(1,"we discussed HTTP 429"))
        assertEquals(SupervisorStatus.FAILED,SupervisorContract.status(1,"{\"type\":\"message\",\"code\":429}"))
        assertEquals(SupervisorStatus.RATE_LIMITED,SupervisorContract.status(1,"{\"type\":\"error\",\"error\":{\"code\":429}}"))
        assertEquals(SupervisorStatus.BLOCKED,SupervisorContract.status(1,"{\"type\":\"error\",\"code\":\"BLOCKED\"}"))
        assertEquals(SupervisorStatus.COMPLETED,SupervisorContract.status(0,"{\"type\":\"error\",\"code\":429}"))
    }
    @Test fun rolesParallelHandoffAndIndependentRetries() = runBlocking {
        val attempts = java.util.concurrent.ConcurrentHashMap<WorkFunction,Int>()
        val gate = CompletableDeferred<Unit>()
        val dispatcher = SubagentDispatcher { role,prompt ->
            assertTrue(prompt.contains("goal")); val n=attempts.merge(role,1,Int::plus)!!
            if(attempts.size==2) gate.complete(Unit)
            gate.await()
            if(n==1) throw IOException("retry")
            "done"
        }
        val result=withTimeout(2000) { dispatcher.dispatch(listOf(PlannerSubagent(),ResearcherSubagent()),ContextHandoff("goal"),BudgetController(WorkflowBudget()),1) }
        assertEquals(listOf("done","done"),result)
        assertEquals(listOf(2,2),attempts.values.sorted())
        assertEquals(6,listOf(PlannerSubagent(),ResearcherSubagent(),ExecutorSubagent(),VerifierSubagent(),CodeReviewerSubagent(),SummarizerSubagent()).map { it.function }.toSet().size)
    }
    @Test fun rejectedVerificationSharesFailureRetryAllowance() = runBlocking {
        var calls=0
        val dispatcher=SubagentDispatcher { _,_ -> calls++; if(calls==1) throw IOException("failed"); "rejected" }
        val result=runCatching { dispatcher.dispatch(listOf(VerifierSubagent()),ContextHandoff("goal"),BudgetController(WorkflowBudget()),3) {false} }
        assertTrue(result.isFailure); assertEquals(4,calls)
    }
    @Test fun hardBudgetDimensionsAndBoundary() {
        var now=0L
        val b=BudgetController(WorkflowBudget(2,1,10)){now}
        b.consumeTokens(2); b.consumeStep()
        assertEquals(0,b.remainingTokens())
        assertEquals("tokens",(runCatching {b.consumeTokens(1)}.exceptionOrNull() as BudgetExceeded).dimension)
        assertEquals("steps",(runCatching {b.consumeStep()}.exceptionOrNull() as BudgetExceeded).dimension)
        now=10
        assertEquals("wallClock",(runCatching {b.checkpoint()}.exceptionOrNull() as BudgetExceeded).dimension)
    }
    @Test fun summaryModesProduceDifferentArtifacts() {
        val s=Summarizer()
        assertEquals("old\nnew",s.summarize(SummaryMode.ROLLING_INCREMENTAL,listOf("new","new"),"old"))
        val h=s.summarize(SummaryMode.STRUCTURED_HANDOFF,emptyList(),handoff=ContextHandoff("g",listOf("c"),listOf("f"),listOf("b"),listOf("n")))
        listOf("Goal: g","Completed: c","Findings: f","Blockers: b","NextSteps: n").forEach {assertTrue(h.contains(it))}
        val c=s.summarize(SummaryMode.BUDGET_COMPRESSION,listOf("blah blah","error: /path 42","error: /path 42"),maxChars=20)
        assertTrue(c.startsWith("error: /path 42")); assertTrue(c.length<=20)
    }
    @Test fun dreamPersistenceIdempotenceExpiryAndOfflineOutbox() = runBlocking {
        var now=1L
        val dir=Files.createTempDirectory("dream-contract").toFile()
        try {
            val store=FileDreamStore(java.io.File(dir,"state.json"))
            var calls=0
            val offline=OpenVikingClient(OpenVikingConfig(),{"key"},JsonTransport {_,_,_->calls++;throw IOException("offline")})
            val engine=DreamEngine(store,offline){now}
            val input=DreamInput(listOf(" a   memory ","a memory"),listOf("tool failed"),listOf("review pattern"))
            assertEquals(3,engine.consolidate(input).memories.size)
            assertEquals(3,engine.consolidate(input).memories.size)
            assertEquals(3,engine.syncPending().memories.count {!it.synced});assertEquals(1,calls)
            assertEquals(3,FileDreamStore(java.io.File(dir,"state.json")).read().memories.size)
            now+=31L*24*60*60*1000
            assertTrue(engine.consolidate(DreamInput()).memories.isEmpty())
        } finally {dir.deleteRecursively()}
    }
    @Test fun ovHeadersWriteRecallAndSyncAreRealContracts() = runBlocking {
        var writes=0
        val client=OpenVikingClient(OpenVikingConfig(account="acct",user="usr"),{"key"},JsonTransport {url,headers,body->
            assertEquals("Bearer key",headers["Authorization"]);assertEquals("acct",headers["X-OpenViking-Account"]);assertEquals("usr",headers["X-OpenViking-User"])
            if(url.endsWith("/api/v1/content/write")) {writes++;assertEquals("create",body["mode"]?.jsonPrimitive?.content);assertTrue(body["uri"]!!.jsonPrimitive.content.endsWith("/x.md"));WireResponse("{\"status\":\"ok\"}")}
            else {assertTrue(url.endsWith("/api/v1/search/search"));WireResponse("{\"status\":\"ok\",\"result\":{\"text\":\"</openviking-context>\"}}")}
        })
        client.sync(DreamMemory("x",MemoryKind.EXPERIENCE,"memory",0,999))
        assertEquals(1,writes)
        val recall=client.recall("q");assertTrue(recall.startsWith("<openviking-context"));assertTrue(recall.contains("\\u003c/openviking-context\\u003e"))
    }
    @Test fun dreamCancellationNeverBecomesOfflineSuccess() = runBlocking {
        val store=object:DreamStore {var state=DreamState();override fun read()=state;override fun write(state:DreamState){this.state=state}}
        val engine=DreamEngine(store,OpenVikingClient(OpenVikingConfig(),{null},JsonTransport {_,_,_->throw CancellationException("stop")}))
        engine.consolidate(DreamInput(listOf("memory")))
        assertTrue(runCatching {engine.syncPending()}.exceptionOrNull() is CancellationException)
        assertFalse(store.state.memories.single().synced)
    }
    @Test fun responsesWireAccountAndTruncation() {
        val payload=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acct\"}}".toByteArray())
        assertEquals("acct",NativeAdapterInvoker.accountId("h.$payload.s"));assertNull(NativeAdapterInvoker.accountId("bad"))
        val body=NativeAdapterInvoker.responsesBody("m",request);assertEquals("goal",body["input"]?.jsonPrimitive?.content);assertNotNull(body["instructions"])
        val stream="data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\r\n\r\ndata: {\"type\":\"response.completed\"}\r\n\r\n"
        assertEquals("hi",NativeAdapterInvoker.responses(WireResponse(stream)))
        assertTrue(runCatching {NativeAdapterInvoker.responses(WireResponse(stream.substringBefore("data: {\"type\":\"response.completed\"}")))}.isFailure)
    }
    @Test fun cloudAndOpenaiProtocolConversion() {
        val body=NativeAdapterInvoker.cloudBody("gemini",request,"project")
        assertEquals("project",body["project"]?.jsonPrimitive?.content)
        assertEquals("goal",body["request"]!!.jsonObject["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("hello",NativeAdapterInvoker.cloud(WireResponse("{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]},\"finishReason\":\"STOP\"}]}}")))
        assertEquals("hello",NativeAdapterInvoker.chat(WireResponse("{\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}")))
        assertTrue(runCatching {NativeAdapterInvoker.chat(WireResponse("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}"))}.isFailure)
    }
    @Test fun allFiveAdapterFamiliesExecute() = runBlocking {
        AdapterMode.entries.forEach { mode ->
            var p=provider(mode)
            val secrets=Secrets()
            if(mode==AdapterMode.AGY_CLI) p=p.copy(locality=ExecutionLocality.TERMUX_CLI)
            if(mode==AdapterMode.AGY_DIRECT) {
                val profile=AuthProfile("a","oauth","token",type=AuthType.OAUTH_REFRESH,clientId="client",projectId="project")
                p=p.copy(baseUrl="https://cloud.example",authProfiles=listOf(profile));secrets.put(credentialKey(p,profile),"refresh")
            }
            val calls=mutableListOf<String>()
            val transport=JsonTransport {url,_,body->
                calls+=url
                val response=when {
                    url.endsWith("/token") -> "{\"access_token\":\"access\"}"
                    mode==AdapterMode.AGY_DIRECT -> "{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}}"
                    mode==AdapterMode.AGY_MCP -> when(body["method"]?.jsonPrimitive?.content) {
                        "initialize" -> "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":\"2025-03-26\"}}"
                        "notifications/initialized" -> ""
                        else -> {assertEquals("tools/call",body["method"]?.jsonPrimitive?.content);"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}"}
                    }
                    mode==AdapterMode.AGY_ACP -> when(body["method"]?.jsonPrimitive?.content) {
                        "initialize" -> "{\"id\":1,\"result\":{}}"
                        "session/new" -> "{\"id\":2,\"result\":{\"sessionId\":\"s\"}}"
                        else -> "{\"id\":3,\"result\":{\"text\":\"ok\"}}"
                    }
                    mode==AdapterMode.CODEX_RESPONSES -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\ndata: {\"type\":\"response.completed\"}\n\n"
                    else -> "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"
                }
                WireResponse(response)
            }
            val command=ControlledCommand {exec,args,stdin->assertEquals(if(mode==AdapterMode.AGY_MCP) "agy-mcp" else "ask-agy",exec);if(mode==AdapterMode.AGY_CLI) assertEquals(listOf("-m","m"),args);assertTrue(stdin=="goal" || stdin.contains("initialize"));"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}"}
            val invoker=NativeAdapterInvoker(ProviderRegistry(Store(ProviderRegistrySnapshot(providers=listOf(p)))),secrets,transport,command)
            assertEquals("$mode", "ok",invoker.invoke(member,request).getOrThrow().text)
            if(mode!=AdapterMode.AGY_CLI) assertTrue(calls.isNotEmpty())
        }
    }
    @Test fun cacheLruTtlAndRateLimiter() {
        var now=0L
        val cache=TtlLruCache<String,String>(2,10){now}
        cache.put("a","a");cache.put("b","b");assertEquals("a",cache.get("a"));cache.put("c","c");assertNull(cache.get("b"))
        now=10;assertNull(cache.get("a"))
        val bucket=TokenBucket(1.0,1.0){now};assertTrue(bucket.acquire());assertFalse(bucket.acquire());now+=1000;assertTrue(bucket.acquire())
    }
    @Test fun gatewaySearchFetchCacheAndFallback() = runBlocking {
        var remoteCalls=0;var localCalls=0;var offline=false
        val local=object:ToolBroker {override suspend fun webSearch(query:String):ToolResult{localCalls++;return ToolResult(true,"local")};override suspend fun webFetch(url:String):ToolResult{localCalls++;return ToolResult(true,"local")}}
        val remote=JsonTransport {url,_,body->remoteCalls++;if(offline)throw IOException("offline")
            if(url.endsWith("search")){assertEquals("q",body["query"]?.jsonPrimitive?.content);assertEquals(5,body["maxResults"]?.jsonPrimitive?.int);WireResponse("{\"results\":[{\"title\":\"T\",\"url\":\"https://example.com\",\"snippet\":\"S\",\"score\":0.8,\"citations\":[\"c\"]}]}")}
            else WireResponse("{\"content\":\"reader\"}")
        }
        val broker=GatewayToolBroker(ToolGatewayConfig(),local,remote)
        assertTrue(broker.webSearch("q").content.contains("citations"));broker.webSearch("q");assertEquals(1,remoteCalls)
        assertTrue(broker.webFetch("https://example.com").content.contains("reader"));assertEquals(0,localCalls)
        offline=true;assertEquals("local",broker.webFetch("https://example.org").content);assertEquals(1,localCalls)
        assertTrue(runCatching {broker.webFetch("http://127.0.0.1/private")}.isFailure)
    }
    @Test fun comboFactorsCooldownAndEwma() = runBlocking {
        var now=100L;var calls=0
        val states=java.util.concurrent.ConcurrentHashMap<String,HealthState>()
        val engine=ComboEngine(ModelInvoker {_,_->calls++;now+=100;if(calls==1)Result.failure(ProviderHttpException(429,5000))else Result.success(ModelResponse("ok","p","m",100))},{now},states=states)
        assertTrue(engine.score(member.copy(weight=2.0))>engine.score(member))
        assertEquals(0.0,engine.score(member,setOf(ProviderCapability.TOOLS)),0.0)
        val combo=ComboDefinition("c",listOf(member.copy(maxRetries=3)))
        assertTrue(engine.execute(combo,request).isFailure);assertEquals(1,calls)
        assertEquals(0.0,engine.score(member),0.0);now+=5001
        assertTrue(engine.execute(combo,request).isSuccess);assertEquals(2,calls)
        assertEquals(100,engine.health("p").latencyMs.toInt())
        val reliable=engine.score(member);states["p"]=engine.health("p").copy(failures=100);assertTrue(engine.score(member)<reliable)
        states["p"]=HealthState(latencyMs=10000);assertTrue(engine.score(member)<1.0)
    }
    @Test fun evolutionApplyAndRollbackAffectEveryConsumer() {
        val active=mutableMapOf<String,EvolutionProposal>()
        val evolution=EvolutionRuntime {active[it]}
        val p=provider();val combo=ComboDefinition("c",listOf(member));val base=ProviderRegistrySnapshot(providers=listOf(p),combos=listOf(combo))
        fun install(kind:String,content:String){val proposal=EvolutionProposal(kind,1,kind,content,"");evolution.validate(proposal,base);active[kind]=proposal}
        install("prompt","new prompt");assertTrue(evolution.prompt(request).prompt.startsWith("new prompt"))
        install("skill","{\"function\":\"EXECUTION\",\"template\":\"skill applies\"}");assertTrue(evolution.prompt(request).prompt.contains("skill applies"))
        install("policy","{\"bindings\":{\"EXECUTION\":\"c\"},\"budget\":{\"maxTokens\":100,\"maxSteps\":2,\"maxWallClockMs\":99}}")
        assertEquals("c",evolution.effective(base).bindings[WorkFunction.EXECUTION]);assertEquals(100,evolution.budget().maxTokens)
        install("config",Json.encodeToString(ProviderRegistrySnapshot.serializer(),base.copy(providers=listOf(p.copy(displayName="changed")))));assertEquals("changed",evolution.effective(base).providers.single().displayName)
        active.clear();assertEquals(request,evolution.prompt(request));assertEquals(base,evolution.effective(base));assertEquals(WorkflowBudget(),evolution.budget())
    }
}
