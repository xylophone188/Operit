package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.collects.ApiProviderConfigs
import com.ai.assistance.operit.data.collects.ModelThinkingConfigDefaults
import com.ai.assistance.operit.data.model.ApiProviderType
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verify the current data-driven reasoning contract, not deleted mapper symbols. */
class XaiProviderReasoningTest {
    private var priorSystemLog = true
    private var priorFileLog = true
    @org.junit.Before fun silenceAndroidLog() {
        priorSystemLog = com.ai.assistance.operit.util.AppLogger.enableSystemLog
        priorFileLog = com.ai.assistance.operit.util.AppLogger.enableFileLogging
        com.ai.assistance.operit.util.AppLogger.enableSystemLog = false
        com.ai.assistance.operit.util.AppLogger.enableFileLogging = false
    }
    @org.junit.After fun restoreAndroidLog() {
        com.ai.assistance.operit.util.AppLogger.enableSystemLog = priorSystemLog
        com.ai.assistance.operit.util.AppLogger.enableFileLogging = priorFileLog
    }

    @Test fun defaultConfigUsesTheOfficialXaiEndpointAndModel() {
        assertEquals("grok-4.6",ApiProviderConfigs.getDefaultModelName(ApiProviderType.XAI))
        assertEquals("https://api.x.ai/v1/chat/completions",ApiProviderConfigs.getDefaultApiEndpoint(ApiProviderType.XAI))
        assertEquals("https://api.x.ai/v1/models",ModelListFetcher.getModelsListUrl("https://api.x.ai/v1/chat/completions",ApiProviderType.XAI))
    }
    @Test fun enabledOptionsPreserveXaiEfforts() {
        val rules=JSONArray(ModelThinkingConfigDefaults.forProvider("XAI"))
        val rule=(0 until rules.length()).map {rules.getJSONObject(it)}.single {it.getString("id")=="xai-grok-reasoning-effort"}
        val options=rule.getJSONArray("options")
        assertEquals(listOf("low","medium","high","xhigh"),(0 until options.length()).map {options.getJSONObject(it).getString("value")})
        assertTrue(rule.getJSONObject("match").getJSONArray("modelContains").toString().contains("grok"))
    }
}
