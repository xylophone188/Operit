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

    val runtime=remember {OperitEcosystemRuntime(context)}
    Text("受控进化：prompt / skill / policy / config 审批后新工作流立即读取；回滚恢复上一版本；不修改 APK")
    OutlinedTextField(kind,{kind=it},label={Text("prompt / skill / policy / config")})
    OutlinedTextField(version,{version=it},label={Text("版本号 / 回滚目标版本")})
    OutlinedTextField(content,{content=it},label={Text("提案内容")},minLines=3)
    Row {
        TextButton(onClick={try{pending=EvolutionProposal("ui-${System.currentTimeMillis()}",version.toInt(),kind,content,repo.currentDigest(kind))}catch(e:Exception){message=e.message.orEmpty()}}){Text("创建提案")}
        TextButton(onClick={try{val target=(repo.current(kind)?.version ?: error("无活动版本"))-1;require(target>0);repo.rollback(kind,target);message="已回滚上一版本 v$target"}catch(e:Exception){message=e.message.orEmpty()}}){Text("一键回滚上一版本")}
        TextButton(onClick={try{repo.rollback(kind,version.toInt());message="已回滚"}catch(e:Exception){message=e.message.orEmpty()}}){Text("回滚指定版本")}
    }
    pending?.let { proposal ->
        AlertDialog(onDismissRequest={pending=null},title={Text("审批 ${proposal.kind} v${proposal.version}")},text={Text("Diff (- 当前 / + 提案):\n- ${repo.current(proposal.kind)?.content.orEmpty().take(2000)}\n+ ${proposal.content.take(3000)}\n测试：保存前执行 schema / routing / budget 契约校验")},confirmButton={TextButton(onClick={scope.launch{
            val sandbox=ProposalSandbox { p -> attempt { runtime.evolution.validate(p,runtime.registry.snapshot()) } }
            message=EvolutionEngine(sandbox,ProposalApproval{true},repo).apply(proposal).fold({"测试通过；已批准应用，新工作流立即生效"},{"拒绝：${it.javaClass.simpleName}"})
            pending=null
        }}){Text("批准并验证应用")}},dismissButton={TextButton(onClick={pending=null}){Text("拒绝")}})
    }
    Text(message)
}
