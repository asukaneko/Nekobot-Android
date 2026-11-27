package app.lianxu.independent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class RelationshipAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun modelMaterialKeepsStateCausesAndExperiencesInBothContextModes():Unit=runBlocking {
        val name="relation-material-${System.nanoTime()}.db";val store=Store(context,name);val now=System.currentTimeMillis()
        try{
            store.addConfigured(Friend("r","合成角色","成年"),RolePrompts(characterDefinition="成年合成角色"))
            val core=CompanionRepository(store){now};val repo=ProcessingRepository(store);val ledger=ChatLedger(store)
            val words="今天一起看电影很开心，主动交流按模板语气，这是原话引用"
            val t=ledger.begin("r",words,"fake");val ref=core.sourceFor(t);val head=core.snapshot("r")
            val p=ExperienceProposal(quote=words,memory=words,relationInput=RelationInput(ref,now,now/RelationshipProjector.DAY,"关于关系",.3,0.0,0.0,0.0))
            assertTrue(core.accept(t,p,head){true});assertTrue(ledger.finish(t.id,t.attempt,"嗯"));repo.tx{core.finish(it,t,head,p)}
            val before=core.relationship("r").state
            for(adaptive in listOf(false,true)){
                repo.saveMemoryRules(MemoryRules(adaptiveContext=adaptive))
                val s=core.snapshot("r",query=words,tokenBudget=20000)
                val reply=if(adaptive)CompanionPrompt.replyReferences(s.references).joinToString("\n"){it.label+"\n"+it.text}else s.replyPrompt
                for(text in listOf(reply,s.proactivePrompt)){
                    assertEquals(1,Regex.fromLiteral(RelationshipPrompt.LABEL).findAll(text).count())
                    assertTrue(text.contains(RelationshipSummary.mood(before).sentence));assertTrue(text.contains(RelationshipSummary.trust(before.trust)))
                    assertTrue(text.contains(words));assertTrue(text.contains(ExperiencePresentation.reference(ref)))
                    assertTrue(text.contains("状态依据"));assertFalse(text.contains("当前关系小结"));assertFalse(text.contains("主动交流语气："))
                }
                assertEquals(before,core.relationship("r").state)
            }
            assertEquals(words,store.messages().single{it.mine}.text)
        }finally{store.close();context.deleteDatabase(name)}
    }
    @Test fun persistenceCorrectionTemplateBackupAndVersionChanges():Unit=runBlocking {
        val name="relation-${System.nanoTime()}";var store=Store(context,"$name.db")
        val destination=Store(context,"$name-import.db")
        val vault=ModelVault(context,name,store);val otherVault=ModelVault(context,"$name-import",destination)
        // Backup import rebuilds at wall time. This is a recent multi-day history, not Unix-epoch
        // dates that would age 56 years on import; compare both stores at one time after import.
        val origin=System.currentTimeMillis()-118*RelationshipProjector.HOUR
        var clock=origin+20*RelationshipProjector.HOUR
        val oldClock=AppServices.companionClock;AppServices.companionClock={clock}
        try{
            store.addConfigured(Friend("r","合成角色","成年合成角色"),RolePrompts(characterDefinition="合成人设",relationTemplate="DIRECT",relationStart="FRIENDS"))
            var core=CompanionRepository(store){clock}
            suspend fun add(text:String,p:Double=0.0,c:Double=0.0,d:Double?=null,promise:Boolean=false,fulfills:SourceRef?=null):SourceRef{
                val ledger=ChatLedger(store);val turn=ledger.begin("r",text,"synthetic")
                val ref=core.sourceFor(turn);val snapshot=core.snapshot("r")
                val input=RelationInput(ref,clock,clock/RelationshipProjector.DAY,"关于关系",p,c,0.0,0.0,d,promise=promise,fulfills=fulfills)
                val proposal=ExperienceProposal(relationInput=input)
                assertTrue(core.accept(turn,proposal,snapshot){true});assertTrue(ledger.finish(turn.id,1,"合成回复"))
                ProcessingRepository(store).tx{core.finish(it,turn,snapshot,proposal)}
                return ref
            }
            val promise=add("周六晚上一起去看电影吧",p=.3,promise=true);clock=origin+66*RelationshipProjector.HOUR
            val friday=add("周六加班，改到周日晚上行吗",d=.27)
            val before=core.relationship("r").state;assertEquals(.27,before.disappointment,0.0)
            store.close();store=Store(context,"$name.db");core=CompanionRepository(store){clock}
            assertEquals(before,core.relationship("r").state)
            assertTrue(core.snapshot("r").references.single{it.label==RelationshipPrompt.LABEL}.mandatory)
            assertTrue(core.snapshot("r").prompt.contains("对先前的落空还有点在意"))
            clock=origin+118*RelationshipProjector.HOUR;add("电影真好看，谢谢你陪我",c=.6,fulfills=promise)
            // The first small positive impact fades for 98 hours with a 14-day half-life.
            // Fulfilment then adds .22 * .6 of the remaining trust capacity (independent arithmetic).
            val fadedTrust=.5+(.22*.3*(1-.5))*Math.pow(2.0,-98.0/(24*14))
            val expectedTrust=fadedTrust+.22*.6*(1-fadedTrust)
            assertEquals(expectedTrust,core.relationship("r").state.trust,1e-12)
            assertTrue(core.correctRelationshipRecord(friday));assertFalse(core.correctRelationshipRecord(friday))
            assertEquals(0.0,core.relationship("r").state.disappointment,0.0)
            assertEquals("disputed",core.relationshipHistory("r").single{it.source==friday}.status)
            core.changeRelationshipTemplate("r",RelationTemplate.RESERVED)
            assertEquals(expectedTrust,core.relationship("r").state.trust,1e-12)
            val export=BackupRepository(store,vault).export("r",false)
            val bytes=ByteArrayOutputStream().also{BackupArchive.write(export.root,export.files,it)}.toByteArray()
            val pack=BackupArchive.read(bytes.inputStream());val target=BackupRepository(destination,otherVault)
            val imported=target.commit(target.preview(pack,"ADD")).single()
            clock=System.currentTimeMillis()
            val copy=CompanionRepository(destination){clock}.relationship(imported)
            assertEquals(RelationTemplate.RESERVED,copy.template);assertEquals(core.relationship("r").state.trust,copy.state.trust,0.0)
            assertTrue(copy.inputs.all{it.source.characterId==imported});assertEquals(3,CompanionRepository(destination).relationshipHistory(imported).size)
            assertEquals(imported,copy.state.promises.single().fulfilledBy!!.characterId)
            val changedSource=core.relationship("r").inputs.first().source.turnId
            ProcessingRepository(store).tx{it.execSQL("UPDATE chat_turns SET source_revision=source_revision+1 WHERE user_message_id=?",arrayOf<Any>(changedSource))}
            assertEquals(.5,core.relationship("r").state.trust,.000001) // Losing the promise also invalidates its C.
        }finally{AppServices.companionClock=oldClock;store.close();destination.close();context.deleteDatabase("$name.db");context.deleteDatabase("$name-import.db")}
    }
}
