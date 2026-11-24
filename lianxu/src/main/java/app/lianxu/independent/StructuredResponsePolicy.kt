package app.lianxu.independent

/** Enable provider extensions only for the exact documented endpoint/model family. */
internal object StructuredResponsePolicy {
    fun supportsJsonObject(config:ModelConfig)=config.baseUrl.trimEnd('/')=="https://api.xiaomimimo.com/v1"&&
        config.modelId in setOf("mimo-v2.6-flash","mimo-v2.6-pro","mimo-v2.6-pro-ultraspeed")
    // Tool calling and final JSON are separate protocol stages; joint support is not assumed.
    fun jsonMode(config:ModelConfig,request:TextChatRequest)=request.jsonObject&&request.tools.isEmpty()&&supportsJsonObject(config)
}
