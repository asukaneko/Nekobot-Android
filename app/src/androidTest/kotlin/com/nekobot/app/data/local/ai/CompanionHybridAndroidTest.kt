package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic vectors verify plumbing and call counts, not a provider's language accuracy. */
@RunWith(AndroidJUnit4::class)
class CompanionHybridAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val gson = Gson()
    private fun db() = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
    private suspend fun session(db: NekobotDatabase): LocalSessionEntity {
        db.characterDao().upsert(LocalCharacterEntity("c", "Fixture", systemPrompt="温柔自然", createdAt="now", updatedAt="now"))
        return LocalSessionEntity("s", "Fixture", characterId="c", sessionMode="agent", inheritCharacter=true,
            longConversationEnabled=true, createdAt="now", updatedAt="now").also {
            db.sessionDao().upsert(it); CompanionMemoryStore(db).ensureScope(it)
        }
    }
    private suspend fun say(db: NekobotDatabase, s: LocalSessionEntity, id: String, text: String, role: String="user") =
        LocalMessageEntity(id, s.id, role, text, timestamp="2026-10-07", createdAt="2026-10-07").also { db.messageDao().upsert(it) }
    private fun draft(row: LocalMessageEntity, operation: String="add", content: String=row.content,
        key: String="diet", target: String="", conflict: String="", question: String="") = mapOf(
        "key" to key, "target_key" to target, "conflict_id" to conflict, "operation" to operation,
        "kind" to "fact", "subject" to "用户", "content" to content, "source_id" to row.id,
        "quote" to row.content, "event_evidence" to "", "tags" to emptyList<String>(), "question" to question)
    private fun response(change: Map<String, Any>) = gson.toJson(mapOf("changes" to listOf(change)))
    private suspend fun seed(db: NekobotDatabase, s: LocalSessionEntity, store: CompanionMemoryStore): CompanionMemoryEntity {
        val r=say(db,s,"old","我怕辣")
        store.process(s,{_,_->response(draft(r,content="用户怕辣"))})
        return db.companionMemoryDao().memories(store.ensureScope(s).id).single()
    }
    private suspend fun conflict(db: NekobotDatabase, s: LocalSessionEntity, store: CompanionMemoryStore): CompanionClarificationEntity {
        val r=say(db,s,"new","我喜欢吃辣")
        store.process(s,{prompt,material->
            assertTrue(prompt.contains("target_key")); assertTrue(material.contains("用户怕辣"))
            response(draft(r,"clarify","用户喜欢吃辣",key="generated-alias",target="diet",question="我记得你以前怕辣，现在能吃辣了吗？"))
        })
        return db.companionMemoryDao().clarifications(store.ensureScope(s).id).single()
    }

    @Test fun bothCollectionsReuseOneQueryVectorAndOnlyChangedTextIsReembedded() = runBlocking {
        val db=db()
        try {
            val s=session(db); val scope=CompanionMemoryStore(db).ensureScope(s)
            val requests=mutableListOf<List<String>>()
            val backend=CompanionEmbeddingBackend("fixture") { inputs -> requests += inputs; inputs.map { floatArrayOf(1f,0f) } }
            val search=CompanionHybridSearch(db) { backend }
            val items=listOf(CompanionSearchItem("fact","f","偏好"),CompanionSearchItem("archive","a","曾经的经历"))
            assertEquals(2,search.scores(scope,"问题",items,true).size)
            assertEquals(listOf(listOf("偏好","曾经的经历"),listOf("问题")),requests)
            requests.clear(); CompanionHybridSearch(db) { backend }.scores(scope,"再问",items,true)
            assertEquals(listOf(listOf("再问")),requests)
            requests.clear(); search.scores(scope,"改后问",items.map { if(it.type=="fact") it.copy(text="新偏好") else it },true)
            assertEquals(listOf(listOf("新偏好"),listOf("改后问")),requests)
            requests.clear(); search.scores(scope,"维护",items.take(1))
            assertEquals(2,db.companionMemoryDao().vectors(scope.id,"fixture").size)
        } finally { db.close() }
    }

    @Test fun matchingByMeaningFeedsFactsArchivesAndExactSourceWithinSameBudget() = runBlocking {
        val db=db()
        try {
            val s=session(db); val requests=mutableListOf<List<String>>()
            val search=CompanionHybridSearch(db) { CompanionEmbeddingBackend("fixture") { inputs -> requests += inputs; inputs.map { floatArrayOf(1f,0f) } } }
            val store=CompanionMemoryStore(db,search)
            val r=say(db,s,"original","我受不了香水的气味")
            store.process(s,{_,_->response(draft(r,content="用户受不了香水"))})
            db.experienceArchiveDao().saveGenerated(LocalExperienceArchiveEntity("episode",s.id,characterId=s.characterId,
                startMessageId=r.id,endMessageId=r.id,sourceStartedAt=r.createdAt,sourceEndedAt=r.createdAt,
                summary="闻到浓郁香气时不舒服",sourceFingerprint="fixture",createdAt="now",updatedAt="now"),listOf(r.id))
            requests.clear()
            val output=store.recall(s,"什么会让我的鼻子难受，原话呢？",emptyList(),6000)
            assertTrue(output.contains("用户受不了香水")); assertTrue(output.contains("闻到浓郁香气时不舒服"))
            assertTrue(output.contains("\"verbatim\":\"我受不了香水的气味\"")); assertTrue(output.length<=6000)
            assertEquals(1,requests.count { it==listOf("什么会让我的鼻子难受，原话呢？") })
        } finally { db.close() }
    }

    @Test fun disabledBackendAndFailuresKeepKeywordRecallWithoutRetries() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store)
            var calls=0
            val failure=CompanionHybridSearch(db) { CompanionEmbeddingBackend("broken") { calls++; error("fixture failure") } }
            val output=CompanionMemoryStore(db,failure).recall(s,"我怕辣吗",emptyList(),6000)
            assertTrue(output.contains("用户怕辣")); assertEquals(1,calls)
            assertTrue(CompanionMemoryStore(db,CompanionHybridSearch(db){null}).recall(s,"我怕辣吗",emptyList(),6000).contains("用户怕辣"))
        } finally { db.close() }
    }

    @Test fun modelSwitchAndDimensionChangeCannotReuseIncompatibleVectors() = runBlocking {
        val db=db()
        try {
            val s=session(db); val scope=CompanionMemoryStore(db).ensureScope(s)
            val item=listOf(CompanionSearchItem("fact","f","text")); var key="one"; var dimensions=2; var calls=0
            val search=CompanionHybridSearch(db) { CompanionEmbeddingBackend(key) { inputs -> calls++; inputs.map { FloatArray(dimensions){1f} } } }
            search.scores(scope,"q",item,true); key="two"; calls=0
            assertEquals(1,search.scores(scope,"q",item,true).size); assertEquals(2,calls)
            assertTrue(db.companionMemoryDao().vectors(scope.id,"one").isEmpty())
            dimensions=3; assertTrue(search.scores(scope,"q",item,true).isEmpty())
            assertTrue(db.companionMemoryDao().vectors(scope.id,"two").isEmpty())
            assertEquals(1,search.scores(scope,"q",item,true).size)
        } finally { db.close() }
    }

    @Test fun lateEmbeddingAfterCharacterSwitchIsDiscarded() = runBlocking {
        val db=db()
        try {
            val s=session(db); val scope=CompanionMemoryStore(db).ensureScope(s)
            val search=CompanionHybridSearch(db) { CompanionEmbeddingBackend("late") { inputs ->
                db.sessionDao().upsert(s.copy(characterId="other")); inputs.map { floatArrayOf(1f) }
            } }
            assertTrue(search.scores(scope,"q",listOf(CompanionSearchItem("fact","f","text")),true).isEmpty())
            assertTrue(db.companionMemoryDao().vectors(scope.id,"late").isEmpty())
        } finally { db.close() }
    }

    @Test fun repeatsAndCanonicalAliasesKeepOnePendingThenShortConfirmationClosesIt() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store)
            val pending=conflict(db,s,store); val scope=store.ensureScope(s)
            val repeat=say(db,s,"repeat","我就是喜欢吃辣")
            store.process(s,{_,_->response(draft(repeat,"clarify","用户喜欢吃辣",key="another-name",target="diet",question=pending.question))})
            assertEquals(1,db.companionMemoryDao().clarifications(scope.id).size)
            assertEquals(2,db.companionMemoryDao().memories(scope.id).size)
            assertEquals(pending.memoryId,db.companionMemoryDao().clarifications(scope.id).single().memoryId)
            say(db,s,"asked",pending.question,"assistant"); store.recordLegacyClarificationQuestion(s,"asked")
            assertNull(store.legacyQuestionForReply(s))
            val yes=say(db,s,"yes","对，现在可以吃辣了")
            store.process(s,{_,_->response(draft(yes,"resolve","用户喜欢吃辣",target="diet",conflict=pending.memoryId))})
            assertTrue(db.companionMemoryDao().clarifications(scope.id).isEmpty())
            val all=store.validatedMemories(store.ensureScope(s))
            assertEquals("用户喜欢吃辣",all.single { it.current }.content)
            assertTrue(all.any { !it.current && it.content=="用户怕辣" })
            assertFalse(all.any { it.status=="conflict" })
            assertNull(store.legacyQuestionForReply(s))
        } finally { db.close() }
    }

    @Test fun confirmationCannotUseAnUnaskedOrForeignQuestion() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store); val pending=conflict(db,s,store)
            val yes=say(db,s,"yes","对")
            try { store.process(s,{_,_->response(draft(yes,"resolve","用户喜欢吃辣",target="diet",conflict=pending.memoryId))}); fail() }
            catch (_: IllegalArgumentException) {}
            assertEquals("用户怕辣",store.validatedMemories(store.ensureScope(s)).single { it.current }.content)
            assertTrue(db.companionMemoryDao().pending(store.ensureScope(s).id).any { it.id==yes.id })
        } finally { db.close() }
    }

    @Test fun spontaneousQuestionInSameTurnIsRecordedWithoutAskingAgain() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store)
            val r=say(db,s,"changed","我喜欢吃辣")
            val a=say(db,s,"spontaneous","你之前不是怕辣吗，现在口味变了？","assistant")
            store.process(s,{_,_->gson.toJson(mapOf("changes" to listOf(
                draft(r,"clarify","用户喜欢吃辣",target="diet",question="现在能吃辣了吗？"),
                draft(a,"asked",target="diet"))))})
            assertNull(store.legacyQuestionForReply(s))
            assertEquals(a.id,db.companionMemoryDao().clarifications(store.ensureScope(s).id).single().questionMessageId)
        } finally { db.close() }
    }

    @Test fun unchangedPreferenceMergesAndExplicitChangeNeedsNoClarification() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); val original=seed(db,s,store)
            val same=say(db,s,"same","我一点辣椒也受不了")
            store.process(s,{_,_->response(draft(same,"merge","用户怕辣",key="new-label",target="diet"))})
            assertEquals(1,db.companionMemoryDao().memories(store.ensureScope(s).id).size)
            assertEquals(2,db.companionMemoryDao().sources(original.id).size)
            val change=say(db,s,"changed","现在我能吃辣了")
            store.process(s,{_,_->response(draft(change,"update","用户能吃辣",target="diet"))})
            assertEquals("用户能吃辣",store.validatedMemories(store.ensureScope(s)).single { it.current }.content)
            assertTrue(db.companionMemoryDao().clarifications(store.ensureScope(s).id).isEmpty())
        } finally { db.close() }
    }

    @Test fun shortConfirmationUsesTheAskedCandidateDespiteModelRewording() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store); val pending=conflict(db,s,store)
            say(db,s,"asked",pending.question,"assistant"); store.recordLegacyClarificationQuestion(s,"asked")
            val yes=say(db,s,"yes","对")
            store.process(s,{_,_->response(draft(yes,"resolve","用户现在喜欢辣椒了",target="diet",conflict=pending.memoryId))})
            assertEquals("用户喜欢吃辣",store.validatedMemories(store.ensureScope(s)).single { it.current }.content)
            val text=store.recall(s,"我的口味？",emptyList(),6000)
            assertTrue(text.contains("\"confirmed_by_user\":true"))
            assertTrue(db.companionMemoryDao().clarifications(store.ensureScope(s).id).isEmpty())
        } finally { db.close() }
    }

    @Test fun manyDuplicateSourcesStayStoredWithoutCrowdingFactOutOfRecall() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); val fact=seed(db,s,store)
            repeat(60) { i ->
                val r=say(db,s,"repeat-evidence-$i","我怕辣")
                store.process(s,{_,_->response(draft(r,"merge","用户怕辣",target="diet"))})
            }
            assertEquals(61,db.companionMemoryDao().sources(fact.id).size)
            val output=store.recall(s,"我饮食方面有什么偏好，原话是什么？",emptyList(),6000)
            assertTrue(output.contains("用户怕辣")); assertTrue(output.length<=6000)
            assertEquals(1,db.companionMemoryDao().memories(store.ensureScope(s).id).size)
        } finally { db.close() }
    }

    @Test fun resolvedCandidateEvidenceStillParticipatesInEditingAndForgetting() = runBlocking {
        for (edit in listOf(true,false)) {
            val db=db()
            try {
                val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store); val pending=conflict(db,s,store)
                say(db,s,"asked",pending.question,"assistant"); store.recordLegacyClarificationQuestion(s,"asked")
                val yes=say(db,s,"yes","对")
                store.process(s,{_,_->response(draft(yes,"resolve","用户喜欢吃辣",target="diet",conflict=pending.memoryId))})
                val current=store.validatedMemories(store.ensureScope(s)).single { it.current }
                assertTrue(db.companionMemoryDao().sources(current.id).any { it.messageId=="new" })
                if (edit) {
                    db.messageDao().upsert(db.messageDao().getById("new")!!.copy(content="原候选消息已更正"))
                    assertFalse(store.validatedMemories(store.ensureScope(s)).any { it.current })
                } else {
                    assertTrue(store.forgetRequested(s,"忘记diet"))
                    assertTrue(db.companionMemoryDao().forgotten(store.ensureScope(s).id).contains("new"))
                    assertFalse(store.recall(s,"我喜欢吃辣的原话？",emptyList(),6000).contains("我喜欢吃辣"))
                }
            } finally { db.close() }
        }
    }

    @Test fun retainingOldFactClosesPendingAndManualLockSurvivesConfirmation() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store); var pending=conflict(db,s,store)
            say(db,s,"asked",pending.question,"assistant"); store.recordLegacyClarificationQuestion(s,"asked")
            val no=say(db,s,"no","不是，我还是怕辣")
            store.process(s,{_,_->response(draft(no,"retain",target="diet",conflict=pending.memoryId))})
            assertTrue(db.companionMemoryDao().clarifications(store.ensureScope(s).id).isEmpty())
            store.lock(s,"diet",true)
            val r=say(db,s,"locked-change","现在我喜欢吃辣了")
            store.process(s,{_,_->response(draft(r,"update","用户喜欢吃辣",target="diet",question="现在能吃辣了吗？"))})
            pending=db.companionMemoryDao().clarifications(store.ensureScope(s).id).single()
            say(db,s,"locked-asked",pending.question,"assistant"); store.recordLegacyClarificationQuestion(s,"locked-asked")
            val yes=say(db,s,"locked-yes","对")
            store.process(s,{_,_->response(draft(yes,"resolve","用户喜欢吃辣",target="diet",conflict=pending.memoryId))})
            assertEquals("用户怕辣",store.validatedMemories(store.ensureScope(s)).single { it.current }.content)
            assertTrue(store.validatedMemories(store.ensureScope(s)).single { it.current }.locked)
            assertNull(store.legacyQuestionForReply(s))
            assertTrue(db.companionMemoryDao().clarifications(store.ensureScope(s).id).isEmpty())
            val repeat=say(db,s,"locked-repeat","现在我喜欢吃辣了")
            store.process(s,{_,_->response(draft(repeat,"update","用户喜欢吃辣",target="diet"))})
            assertTrue(db.companionMemoryDao().clarifications(store.ensureScope(s).id).isEmpty())
        } finally { db.close() }
    }

    @Test fun actualPipelinePersistsOneNaturalQuestionAndDoesNotReaskNextTurn() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store); val pending=conflict(db,s,store)
            val model=LocalAiModelEntity("offline","Fixture",protocol="openai_chat",apiKey="",baseUrl="https://example.invalid",model="fixture",createdAt="now")
            val request=ChatRequest.forLocal(s.id,"今天的天气真好")
            val ctx=PipelineContext(request)
            val callbacks=LocalPipelineCallbacks(db,LocalAiClient(),model,s,db.characterDao().getById("c"))
            callbacks.loadMessages(ctx)
            callbacks.saveAssistantMessage(ctx,mapOf("id" to "reply","content" to "是呀，一起散步吧。"))
            val reply=db.messageDao().getById("reply")!!
            assertEquals(1,Regex(Regex.escape(pending.question)).findAll(reply.content).count())
            callbacks.sendResponse(ctx,mapOf("id" to "reply","content" to "是呀，一起散步吧。"))
            val event=callbacks.eventChannel.tryReceive().getOrThrow() as com.nekobot.app.data.remote.RealtimeEvent.AiResponse
            assertEquals(reply.content,event.message!!.content)
            assertEquals("reply",db.companionMemoryDao().clarifications(store.ensureScope(s).id).single().questionMessageId)
            val next=LocalPipelineCallbacks(db,LocalAiClient(),model,s,db.characterDao().getById("c"))
            val nextCtx=PipelineContext(ChatRequest.forLocal(s.id,"聊点别的")); next.loadMessages(nextCtx)
            next.saveAssistantMessage(nextCtx,mapOf("id" to "next-reply","content" to "好呀。"))
            assertEquals("好呀。",db.messageDao().getById("next-reply")!!.content)
        } finally { db.close() }
    }

    @Test fun forgottenOrEditedEvidenceRemovesVectorsAndPendingClarification() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store); val pending=conflict(db,s,store)
            val scope=store.ensureScope(s)
            db.companionMemoryDao().saveVectors(listOf(CompanionVectorEntity(scope.id,"fact",pending.memoryId,"fixture","d","[1]","now")))
            db.messageDao().upsert(db.messageDao().getById("new")!!.copy(content="更改后的原句"))
            assertTrue(db.companionMemoryDao().vectors(scope.id,"fixture").isEmpty())
            assertTrue(db.companionMemoryDao().clarifications(scope.id).isEmpty())
            val newest=say(db,s,"newest","我喜欢吃辣")
            store.process(s,{_,_->response(draft(newest,"clarify","用户喜欢吃辣",target="diet"))})
            assertTrue(store.forgetRequested(s,"忘记辣"))
            assertTrue(db.companionMemoryDao().clarifications(scope.id).isEmpty())
            assertTrue(db.companionMemoryDao().forgotten(scope.id).contains("newest"))
            assertFalse(store.recall(s,"我怕辣吗",emptyList(),6000).contains("用户怕辣"))
        } finally { db.close() }
    }

    @Test fun sessionDeletionCleansBothNewTables() = runBlocking {
        val db=db()
        try {
            val s=session(db); val store=CompanionMemoryStore(db); seed(db,s,store); val pending=conflict(db,s,store)
            val scope=store.ensureScope(s)
            db.companionMemoryDao().saveVectors(listOf(CompanionVectorEntity(scope.id,"fact",pending.memoryId,"fixture","d","[1]","now")))
            db.openHelper.writableDatabase.execSQL("DELETE FROM local_sessions WHERE id=?",arrayOf(s.id))
            assertTrue(db.companionMemoryDao().vectors(scope.id,"fixture").isEmpty())
            assertTrue(db.companionMemoryDao().clarifications(scope.id).isEmpty())
        } finally { db.close() }
    }

    @Test fun actualEmbeddingHttpBatchesIndexesAndSendsOnlyQuestionOnRepeat() = runBlocking {
        val db=db()
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val keys=java.security.KeyStore.getInstance("PKCS12").apply {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                .open("companion-loopback.p12").use { load(it,"fixturepass".toCharArray()) }
        }
        val km=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys,"fixturepass".toCharArray()) }
        val tm=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val tls=javax.net.ssl.SSLContext.getInstance("TLS").apply { init(km.keyManagers,tm.trustManagers,null) }
        val client=LocalAiClient(okhttp3.OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,
            tm.trustManagers.single() as javax.net.ssl.X509TrustManager).build())
        val server=tls.serverSocketFactory.createServerSocket(0,3,java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val s=session(db); val scope=CompanionMemoryStore(db).ensureScope(s)
            val captured=executor.submit<List<List<String>>> {
                (1..3).map {
                    server.accept().use { socket ->
                        socket.soTimeout=10_000
                        val input=socket.getInputStream(); val header=StringBuilder()
                        while(!header.endsWith("\r\n\r\n")) { val b=input.read(); check(b>=0); header.append(b.toChar()) }
                        assertTrue(header.startsWith("POST /v1/embeddings "))
                        val length=Regex("Content-Length: (\\d+)",RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                        val body=ByteArray(length); var offset=0
                        while(offset<length) { val n=input.read(body,offset,length-offset); check(n>0); offset+=n }
                        val payload=com.google.gson.JsonParser.parseString(String(body,Charsets.UTF_8)).asJsonObject
                        assertEquals("fixture",payload["model"].asString)
                        assertEquals(2,payload["dimensions"].asInt)
                        val texts=payload["input"].asJsonArray.map { it.asString }
                        val reply=gson.toJson(mapOf("data" to texts.indices.reversed().map { index ->
                            mapOf("index" to index, "embedding" to listOf(1.0,0.0))
                        },"usage" to mapOf("prompt_tokens" to 17))).toByteArray()
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().write(reply); socket.getOutputStream().flush()
                        texts
                    }
                }
            }
            val model=LocalAiModelEntity("offline","Loopback",protocol="openai_chat",apiKey="",baseUrl="https://127.0.0.1:${server.localPort}/v1",
                model="fixture",purpose="embedding",dimensions=2,createdAt="now")
            var reportedTokens=0
            val backend=CompanionEmbeddingBackend("http-fixture") { inputs ->
                val result=client.createEmbeddings(model,inputs); reportedTokens+=result.inputTokens; result.vectors
            }
            val items=listOf(CompanionSearchItem("fact","f","用户偏好"),CompanionSearchItem("archive","a","一段经历"))
            CompanionHybridSearch(db){backend}.scores(scope,"第一问",items,true)
            CompanionHybridSearch(db){backend}.scores(scope,"第二问",items,true)
            assertEquals(listOf(listOf("用户偏好","一段经历"),listOf("第一问"),listOf("第二问")),captured.get(10,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(51,reportedTokens) // Fixture usage only; this is not a provider bill.
        } finally { server.close(); executor.shutdownNow(); db.close() }
    }

    @Test fun reopenedDatabaseReusesPersistedIndexesAndAskedState() = runBlocking {
        val name="hybrid-reopen-${java.util.UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,NekobotDatabase::class.java,name).build()
        try {
            val s=session(db); var store=CompanionMemoryStore(db); seed(db,s,store); val pending=conflict(db,s,store)
            say(db,s,"asked",pending.question,"assistant"); store.recordLegacyClarificationQuestion(s,"asked")
            val scope=store.ensureScope(s); val items=listOf(CompanionSearchItem("fact","f","text")); var calls=0
            val backend=CompanionEmbeddingBackend("persist") { inputs -> calls++; inputs.map { floatArrayOf(1f) } }
            CompanionHybridSearch(db){backend}.scores(scope,"q",items,true); assertEquals(2,calls)
            db.close(); db=Room.databaseBuilder(context,NekobotDatabase::class.java,name).build(); store=CompanionMemoryStore(db)
            calls=0; CompanionHybridSearch(db){backend}.scores(store.ensureScope(s),"q",items,true)
            assertEquals(1,calls); assertNull(store.legacyQuestionForReply(s))
            assertEquals("asked",db.companionMemoryDao().clarifications(scope.id).single().questionMessageId)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
