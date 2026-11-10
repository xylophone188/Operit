package com.ai.assistance.operit.core.ecosystem

import android.content.Context
import androidx.core.content.edit
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class EcosystemServicesConfig(val openViking: OpenVikingConfig? = null, val tools: ToolGatewayConfig? = null)
class EcosystemServicesStore(context: Context) {
    private val prefs=context.getSharedPreferences("ecosystem_services",Context.MODE_PRIVATE)
    fun read(): EcosystemServicesConfig = prefs.getString("config",null)?.let {Json.decodeFromString(EcosystemServicesConfig.serializer(),it)} ?: EcosystemServicesConfig()
    fun write(value:EcosystemServicesConfig) { prefs.edit(commit=true) {putString("config",Json.encodeToString(EcosystemServicesConfig.serializer(),value))} }
}

/** Android's durable scheduler, idle + battery constraints; never a chat-path retry loop. */
class DreamWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val runtime=OperitEcosystemRuntime(applicationContext)
        runtime.dream().consolidate(DreamInput()) // Expire stale fragments even when offline.
        runtime.dream().syncPending() // One bounded outbox pass. Offline is persisted, not retried now.
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val constraints=Constraints.Builder().setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).build()
            val request=PeriodicWorkRequestBuilder<DreamWorker>(6,TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("ecosystem-dream",ExistingPeriodicWorkPolicy.KEEP,request)
        }
    }
}
