package app.lianxu.independent

/** Enable provider extensions only for the exact documented endpoint/model family. */
internal object StructuredResponsePolicy {
    private fun isSupportedMimo(config: ModelConfig) = config.baseUrl.trimEnd('/') == "https://api.xiaomimimo.com/v1" &&
        config.modelId in setOf("mimo-v2.6-flash","mimo-v2.6-pro","mimo-v2.6-pro-ultraspeed")
    fun supportsJsonObject(config: ModelConfig) = isSupportedMimo(config)

    fun supportsReasoningContinuation(config: ModelConfig): Boolean {
        if (isSupportedMimo(config)) return true
        val base = config.baseUrl.trimEnd('/')
        if (base !in setOf("https://api.deepseek.com", "https://api.deepseek.com/v1")) return false
        return config.modelId in setOf("deepseek-reasoner", "deepseek-chat") ||
            ModelTokenPolicy.capability("deepseek", base, config.modelId)?.reasoning == true
    }
    // Tool calling and final JSON are separate protocol stages; joint support is not assumed.
    fun jsonMode(config:ModelConfig,request:TextChatRequest)=request.jsonObject&&request.tools.isEmpty()&&supportsJsonObject(config)
}
