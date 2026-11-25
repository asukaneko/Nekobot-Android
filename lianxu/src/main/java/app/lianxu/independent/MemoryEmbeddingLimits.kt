package app.lianxu.independent

/** Verified endpoint/model limits, not capabilities inferred from a model's name. */
internal data class MemoryEmbeddingLimits(val items:Int,val inputTokens:Int,val totalTokens:Int,val inputChars:Int=Int.MAX_VALUE) {
    fun batches(config:ModelConfig,texts:List<String>):List<List<String>> {
        val result=mutableListOf<List<String>>();var batch=mutableListOf<String>();var tokens=0;var bytes=0
        for(text in texts){
            val cost=ConservativeTokens.tokens(text);val size=text.toByteArray().size+16
            require(text.isNotBlank()&&text.length<=inputChars&&cost<=minOf(inputTokens,config.contextTokens)&&size<=240*1024)
            if(batch.isNotEmpty()&&(batch.size==items||tokens+cost>totalTokens||bytes+size>240*1024)){result+=batch;batch=mutableListOf();tokens=0;bytes=0}
            batch+=text;tokens+=cost;bytes+=size
        }
        if(batch.isNotEmpty())result+=batch;return result
    }
    companion object {
        fun of(c:ModelConfig):MemoryEmbeddingLimits {
            val endpoint=c.baseUrl.trimEnd('/')
            if(c.providerId=="openai"&&endpoint=="https://api.openai.com/v1"&&c.modelId in setOf("text-embedding-3-small","text-embedding-3-large","text-embedding-ada-002"))return MemoryEmbeddingLimits(2048,8192,300000)
            if(c.providerId=="bailian-cn"&&(endpoint=="https://dashscope.aliyuncs.com/compatible-mode/v1"||endpoint.matches(Regex("https://[A-Za-z0-9-]+\\.cn-beijing\\.maas\\.aliyuncs\\.com/compatible-mode/v1"))))return when(c.modelId){
                "qwen3.7-text-embedding","qwen3.7-text-embedding-flash"->MemoryEmbeddingLimits(20,128000,128000)
                "text-embedding-v4"->MemoryEmbeddingLimits(10,33000,33000)
                "text-embedding-v3"->MemoryEmbeddingLimits(10,8192,8192)
                "text-embedding-v2"->MemoryEmbeddingLimits(25,2048,2048)
                else->MemoryEmbeddingLimits(2048,c.contextTokens,c.contextTokens)
            }
            if(ProviderCatalog.modelKind(c)==ProviderModelKind.TEXT_EMBEDDING){
                if(c.providerId.startsWith("tokenhub-"))return MemoryEmbeddingLimits(128,32000,minOf(c.contextTokens,32000),2000)
                // One item is a conservative client batch choice, not a claimed Gemini service maximum.
                if(c.providerId=="gemini")return MemoryEmbeddingLimits(1,2048,2048)
                if(c.providerId=="qianfan")return when(c.modelId){
                    "embedding-v1"->MemoryEmbeddingLimits(16,384,16*384,1000)
                    "bge-large-zh","bge-large-en"->MemoryEmbeddingLimits(16,512,16*512,2000)
                    "tao-8k"->MemoryEmbeddingLimits(1,8192,8192)
                    else->MemoryEmbeddingLimits(16,8192,minOf(c.contextTokens,16*8192))
                }
            }
            // Unknown compatible services get one bounded request, not a guessed vendor batch size.
            // Rejection falls back to the original summary; authentication errors are never retried.
            return MemoryEmbeddingLimits(2048,c.contextTokens,c.contextTokens)
        }
    }
}
