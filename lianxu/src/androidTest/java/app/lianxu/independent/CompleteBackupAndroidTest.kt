package app.lianxu.independent

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream

/** Only isolated synthetic databases, opaque fixture files and fake credentials. */
class CompleteBackupAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private data class Fixture(val store:Store,val repo:ProcessingRepository,val vault:ModelVault,val backup:BackupRepository,val name:String)
    private val fixtures=mutableListOf<Fixture>();private lateinit var f:Fixture
    private suspend fun fixture():Fixture {
        val name="backup-${System.nanoTime()}";val s=Store(context,"$name.db");val p=ProcessingRepository(s);val v=ModelVault(context,name,s)
        return Fixture(s,p,v,BackupRepository(s,v),name).also{fixtures+=it}
    }
    @Before fun setup()=runBlocking{f=fixture();f.store.add(listOf(Friend("a","合成甲","成年角色甲","原人设"),Friend("b","合成乙","另一角色")),false);seed()}
    @After fun cleanup(){fixtures.forEach{x->x.vault.list().forEach{x.vault.delete(it.id)};x.store.close();context.deleteDatabase("${x.name}.db");java.io.File(context.noBackupFilesDir,x.name).deleteRecursively()}}
    private fun config(id:String="service",endpoint:String="https://example.invalid/v1")=ModelConfig(id,"合成服务","custom",endpoint,"fake-model",32000,1024)
    private suspend fun turn(id:String="a",body:String="合成原话"):SourceDialogue {val l=ChatLedger(f.store);val t=l.begin(id,body,"service");check(l.finish(t.id,1,"合成回复，这是完整测试回答。"));return f.repo.sources(id).last()}
    private suspend fun seed(){
        val source=turn();turn("b","其他角色的原话")
        val m=StoredMemory("memory-a","a",MemoryCategory.STATE_FACT,"合成事实",core=true,locked=true,about=MemoryAbout.USER,evidence=listOf(MemoryEvidence(source.source,"user",source.user.text,source.user.text,source.user.created,source.zone)))
        val n=m.copy(id="memory-b",text="另一条合成事实")
        f.repo.tx{db->f.repo.saveMemory(db,m);f.repo.saveMemory(db,n);f.repo.candidate(db,"a",m.id,m,"需要确认","candidate-a")
            db.insertOrThrow("candidate_injections",null,ContentValues().apply{put("candidate_id","candidate-a");put("friend_id","a");put("user_message_id",source.user.id)})
        }
        f.store.setPreference(RolePromptStore.key("a"),RolePromptStore.encode(RolePrompts(expression="合成语气",examples=listOf(DialogueExample(source.user.text,requireNotNull(source.assistant).text)),examplesEnabled=true)))
        f.store.setPreference("role-examples:a",JSONObject().put("state","ACTIVE").put("optedIn",true).put("selected",org.json.JSONArray().put(JSONObject().put("id",source.requestId).put("revision",1).put("pair",JSONObject().put("user",source.user.text).put("assistant",requireNotNull(source.assistant).text)))).toString())
        f.store.setPreference("memory-associations-v1:a","""[{"character":"a","source":"memory-a","source_version":1,"target":"memory-b","target_version":1,"kind":"explicit_reference","reason":"合成关联","quote":"合成原话","target_quote":"合成事实","batch":"old-batch"}]""")
        f.store.setPreference("memory-reference-v1:a:${source.user.id}","""{"format":1,"memoryIds":["memory-a"]}""")
        f.store.setPreference("nickname","合成用户");f.store.setPreference("compact","true")
        RoleAssets(f.repo).put("a",RoleAssets.Asset("avatar.png","avatar","image/png",byteArrayOf(1,2,3,4)))
        RoleAssets(f.repo).put("a",RoleAssets.Asset("attachments/note.bin","attachment","application/octet-stream",byteArrayOf(9,8,7)))
        f.vault.save(config(),"synthetic-key-original");f.vault.setPurpose(ModelPurpose.CHAT,"service")
    }
    /** 带密钥的备份必须用备份密码封存（至少 BackupKeySeal.MIN_PASSWORD 位）；这里用一条 16 位以上的合成密码。 */
    private fun pw()="合成备份密码-synthetic-0123456789".toCharArray()
    private fun pack(export:BackupRepository.Export):BackupArchive.Package=BackupArchive.read(ByteArrayOutputStream().also{BackupArchive.write(export.root,export.files,it)}.toByteArray().inputStream())
    private suspend fun roundTrip(target:Fixture,all:Boolean=false,keys:Boolean=false):List<String>{val p=pack(f.backup.export(if(all)null else "a",keys,if(keys)pw()else null));return target.backup.commit(target.backup.preview(p,if(all)"REPLACE"else"ADD"),all,password=if(keys)pw()else null)}
    @Test fun learningControlsAndDisputedNoteTravelWithRoleAndFullBackup()=runBlocking{
        val seed=LearningRecordsFixture(f.store)
        seed.now=System.currentTimeMillis()
        val turn=seed.proposeHabit()
        val learning=seed.core.learningRecords("a").single().state
        seed.core.controlLearning("a",learning.id,"pin")
        seed.core.controlLearning("a",learning.id,"pause")
        val ref=learning.source
        assertEquals(turn.userMessageId,ref.turnId)
        f.repo.tx{db->
            val proposal=JsonParser.parseString(db.rawQuery("SELECT proposal FROM companion_sources WHERE role=? AND source_id=?",arrayOf("a",ref.turnId.toString())).use{it.moveToFirst();it.getString(0)}).asJsonObject
            proposal.add("innerNote",Gson().toJsonTree(ReviewInnerNote("想一起读完这本书",seed.now)))
            db.execSQL("UPDATE companion_sources SET proposal=? WHERE role=? AND source_id=?",arrayOf<Any>(proposal.toString(),"a",ref.turnId))
        }
        assertTrue(seed.core.correctInnerNote(ref))
        for(all in listOf(false,true)){
            val target=fixture()
            val ids=roundTrip(target,all)
            val id=if(all)ids.first{restored->target.store.friends().first{it.id==restored}.name=="合成甲"} else ids.single()
            val core=CompanionRepository(target.store){seed.now}
            val restored=core.learningRecords(id).single().state
            assertTrue(restored.pinned);assertEquals("paused",restored.state);assertEquals("chat",restored.origin)
            assertNull(core.relationship(id).innerNote)
            val record=target.repo.read{db->db.rawQuery("SELECT proposal FROM companion_sources WHERE role=? AND source_id=?",arrayOf(id,restored.source.turnId.toString())).use{it.moveToFirst();Gson().fromJson(it.getString(0),ExperienceProposal::class.java)}}
            assertTrue(requireNotNull(record.innerNote).disputed)
            core.controlLearning(id,restored.id,"resume")
            assertEquals("已记牢",core.learningRecords(id).single().state.familiarity)
            core.forget(id,restored.source.turnId);assertTrue(core.learningRecords(id).isEmpty())
            target.backup.deleteRole(target.backup.previewRoleDeletion(id),true)
            assertEquals("",target.store.preference(LearningControls.key(id),""))
        }
    }
}
