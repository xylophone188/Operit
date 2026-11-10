package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.core.ecosystem.*
import kotlinx.coroutines.launch

@Composable fun EcosystemEvolutionEditor() {
    val context=LocalContext.current
    val repo=remember {AndroidProposalRepository(context)}
    val scope=rememberCoroutineScope()
    var kind by remember {mutableStateOf("prompt")}
    var version by remember {mutableStateOf("1")}
    var content by remember {mutableStateOf("")}
    var message by remember {mutableStateOf("")}
    var pending by remember {mutableStateOf<EvolutionProposal?>(null)}
    Text("受控进化：审批 prompt 将用于新工作流；skill / policy / config 仅版本化存档，尚不激活；不修改 APK/代码")
    OutlinedTextField(kind,{kind=it},label={Text("prompt / skill / policy / config")})
    OutlinedTextField(version,{version=it},label={Text("版本号 / 回滚目标版本")})
    OutlinedTextField(content,{content=it},label={Text("提案内容")},minLines=3)
    Row {
        TextButton(onClick={try{pending=EvolutionProposal("ui-${System.currentTimeMillis()}",version.toInt(),kind,content,repo.currentDigest(kind))}catch(e:Exception){message=e.message.orEmpty()}}){Text("创建提案")}
        TextButton(onClick={try{repo.rollback(kind,version.toInt());message="已回滚"}catch(e:Exception){message=e.message.orEmpty()}}){Text("回滚版本")}
    }
    pending?.let { proposal ->
        AlertDialog(onDismissRequest={pending=null},title={Text("审批 ${proposal.kind} v${proposal.version}")},text={Text(proposal.content.take(4000))},confirmButton={TextButton(onClick={scope.launch{
            message=EvolutionEngine(ConfigurationProposalSandbox(),ProposalApproval{true},repo).apply(proposal).fold({"提案已存档；仅 prompt 会用于新工作流，不修改应用本体"},{"拒绝：${it.javaClass.simpleName}"})
            pending=null
        }}){Text("批准并验证应用")}},dismissButton={TextButton(onClick={pending=null}){Text("拒绝")}})
    }
    Text(message)
}
