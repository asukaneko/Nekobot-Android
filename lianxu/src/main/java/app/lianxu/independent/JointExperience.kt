package app.lianxu.independent

import com.google.gson.JsonObject

/** The model classifies meaning; this contract checks only structure and quoted sources. */
internal object JointExperience {
    private const val CONTROL_GUIDANCE="有控制意图时按需回查定位，调用companion_memory_control，依据真实结果按人设自然回答；失败可按原因有限重试，反复失败或目标不明则说明/澄清。程序另显真实结果，不伪造回执。"
    fun guide(afterReply:Boolean)=if(afterReply)instruction.replace(CONTROL_GUIDANCE,"当前控制操作已在回复流程处理；后台不得执行或补做，不把控制意图当执行成功。只依据给出的实际结果记录。")else instruction
    val requiredKeys=setOf("quote","memory","emotion","valence","relationship","control","targets","reconsider","claimKind","controlIntent","requestQuote")
    /** Field guide for the optional [CompanionRecord] payload only; never a requirement for the visible reply. */
    val instruction="""
        [companion.joint_experience.v1] 这段只用于 companion_record 的 experience 字段；不调用 companion_record 就不填，也不要写进回复。
        experience 结构：
        {"quote":"","memory":"","emotion":"unknown","valence":0,"relationship":"none","control":"none","targets":[],"reconsider":null,"claimKind":"none","controlIntent":"none","requestQuote":""}
        quote逐字引当前原话；memory仅存本轮可靠新经历，无则空，勿用历史或自己reply制造当前事实。
        emotion是用户观察：unknown/calm/happy/sad/anxious/angry；valence=-1/0/1，unknown配0；角色感受另填feelings。
        relationship=none/appreciation/boundary（无/明确感谢/明确边界），非关系分数。
        claimKind=reported_event/plan/hypothesis/fiction/quoted_report/interaction/none；假设、虚构不作真实事件计账。
        control固定none，targets固定[]，reconsider固定null；未来事项写future。
        controlIntent=none/forget/stop_reminding/correct/uncertain；requestQuote逐字引当前直接要求，引用/否定/往事不授权。
        $CONTROL_GUIDANCE
    """.trimIndent()
    fun parse(root:JsonObject,input:String,now:Long):ExperienceProposal {
        val j=root.get("experience")?.takeIf{it.isJsonObject}?.asJsonObject?:throw RoleContractViolation("MISSING_EXPERIENCE")
        require(j.keySet().containsAll(requiredKeys))
        require(j.get("control").asString=="none"&&j.getAsJsonArray("targets").isEmpty&&j.get("reconsider").isJsonNull)
        val p=CompanionContracts.experience(j.toString(),input,now,emptySet())
        require(p.controlIntent in setOf("none","forget","stop_reminding","correct","uncertain"))
        require(p.controlIntent=="none"||p.requestQuote.isNotBlank()&&p.requestQuote in input)
        return p.copy(controlAuthorized=false,controlChecked=false,controlQuote="",interpretationUnknown=false)
    }
}
