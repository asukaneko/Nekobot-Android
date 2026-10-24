package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in scale fixture, supplied on-device outside the APK/repository. Never logs chat text. */
class FusedCompanionScaleAndroidTest {
    private val gson=Gson()
    @Test fun externalLargeHistoryAndRealVectors() = runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("companion_scale")=="true")
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir=File(context.getExternalFilesDir(null),"companion-stress")
        val log=File(dir,"metrics.jsonl")
        fun record(phase:String, values:Map<String,Any>) {
            log.appendText(gson.toJson(values + ("phase" to phase))+"\n")
            println("SCALE $phase ${gson.toJson(values)}")
        }
        val stage=args.getString("scale_stage") ?: "local"
        val db=Room.databaseBuilder(context,NekobotDatabase::class.java,"companion-scale.db").build()
        try {
            val dao=db.companionMemoryDao()
            val session=LocalSessionEntity("scale-window","Scale fixture",characterId="scale-role",sessionMode="agent",
                inheritCharacter=true,longConversationEnabled=true,companionMode=true,createdAt="now",updatedAt="now")
            FusedCompanionSchema.installTriggers(db.openHelper.writableDatabase)
            if (db.sessionDao().getById(session.id)==null) {
                var started=System.nanoTime()
                db.sessionDao().upsert(session)
                CompanionMemoryStore(db).ensureScope(session)
                var count=0
                db.withTransaction {
                    File(dir,"private-messages.ndjson").bufferedReader().useLines { lines ->
                        // Room transaction runs on its coroutine context; parse lazily to avoid
                        // retaining the entire imported history as a JSON tree.
                        for (line in lines) {
                            val x=JsonParser.parseString(line).asJsonObject
                            db.messageDao().upsert(LocalMessageEntity(x["id"].asString,session.id,x["role"].asString,
                                x["content"].asString,timestamp=x["timestamp"].asString,createdAt=x["created_at"].asString))
                            count++
                        }
                    }
                }
                record("seed_messages",mapOf("count" to count,"ms" to (System.nanoTime()-started)/1e6))
                val scope=CompanionMemoryStore(db).ensureScope(session)
                started=System.nanoTime();var facts=0;var sources=0
                db.withTransaction {
                    File(dir,"private-facts.ndjson").bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val x=JsonParser.parseString(line).asJsonObject
                            val id=x["id"].asString
                            dao.saveMemory(CompanionMemoryEntity(id,scope.id,id,1,"episode","双方",x["content"].asString,"active",
                                category=x["category"].asString,tagsJson=x["tags"].asString,manual=x["manual"].asBoolean,
                                createdAt=x["date"].asString,updatedAt=x["date"].asString))
                            val links=x["source_ids"].asJsonArray.map { element ->
                                val row=requireNotNull(db.messageDao().getById(element.asString))
                                CompanionSourceEntity(id,row.id,CompanionMemoryPolicy.fingerprint(row),row.content.take(120),row.createdAt.take(10),row.role)
                            }
                            dao.saveSources(links);sources+=links.size;facts++
                        }
                    }
                    val rows=db.messageDao().listBySession(session.id)
                    dao.saveReceipts(rows.map { CompanionReceiptEntity(scope.id,it.id,CompanionMemoryPolicy.fingerprint(it)) })
                }
                record("seed_facts",mapOf("facts" to facts,"sources" to sources,"ms" to (System.nanoTime()-started)/1e6))
            }
            val backendKey="live-siliconflow-bge-m3-native"
            var apiCalls=0;var documentInputs=0;var queryInputs=0
            val bridge=args.getString("scale_bridge").orEmpty()
            val client=LocalAiClient(okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val req=chain.request()
                val buffer=okio.Buffer();req.body!!.writeTo(buffer)
                val uri=java.net.URI(bridge)
                require(uri.host=="127.0.0.1")
                val bytes=buffer.readByteArray()
                val reply=java.net.Socket(uri.host,uri.port).use { socket ->
                    socket.soTimeout=60000
                    socket.getOutputStream().apply {
                        write("POST /embeddings HTTP/1.0\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                        write(bytes);flush()
                    }
                    socket.getInputStream().bufferedReader().readText()
                }
                val status=reply.lineSequence().first().split(' ')[1].toInt()
                okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("Fixture bridge")
                    .body(reply.substringAfter("\r\n\r\n").toResponseBody("application/json".toMediaType())).build()
            }.build())
            val model=LocalAiModelEntity("scale-embedding","Free BGE-M3",model="BAAI/bge-m3",purpose="embedding",
                protocol="openai_chat",baseUrl="http://127.0.0.1/v1",apiKey="",createdAt="now")
            val backend=CompanionEmbeddingBackend(backendKey) { inputs ->
                apiCalls++
                if (inputs.size==1) queryInputs++ else documentInputs+=inputs.size
                client.createEmbeddings(model,inputs).vectors
            }
            val search=CompanionHybridSearch(db) { if (stage=="local") null else backend }
            val store=CompanionMemoryStore(db,search)
            val scope=store.ensureScope(session)
            val messages=db.messageDao().listBySession(session.id)
            assertEquals(62000,messages.size)
            var started=System.nanoTime()
            val memories=store.validatedMemories(scope).filter { it.current }
            record("validate_sources",mapOf("facts" to memories.size,"ms" to (System.nanoTime()-started)/1e6))
            assertEquals(764,memories.size)
            if (stage=="index") {
                val items=memories.map { CompanionSearchItem("fact",it.id,
                    "${it.subject} ${it.kind} ${FusedCompanionPolicy.categories[it.category].orEmpty()} ${it.content} ${it.tagsJson} ${it.eventDate.orEmpty()}") }
                var previous=dao.vectors(scope.id,backendKey).size
                started=System.nanoTime()
                repeat(30) {
                    if (previous>=items.size) return@repeat
                    search.index(scope,items)
                    val current=dao.vectors(scope.id,backendKey).size
                    record("index_progress",mapOf("indexed" to current,"apiCalls" to apiCalls))
                    check(current>previous) { "Index made no progress: $current" }
                    previous=current
                }
                assertEquals(items.size,previous)
                val before=apiCalls;search.index(scope,items)
                assertEquals("Unchanged documents must reuse persisted vectors",before,apiCalls)
                record("index_complete",mapOf("indexed" to previous,"ms" to (System.nanoTime()-started)/1e6,"documentInputs" to documentInputs))
            }
            val cases=listOf(
                Triple("搬家","前阵子我在外面帮家里人换住处，是在帮谁？","archive-605"),
                Triple("庆生","我提前庆祝那次，许下的心愿是什么？","archive-488"),
                Triple("生图","我那次刷牙拖了四十分钟才回来，是在忙什么？","archive-484"),
                Triple("日期","八月二十九日我许了什么愿望？","archive-488"))
            var hits=0
            for ((label,query,expected) in cases) {
                started=System.nanoTime()
                val result=store.fusedRecall(session,query,FusedCompanionPolicy.budgets(14000))
                val memoryRows=store.validatedMemories(scope)
                val ids=memoryRows.filter { result.first.lines().contains(CompanionPromptLanguage.memory(it)) }.map { it.id }
                val hit=expected in ids
                if(hit)hits++
                File(dir,"private-recall-$stage-$label.json").writeText(gson.toJson(mapOf(
                    "question" to query,"material" to result.first,"expected_id" to expected)))
                record("recall",mapOf("case" to label,"stage" to stage,"ms" to (System.nanoTime()-started)/1e6,
                    "mode" to result.second,"hit" to hit,"ids" to ids,"apiCalls" to apiCalls,"queryInputs" to queryInputs))
            }
            record("complete",mapOf("stage" to stage,"hits" to hits,"cases" to cases.size,"apiCalls" to apiCalls))
            assertEquals("All four reference episodes must be recalled",cases.size,hits)
        } finally { db.close() }
    }
}
