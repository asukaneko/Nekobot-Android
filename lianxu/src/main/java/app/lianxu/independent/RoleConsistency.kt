package app.lianxu.independent

/** One contract for appraisal, final replies and proactive decisions; not a semantic classifier. */
internal object RoleConsistency {
    val RULES="""
        [人设与成长共同规则]
        人设是起点，不能把整段人设都当成永不变化的指令。姓名、身份背景和明确的核心原则保持稳定；普通兴趣、喜恶、表达方式、相处习惯及关系起点是初始倾向，不因写在人设里就成为不可侵犯的底线。不把普通偏好升级为核心原则；不确定属于哪类时保留不确定，必要时澄清，不自动改写身份或原则。
        从相处中学到的变化，只能是有来源、适用范围明确的看法（viewpoint）、兴趣（interest）或习惯（habit）；每个编号（ID）只用当前有效的版本。已经确定的变化（active）可以在相应情境下修正最初的倾向；尚未确定的想法（candidate）和还在尝试的变化（developing）都可以修正，不能当成已经确定的变化；已暂停的变化（paused）和来源已撤回的内容都不能用。以前的评价和早期版本，不代表现在的状态。
        当前明确请求在本次相处方式上优先于旧习惯，但一句临时要求不等于永久改变偏好，不要求角色放弃核心原则，也不授予工具或后台权限。角色可以有不同意见；变化不以迎合为目标。
        新旧内容不一致时先核对人物、时间、适用情境、来源和状态，不机械地以最新、最常出现或写在人设中判胜负。同范围仍有未解决矛盾时不要把任一条说成定论，使用可用来源核实或自然澄清，不以旧设定压过有依据的成长。角色虚构背景、用户资料、真实共同经历和模型推测分别对待，不把它们相互替代。
    """.trimIndent()
    /** Human-voice persona & growth guidance for foreground chat replies; no backend state codes. */
    val REPLY_RULES="""
        [人设与相处]
        始终以你自己的身份、性格、说话节奏和关系远近开口——该克制就克制，该正式、温柔、冷淡、毒舌或直爽就按人设来，保住你原本的脾气和偏爱，别滑回客服、咨询师或万能助手的套话。
        身份背景与核心原则保持稳定；普通喜恶、口吻和相处习惯可以随真实互动自然演变：已经稳定的新看法、兴趣或习惯可在相应情境下替代最初的习惯，刚冒头或还在尝试的想法仅供参考、不能推翻原设定，已停用或对方撤回的内容不再沿用。
        对方此刻明确提出的要求在这一回优先照办，但一次临时要求不等于永久改写你的习惯或底线，也不必为了迎合而放弃立场；新旧内容在同一件事上互相冲突时，别把任一条说成定论，先对照来源或自然问清楚。
        无论性格多粘人或有占有欲，都可以吃醋或闹小脾气，但尊重对方与他人的正常来往，不诱导排他依赖，也不要求对方只能依靠你。
        角色背景、对方资料、真实聊过的事和你的猜测分清界限：真发生过的按实说，拿不准的别硬编。
    """.trimIndent()
    const val GENERATION_GUIDANCE="把稳定的身份背景、核心原则，与可随相处成长的兴趣、口吻和习惯区分写清；普通喜恶不要写成死板底线，核心原则也别把日常相处锁死，让角色既有鲜明棱角，也能在真实交流里自然变化。"

    // A retry must not reuse an appraisal produced under a different consistency contract.
    fun appraisalStamp(profile:String)=stableDigest(RULES+"\u0000"+profile)

    /** Shared by wire parsing and final acceptance; never infer semantic scope from a string. */
    fun learningUseError(considered:List<String>,used:List<String>,judgment:String,learning:List<RoleLearningState>?):String? {
        if(considered.distinct().size!=considered.size||used.distinct().size!=used.size)return "DUPLICATE_LEARNING"
        if(!considered.containsAll(used))return "LEARNING_NOT_CONSIDERED"
        if(judgment !in setOf("","evidence_based","inference","uncertain"))return "INVALID_judgmentKind"
        if(used.isNotEmpty()&&judgment.isEmpty())return "LEARNING_USE_NEEDS_JUDGMENT_KIND"
        if(considered.isEmpty())return null
        if(learning==null)return "LEARNING_CONTEXT_REQUIRED"
        if(considered.any{id->learning.count{it.id==id}!=1})return "UNKNOWN_LEARNING"
        for(id in used){val value=learning.single{it.id==id}
            if(value.kind !in setOf("viewpoint","interest","habit"))return "INVALID_LEARNING_KIND"
            if(value.state !in setOf("candidate","developing","active","paused"))return "INVALID_LEARNING_STATE"
            if(value.state=="paused")return "PAUSED_LEARNING"
            if(value.state!="active"&&judgment=="evidence_based")return "CANDIDATE_IS_NOT_ESTABLISHED"
        }
        return null
    }
}
