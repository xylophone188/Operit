package com.ai.assistance.operit.core.ecosystem

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProviderRegistry(private val store: ProviderStateStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Synchronized fun snapshot(): ProviderRegistrySnapshot = json.decodeFromString(json.encodeToString(store.read()))
    @Synchronized fun replace(snapshot: ProviderRegistrySnapshot) {
        validate(snapshot)
        val previous=store.read()
        snapshot.providers.forEach { p ->
            val old=previous.providers.firstOrNull {it.id==p.id}
            require(old==null || old.baseUrl==p.baseUrl || p.authProfiles.none {it.secretRef!=null}) {"endpoint change requires clearing credentials first"}
        }
        store.write(snapshot.copy(revision = store.read().revision + 1))
    }
    @Synchronized fun upsert(provider: ProviderDescriptor) { val s = snapshot(); replace(s.copy(providers = (s.providers.filterNot { it.id == provider.id } + provider).sortedBy { it.priority })) }
    @Synchronized fun remove(id: String) { val s = snapshot(); require(s.combos.none { c -> c.members.any { it.providerId == id } }) { "provider is used by a combo" }; replace(s.copy(providers = s.providers.filterNot { it.id == id })) }
    fun find(id: String) = snapshot().providers.firstOrNull { it.id == id }
    fun exportRedacted(): String {
        val snapshot = snapshot()
        return json.encodeToString(snapshot.copy(providers = snapshot.providers.map { p -> p.copy(headers = emptyMap(), existingConfigId = null, authProfiles = p.authProfiles.map { it.copy(secretRef = null, clientSecretRef = null, customHeaders = emptyMap(), organizationId = null, projectId = null) }) }))
    }
    @Synchronized fun import(value: String) {
        val input=json.decodeFromString<ProviderRegistrySnapshot>(value)
        require(input.providers.all {it.existingConfigId==null && it.authProfiles.all {a->a.secretRef==null && a.clientSecretRef==null && a.customHeaders.isEmpty()}}) {"import may not carry credential authority; use UI secret entry"}
        // Imported files cannot obtain authority to existing credentials or OAuth profiles.
        replace(input.copy(providers=input.providers.map { it.copy(existingConfigId=null,authProfiles=it.authProfiles.map { a->a.copy(secretRef=null) }) }))
    }

    companion object {
        fun validate(s: ProviderRegistrySnapshot) {
            require(s.providers.map { it.id }.distinct().size == s.providers.size) { "duplicate provider id" }
            require(s.combos.map { it.id }.distinct().size == s.combos.size) { "duplicate combo id" }
            s.providers.forEach { p ->
                require(p.id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "invalid provider id" }
                val u = p.baseUrl.toHttpUrlOrNull() ?: error("invalid provider endpoint")
                require((u.isHttps || (u.scheme == "http" && (u.host == "127.0.0.1" || u.host == "localhost"))) && u.username.isEmpty() && u.password.isEmpty() && u.query == null && u.fragment == null) { "invalid endpoint; credentials must use SecretStore" }
                require(u.scheme=="https" || u.host in setOf("127.0.0.1","localhost")) {"cleartext endpoint only permitted on loopback"}
                require(p.authProfiles.map {it.id}.distinct().size==p.authProfiles.size)
                require(p.activeAuthProfileId==null || p.authProfiles.any {it.id==p.activeAuthProfileId})
                p.authProfiles.forEach { profile ->
                    val tokenUri=URI(profile.tokenUrl)
                    require(tokenUri.scheme=="https" && tokenUri.host!=null && tokenUri.userInfo==null && tokenUri.fragment==null) {"OAuth requires HTTPS"}
                    require(profile.clientSecretRef==null || profile.clientSecretRef=="ecosystem.${p.id}.${profile.id}.client") {"OAuth client secret scope"}
                    require(profile.customHeaders.keys.none {it.lowercase() in setOf("host","content-length","transfer-encoding","connection") || it.contains(Regex("(?i)authorization|cookie|token|secret|api.?key"))}) {"sensitive headers belong in SecretStore"}
                    require(profile.customHeaders.all {(key,value)-> !key.contains(Regex("[\\r\\n]")) && !value.contains(Regex("[\\r\\n]"))})
                }
                p.authProfiles.forEach { a -> require(a.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {"invalid auth profile id"}; require(a.secretRef==null || a.secretRef=="ecosystem.${p.id}.${a.id}") {"credential ref must be provider/profile scoped"} }
                require(p.headers.keys.none {it.lowercase() in setOf("host","content-length","transfer-encoding","connection")}) {"reserved header"}
                require(p.headers.values.none {it.contains(Regex("(?i)Bearer\\s+|sk-[A-Za-z0-9_-]{16,}|BEGIN .*PRIVATE KEY"))}) {"secret header value forbidden"}
                require(p.models.isNotEmpty() && p.models.all { it.id.isNotBlank() }) { "provider needs a model" }
                require(p.headers.all { (k,v) -> !k.contains('\n') && !v.contains('\n') && !k.contains('\r') && !v.contains('\r') }) { "invalid header" }
                require(p.headers.keys.none { it.contains(Regex("(?i)authorization|cookie|token|secret|api.?key")) }) { "sensitive headers belong in SecretStore" }
            }
            s.combos.forEach { c ->
                require(c.id.isNotBlank() && c.members.isNotEmpty() && c.totalTimeoutMs in 1..600_000) { "invalid combo" }
                c.members.forEach { m ->
                    require(m.weight.isFinite() && m.weight > 0 && m.timeoutMs in 1..600_000 && m.maxRetries in 0..3) { "invalid member policy" }
                    require(s.providers.any { p -> p.id == m.providerId && p.models.any { it.id == m.modelId } }) { "unknown combo model" }
                }
            }
            require(s.bindings.values.all { id -> s.combos.any { it.id == id } }) { "unknown functional combo" }
        }
    }
}

class FunctionalModelRouter(
    private val registry: ProviderRegistry,
    private val functionBindings: Map<WorkFunction, List<String>> = emptyMap(),
    private val health: Map<String, () -> HealthState> = emptyMap(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val evolution: EvolutionRuntime? = null,
) {
    fun route(request: RouteRequest): RouteDecision? {
        val snapshot = evolution?.effective(registry.snapshot()) ?: registry.snapshot()
        val selected = snapshot.combos.firstOrNull { it.id == snapshot.bindings[request.function] }
        val bound = selected?.members?.map { it.providerId } ?: functionBindings[request.function].orEmpty()
        return snapshot.providers.filter { it.enabled && !(health[it.id]?.invoke() ?: HealthState()).isOpen(nowMs()) && (bound.isEmpty() || it.id in bound) }
            .flatMap { p -> p.models.filter { model -> selected==null || selected.members.any {it.providerId==p.id && it.modelId==model.id} }.map { p to it } }
            .filter { (p,m) -> request.requiredCapabilities.all { it in p.capabilities && it in m.capabilities } && (request.preferredModel == null || m.id == request.preferredModel) }
            .map { (p,m) -> RouteDecision(p.id,m.id, -p.priority.toDouble(), "function=${request.function}") }.maxByOrNull { it.score }
    }
}
