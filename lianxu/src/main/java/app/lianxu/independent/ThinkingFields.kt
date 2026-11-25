package app.lianxu.independent

import org.json.JSONArray
import org.json.JSONObject

/** 思考档位。存进配置的是 key；ON 是旧配置的写法，实际按 HIGH 发送。 */
internal enum class ThinkingMode(val key:String,val label:String,val hint:String) {
    DEFAULT("default","跟随服务商默认","不额外指定强度"),
    OFF("off","关闭思考","尽量让它直接答，不先想一大段"),
    ON("on","开启思考","先想再答，通常更认真，也更容易慢和贵"),
    LOW("low","低","少量思考，更快，也更省"),
    HIGH("high","高","认真思考，兼顾速度和深度"),
    MAX("max","Max","尽量多想，通常最慢也最贵")
}

/**
 * 思考参数只在一处合并进请求体：这里给出「参数名 → 值」，[ImageWire.body] 负责塞进去。
 * 自定义服务商走整段 JSON：用户填什么就合并什么，但关键字段一律不许碰。
 *
 * 官方直连只认精确的地址和模型：DeepSeek 直连支持关闭与三档，OpenCode Go 支持三档；
 * 其余服务商和模型只保留 default，不按域名或名字猜非官方网关。
 */
internal object ThinkingFields {
    /** 程序自己维护的字段，自定义片段不能覆盖。 */
    private val protected=setOf("model","messages","stream","tools","tool_choice","max_tokens","max_completion_tokens","response_format")

    private val deepSeekChatModels=setOf("deepseek-flash","deepseek-v4-pro")
    private val goChatModels=setOf("deepseek-v4.1-flash","glm-5.3","glm-5.3-flash")
    private val deepSeekBases=setOf("https://api.deepseek.com","https://api.deepseek.com/v1")
    private const val goBase="https://opencode.ai/inference/go/openai/v1"

    private val deepSeekOptions=listOf(ThinkingMode.DEFAULT,ThinkingMode.OFF,ThinkingMode.LOW,ThinkingMode.HIGH,ThinkingMode.MAX)
    private val goOptions=listOf(ThinkingMode.DEFAULT,ThinkingMode.LOW,ThinkingMode.HIGH,ThinkingMode.MAX)
    private val effortModes=setOf(ThinkingMode.LOW,ThinkingMode.HIGH,ThinkingMode.MAX)

    private fun base(raw:String)=raw.trim().trimEnd('/')
    private fun isDeepSeekChat(config:ModelConfig)=
        config.providerId=="deepseek"&&base(config.baseUrl) in deepSeekBases&&config.modelId.trim() in deepSeekChatModels
    private fun isGoChat(config:ModelConfig)=
        config.providerId=="opencode-go"&&base(config.baseUrl)==goBase&&config.modelId.trim() in goChatModels

    fun mode(raw:String)=ThinkingMode.entries.firstOrNull{it.key==raw}?:ThinkingMode.DEFAULT
    fun canDisable(preset:ProviderPreset)=preset.id!="opencode-go"&&preset.thinkingParam!=null&&preset.thinkingOff!=null
    fun canEnable(preset:ProviderPreset)=preset.thinkingParam!=null&&preset.thinkingOn!=null
    /** 这家能不能选思考设置：表里三列都空、又不是自定义服务，就只能跟随默认。 */
    fun adjustable(preset:ProviderPreset)=preset.id=="custom"||canDisable(preset)||canEnable(preset)

    /** 这个配置在界面上能选的档位；顺序也是界面顺序。 */
    fun options(config:ModelConfig):List<ThinkingMode> = when {
        isDeepSeekChat(config)->deepSeekOptions
        isGoChat(config)->goOptions
        else->listOf(ThinkingMode.DEFAULT)
    }

    /** 把旧 on/off 和当前支持范围折算成真正要发送的档位；不支持的档位回 default。 */
    fun effectiveMode(config:ModelConfig):ThinkingMode {
        val stored=mode(config.thinkingMode)
        val selected=if(stored==ThinkingMode.ON)ThinkingMode.HIGH else stored
        val available=options(config)
        return if(selected==ThinkingMode.DEFAULT||selected in available)selected else ThinkingMode.DEFAULT
    }

