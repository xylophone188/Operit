package com.ai.assistance.operit.core.ecosystem

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EcosystemContractTest {
    private val request = ModelRequest("fixture",WorkFunction.EXECUTION)
    private fun member(id:String="a",timeout:Long=1000,retries:Int=0)=ComboMember(id,"fixture",timeoutMs=timeout,maxRetries=retries)
    private fun response(m:ComboMember)=ModelResponse("golden",m.providerId,m.modelId,1)

    @Test fun workflowTimeoutIsFailureButParentCancellationPropagates() = runBlocking {
        val engine=ComboEngine(ModelInvoker {_,_->delay(500);Result.success(response(member()))})
        val memberFailure=AgentWorkflowEngine(engine).run(request,ComboDefinition("member",listOf(member(timeout=10))),false,false)
        assertTrue(memberFailure.isFailure)
        assertFalse(memberFailure.exceptionOrNull() is CancellationException)
        val totalFailure=AgentWorkflowEngine(engine).run(request,ComboDefinition("total",listOf(member()),totalTimeoutMs=10),false,false)
        assertTrue(totalFailure.isFailure)
        assertFalse(totalFailure.exceptionOrNull() is CancellationException)
        val job=launch {AgentWorkflowEngine(engine).run(request,ComboDefinition("parent",listOf(member())),false,false);fail("cancel swallowed")}
        delay(20);job.cancelAndJoin();assertTrue(job.isCancelled)
    }
    @Test fun fallbackPreservesOrderAndErrors() = runBlocking {
        val calls=mutableListOf<String>()
        val engine=ComboEngine(ModelInvoker {m,_->calls+=m.providerId;if(m.providerId=="a")Result.failure(IllegalStateException("upstream failure")) else Result.success(response(m))})
        val result=engine.execute(ComboDefinition("fallback",listOf(member(),member("b")),autoScore=false),request)
        assertEquals(listOf("a","b"),calls);assertEquals("b",result.getOrThrow().providerId)
    }
    @Test fun timeoutFallsThroughWithoutFalseSuccess() = runBlocking {
        val engine=ComboEngine(ModelInvoker {m,_->if(m.providerId=="a")delay(500);Result.success(response(m))})
        assertEquals("b",engine.execute(ComboDefinition("c",listOf(member(timeout=10),member("b")),false),request).getOrThrow().providerId)
        assertEquals(1,engine.health("a").failures)
    }
    @Test fun retryHealthAccumulatesAndBreakerOpens() = runBlocking {
        var calls=0
        val engine=ComboEngine(ModelInvoker {_,_->calls++;Result.failure(IllegalStateException())},breakerThreshold=2)
        assertTrue(engine.execute(ComboDefinition("c",listOf(member(retries=3))),request).isFailure)
        assertEquals(2,calls);assertEquals(2,engine.health("a").consecutiveFailures)
        assertTrue(engine.health("a").isOpen(System.currentTimeMillis()))
    }
    @Test fun cancellationPropagates() = runBlocking {
        var completed=false
        val engine=ComboEngine(ModelInvoker {m,_->delay(5000);completed=true;Result.success(response(m))})
        val job=launch {engine.execute(ComboDefinition("c",listOf(member())),request)}
        yield();job.cancelAndJoin();assertFalse(completed);assertEquals(0,engine.health("a").failures)
    }
    @Test fun totalTimeoutBoundsRetries() = runBlocking {
        val engine=ComboEngine(ModelInvoker {_,_->delay(5000);error("not reached")})
        assertTrue(engine.execute(ComboDefinition("c",listOf(member(timeout=1000,retries=3)),totalTimeoutMs=15),request).isFailure)
    }
    @Test fun workflowBudgetRejectsBeforeInvoke() = runBlocking {
        var calls=0
        val workflow=AgentWorkflowEngine(ComboEngine(ModelInvoker{m,_->calls++;Result.success(response(m))}),maxBudget=2)
        assertTrue(workflow.run(request,ComboDefinition("c",listOf(member()))).isFailure);assertEquals(0,calls)
    }
    @Test fun verificationReviewGetTheirOwnFunctions() = runBlocking {
        val functions=java.util.Collections.synchronizedSet(mutableSetOf<WorkFunction>())
        val workflow=AgentWorkflowEngine(ComboEngine(ModelInvoker {m,r->functions+=r.function;Result.success(response(m))}))
        assertEquals(3,workflow.run(request,ComboDefinition("c",listOf(member()))).getOrThrow().unitsUsed)
        assertEquals(setOf(WorkFunction.EXECUTION,WorkFunction.VERIFICATION,WorkFunction.REVIEW),functions)
    }
    @Test fun proposalGateRejectsCodeAndStaleBase() = runBlocking {
        var installs=0
        val repo=object:ProposalRepository {
            override fun currentDigest(kind:String)="base"
            override fun installVersion(proposal:EvolutionProposal){installs++}
            override fun rollback(kind:String,version:Int) { require(version>0) }
        }
        val engine=EvolutionEngine(ProposalSandbox{Result.success(Unit)},ProposalApproval{true},repo)
        assertTrue(engine.apply(EvolutionProposal("p",1,"apk","x","base")).isFailure)
        assertTrue(engine.apply(EvolutionProposal("p",1,"prompt","x","stale")).isFailure)
        assertTrue(engine.apply(EvolutionProposal("p",1,"prompt","x","base")).isSuccess)
        assertEquals(1,installs)
    }
    @Test fun importCannotAcquireCredentialAuthority() {
        val store=object:ProviderStateStore {
            var data=ProviderRegistrySnapshot()
            override fun read()=data
            override fun write(snapshot:ProviderRegistrySnapshot){data=snapshot}
        }
        val registry=ProviderRegistry(store)
        assertTrue(runCatching {registry.import("""{"providers":[{"id":"p","displayName":"p","baseUrl":"https://example.test/v1","models":[{"id":"m"}],"authProfiles":[{"id":"a","label":"a","secretRef":"ecosystem.p.a"}]}]}""")}.isFailure)
        assertTrue(runCatching {ProviderRegistry.validate(ProviderRegistrySnapshot(listOf(ProviderDescriptor("p","p","http://example.test",models=listOf(ModelDescriptor("m"))))))}.isFailure)
    }
    @Test fun endpointChangeCannotReuseSecret() {
        val store=object:ProviderStateStore {
            var data=ProviderRegistrySnapshot()
            override fun read()=data
            override fun write(snapshot:ProviderRegistrySnapshot){data=snapshot}
        }
        val registry=ProviderRegistry(store)
        val p=ProviderDescriptor("p","p","https://one.test/v1",models=listOf(ModelDescriptor("m")),authProfiles=listOf(AuthProfile("a","a","ecosystem.p.a")))
        registry.upsert(p)
        assertTrue(runCatching {registry.upsert(p.copy(baseUrl="https://two.test/v1"))}.isFailure)
        assertEquals("https://one.test/v1",registry.find("p")!!.baseUrl)
    }
    @Test fun configRejectsSecretHeadersAndUnknownModels() {
        val p=ProviderDescriptor("p","p","https://example.test/v1",models=listOf(ModelDescriptor("m")))
        assertTrue(runCatching {ProviderRegistry.validate(ProviderRegistrySnapshot(listOf(p.copy(headers=mapOf("Authorization" to "sensitive")))))}.isFailure)
        val invalid = ProviderRegistrySnapshot(listOf(p), combos = listOf(ComboDefinition("c", listOf(ComboMember("p", "missing")))))
        assertTrue(runCatching { ProviderRegistry.validate(invalid) }.isFailure)
    }
}
