package app.lianxu.independent

import kotlin.math.roundToInt

internal suspend fun CompanionRepository.relationshipQuotes(role:String,ids:Set<Long>)=ProcessingRepository(store).read{db->
    buildMap<Long,String>{ids.toList().chunked(400).forEach{batch->
        db.rawQuery("SELECT m.id,m.body FROM messages m LEFT JOIN message_state s ON s.message_id=m.id WHERE m.friend_id=? AND m.mine=1 AND COALESCE(s.deleted,0)=0 AND m.id IN (${batch.joinToString(","){"?"}})",arrayOf(role,*batch.map{it.toString()}.toTypedArray())).use{c->while(c.moveToNext())put(c.getLong(0),c.getString(1))}
    }}
}

/** UI-facing data only. Never serialize these scores into a model request. */
internal data class RelationMetric(val key:String,val score:Int,val degree:String,val direction:String,
    val source:SourceRef?,val quote:String?,val at:Long?,val canCorrect:Boolean,val correctionSource:SourceRef?=null)
internal data class RelationAudit(val input:RelationInput,val status:String,val corrections:List<RelationInput>)
internal data class RelationRecordingAttempt(val source:SourceRef,val at:Long,val issues:List<RelationIssue>,val recorded:Boolean,val userMessage:String?=null)
internal data class RelationRecordingReport(val examined:Int,val failed:Int,val failureRate:Double,val ignored:List<RelationRecordingAttempt>)
internal object RelationshipQuery {
    /** 相处页「心结」标签：真正还没弥补的心结（解释过但还没弥补的也在），不是「等你解释的事」；原话已删的不列。 */
    fun knots(state:RelationState,quote:(SourceRef)->String?)=state.repairable.filter{quote(it)!=null}
    /** Failure includes a partially discarded relation group; recorded distinguishes partial from whole. */
    fun recording(attempts:List<RelationRecordingAttempt>,limit:Int):RelationRecordingReport {
        require(limit in 1..1000)
        val recent=attempts.sortedWith(compareByDescending<RelationRecordingAttempt>{it.at}.thenByDescending{it.source.turnId}).take(limit)
        val ignored=recent.filter{it.issues.isNotEmpty()}
        return RelationRecordingReport(recent.size,ignored.size,if(recent.isEmpty())0.0 else ignored.size.toDouble()/recent.size,ignored)
    }
    fun metrics(state:RelationState,quote:(SourceRef)->String?):List<RelationMetric> {
        fun metric(key:String,n:Double,label:String,correctable:Boolean,causeKey:String=key):RelationMetric {
            val candidate=state.causes[causeKey];val original=candidate?.source?.let(quote)
            val cause=candidate?.takeIf{original!=null}
            return RelationMetric(key,(n*100).roundToInt(),label,cause?.direction?:"不变",cause?.source,original,cause?.at,correctable&&cause!=null,cause?.source?.takeIf{correctable})
        }
        val mood=RelationshipSummary.mood(state)
        return listOf(metric("trust",state.trust,RelationshipSummary.trust(state.trust),true),
            metric("disappointment",state.disappointment,if(state.disappointment<.05)"没有明显失落"else RelationshipSummary.degree(state.disappointment)+"失落",true),
            metric("closeness",state.closeness,RelationshipSummary.closeness(state.closeness),true),
            metric("tension",state.tension,when{state.tension<.1->"没有明显心结";state.tension<=.3->"还有点在意那件事";else->"心里还有疙瘩"},false),
            metric("mood",mood.primary.value,mood.sentence,false,mood.primary.key))
    }
}
internal suspend fun CompanionRepository.relationshipRecording(role:String,limit:Int=20)=ProcessingRepository(store).read{db->
    val rows=db.rawQuery("SELECT source_id,revision,created,proposal FROM companion_sources WHERE role=? AND committed=1 ORDER BY source_id DESC",arrayOf(role)).use{c->buildList{
        while(c.moveToNext()){
            val source=SourceRef(role,c.getLong(0),c.getLong(1));if(!refCurrent(db,source))continue
            val p=runCatching{com.google.gson.Gson().fromJson(c.getString(3),ExperienceProposal::class.java)}.getOrNull()?:continue
            val message=db.rawQuery("SELECT body FROM messages WHERE friend_id=? AND id=?",arrayOf(role,source.turnId.toString())).use{if(it.moveToFirst())it.getString(0)else null}
            if(p.relationAttempted)add(RelationRecordingAttempt(source,p.timingReferenceAt?:c.getLong(2),p.relationIssues.orEmpty(),p.relationInput!=null,message))
        }
    }}
    RelationshipQuery.recording(rows,limit)
}
internal suspend fun CompanionRepository.relationshipPromises(role:String,at:Long=clock())=relationship(role,at).state.promises
internal suspend fun CompanionRepository.relationshipMetrics(role:String,at:Long=clock())=ProcessingRepository(store).read{db->
    RelationshipQuery.metrics(relationship(db,role,at).state){source->
        db.rawQuery("SELECT m.body FROM messages m LEFT JOIN message_state s ON s.message_id=m.id WHERE m.friend_id=? AND m.id=? AND COALESCE(s.deleted,0)=0",arrayOf(role,source.turnId.toString())).use{if(it.moveToFirst())it.getString(0)else null}
    }
}
internal suspend fun CompanionRepository.relationshipAudit(role:String)=ProcessingRepository(store).read{db->
    val all=relationshipInputs(db,role,true);val valid=all.filter{refCurrent(db,it.source)&&it.status=="valid"}
    all.map{input->
        val corrections=valid.filter{it.previous==input.source}.distinctBy{it.correction}
        RelationAudit(input,when{!refCurrent(db,input.source)->"来源已失效";input.status=="disputed"->"已改正";corrections.isNotEmpty()->"已改判";else->"有效"},corrections)
    }
}
