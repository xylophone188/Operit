package com.ai.assistance.operit.core.ecosystem

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-private versions and active pointers share a process lock, including AtomicFile recovery. */
class AndroidProposalRepository(context: Context) : ProposalRepository {
    companion object { private val lock=Any() }
    private val root=File(context.noBackupFilesDir,"ecosystem/proposals").apply {mkdirs()}
    private val json=Json {encodeDefaults=true}
    private fun dir(kind:String):File {require(kind in setOf("prompt","skill","policy","config"));return File(root,kind).apply{mkdirs()}}
    private fun active(kind:String)=AtomicFile(File(dir(kind),"active.json"))
    private fun read(file:AtomicFile):ByteArray? = try {file.readFully()} catch(_:FileNotFoundException) {null}
    override fun currentDigest(kind:String):String = synchronized(lock) {
        MessageDigest.getInstance("SHA-256").digest(read(active(kind)) ?: byteArrayOf()).joinToString(""){"%02x".format(it)}
    }
    fun current(kind:String):EvolutionProposal? = synchronized(lock) {
        read(active(kind))?.let {json.decodeFromString<EvolutionProposal>(it.toString(Charsets.UTF_8))}
    }
    override fun installVersion(proposal:EvolutionProposal):Unit = synchronized(lock) {
        require(proposal.version>0)
        val bytes=json.encodeToString(proposal).toByteArray()
        if(current(proposal.kind)==proposal) return@synchronized
        require(currentDigest(proposal.kind)==proposal.baseDigest){"proposal base changed"}
        val directory=dir(proposal.kind)
        val archive=AtomicFile(File(directory,"${proposal.version}.json"))
        val existing=read(archive)
        if(existing!=null) {
            check(existing.contentEquals(bytes)){"version content differs"}
        } else {
            val latest=directory.listFiles().orEmpty().mapNotNull {it.name.removeSuffix(".bak").removeSuffix(".json").toIntOrNull()}.maxOrNull() ?: 0
            require(proposal.version>latest){"version must increase"}
            write(archive,bytes)
        }
        write(active(proposal.kind),bytes)
    }
    override fun rollback(kind:String,version:Int):Unit = synchronized(lock) {
        require(version>0)
        val bytes=read(AtomicFile(File(dir(kind),"$version.json"))) ?: error("unknown version")
        val proposal=json.decodeFromString<EvolutionProposal>(bytes.toString(Charsets.UTF_8))
        require(proposal.kind==kind && proposal.version==version)
        write(active(kind),bytes)
    }
    private fun write(file:AtomicFile,bytes:ByteArray) {
        val stream=file.startWrite()
        try{stream.write(bytes);file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e}
    }
}

class ConfigurationProposalSandbox : ProposalSandbox {
    override suspend fun validate(proposal:EvolutionProposal):Result<Unit> = attempt {
        require(proposal.content.toByteArray().size<=256_000){"proposal too large"}
        require(!proposal.content.contains('\u0000'))
        if(proposal.kind=="config") {
            val config=Json.decodeFromString<ProviderRegistrySnapshot>(proposal.content)
            require(config.providers.all {it.existingConfigId==null && it.authProfiles.all {a->a.secretRef==null}}) {"proposal cannot carry credential authority"}
            ProviderRegistry.validate(config)
        }
        require(!proposal.content.contains(Regex("(?i)BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY|Bearer\\s+[A-Za-z0-9._-]{20,}"))) {"secret material forbidden"}
    }
}
