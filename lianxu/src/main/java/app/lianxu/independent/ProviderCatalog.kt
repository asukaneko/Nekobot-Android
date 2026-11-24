package app.lianxu.independent

/** Documentation metadata only. A listed preset is not an account-level live capability check. */
internal enum class ProviderCapability { DOCUMENTED, UNKNOWN }
internal enum class ProviderModelKind { TEXT_EMBEDDING, OTHER_PROTOCOL, UNKNOWN }
internal data class ProviderInfo(val family:String,val aliases:String="",val textModels:Set<String> = emptySet(),val chat:ProviderCapability=ProviderCapability.DOCUMENTED,val sessionHeader:Boolean=false){
    val embedding get()=if(textModels.isEmpty())ProviderCapability.UNKNOWN else ProviderCapability.DOCUMENTED
}
internal data class ProviderGroup(val name:String,val regions:List<ProviderPreset>)
internal object ProviderCatalog {
    private val sf=setOf("BAAI/bge-large-zh-v1.5","BAAI/bge-large-en-v1.5","BAAI/bge-m3","Pro/BAAI/bge-m3","netease-youdao/bce-embedding-base_v1","Qwen/Qwen3-Embedding-8B","Qwen/Qwen3-Embedding-4B","Qwen/Qwen3-Embedding-0.6B")
    private val tencent=setOf("kinfra-text-embedding-0.6b","kinfra-text-embedding-4b")
    fun info(p:ProviderPreset):ProviderInfo=when(p.id){
        "silicon-cn"->ProviderInfo("硅基流动","siliconflow 硅基",sf)
        "bailian-cn"->ProviderInfo("阿里百炼","aliyun alibaba qwen 通义 千问",setOf("qwen3.7-text-embedding","qwen3.7-text-embedding-flash","text-embedding-v4","text-embedding-v3","text-embedding-v2","text-embedding-v1"))
        "ark-cn"->ProviderInfo("火山方舟","volcengine doubao 豆包 字节")
        "zhipu-cn"->ProviderInfo("智谱","glm bigmodel zhipu",setOf("embedding-2","embedding-3"))
        "mimo"->ProviderInfo("小米 MiMo","xiaomi 小米")
        "minimax-cn","minimax-intl"->ProviderInfo("MiniMax","海螺 minimax")
        "moonshot","moonshot-cn"->ProviderInfo("Moonshot / Kimi","月之暗面 kimi moonshot")
        "tokenhub-cn","tokenhub-intl"->ProviderInfo("腾讯 TokenHub","腾讯 tencent tokenhub",tencent)
        "stepfun-cn","stepfun-intl"->ProviderInfo("阶跃星辰","stepfun step 阶跃")
        "gemini"->ProviderInfo("Gemini AI Studio","google 谷歌 gemini ai studio",setOf("gemini-embedding-001"))
        "qianfan"->ProviderInfo("百度千帆","baidu qianfan 文心 ernie",setOf("embedding-v1","tao-8k","bge-large-zh","bge-large-en","qwen3-embedding-0.6b","qwen3-embedding-4b"))
        "openai"->ProviderInfo("OpenAI","openai chatgpt",setOf("text-embedding-3-small","text-embedding-3-large","text-embedding-ada-002"))
        "opencode-go"->ProviderInfo("OpenCode Go","opencode go opencodego",sessionHeader=true)
        "custom"->ProviderInfo("自定义服务","custom openai-compatible 兼容 自定义 手填",chat=ProviderCapability.UNKNOWN)
        else->ProviderInfo(p.label.substringBefore(" · "),p.id)
    }
    fun matches(p:ProviderPreset,query:String):Boolean {
        val words=query.trim().split(Regex("\\s+")).filter{it.isNotBlank()}
        val hay="${p.label} ${p.id} ${p.base} ${info(p).aliases}"
        return words.all{hay.contains(it,ignoreCase=true)}
    }
    fun groups(embedding:Boolean,query:String="",all:Boolean=false):List<ProviderGroup>{
        val common=if(embedding)setOf("silicon-cn","openai","bailian-cn","gemini") else setOf("silicon-cn","deepseek","bailian-cn","gemini")
        return ProviderPresets.all.filter{p->(!embedding||p.id=="custom"||info(p).embedding==ProviderCapability.DOCUMENTED)&&matches(p,query)&&
            (all||query.isNotBlank()||p.id in common||p.id=="custom")}
            .groupBy{info(it).family}.map{ProviderGroup(it.key,it.value)}
    }
    fun modelKind(c:ModelConfig):ProviderModelKind {
        val id=c.modelId
        val endpoint=c.baseUrl.trim().trimEnd('/')
        // A custom preset at a known official host cannot turn a known multimodal-only protocol into text embeddings.
        val transport=ProviderPresets.all.firstOrNull{it.base.isNotBlank()&&it.base.trimEnd('/')==endpoint}?.id?:c.providerId
        if((transport=="ark-cn"&&id.startsWith("doubao-embedding-vision"))||
            (transport.startsWith("tokenhub-")&&id.startsWith("kinfra-vl-embedding"))||
            (transport=="bailian-cn"&&(id.startsWith("qwen3-vl-embedding")||id.startsWith("tongyi-embedding-vision"))))return ProviderModelKind.OTHER_PROTOCOL
        val p=ProviderPresets.get(c.providerId)
        val base=c.baseUrl.trim().trimEnd('/')
        val expected=base==p.base.trimEnd('/') || (p.id=="bailian-cn"&&base.matches(Regex("https://[A-Za-z0-9-]+\\.cn-beijing\\.maas\\.aliyuncs\\.com/compatible-mode/v1")))
        return if(expected&&id in info(p).textModels)ProviderModelKind.TEXT_EMBEDDING else ProviderModelKind.UNKNOWN
    }
    fun configurations(configs:List<ModelConfig>,purpose:ModelPurpose,current:String,includeUnknown:Boolean=false):List<ModelConfig> = configs.filter{c->
        c.id==current || if(purpose==ModelPurpose.EMBEDDING)modelKind(c)==ProviderModelKind.TEXT_EMBEDDING||(includeUnknown&&modelKind(c)==ProviderModelKind.UNKNOWN)
        else modelKind(c)==ProviderModelKind.UNKNOWN
    }
}
internal fun providerMatches(p:ProviderPreset,query:String)=ProviderCatalog.matches(p,query)
internal object ProviderRequestMetadata {
    const val USER_AGENT="Lianxu (Android; companion-chat)"
    const val GO_UNVERIFIED_NOTICE="当前 Go 地址未核实，未发送请求或会话标识。请核对官方 Go 兼容地址；其他服务请使用普通自定义配置。原配置保留。"
    private const val GO_PATH="/inference/go/openai/v1"
    fun conversation(friendId:String)="lianxu-chat-"+stableDigest(friendId)
    /** Compare parsed origin and raw path. Never normalize encoded/dot paths into a trusted target. */
    fun verifiedGoBase(base:String):Boolean = runCatching {
        val u=java.net.URI(base.trim())
        u.scheme.equals("https",ignoreCase=true)&&u.host.equals("opencode.ai",ignoreCase=true)&&
            (u.port==-1||u.port==443)&&u.rawUserInfo==null&&u.rawQuery==null&&u.rawFragment==null&&
            u.rawPath.orEmpty().trimEnd('/')==GO_PATH
    }.getOrDefault(false)
    fun compatibilityNotice(config:ModelConfig):String? {
        if(verifiedGoBase(config.baseUrl))return null
        if(ProviderCatalog.info(ProviderPresets.get(config.providerId)).sessionHeader)return GO_UNVERIFIED_NOTICE
        val sameHost=runCatching{java.net.URI(config.baseUrl.trim()).host.equals("opencode.ai",true)}.getOrDefault(false)
        return if(sameHost)"当前为未核实的 OpenCode 路由，仅使用普通兼容请求，不附 Go 会话标识；Go 兼容性尚未验证。" else null
    }
    fun blockedGoPreset(config:ModelConfig)=ProviderCatalog.info(ProviderPresets.get(config.providerId)).sessionHeader&&!verifiedGoBase(config.baseUrl)
    fun headers(config:ModelConfig,request:TextChatRequest):Map<String,String> =
        if(verifiedGoBase(config.baseUrl))mapOf("User-Agent" to USER_AGENT,"x-opencode-session" to request.conversationId) else emptyMap()
}
