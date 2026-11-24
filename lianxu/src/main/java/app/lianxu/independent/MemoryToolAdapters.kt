package app.lianxu.independent

import com.google.gson.Gson

internal fun estimateLocalTextTokens(text:String)=ConservativeTokens.tokens(text)
internal fun cleanRecallMessage(message:Map<String,Any>)=message.filterKeys{!it.startsWith("_")}
internal fun estimateLocalMessagesTokens(messages:List<Map<String,Any>>)=messages.sumOf{ConservativeTokens.tokens(Gson().toJson(it))}
internal fun toolNameOf(tool:Map<String,Any>)=((tool["function"] as? Map<*,*>)?.get("name")?:tool["name"]) as? String ?: ""
