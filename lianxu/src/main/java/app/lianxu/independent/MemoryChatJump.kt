package app.lianxu.independent

internal fun memoryChatIndex(messages: List<Message>, source: SourceRef): Int? =
    messages.indexOfFirst { it.friendId == source.characterId && it.id == source.turnId }.takeIf { it >= 0 }

internal class MemoryChatRequest {
    var pending: SourceRef? = null
    fun consume(): SourceRef? = pending.also { pending = null }
}

/** 来源卡只显示她记下时的那个版本：按完整 SourceRef 精确匹配；对不上就返回 null，由调用方回退到 evidence.quote/snapshot。跳转定位另按 turnId 找。 */
internal fun memorySourceDialogue(sources: List<SourceDialogue>, evidence: MemoryEvidence): SourceDialogue? =
    sources.firstOrNull { it.source == evidence.source }
