package com.ai.assistance.operit.core.ecosystem

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable enum class CcbMode { PLAN, RESEARCH, EXP, EXECUTION, VERIFICATION, REVIEW, DREAM }
@Serializable enum class SupervisorStatus { COMPLETED, RATE_LIMITED, BLOCKED, TIMEOUT, FAILED }

/** Exit status is authoritative; only structured error events may refine generic failures. */
object SupervisorContract {
    fun status(exitCode: Int, jsonl: String = ""): SupervisorStatus {
        when (exitCode) {
            0 -> return SupervisorStatus.COMPLETED
            75 -> return SupervisorStatus.RATE_LIMITED
            77 -> return SupervisorStatus.BLOCKED
            124 -> return SupervisorStatus.TIMEOUT
        }
        var structuredStatus: SupervisorStatus? = null
        jsonl.lineSequence().forEach { line ->
            if (structuredStatus != null) return@forEach
            val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
            if ((event["type"] as? JsonPrimitive)?.contentOrNull != "error") return@forEach
            val error = event["error"] as? JsonObject ?: event
            structuredStatus = when ((error["code"] as? JsonPrimitive)?.contentOrNull ?: (error["status"] as? JsonPrimitive)?.contentOrNull ?: (error["type"] as? JsonPrimitive)?.contentOrNull) {
                "429", "rate_limit_exceeded", "RATE_LIMITED" -> SupervisorStatus.RATE_LIMITED
                "403", "permission_denied", "BLOCKED" -> SupervisorStatus.BLOCKED
                else -> null
            }
        }
        return structuredStatus ?: SupervisorStatus.FAILED
    }
}

class CcbStateMachine(initial: CcbMode = CcbMode.PLAN) {
    var mode: CcbMode = initial; private set
    fun transition(next: CcbMode) {
        require(next in allowed.getValue(mode)) { "Illegal CCB transition: $mode -> $next" }
        mode = next
    }
    companion object {
        val allowed = mapOf(
            CcbMode.PLAN to setOf(CcbMode.PLAN, CcbMode.RESEARCH, CcbMode.EXP, CcbMode.EXECUTION),
            CcbMode.RESEARCH to setOf(CcbMode.PLAN, CcbMode.EXP, CcbMode.EXECUTION),
            CcbMode.EXP to setOf(CcbMode.RESEARCH, CcbMode.EXECUTION, CcbMode.VERIFICATION),
            CcbMode.EXECUTION to setOf(CcbMode.VERIFICATION),
            CcbMode.VERIFICATION to setOf(CcbMode.EXECUTION, CcbMode.REVIEW),
            CcbMode.REVIEW to setOf(CcbMode.EXECUTION, CcbMode.DREAM),
            CcbMode.DREAM to setOf(CcbMode.PLAN),
        )
    }
}

@Serializable data class ContextHandoff(
    val goal: String, val completed: List<String> = emptyList(), val findings: List<String> = emptyList(),
    val blockers: List<String> = emptyList(), val nextSteps: List<String> = emptyList(),
)
interface WorkflowSubagent { val function: WorkFunction; val mode: CcbMode }
class PlannerSubagent : WorkflowSubagent { override val function = WorkFunction.PLANNING; override val mode = CcbMode.PLAN }
class ResearcherSubagent : WorkflowSubagent { override val function = WorkFunction.RESEARCH; override val mode = CcbMode.RESEARCH }
class ExecutorSubagent : WorkflowSubagent { override val function = WorkFunction.EXECUTION; override val mode = CcbMode.EXECUTION }
class VerifierSubagent : WorkflowSubagent { override val function = WorkFunction.VERIFICATION; override val mode = CcbMode.VERIFICATION }
class CodeReviewerSubagent : WorkflowSubagent { override val function = WorkFunction.REVIEW; override val mode = CcbMode.REVIEW }
class SummarizerSubagent : WorkflowSubagent { override val function = WorkFunction.SUMMARIZATION; override val mode = CcbMode.DREAM }

/** Each child owns one counter: failed calls and rejected verification consume the same allowance. */
class SubagentDispatcher(private val execute: suspend (WorkFunction, String) -> String) {
    suspend fun dispatch(roles: List<WorkflowSubagent>, handoff: ContextHandoff, budget: BudgetController,
                         maxRetries: Int = 3, verify: (String) -> Boolean = { true }): List<String> = coroutineScope {
        require(maxRetries in 0..3)
        roles.map { role -> async<String> {
            budget.consumeStep()
            for (attempt in 0..maxRetries) {
                val output = try {
                    execute(role.function, Json.encodeToString(ContextHandoff.serializer(), handoff))
                } catch (e: CancellationException) { throw e
                } catch (e: BudgetExceeded) { throw e
                } catch (e: Exception) {
                    if (attempt >= maxRetries) throw e
                    continue
                }
                budget.consumeTokens(BudgetController.estimateTokens(output))
                if (verify(output)) return@async output
                if (attempt >= maxRetries) throw IllegalStateException("Subagent verification exhausted: ${role.function}")
            }
            error("Subagent dispatch exhausted: ${role.function}")
        } }.awaitAll()
    }
}

class BudgetExceeded(val dimension: String) : IllegalStateException("Budget exceeded: $dimension")
class BudgetController(private val limits: WorkflowBudget, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val started = clock()
    private var tokens = 0
    private var steps = 0
    init { require(limits.maxTokens > 0 && limits.maxSteps > 0 && limits.maxWallClockMs > 0) }
    @Synchronized fun checkpoint() { if (clock() - started >= limits.maxWallClockMs) throw BudgetExceeded("wallClock") }
    @Synchronized fun consumeStep() { checkpoint(); if (steps >= limits.maxSteps) throw BudgetExceeded("steps"); steps++ }
    @Synchronized fun consumeTokens(count: Int) {
        require(count >= 0); checkpoint()
        if (count > limits.maxTokens - tokens) throw BudgetExceeded("tokens")
        tokens += count
    }
    @Synchronized fun remainingTokens(): Int = limits.maxTokens - tokens
    companion object { fun estimateTokens(text: String): Int = text.toByteArray(Charsets.UTF_8).size }
}

@Serializable enum class SummaryMode { ROLLING_INCREMENTAL, STRUCTURED_HANDOFF, BUDGET_COMPRESSION }
class Summarizer {
    fun summarize(mode: SummaryMode, recent: List<String>, previous: String = "", handoff: ContextHandoff? = null, maxChars: Int = 2048): String {
        require(maxChars > 0)
        return when (mode) {
            SummaryMode.STRUCTURED_HANDOFF -> {
                val h = requireNotNull(handoff)
                "Goal: ${h.goal}\nCompleted: ${h.completed.joinToString("; ")}\nFindings: ${h.findings.joinToString("; ")}\nBlockers: ${h.blockers.joinToString("; ")}\nNextSteps: ${h.nextSteps.joinToString("; ")}".take(maxChars)
            }
            SummaryMode.ROLLING_INCREMENTAL -> (listOf(previous) + recent).filter { it.isNotBlank() }.distinct().joinToString("\n").takeLast(maxChars)
            SummaryMode.BUDGET_COMPRESSION -> (listOf(previous) + recent).flatMap { it.lineSequence().toList() }
                .map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }.distinct()
                .sortedByDescending { line -> if (Regex("(?i)error|block|goal|next|/|[0-9]").containsMatchIn(line)) 1 else 0 }
                .joinToString(";").take(maxChars)
        }
    }
}
