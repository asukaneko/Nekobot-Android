package app.lianxu.independent

/** Checked, exact model IDs on their official endpoint only. Not a guess based on a name prefix. */
data class ModelTokenCapability(val context:Int,val maxOutput:Int,val reasoning:Boolean)
/** Defaults for a model we have no checked capability for: 1M window, 64K reply, 32K summary. */
data class ModelTokenDefaults(val context:Int=ModelTokenPolicy.DEFAULT_CONTEXT,val chat:Int=ModelTokenPolicy.DEFAULT_CHAT,val memory:Int=ModelTokenPolicy.DEFAULT_MEMORY)
object ModelTokenPolicy {
    const val MAX_OUTPUT=393216
    /** The user's 2026-11-06 ruling: a normal reply budget is 64K on a 1M window, not 16K/2K. */
    const val DEFAULT_CONTEXT=1048576
    const val DEFAULT_CHAT=65536
    const val DEFAULT_MEMORY=32768
    /** The pair older builds pre-filled; treated as "never touched" when read from an unknown model. */
    const val LEGACY_CONTEXT=16384
    const val LEGACY_OUTPUT=2048
    private val openai=mapOf(
        "gpt-4o-mini" to ModelTokenCapability(128000,16384,false),
        "gpt-4o-mini-2024-07-18" to ModelTokenCapability(128000,16384,false),
        "gpt-4.1" to ModelTokenCapability(1047576,32768,false),
        "gpt-4.1-2025-04-14" to ModelTokenCapability(1047576,32768,false),
        "gpt-5-mini" to ModelTokenCapability(400000,128000,true),
        "gpt-5-mini-2025-08-07" to ModelTokenCapability(400000,128000,true)
    )
    // Official pages say 1M; use decimal 1,000,000 conservatively, not an inferred binary limit.
    private val mimo=setOf("mimo-v2.6-flash","mimo-v2.6-pro","mimo-v2.6-pro-ultraspeed")
    private val deepseek=setOf("deepseek-flash","deepseek-v4-pro","deepseek-v4-flash","deepseek-v4-flash-vision-exp")
    fun capability(provider:String,base:String,model:String):ModelTokenCapability? {
        val endpoint=base.trim().trimEnd('/')
        return when {
            provider=="openai"&&endpoint=="https://api.openai.com/v1" -> openai[model]
            provider=="mimo"&&endpoint=="https://api.xiaomimimo.com/v1"&&model in mimo -> ModelTokenCapability(1000000,131072,true)
            provider=="deepseek"&&endpoint in setOf("https://api.deepseek.com","https://api.deepseek.com/v1")&&model in deepseek -> ModelTokenCapability(1000000,393216,true)
            else -> null
        }
    }
    fun capability(config:ModelConfig)=capability(config.providerId,config.baseUrl,config.modelId)
    fun defaults(provider:String,base:String,model:String):ModelTokenDefaults {
        val known=capability(provider,base,model)?:return ModelTokenDefaults()
        // A checked model keeps its documented window; the reply budget follows the same 64K rule,
        // capped by what that model actually accepts. Summary output stays the smaller purpose budget.
        return ModelTokenDefaults(known.context,minOf(DEFAULT_CHAT,known.maxOutput),minOf(DEFAULT_MEMORY,known.maxOutput))
    }
    /**
     * 老版本建配置时预填的就是 16384/2048，这个组合和「用户从没动过」无法区分。
     * 读到时按模型自己的上限升到新默认（未核对模型即 1M/64K）；用户改过的任何其它值原样保留。
     */
    fun liftLegacy(context:Int,output:Int,provider:String,base:String,model:String):Pair<Int,Int> =
        if(context==LEGACY_CONTEXT&&output==LEGACY_OUTPUT)defaults(provider,base,model).let{it.context to it.chat}
        else context to output
    fun error(config:ModelConfig):String? {
        val memory=config.memoryOutputLimit()
        if(config.contextTokens !in 4096..2000000||config.outputTokens !in 1..MAX_OUTPUT||config.memoryOutputTokens !in 0..MAX_OUTPUT||memory>=config.contextTokens||config.outputTokens>=config.contextTokens)
            return "请检查上下文容量、聊天与总结记忆生成上限。"
        val known=capability(config)?:return null
        return when {
            config.contextTokens>known.context -> "上下文容量超过该模型已核对的上限 ${known.context}。"
            config.outputTokens>known.maxOutput||memory>known.maxOutput -> "生成上限超过该模型已核对的上限 ${known.maxOutput}。"
            else -> null
        }
    }
    fun acceptsRequest(config:ModelConfig,output:Int)=output in 1..(capability(config)?.maxOutput?:MAX_OUTPUT)&&output<config.contextTokens
}

/** Draft policy is independent of Compose; saved custom values never opt into automatic changes. */
data class ModelBudgetDraft(val context:String="1048576",val chat:String="65536",val memory:String="32768",val automatic:Boolean=true) {
    fun manual(context:String=this.context,chat:String=this.chat,memory:String=this.memory)=copy(context=context,chat=chat,memory=memory,automatic=false)
    fun apply(provider:String,base:String,model:String):ModelBudgetDraft {
        val d=ModelTokenPolicy.defaults(provider,base,model)
        return copy(context=d.context.toString(),chat=d.chat.toString(),memory=d.memory.toString())
    }
    fun forModel(provider:String,base:String,model:String)=if(automatic)apply(provider,base,model) else this
    companion object {
        fun existing(config:ModelConfig)=ModelBudgetDraft(config.contextTokens.toString(),config.outputTokens.toString(),config.memoryOutputLimit().toString(),false)
    }
}
