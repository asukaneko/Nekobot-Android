package app.lianxu.independent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ProcessAction { START, OPEN, OPEN_MODELS, VIEW, ADD_MODEL, EDIT_MODEL, SAVE_MODEL, FETCH_MODELS, TEST_MODEL }
internal enum class ExitKind { JAVA_CRASH, NATIVE_CRASH, ANR, LOW_MEMORY, USER_REQUESTED, USER_STOPPED, SIGNAL }
internal data class OwnExit(val pid:Int, val at:Long, val reason:ExitKind)

/** Private, size-bounded records outside the asynchronous journal. A damaged file is never copied into an export. */
internal class ProcessEvidence(private val dir: File, private val pid: Int, private val start: Long = System.currentTimeMillis()) {
    val sessionId: String = UUID.randomUUID().toString()
    private data class Mark(val page:String,val action:ProcessAction)
    @Volatile private var last = Mark("startup",ProcessAction.START)
    private val crashing = AtomicBoolean()
    init {
        dir.mkdirs()
        prune(dir,start)
        write("run-$sessionId", row("process.last_action",start,last))
    }

    @Synchronized fun action(action:ProcessAction) = mark(last.page,action)
    @Synchronized fun view(page:String) {
        val safe=page.takeIf { it in pages } ?: "other"
        if (safe!=last.page) mark(safe,ProcessAction.VIEW)
    }

    @Synchronized fun mark(page:String,action:ProcessAction) {
        val next=Mark(if(page in pages) page else "other",action)
        if (last==next) return
        last=next
        write("run-$sessionId",row("process.last_action",System.currentTimeMillis(),next))
    }

    fun before(previous: Thread.UncaughtExceptionHandler) = Thread.UncaughtExceptionHandler { thread,error ->
        try { runCatching { uncaught(error) } } finally { previous.uncaughtException(thread,error) }
    }

    fun uncaught(error:Throwable) {
        if(!crashing.compareAndSet(false,true))return
        val at=System.currentTimeMillis()
        val record=row("process.uncaught",at,last)
        var cause:Throwable?=error
        val frames=linkedSetOf<String>()
        error.message?.let { Diagnostics.redact(it).take(240) }?.takeIf { it.isNotBlank() }?.let {
            record.addProperty("error_summary", it)
        }
        for (depth in 0..2) {
            val item=cause ?: break
            val kind=item.javaClass.simpleName.takeIf { typeName.matches(it) } ?: "OTHER"
            record.addProperty(if(depth==0)"exception_type" else "cause_type",kind)
            for(frame in item.stackTrace.take(48)) {
                val name=frame.className
                if(!name.startsWith(APP_PREFIX))continue
                val simple=name.removePrefix(APP_PREFIX)
                if(!classToken.matches(simple))continue
                if(runCatching { Class.forName(name,false,javaClass.classLoader) }.isFailure) continue
                frames.add("$simple:${frame.lineNumber.coerceIn(0,999999)}")
                if(frames.size>=8)break
            }
            if(frames.size>=8 || item.cause===item)break
            cause=item.cause
        }
        record.addProperty("app_frames",frames.joinToString("|"))
        write("fault-$sessionId",record)
    }

    private fun row(event:String,time:Long,mark:Mark)=JsonObject().apply {
        addProperty("event",event); addProperty("at",time); addProperty("trace",sessionId)
        addProperty("pid",pid); addProperty("started_at",start)
        addProperty("page",mark.page); addProperty("action",mark.action.name)
    }

    private val historyLock = Any()
    fun recover(exits:List<OwnExit>,now:Long=System.currentTimeMillis()) = synchronized(historyLock) {
        val runs=rows(dir,now).filter { it["event"].asString=="process.last_action" }
        for(exit in exits.take(32)) {
            if(exit.pid<=0 || exit.at !in (now-WINDOW)..now || (exit.pid==pid && exit.at>=start))continue
            val name="exit-${exit.at}-${exit.pid}"
            if(File(dir,"$name.json").exists())continue
            // An interval ends when the next main process starts. Reused PIDs alone are insufficient.
            val run=runs.filter { it["trace"].asString!=sessionId && it["started_at"].asLong<=exit.at }.maxByOrNull { it["started_at"].asLong }
                ?.takeIf { it["pid"].asInt==exit.pid && it["at"].asLong<=exit.at }
            write(name,JsonObject().apply {
                addProperty("event","process.exit"); addProperty("at",exit.at)
                addProperty("pid",exit.pid); addProperty("reason",exit.reason.name)
                run?.get("trace")?.let { add("trace",it) }
            })
        }
        prune(dir,now)
    }

