package com.nekobot.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nekobot.app.R
import com.nekobot.app.data.SessionActivity
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.components.resolveAvatarUrl
import com.nekobot.app.ui.screens.sessions.SessionListRow
import com.nekobot.app.ui.screens.sessions.SessionModeLabel

/**
 * 用首页的会话快照算出三张模式卡：标题取该模式最近一段会话，没有会话时退回模式说明。
 *
 * 角色聊天卡的封面直接用最近那段角色会话的立绘；还没有会话时退回内容库里的第一个角色。
 *
 * @param runningSessionIds 全局「生成中」的会话集合，Agent 卡据此展示运行状态动画
 * @param activities sessionId → 当前阶段（思考 / 调用工具 / 等待处理）
 */
@Composable
fun homeModeCards(
    rows: List<SessionListRow>,
    characters: List<CharacterPreset>,
    runningSessionIds: Set<String> = emptySet(),
    activities: Map<String, SessionActivity> = emptyMap()
): List<ModeCardUi> {
    val characterRow = rows.firstOrNull { it.modeLabel == SessionModeLabel.CHARACTER }
    val agentRows = rows.filter { it.modeLabel == SessionModeLabel.AGENT }
    // 运行中的 Agent 会话：可能同时有多个，按最近活跃排序
    val agentRuns = buildAgentRunUis(rows, runningSessionIds, activities)
    val listAction = stringResource(R.string.home_mode_card_action_all)
    val latestCharacter = characterRow?.characterId
        ?.let { id -> characters.firstOrNull { it.id == id } }
    val coverCharacter = latestCharacter ?: characters.firstOrNull { !it.id.isNullOrBlank() }
    // 封面优先用角色立绘（比列表头像更完整），其次用会话里的头像快照
    val coverUrl = latestCharacter?.let { resolveAvatarUrl(it.portrait) }
        ?: characterRow?.portraitUrl
        ?: coverCharacter?.let { resolveAvatarUrl(it.portrait ?: it.avatar) }
    // 取景按角色保存：封面角色可能是"最近会话角色"或内容库第一个角色，
    // 会话快照没有角色卡时仍用会话里的角色 ID，保证和首页 banner 对得上
    val coverCharacterId = latestCharacter?.id
        ?: coverCharacter?.id
        ?: characterRow?.characterId
    val coverCharacterName = latestCharacter?.displayName
        ?: coverCharacter?.displayName
        ?: characterRow?.listTitle
    val coverInitial = (characterRow?.listTitle ?: coverCharacter?.displayName)
        ?.takeIf { it.isNotBlank() }
        ?.let { it.substring(0, it.offsetByCodePoints(0, 1)) }

    return listOf(
        ModeCardUi(
            mode = HomeMode.CHARACTER,
            order = 1,
            badge = stringResource(R.string.home_mode_card_character),
            title = characterRow?.listTitle ?: stringResource(R.string.home_mode_card_character_empty),
            subtitle = characterRow?.lastMessage ?: stringResource(R.string.home_mode_card_character_hint),
            meta = characterRow?.listDate.orEmpty(),
            action = listAction,
            coverUrl = coverUrl,
            coverCharacterId = coverCharacterId,
            coverCharacterName = coverCharacterName,
            coverInitial = coverInitial
        ),
        ModeCardUi(
            mode = HomeMode.AGENT,
            order = 2,
            badge = stringResource(R.string.home_mode_card_agent),
            title = stringResource(R.string.home_mode_card_agent_title),
            // 有会话在跑时副标题直接说清有几个在运行，剩下的细节交给卡内的实时状态行
            subtitle = if (agentRuns.isEmpty()) {
                stringResource(R.string.home_mode_card_agent_hint)
            } else {
                stringResource(R.string.home_mode_card_agent_run_count, agentRuns.size)
            },
            meta = if (agentRows.isEmpty()) {
                ""
            } else {
                stringResource(R.string.home_mode_card_count, agentRows.size)
            },
            action = listAction,
            agentLines = if (agentRuns.isEmpty()) {
                listOf(
                    if (agentRows.isEmpty()) {
                        stringResource(R.string.home_mode_card_agent_idle)
                    } else {
                        stringResource(R.string.home_mode_card_agent_running, agentRows.size)
                    },
                    stringResource(R.string.home_mode_card_agent_ready)
                )
            } else {
                emptyList()
            },
            agentRuns = agentRuns
        ),
        ModeCardUi(
            mode = HomeMode.COMPANION,
            order = 3,
            badge = stringResource(R.string.home_mode_card_companion),
            title = stringResource(R.string.home_mode_card_companion),
            subtitle = stringResource(R.string.home_mode_card_companion_hint),
            meta = null,
            action = stringResource(R.string.home_mode_card_enter)
        )
    )
}
