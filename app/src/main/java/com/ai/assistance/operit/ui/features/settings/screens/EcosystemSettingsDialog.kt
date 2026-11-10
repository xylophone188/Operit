package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.ecosystem.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses the existing settings entrypoint; persisted snapshot edits never alter a running task. */
@Composable
fun EcosystemSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { OperitEcosystemRuntime(context) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(runtime.registry.snapshot()) }
    var json by remember { mutableStateOf(runtime.registry.exportRedacted()) }
    var id by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun refresh() { snapshot = runtime.registry.snapshot(); json = runtime.registry.exportRedacted() }
    AlertDialog(onDismissRequest=onDismiss, confirmButton={ TextButton(onClick=onDismiss) { Text("关闭") } },
        title={ Text("统一模型生态") },text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Provider / 职能 / Combo 配置；PC stdio/Direct 通道须配置真实网关，不代表手机原生直连。")
            OutlinedTextField(id,{id=it},label={Text("Provider ID")})
            OutlinedTextField(endpoint,{endpoint=it},label={Text("Base URL")})
            OutlinedTextField(model,{model=it},label={Text("模型 ID")})
            OutlinedTextField(secret,{secret=it},label={Text("API Key → Android Keystore")},visualTransformation=PasswordVisualTransformation())
            Button(onClick={
                try {
                    val ref="ecosystem.$id.default"
                    val provider=ProviderDescriptor(id,id,endpoint,models=listOf(ModelDescriptor(model)),authProfiles=if(secret.isNotBlank()) listOf(AuthProfile("default","默认",ref)) else emptyList())
                    ProviderRegistry.validate(ProviderRegistrySnapshot(listOf(provider)))
                    if(secret.isNotBlank()) runtime.secrets.put(credentialKey(provider,provider.authProfiles.first()),secret)
                    runtime.registry.upsert(provider); secret=""; refresh(); result="已保存"
                } catch(e:Exception) { result=e.message ?: e.javaClass.simpleName }
            },enabled=!busy) { Text("保存 Provider") }
            snapshot.providers.forEach { p ->
                Row { Text(p.displayName,Modifier.weight(1f)); Switch(p.enabled,{ enabled -> runtime.registry.upsert(p.copy(enabled=enabled)); refresh() }) }
                TextButton(enabled=!busy,onClick={ busy=true; scope.launch { try { result=withContext(Dispatchers.IO) { runtime.test(p).fold({"连通性：${it.latencyMs}ms"},{"连通性失败：${it.javaClass.simpleName}"}) } } finally { busy=false } } }) { Text("真实模型连通性测试") }
            }
            EcosystemComboEditor(snapshot) { combo ->
                runtime.registry.replace(snapshot.copy(combos=snapshot.combos.filterNot { it.id==combo.id }+combo))
                refresh()
            }
            Text("职能 → Combo ID（先配置 Combo）")
            WorkFunction.entries.forEach { f ->
                var binding by remember(snapshot.revision,f) { mutableStateOf(snapshot.bindings[f].orEmpty()) }
                OutlinedTextField(binding,{binding=it},label={Text(f.name)},trailingIcon={ TextButton(onClick={
                    try { runtime.registry.replace(snapshot.copy(bindings=if(binding.isBlank()) snapshot.bindings-f else snapshot.bindings+(f to binding)));refresh();result="映射已保存" } catch(e:Exception) {result=e.message.orEmpty()}
                }) {Text("保存")} })
            }
            Text("高级配置 JSON：Provider Header / 组织与项目引用 / Adapter Mode / Combo 权重、超时、重试与顺序。导出移除凭据引用与 Header。")
            OutlinedTextField(json,{json=it},label={Text("配置导入 / 脱敏导出")},minLines=6)
            Row { TextButton(onClick={json=runtime.registry.exportRedacted()}) {Text("脱敏导出")}; TextButton(onClick={try{runtime.registry.import(json);refresh();result="配置已导入"}catch(e:Exception){result=e.message.orEmpty()}}) {Text("导入")}}
            OutlinedTextField(prompt,{prompt=it},label={Text("工作流输入（EXECUTION 映射）")})
            Button(enabled=!busy && prompt.isNotBlank(),onClick={busy=true;scope.launch{try {result=withContext(Dispatchers.IO){runtime.run(ModelRequest(prompt,WorkFunction.EXECUTION)).fold({it.primary.text+"\n"+it.auxiliaryFindings.entries.joinToString("\n"){e->"${e.key}: ${e.value}"}},{"工作流失败：${it.javaClass.simpleName}"})}}finally{busy=false}}}) {Text("执行 + 并行验证/审查")}
            EcosystemEvolutionEditor()
            if(busy) LinearProgressIndicator()
            Text(result)
        }
    })
}
