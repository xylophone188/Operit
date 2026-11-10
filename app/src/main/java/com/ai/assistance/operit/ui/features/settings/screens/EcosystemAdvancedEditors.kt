package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ai.assistance.operit.core.ecosystem.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Composable fun EcosystemProviderEditor(runtime: OperitEcosystemRuntime, snapshot: ProviderRegistrySnapshot, refresh: () -> Unit) {
    var selected by remember { mutableStateOf("") }
    val provider = snapshot.providers.firstOrNull { it.id == selected }
    Row { snapshot.providers.forEach { p -> TextButton(onClick={selected=p.id}) { Text(p.displayName) } } }
    provider?.let { p ->
        var url by remember(p) { mutableStateOf(p.baseUrl) }
        var mode by remember(p) { mutableStateOf(p.mode) }
        var locality by remember(p) { mutableStateOf(p.locality) }
        var profiles by remember(p) { mutableStateOf(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AuthProfile.serializer()), p.authProfiles)) }
        var models by remember(p) { mutableStateOf(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ModelDescriptor.serializer()), p.models)) }
        var headers by remember(p) { mutableStateOf(p.headers.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
        var message by remember { mutableStateOf("") }
        var authId by remember(p) {mutableStateOf(p.activeAuthProfileId.orEmpty())}
        var credential by remember(p) {mutableStateOf("")}
        var clientSecret by remember(p) {mutableStateOf("")}
        var caps by remember(p) {mutableStateOf(p.capabilities)}
        OutlinedTextField(url,{url=it},label={Text("Base URL")})
        Text("Access Mode: $mode")
        AdapterMode.entries.forEach { value -> TextButton(onClick={mode=value}) {Text(value.name)} }
        Text("ExecutionLocality: $locality")
        ExecutionLocality.entries.forEach { value -> TextButton(onClick={locality=value}) {Text(value.name)} }
        OutlinedTextField(headers,{headers=it},label={Text("Custom Headers: name=value，每行一个；密钥只存 Keystore")})
        OutlinedTextField(profiles,{profiles=it},label={Text("AuthProfile 列表 JSON：type/secretRef/tokenUrl/clientId/organizationId/projectId/customHeaders")},minLines=3)
        OutlinedTextField(models,{models=it},label={Text("Model 列表 JSON：id/capabilities/contextWindow")},minLines=3)
        ProviderCapability.entries.forEach { capability ->
            Row {Checkbox(capability in caps,{checked -> caps=if(checked) caps+capability else caps-capability});Text("能力：$capability")}
        }
        OutlinedTextField(authId,{authId=it},label={Text("活动 AuthProfile ID；空=第一个")})
        OutlinedTextField(credential,{credential=it},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),label={Text("Bearer / OAuth Refresh Token → Keystore")})
        OutlinedTextField(clientSecret,{clientSecret=it},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),label={Text("OAuth Client Secret → Keystore")})
        Text("Health: ${runtime.health(p.id)}")
        TextButton(onClick={try {
            runtime.registry.upsert(p.copy(baseUrl=url,mode=mode,locality=locality,
                headers=headers.lineSequence().filter {it.isNotBlank()}.associate {val pair=it.split('=',limit=2);require(pair.size==2);pair[0] to pair[1]},
                authProfiles=Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AuthProfile.serializer()),profiles),
                models=Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ModelDescriptor.serializer()),models),capabilities=caps,activeAuthProfileId=authId.ifBlank {null}))
            val persisted=runtime.registry.find(p.id)!!
            val profile=persisted.authProfiles.firstOrNull {it.id==authId} ?: persisted.authProfiles.firstOrNull()
            if(credential.isNotBlank() && profile!=null) {runtime.secrets.put(credentialKey(persisted,profile),credential);credential=""}
            else if(credential.isNotBlank()) message="AuthProfile required to store credential"
            if(clientSecret.isNotBlank() && !profile?.clientSecretRef.isNullOrBlank()) {runtime.secrets.put(requireNotNull(profile.clientSecretRef),clientSecret);clientSecret=""}
            else if(clientSecret.isNotBlank()) message="OAuth client secret ref required"
            refresh(); message="已保存"
        } catch(e:Exception) {message=e.message.orEmpty()}}) {Text("保存 Provider 详情")}
        Text(message)
    }
}

@Composable fun EcosystemDreamEditor(runtime: OperitEcosystemRuntime) {
    val scope = rememberCoroutineScope()
    val saved=remember {runtime.services.read()}
    var url by remember { mutableStateOf(saved.openViking?.url ?: "http://127.0.0.1:1933") }
    var account by remember { mutableStateOf(saved.openViking?.account ?: "default") }
    var user by remember { mutableStateOf(saved.openViking?.user ?: "operit") }
    var apiKey by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf(saved.tools?.url ?: "http://127.0.0.1:20128") }
    var input by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(runtime.dream().state()) }
    var busy by remember { mutableStateOf(false) }
    Text("Dream 记忆：${state.memories.size}，待同步：${state.memories.count { !it.synced }}；${state.lastError.orEmpty()}")
    OutlinedTextField(url,{url=it},label={Text("OpenViking URL")})
    OutlinedTextField(account,{account=it},label={Text("X-OpenViking-Account")})
    OutlinedTextField(user,{user=it},label={Text("X-OpenViking-User")})
    OutlinedTextField(apiKey,{apiKey=it},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),label={Text("OpenViking API Key（Keystore）")})
    OutlinedTextField(gateway,{gateway=it},label={Text("Tool Gateway URL")})
    TextButton(onClick={
        if(apiKey.isNotBlank()) {runtime.secrets.put("openviking",apiKey);apiKey=""}
        runtime.services.write(EcosystemServicesConfig(OpenVikingConfig(url=url,account=account,user=user),ToolGatewayConfig(url=gateway)))
    }) {Text("保存 Dream / ToolBroker 服务配置")}
    OutlinedTextField(input,{input=it},label={Text("近期摘要 / 工具失败 / 审查结论")})
    Button(enabled=!busy,onClick={busy=true;scope.launch { try {
        val engine=runtime.dream(OpenVikingConfig(url=url,account=account,user=user))
        state=engine.consolidate(DreamInput(sessionSummaries=listOf(input)))
        state=engine.syncPending()
    } finally {busy=false} }}) {Text("Dream 整理与离线队列同步")}
}
