package com.nekobot.app.data.local

import java.util.UUID

/**
 * 临时会话标记。
 *
 * 临时会话（Agent 专用）与普通会话的区别：
 * - 不读写全局 Agent 记忆，工具集里也不提供记忆读写工具；
 * - 不参与自动长期记忆抽取、自动技能沉淀与会话自动命名；
 * - 不进入长期记录：结束会话或应用重启时连同消息一起清除。
 *
 * 通过固定 ID 前缀识别，因此不需要新增数据库列或迁移版本。
 */
object TemporarySession {
    /** 临时会话 ID 前缀。 */
    const val ID_PREFIX = "temp_"

    /** 生成临时会话 ID。 */
    fun newId(): String = ID_PREFIX + UUID.randomUUID().toString().replace("-", "")

    /** 判断会话 ID 是否属于临时会话。 */
    fun isTemporary(id: String?): Boolean = id?.startsWith(ID_PREFIX) == true

    /** 判断本地会话实体是否属于临时会话。 */
    fun isTemporary(entity: com.nekobot.app.data.local.db.LocalSessionEntity?): Boolean =
        isTemporary(entity?.id)

    /** 判断会话模型是否属于临时会话。 */
    fun isTemporary(session: com.nekobot.app.data.model.Session?): Boolean =
        isTemporary(session?.id)
}
