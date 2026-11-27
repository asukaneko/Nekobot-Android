package app.lianxu.independent

import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal data class PlanWake(val role:String,val version:Long,val due:Long,val lastUser:Long,val retries:Int)

internal class DailyPlanner(private val core:CompanionRepository){
    private val repo=ProcessingRepository(core.store)
    private val zone get()=ZoneId.systemDefault()

    suspend fun wakes()=repo.read{db->
        val now=core.clock()
        val quiet=QuietHours.read(db)
        db.rawQuery("""SELECT role,policy_version,plan_due,plan_user,plan_retry,
            COALESCE((SELECT MAX(t.user_message_id) FROM chat_turns t LEFT JOIN message_state ms ON ms.message_id=t.user_message_id
                WHERE t.friend_id=s.role AND t.state='COMPLETED' AND COALESCE(ms.deleted,0)=0),0),
            EXISTS(SELECT 1 FROM companion_opportunities o WHERE o.role=s.role AND o.state IN ('queued','running') AND o.not_before>=?),s.plan_ran_at,s.plan_error
            FROM companion_state s WHERE policy='enabled'""",arrayOf(now.toString())).use{c->buildList{
            while(c.moveToNext()){
                val role=c.getString(0)
                val latest=c.getLong(5)
                val pace=ContactPace.read(db,role,now)
                if(pace.streak>=3)continue
                val newConversation=latest>c.getLong(3) && c.getInt(6)==0 && c.getInt(4)==0 && c.getString(8).isEmpty()
                val due=DailyAgenda.nextDue(c.getLong(2),newConversation,pace.lastUser,c.getLong(7))
                val target=maxOf(now,due)
                add(PlanWake(role,c.getLong(1),if(quiet.contains(target,zone))quiet.nextMorning(target,zone) else due,latest,c.getInt(4)))
            }
        }}
    }
    suspend fun run(role:String,access:ModelAccess,client:TextChatClient){
        val at=core.clock()
        val wake=wakes().firstOrNull{it.role==role && it.due<=at}?:return
        if(CompanionRuntime.busy(role)||core.inputPending(role))return
        val snapshot=core.snapshot(role,"作息 上班 休息 约定")
        if(snapshot.policy!="enabled"||snapshot.policyVersion!=wake.version)return
        val quiet=repo.read{QuietHours.read(it)}
        if(quiet.contains(at,zone))return
        val morning=quiet.nextMorning(at,zone)
        try{
            val credential=access.reviewCredential()?:throw CompanionModelFailure(ChatFailure.MISSING_CONFIG)
            val history=ProactiveMaterial.recent(core.contactHistory(role)).joinToString("\n"){ContactTime.clock(it.created,zone)+" "+(if(it.mine)"对方" else "你")+"："+it.text.take(400)}
            val queued=core.queued()
            val booked=repo.read{db->queued.filter{it.role==role && it.policyVersion==wake.version && !it.id.startsWith("daily:") && core.refCurrent(db,SourceRef(role,it.source,it.revision)) && CompanionRepository.aiAllowed(db,role,it.source)}.joinToString("\n"){ContactTime.clock(it.notBefore,zone)+" "+it.reason.take(160)}}
            val memories=repo.read{db->repo.memories(db,role).filter{it.valid && it.life==MemoryLife.ACTIVE && it.evidence.all{e->repo.evidenceValid(db,e)&&CompanionRepository.aiAllowed(db,role,e.source.turnId)}}.takeLast(12).joinToString("\n"){it.text.take(240)}}
            val facts=core.contactPace(role,at).facts(at)+"\n"+core.contactFacts(role,at)
            val time=ProactiveMaterial.currentTime(Instant.ofEpochMilli(at),zone)
            val hours=if(quiet.enabled)"%02d:%02d–%02d:%02d".format(quiet.start/60,quiet.start%60,quiet.end/60,quiet.end%60) else "关闭"
            val prompt="按你的性格为未来24小时留0至5个自然想起对方的时间，可以完全留白。避开免打扰和已有约定。${ContactTime.GUIDE}。只返回 JSON 如 {\"points\":[{\"when\":\"今天 15:35\",\"reason\":\"想问问上午那件事\"}]}。现在：$time，免打扰：$hours。"
            fun active()=access.reviewCurrent(credential.stamp)&&!CompanionRuntime.busy(role)&&core.current(core.store.readableDatabase,snapshot)
            if(!active())return
            val material=listOf(snapshot.roleProfile,snapshot.proactivePrompt,memories,history,facts,"已有预约：$booked").joinToString("\n")
            val points=StructuredModelCall(client).generate("daily_contact_plan",credential.config,credential.key,listOf(WireMessage("system",prompt),WireMessage("user",material)),active=::active){DailyAgenda.parse(it,at,quiet,zone)}
            repo.tx{db->
                if(!active() || QuietHours.read(db)!=quiet)return@tx
                for(point in points.filter{it.at>core.clock()}){
                    db.execSQL("INSERT INTO companion_opportunities(id,role,source_id,revision,epoch,policy_version,not_before,expires_at,reason,state) VALUES(?,?,NULL,0,?,?,?,?,?,'queued')",arrayOf<Any>("daily:"+UUID.randomUUID(),role,snapshot.epoch,wake.version,point.at,at+RelationshipProjector.DAY,point.reason))
                }
                update(db,wake,morning,0,at)
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){
            val retry=if(ContactFailures.transient(e))ContactFailures.retryDelay(wake.retries) else null
            repo.tx{db->if(core.current(db,snapshot)){
                update(db,wake,retry?.let{at+it}?:morning,if(retry!=null)wake.retries+1 else 0)
                if(retry==null)db.execSQL("UPDATE companion_state SET plan_error=?,plan_failed_at=? WHERE role=?",arrayOf<Any>(ContactFailures.classify(e).name,at,role))
            }}
            Diagnostics.event("daily.plan.failure",mapOf("kind" to ContactFailures.classify(e).name))
        }
    }
    private fun update(db:SQLiteDatabase,wake:PlanWake,due:Long,retries:Int,ranAt:Long?=null){
        db.execSQL("UPDATE companion_state SET plan_error='',plan_failed_at=0,plan_due=?,plan_retry=?,plan_user=?,plan_ran_at=COALESCE(?,plan_ran_at) WHERE role=? AND policy='enabled' AND policy_version=?",arrayOf<Any?>(due,retries,wake.lastUser,ranAt,wake.role,wake.version))
    }
}