    private fun write(name:String,value:JsonObject) {
        val bytes=value.toString().toByteArray(Charsets.UTF_8)
        if(bytes.size>MAX_BYTES)return
        val tmp=File(dir,"$name.tmp")
        FileOutputStream(tmp).use { it.write(bytes);it.fd.sync() }
        Files.move(tmp.toPath(),File(dir,"$name.json").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val APP_PREFIX="app.lianxu.independent."
        private const val MAX_BYTES=4096
        private const val WINDOW=7L*24*60*60*1000
        private val classToken=Regex("[A-Za-z_$][A-Za-z0-9_$]{0,125}")
        private val frameToken=Regex("[A-Za-z_$][A-Za-z0-9_$]{0,125}:[0-9]{1,6}")
        private val typeName=Regex("[A-Za-z_$][A-Za-z0-9_$.]{0,120}")
        private val pages=setOf("startup","home","settings","contacts","profile","chat","models","model-editor","model-providers","voice-studio",
            "create","detail","search","role-archive","search-detail","toolbox","models-purposes","processing-role","settings-memory","settings-info",
            "settings-updates","role-prompts","role-learning","role-relationship","role-companion","role-voice","toolbox-image","toolbox-video",
            "toolbox-memory","toolbox-contact","other")
        internal fun prune(dir:File,now:Long) {
            val files=dir.listFiles().orEmpty()
            for(prefix in listOf("run-","fault-","exit-")) {
                files.filter { it.name.startsWith(prefix) && it.extension=="json" }.sortedByDescending { it.lastModified() }
                    .forEachIndexed { n,f -> if(n>=16 || f.lastModified()<now-WINDOW) f.delete() }
            }
            files.filter { it.extension=="tmp" && it.lastModified()<now-60000 }.forEach { it.delete() }
        }
        fun rows(dir:File,now:Long=System.currentTimeMillis()):List<JsonObject> =
            dir.listFiles().orEmpty().filter { it.extension=="json" && it.length() in 1..MAX_BYTES.toLong() }
                .mapNotNull { file -> runCatching {
                    val src=JsonParser.parseString(file.readText()).asJsonObject
                    val event=src.get("event").asString
                    require(event in setOf("process.exit","process.last_action","process.uncaught"))
                    val at=src.get("at").asLong; require(at in (now-WINDOW)..now)
                    val pid=src.get("pid").asInt;require(pid>0)
                    JsonObject().apply {
                        addProperty("at",at);addProperty("event",event);addProperty("pid",pid)
                        src.get("trace")?.asString?.takeIf(ChatVoiceDiagnostics::validTurn)?.let { addProperty("trace",it) }
                        if(event=="process.exit") {
                            addProperty("reason",ExitKind.valueOf(src.get("reason").asString).name)
                        } else {
                            require(has("trace"));val begun=src.get("started_at").asLong;require(begun in 1..at)
                            addProperty("started_at",begun)
                            addProperty("page",src.get("page").asString.takeIf { it in pages } ?: "other")
                            addProperty("action",ProcessAction.valueOf(src.get("action").asString).name)
                            if(event=="process.uncaught") {
                                for(key in listOf("exception_type","cause_type")) src.get(key)?.asString?.let {
                                    addProperty(key,if(typeName.matches(it)) it else "OTHER")
                                }
                                src["error_summary"]?.asString?.let { addProperty("error_summary", Diagnostics.redact(it).take(240)) }
                                val frames=src.get("app_frames")?.asString.orEmpty().split('|').take(8).filter(frameToken::matches)
                                addProperty("app_frames",frames.joinToString("|"))
                            }
                        }
                    }
                }.getOrNull() }.sortedBy { it["at"].asLong }
    }
}
