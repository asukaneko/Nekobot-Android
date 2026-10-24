package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity

/** 同一进程内按完整批次指纹记失败；两次缺done后按整轮拆成两批。 */
internal class FusedMemoryRetryPolicy {
    private val failures = linkedMapOf<String, Int>()
    fun key(profile: String, scope: String, rows: List<LocalMessageEntity>) =
        CompanionMemoryPolicy.digest(profile + scope + rows.joinToString { CompanionMemoryPolicy.fingerprint(it) })
    @Synchronized fun incomplete(key: String) {
        failures[key] = (failures[key] ?: 0) + 1
        if (failures.size > 128) failures.remove(failures.keys.first())
    }
    @Synchronized fun complete(key: String) { failures.remove(key) }
    @Synchronized fun batches(key: String, rows: List<LocalMessageEntity>): List<List<LocalMessageEntity>> {
        val turns = FusedCompanionPolicy.turns(rows)
        if ((failures[key] ?: 0) < 2 || turns.size < 2) return listOf(rows)
        val middle = (turns.size + 1) / 2
        return listOf(turns.take(middle).flatten(), turns.drop(middle).flatten())
    }
}