    /** 表里存的是写法片段：对象、数组、true/false、其余当字符串。 */
    fun parse(raw:String):Any {
        val text=raw.trim()
        return when {
            text.startsWith("{")->JSONObject(text)
            text.startsWith("[")->JSONArray(text)
            text=="true"->true
            text=="false"->false
            else->text
        }
    }

    /**
     * 这个配置本次请求要写哪些思考字段；空的表示一条都不写。
     * DeepSeek 关闭时只写 thinking，三档同时写 thinking 和 reasoning_effort；Go 只写 reasoning_effort。
     */
    private fun fields(config:ModelConfig):List<Pair<String,Any>> {
        val effective=effectiveMode(config)
        if(effective==ThinkingMode.DEFAULT)return emptyList()
        return when {
            isDeepSeekChat(config)&&effective==ThinkingMode.OFF->
                listOf<Pair<String,Any>>("thinking" to JSONObject().put("type","disabled"))
            isDeepSeekChat(config)&&effective in effortModes->listOf<Pair<String,Any>>(
                "thinking" to JSONObject().put("type","enabled"),
                "reasoning_effort" to effective.key
            )
            isGoChat(config)&&effective in effortModes->
                listOf<Pair<String,Any>>("reasoning_effort" to effective.key)
            else->emptyList()
        }
    }

    /** 兼容旧调用：返回本次要写的第一个思考字段；多字段请求由 [apply] 完整写入。 */
    fun field(config:ModelConfig):Pair<String,Any>?=fields(config).firstOrNull()

    /** 自定义服务商的整段 JSON；没填或写坏了就返回 null，请求照常发。 */
    private fun custom(config:ModelConfig):JSONObject? {
        if(ProviderPresets.get(config.providerId).id!="custom")return null
        if(config.thinkingCustom.isBlank()||customError(config.thinkingCustom)!=null)return null
        return runCatching{JSONObject(config.thinkingCustom.trim())}.getOrNull()
    }

    /** 把思考字段并进请求体：表驱动的写完整字段，自定义服务商合并整段。 */
    fun apply(body:JSONObject,config:ModelConfig) {
        custom(config)?.let{extra->
            val keys=extra.keys()
            while(keys.hasNext()){val name=keys.next();if(name !in protected)body.put(name,extra.get(name))}
            return
        }
        fields(config).forEach{(name,value)->body.put(name,value)}
    }

    /** 自定义片段的填写错误；null 表示可以存。 */
    fun customError(raw:String):String? {
        if(raw.isBlank())return null
        val text=raw.trim()
        if(!text.startsWith("{"))return "请填一段花括号包起来的设置，例如 {\"reasoning_effort\":\"high\"}"
        val obj=runCatching{JSONObject(text)}.getOrNull() ?: return "这段写法有问题，检查一下括号、引号和逗号。"
        if(obj.length()==0)return "这段是空的，填上内容或者留空都可以。"
        val blocked=obj.keys().asSequence().filter{it in protected}.toList()
        if(blocked.isNotEmpty())return "这里不能写 ${blocked.joinToString("、")}，这些程序自己会填。"
        return null
    }

    /** 界面上那句解释：让用户知道选了以后到底会发生什么。 */
    fun describe(config:ModelConfig):String {
        val preset=ProviderPresets.get(config.providerId)
        if(preset.id=="custom"){
            if(config.thinkingCustom.isBlank())return "不额外写思考参数，用服务商默认。"
            return customError(config.thinkingCustom) ?: "每次请求都会把这几个设置原样带出去。"
        }
        val available=options(config)
        val stored=mode(config.thinkingMode)
        val effective=effectiveMode(config)
        return when {
            available.size==1->"这家服务商当前只提供默认思考方式，请求里不写思考参数。"
            stored!=ThinkingMode.DEFAULT&&effective==ThinkingMode.DEFAULT->"当前模型不支持所选档位，本次按默认发，不写思考参数。"
            effective==ThinkingMode.DEFAULT->"请求里不写思考参数，由服务商自己决定。"
            effective==ThinkingMode.OFF->"会明确告诉服务商：这次不用思考。"
            isGoChat(config)->"会发送 reasoning_effort=${effective.key}。"
            else->"会发送 thinking:{type:enabled} 和 reasoning_effort=${effective.key}。"
        }
    }
}
