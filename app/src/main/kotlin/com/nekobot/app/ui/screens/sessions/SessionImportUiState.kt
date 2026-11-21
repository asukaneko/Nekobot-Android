package com.nekobot.app.ui.screens.sessions

import com.nekobot.app.data.local.sessionimport.ImportableConversation
import com.nekobot.app.data.local.sessionimport.SessionImportFormat

/** 导入弹窗当前所处步骤。 */
enum class SessionImportStep {
    /** 选择导入格式（可扩展的格式列表）。 */
    FORMAT,

    /** DeepSeek 格式的来源选择：下载链接或本地 ZIP。 */
    SOURCE,

    /** 勾选要导入的会话。 */
    CONVERSATIONS
}

/**
 * 会话导入弹窗状态。
 *
 * 只承载界面需要的快照，临时归档文件由 ViewModel 私有持有并在关闭时清理。
 */
data class SessionImportUiState(
    val visible: Boolean = false,
    val step: SessionImportStep = SessionImportStep.FORMAT,
    val format: SessionImportFormat? = null,
    val archiveName: String? = null,
    val conversations: List<ImportableConversation> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    /** 正在下载/解析归档。 */
    val reading: Boolean = false,
    /** 正在写入会话。 */
    val importing: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val processedSessions: Int = 0,
    val totalSessions: Int = 0,
    val error: String? = null
) {
    val allSelected: Boolean get() = conversations.isNotEmpty() && selectedIds.size == conversations.size
    val selectedCount: Int get() = selectedIds.size
    val busy: Boolean get() = reading || importing

    /** 下载进度（0..1）；总长度未知时为 null。 */
    val downloadFraction: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            return (downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
        }
}
