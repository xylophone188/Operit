package com.ai.assistance.operit.core.ecosystem

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Adapter-local policy derived from OmniRoute ordered/auto-score contracts; not its Node runtime. */
class ComboEngine(
    private val invoker: ModelInvoker,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onHealth: (String, Boolean, Long) -> Unit = { _,_,_ -> },
    private val breakerThreshold: Int = 3,
    private val breakerOpenMs: Long = 30_000,
    private val states: ConcurrentHashMap<String,HealthState> = ConcurrentHashMap(),
    private val capabilities: (ComboMember) -> Set<ProviderCapability> = { setOf(ProviderCapability.CHAT) },
) {
    fun health(id: String) = states[id] ?: HealthState()
    fun score(m: ComboMember, required: Set<ProviderCapability> = setOf(ProviderCapability.CHAT)): Double {
        val h = health(m.providerId)
        if (h.isOpen(nowMs()) || h.cooldownUntilMs > nowMs() || !capabilities(m).containsAll(required)) return 0.0
        val reliability = (h.successes + 1.0) / (h.successes + h.failures + 1.0)
        val capability = 1.0 + capabilities(m).intersect(required).size.toDouble() / required.size.coerceAtLeast(1)
        return m.weight * capability * reliability / (1.0 + h.latencyMs / 1000.0)
    }
    suspend fun execute(combo: ComboDefinition, request: ModelRequest, budget: BudgetController? = null): Result<ModelResponse> = try {
        withTimeout(combo.totalTimeoutMs) { executeMembers(combo,request,budget) }
    } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); Result.failure(java.io.IOException("model deadline exceeded",e)) }

    private suspend fun executeMembers(combo: ComboDefinition, request: ModelRequest, budget: BudgetController?): Result<ModelResponse> {
        var last: Result<ModelResponse> = Result.failure(IllegalStateException("no healthy combo candidate"))
        val members = if (combo.autoScore) combo.members.sortedByDescending { score(it) } else combo.members
        for (m in members) {
            require(m.timeoutMs > 0 && m.maxRetries in 0..3)
            if (!capabilities(m).containsAll(setOf(ProviderCapability.CHAT))) continue
            budget?.consumeTokens(BudgetController.estimateTokens(request.prompt) / 4 + request.maxOutputTokens)
            for (attempt in 0..m.maxRetries) {
                if (health(m.providerId).isOpen(nowMs())) break
                budget?.consumeStep()
                // Reserve the provider-enforced output cap before network admission; retries are charged independently.

                val start = nowMs()
                last = try { attempt { withTimeout(m.timeoutMs) { invoker.invoke(m,request).getOrThrow() } } }
                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); Result.failure(java.io.IOException("member deadline exceeded",e)) }
                if(last.exceptionOrNull() is BudgetExceeded) return last
                val elapsed = (nowMs()-start).coerceAtLeast(0)
                states.compute(m.providerId) { _, old ->
                    val h = old ?: HealthState()
                    if (last.isSuccess) h.copy(successes=h.successes+1, consecutiveFailures=0, openedUntilMs=0, cooldownUntilMs=0, latencyMs=if(h.latencyMs==0L) elapsed else (h.latencyMs*3+elapsed)/4)
                    else h.copy(failures=h.failures+1, consecutiveFailures=h.consecutiveFailures+1, openedUntilMs=if(h.consecutiveFailures+1>=breakerThreshold) nowMs()+breakerOpenMs else 0, latencyMs=if(h.latencyMs==0L) elapsed else (h.latencyMs*3+elapsed)/4, cooldownUntilMs=(last.exceptionOrNull() as? ProviderHttpException)?.takeIf { it.status==429 }?.let { nowMs()+it.retryAfterMs.coerceAtLeast(1000) } ?: h.cooldownUntilMs)
                }
                onHealth(m.providerId,last.isSuccess,elapsed)
                if (last.isSuccess) return last
            }
        }
        return last
    }
}
