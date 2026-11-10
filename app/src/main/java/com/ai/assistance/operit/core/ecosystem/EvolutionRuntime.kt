package com.ai.assistance.operit.core.ecosystem

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class SkillTemplate(val function: WorkFunction, val template: String)
@Serializable data class RoutingPolicy(val bindings: Map<WorkFunction, String> = emptyMap(), val budget: EvolutionBudget = EvolutionBudget())
@Serializable data class EvolutionBudget(val maxTokens: Int = 131_072, val maxSteps: Int = 32, val maxWallClockMs: Long = 180_000)

/** Read active pointers for each new turn. Rollback is visible without restarting the app. */
class EvolutionRuntime(private val current: (String) -> EvolutionProposal?) {
    private val json = Json { ignoreUnknownKeys = false }
    fun effective(base: ProviderRegistrySnapshot): ProviderRegistrySnapshot {
        var snapshot = current("config")?.let { json.decodeFromString(ProviderRegistrySnapshot.serializer(), it.content) } ?: base
        current("policy")?.let {
            val policy = json.decodeFromString(RoutingPolicy.serializer(), it.content)
            snapshot = snapshot.copy(bindings = snapshot.bindings + policy.bindings)
        }
        ProviderRegistry.validate(snapshot)
        return snapshot
    }
    fun prompt(request: ModelRequest): ModelRequest {
        val skill = current("skill")?.let { json.decodeFromString(SkillTemplate.serializer(), it.content) }
        val parts = listOfNotNull(current("prompt")?.content, skill?.takeIf { it.function == request.function }?.template, request.prompt)
        return request.copy(prompt = parts.joinToString("\n\n"))
    }
    fun budget(): WorkflowBudget = current("policy")?.let {
        val budget = json.decodeFromString(RoutingPolicy.serializer(), it.content).budget
        WorkflowBudget(budget.maxTokens, budget.maxSteps, budget.maxWallClockMs)
    } ?: WorkflowBudget()
    fun validate(proposal: EvolutionProposal, base: ProviderRegistrySnapshot) {
        require(proposal.content.isNotBlank())
        when (proposal.kind) {
            "config" -> {
                val candidate=json.decodeFromString(ProviderRegistrySnapshot.serializer(), proposal.content)
                ProviderRegistry.validate(candidate)
                candidate.providers.forEach { provider ->
                    val original=base.providers.firstOrNull {it.id==provider.id}
                    require(provider.authProfiles==original?.authProfiles.orEmpty() && provider.existingConfigId==original?.existingConfigId) {"Evolution cannot acquire credential authority"}
                    require(original==null || provider.baseUrl==original.baseUrl || provider.authProfiles.none {it.secretRef!=null}) {"Endpoint change must clear credential authority"}
                }
            }
            "policy" -> {
                val policy = json.decodeFromString(RoutingPolicy.serializer(), proposal.content)
                ProviderRegistry.validate(base.copy(bindings = base.bindings + policy.bindings))
                require(policy.budget.maxTokens > 0 && policy.budget.maxSteps > 0 && policy.budget.maxWallClockMs > 0)
            }
            "skill" -> require(json.decodeFromString(SkillTemplate.serializer(), proposal.content).template.isNotBlank())
            "prompt" -> require(proposal.content.length <= 64_000)
            else -> error("Unsupported evolution kind")
        }
    }
}
