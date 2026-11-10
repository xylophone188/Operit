package com.ai.assistance.operit.core.ecosystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcosystemRoutingTest {
    private class Store(var value: ProviderRegistrySnapshot = ProviderRegistrySnapshot()) : ProviderStateStore {
        override fun read() = value
        override fun write(snapshot: ProviderRegistrySnapshot) { value = snapshot }
    }

    private val provider = ProviderDescriptor(
        id = "omniroute",
        displayName = "OmniRoute",
        baseUrl = "http://127.0.0.1:20128/v1",
        models = listOf(ModelDescriptor("model-a", capabilities = setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING))),
        capabilities = setOf(ProviderCapability.CHAT, ProviderCapability.STREAMING),
    )

    @Test fun registryRoundTripRedactsSecretRefs() {
        val store = Store()
        val registry = ProviderRegistry(store)
        registry.upsert(provider.copy(authProfiles = listOf(AuthProfile("p", "local", "ecosystem.omniroute.p"))))
        assertTrue(!registry.exportRedacted().contains("\"secretRef\":\"secret\""))
        assertEquals("omniroute", registry.find("omniroute")?.id)
    }

    @Test fun functionRouterSelectsBoundProvider() {
        val registry = ProviderRegistry(Store(ProviderRegistrySnapshot(listOf(provider))))
        val decision = FunctionalModelRouter(registry, mapOf(WorkFunction.CODING to listOf("omniroute")))
            .route(RouteRequest(WorkFunction.CODING, setOf(ProviderCapability.STREAMING)))
        assertNotNull(decision)
        assertEquals("model-a", decision?.modelId)
    }
}
