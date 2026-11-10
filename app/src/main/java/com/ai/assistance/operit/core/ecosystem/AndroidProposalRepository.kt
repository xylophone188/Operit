package com.ai.assistance.operit.core.ecosystem

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-private immutable versions plus atomic active pointer. No code/APK installation surface. */
class AndroidProposalRepository(context: Context) : ProposalRepository {
    private val root=File(context.noBackupFilesDir,"ecosystem/proposals").apply {mkdirs()}
    private val json=Json {encodeDefaults=true}
    private fun dir(kind:String):File {require(kind in setOf("prompt","skill","policy","config"));return File(root,kind).apply{mkdirs()}}
    private fun active(kind:String)=AtomicFile(File(dir(kind),"active.json"))
    @Synchronized override fun currentDigest(kind:String):String {
        val file=active(kind)
        val bytes=try {file.readFully()} catch(_:java.io.FileNotFoundException) {byteArrayOf()}
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    }
    @Synchronized fun current(kind:String):EvolutionProposal? = active(kind).let {
        try {json.decodeFromString<EvolutionProposal>(it.readFully().toString(Charsets.UTF_8))} catch(_:java.io.FileNotFoundException) {null}
    }
    @Synchronized override fun installVersion(proposal:EvolutionProposal) {
        require(proposal.version>0)
        val bytes=json.encodeToString(proposal).toByteArray()
        if(current(proposal.kind)==proposal) return // Approved transaction already committed.
        require(currentDigest(proposal.kind)==proposal.baseDigest){"proposal base changed"}
        val directory=dir(proposal.kind)
        val version=File(directory,"${proposal.version}.json")
        if(version.exists()) {
            // Recover a crash between archive write and active-pointer update, not a conflicting version.
            check(version.readBytes().contentEquals(bytes)){"version content differs"}
        } else {
            val latest=directory.listFiles().orEmpty().mapNotNull {it.name.removeSuffix(".json").toIntOrNull()}.maxOrNull() ?: 0
            require(proposal.version>latest){"version must increase"}
            val archive=AtomicFile(version); val stream=archive.startWrite()
            try{stream.write(bytes);archive.finishWrite(stream)}catch(e:Exception){archive.failWrite(stream);throw e}
        }
        setActive(proposal.kind,bytes)
    }
    @Synchronized override fun rollback(kind:String,version:Int) {
        require(version>0)
        val file=File(dir(kind),"$version.json")
        require(file.isFile){"unknown version"}
        val proposal=json.decodeFromString<EvolutionProposal>(file.readText())
        require(proposal.kind==kind && proposal.version==version)
        setActive(kind,file.readBytes())
    }
    private fun setActive(kind:String,bytes:ByteArray){val file=active(kind);val output=file.startWrite();try{output.write(bytes);file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}}
}

class ConfigurationProposalSandbox : ProposalSandbox {
    override suspend fun validate(proposal:EvolutionProposal):Result<Unit> = attempt {
        require(proposal.content.toByteArray().size<=256_000){"proposal too large"}
        require(!proposal.content.contains('\u0000'))
        if(proposal.kind=="config") ProviderRegistry.validate(Json.decodeFromString<ProviderRegistrySnapshot>(proposal.content))
        require(!proposal.content.contains(Regex("(?i)BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY|Bearer\\s+[A-Za-z0-9._-]{20,}"))) {"secret material forbidden"}
    }
}
