package com.ai.assistance.operit.ui.features.settings.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.ecosystem.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Reuses the already shipped Calvin-LL Reorderable dependency. */
@Composable
fun EcosystemComboEditor(snapshot: ProviderRegistrySnapshot, onSave: (ComboDefinition)->Unit) {
    var name by remember { mutableStateOf("execution") }
    var members by remember { mutableStateOf(emptyList<ComboMember>()) }
    var auto by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    Text("Combo / Auto-Combo（拖拽调整 Fallback 优先级）")
    snapshot.combos.forEach { c -> TextButton(onClick={name=c.id;members=c.members;auto=c.autoScore}) {Text("编辑 ${c.id}")} }
    OutlinedTextField(name,{name=it},label={Text("Combo ID")})
    Row {Text("Auto-score",Modifier.weight(1f));Switch(auto,{auto=it})}
    snapshot.providers.forEach { p -> p.models.forEach { m ->
        TextButton(onClick={if(members.none {it.providerId==p.id && it.modelId==m.id}) members=members+ComboMember(p.id,m.id)}) {Text("+ ${p.id} / ${m.id}")}
    } }
    val state=rememberLazyListState()
    val reorder=rememberReorderableLazyListState(state) {from,to ->members=members.toMutableList().apply {add(to.index,removeAt(from.index))}}
    LazyColumn(state=state,modifier=Modifier.fillMaxWidth().heightIn(max=320.dp)) {
        itemsIndexed(members,key={_,m->"${m.providerId}/${m.modelId}"}) {index,m ->
            ReorderableItem(reorder,key="${m.providerId}/${m.modelId}") {
                Column {
                    Row {Text("${m.providerId}/${m.modelId}",Modifier.weight(1f));TextButton(onClick={},modifier=Modifier.draggableHandle()) {Text("↕ 拖拽")};TextButton(onClick={members=members.filterIndexed {i,_->i!=index}}) {Text("移除")}}
                    Row {
                        OutlinedTextField(m.weight.toString(),{v->v.toDoubleOrNull()?.let {w->members=members.toMutableList().apply{set(index,m.copy(weight=w))}}},label={Text("权重")},modifier=Modifier.weight(1f))
                        OutlinedTextField(m.timeoutMs.toString(),{v->v.toLongOrNull()?.let {t->members=members.toMutableList().apply{set(index,m.copy(timeoutMs=t))}}},label={Text("超时ms")},modifier=Modifier.weight(1f))
                    }
                }
            }
        }
    }
    Button(onClick={try{onSave(ComboDefinition(name,members,auto));error="已保存"}catch(e:Exception){error=e.message.orEmpty()}}) {Text("保存 Combo")}
    Text(error)
}
