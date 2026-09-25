package com.nekobot.app.data.local

/**
 * 待导入的一张表情：名称 + 图片字节。
 *
 * 名称可由文件名推导或用户在导入预览中修改；同名导入视为更新。
 */
class StickerImport(
    val name: String,
    val bytes: ByteArray,
    val mimeType: String? = null,
    val sourceName: String? = null,
    val source: String = "import"
)
