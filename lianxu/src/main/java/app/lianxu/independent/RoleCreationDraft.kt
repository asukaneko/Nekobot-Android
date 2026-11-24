package app.lianxu.independent

import androidx.compose.runtime.saveable.listSaver
import java.util.UUID

internal enum class RoleCreationStep { NAME, DEFINITION, RELATIONSHIP, MORE, MODEL, CONFIRM }

internal data class RoleCreationText(val name:String,val definition:String,val relationship:String,val expression:String,val opening:String,val extra:String)

/** Presentation-only draft. No role is persisted before the final confirmation. */
internal data class RoleCreationDraft(
    val step:RoleCreationStep=RoleCreationStep.NAME,
    val name:String="",val definition:String="",val relationship:String="",
    val expression:String="",val opening:String="",val extra:String="",
    val creationId:String=UUID.randomUUID().toString(),
    val polished:RoleCreationText?=null,val usePolished:Boolean=false
){
    val text get()=RoleCreationText(name,definition,relationship,expression,opening,extra)
    val selected get()=if(usePolished)polished?:text else text
    fun selectedDraft():RoleCreationDraft {val value=selected;return copy(name=value.name,definition=value.definition,relationship=value.relationship,expression=value.expression,opening=value.opening,extra=value.extra,polished=null,usePolished=false)}
    val hasContent get()=listOf(name,definition,relationship,expression,opening,extra).any{it.isNotEmpty()}
    fun problem():String?=when(step){
        RoleCreationStep.NAME->if(name.isBlank())"请先填写名字。"else null
        RoleCreationStep.DEFINITION->if(definition.isBlank())"请先写一点角色设定。"else null
        RoleCreationStep.CONFIRM->when{ selected.name.isBlank()->"请先填写名字。";selected.definition.isBlank()->"请先写一点角色设定。";else->null }
        else->null
    }
    fun next()=RoleCreationStep.entries.getOrNull(step.ordinal+1)
    fun previous()=RoleCreationStep.entries.getOrNull(step.ordinal-1)
}

internal val RoleCreationDraftSaver=listSaver<RoleCreationDraft,String>(
    save={draft->listOf(draft.step.name,draft.name,draft.definition,draft.relationship,draft.expression,draft.opening,draft.extra,draft.creationId,draft.usePolished.toString())+(draft.polished?.let{listOf(it.name,it.definition,it.relationship,it.expression,it.opening,it.extra)}?:emptyList())},
    restore={v->runCatching{
        require(v.size in setOf(8,9,15))
        val preview=if(v.size==15)RoleCreationText(v[9],v[10],v[11],v[12],v[13],v[14])else null
        RoleCreationDraft(RoleCreationStep.valueOf(v[0]),v[1],v[2],v[3],v[4],v[5],v[6],v[7],preview,v.getOrNull(8)=="true"&&preview!=null)
    }.getOrNull()}
)

/** Preserve complete supplementary characters when enforcing the existing UTF-16 limits. */
internal fun creationText(value:String,limit:Int)=value.take(limit).let{if(it.lastOrNull()?.isHighSurrogate()==true)it.dropLast(1)else it}
