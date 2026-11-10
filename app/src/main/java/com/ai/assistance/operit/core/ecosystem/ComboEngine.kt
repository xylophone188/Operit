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
) {
    fun health(id: String) = states[id] ?: HealthState()
    private fun score(m: ComboMember): Double {
        val h = health(m.providerId)
        val reliability = (h.successes + 1.0) / (h.successes + h.failures + 1.0)
        return m.weight * reliability / (1.0 + h.latencyMs / 1000.0)
    }
    suspend fun execute(combo: ComboDefinition, request: ModelRequest): Result<ModelResponse> = try {
        withTimeout(combo.totalTimeoutMs) { executeMembers(combo,request) }
    } catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); Result.failure(e) }

    private suspend fun executeMembers(combo: ComboDefinition, request: ModelRequest): Result<ModelResponse> {
        var last: Result<ModelResponse> = Result.failure(IllegalStateException("no healthy combo candidate"))
        val members = if (combo.autoScore) combo.members.sortedByDescending(::score) else combo.members
        for (m in members) {
            require(m.timeoutMs > 0 && m.maxRetries in 0..3)
            repeat(m.maxRetries + 1) {
                if (health(m.providerId).isOpen(nowMs())) return@repeat
                val start = nowMs()
                last = try { attempt { withTimeout(m.timeoutMs) { invoker.invoke(m,request).getOrThrow() } } }
                catch (e: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); Result.failure(e) }
                val elapsed = (nowMs()-start).coerceAtLeast(0)
                states.compute(m.providerId) { _, old ->
                    val h = old ?: HealthState()
                    if (last.isSuccess) h.copy(successes=h.successes+1, consecutiveFailures=0, openedUntilMs=0, latencyMs=elapsed)
                    else h.copy(failures=h.failures+1, consecutiveFailures=h.consecutiveFailures+1, openedUntilMs=if(h.consecutiveFailures+1>=breakerThreshold) nowMs()+breakerOpenMs else 0, latencyMs=elapsed)
                }
                onHealth(m.providerId,last.isSuccess,elapsed)
                if (last.isSuccess) return last
            }
        }
        return last
    }
}
