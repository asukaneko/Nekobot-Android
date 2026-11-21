package com.nekobot.app.data.local.sessionimport

import com.nekobot.app.R

/**
 * 会话导入格式注册表。
 *
 * 会话页的导入弹窗按 [SessionImportFormat.formats] 顺序渲染格式列表，新增格式只需在这里
 * 追加一项（并接入对应的解析器/选择流程），弹窗本身不需要改动。
 *
 * @property id 稳定的格式标识，用于持久化与日志，不随界面文案变化。
 * @property labelRes 格式名称文案。
 * @property descriptionRes 格式简介文案。
 * @property needsArchiveSelection 是否先选定一个归档（ZIP）再勾选其中的会话。
 */
enum class SessionImportFormat(
    val id: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val needsArchiveSelection: Boolean
) {
    /** nekobot 自身导出的会话 JSON（单会话 {session,messages} 或 {type:"nbot_session_export"}）。 */
    NEKOBOT(
        id = "nekobot",
        labelRes = R.string.session_import_format_nekobot,
        descriptionRes = R.string.session_import_format_nekobot_desc,
        needsArchiveSelection = false
    ),

    /** DeepSeek 官方「导出数据」ZIP（chat_session_index.json + chat_sessions/ 下的会话文件）。 */
    DEEPSEEK(
        id = "deepseek",
        labelRes = R.string.session_import_format_deepseek,
        descriptionRes = R.string.session_import_format_deepseek_desc,
        needsArchiveSelection = true
    );

    companion object {
        /** 全部可导入格式，界面按此顺序展示。 */
        val formats: List<SessionImportFormat> get() = entries

        fun fromId(id: String): SessionImportFormat? = entries.firstOrNull { it.id == id }
    }
}
