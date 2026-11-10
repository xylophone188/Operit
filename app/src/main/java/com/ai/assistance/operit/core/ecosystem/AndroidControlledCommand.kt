package com.ai.assistance.operit.core.ecosystem

import android.content.Context
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect

/** Reuse Operit's selected permission executor, never silently escalate into root. */
class AndroidControlledCommand(private val context: Context) : ControlledCommand {
    override suspend fun execute(executable: String, arguments: List<String>, stdin: String): String = coroutineScope {
        require(executable in setOf("ask-agy","agy","agy-acp","agy-mcp"))
        require(stdin.length <= 60_000 && arguments.sumOf {it.length} <= 60_000)
        val executor=ShellExecutorFactory.getUserPreferredExecutor(context)
        require(executor.hasPermission().granted && executor.isAvailable()) {"Selected command permission unavailable"}
        val directory=java.io.File(context.packageManager.getApplicationInfo("com.termux",0).dataDir,"files/usr")
        val path=java.io.File(directory,"bin/$executable")
        val command="export PREFIX=${quote(directory.absolutePath)}; export HOME=${quote(java.io.File(directory.parentFile,"home").absolutePath)}; export PATH=\"\$PREFIX/bin:\$PATH\"; " +
            "printf '%s' ${quote(stdin)} | " + (listOf(path.absolutePath)+arguments).joinToString(" ",transform=::quote)
        val process=executor.startProcess(command)
        val stdout=StringBuilder()
        val stderr=StringBuilder()
        try {
            val out=async {process.stdout.collect { require(stdout.length+it.length<=1_000_000);stdout.append(it).append('\n') }}
            val err=async {process.stderr.collect { require(stderr.length+it.length<=64_000);stderr.append(it).append('\n') }}
            out.await(); err.await()
            val exit=process.waitFor()
            val status=SupervisorContract.status(exit,stderr.toString())
            when(status) {
                SupervisorStatus.COMPLETED -> stdout.toString()
                SupervisorStatus.RATE_LIMITED -> throw ProviderHttpException(429,30_000)
                SupervisorStatus.BLOCKED -> throw ProviderHttpException(403)
                SupervisorStatus.TIMEOUT -> throw java.io.IOException("CLI timeout")
                SupervisorStatus.FAILED -> throw java.io.IOException("CLI failed: exit=$exit")
            }
        } finally {process.destroy()}
    }
    companion object { fun quote(value:String):String {require(!value.contains('\u0000'));return "'"+value.replace("'","'\"'\"'")+"'"} }
}
