package com.nekobot.app.ui.screens.chat

/** 源段索引同时用于菜单锚点、进场动画和 Compose 身份，不以合并后的序号替换。 */
internal data class ChatBubbleDisplayGroup(val sourceIndices: List<Int>) {
    val sourceIndex: Int get() = sourceIndices.first()
}
