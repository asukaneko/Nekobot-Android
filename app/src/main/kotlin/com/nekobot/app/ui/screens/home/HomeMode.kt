package com.nekobot.app.ui.screens.home

import androidx.compose.runtime.Immutable
import com.nekobot.app.ui.navigation.HomeModeRoutes
import com.nekobot.app.ui.screens.sessions.SessionModeLabel

/**
 * 首页三种会话模式的入口。
 *
 * [routeValue] 与 [HomeModeRoutes] 一一对应，新增模式时这里补一项即可。
 */
enum class HomeMode(val routeValue: String, val modeLabel: SessionModeLabel) {
    CHARACTER(HomeModeRoutes.CHARACTER, SessionModeLabel.CHARACTER),
    AGENT(HomeModeRoutes.AGENT, SessionModeLabel.AGENT),
    COMPANION(HomeModeRoutes.COMPANION, SessionModeLabel.COMPANION);

    companion object {
        fun fromRoute(value: String?): HomeMode? =
            entries.firstOrNull { it.routeValue == value }
    }
}

/** 一张模式卡需要展示的数据；由首页按当前会话快照算好后传入。 */
@Immutable
data class ModeCardUi(
    val mode: HomeMode,
    /** 卡内序号，用于「01 / 02 / 03」的小标。 */
    val order: Int,
    val badge: String,
    val title: String,
    val subtitle: String?,
    val meta: String?,
    val action: String,
    /** 角色聊天卡的封面：最近一段角色会话的立绘。 */
    val coverUrl: String? = null,
    /** 没有立绘时封面里的首字。 */
    val coverInitial: String? = null,
    /** Agent 卡的终端两行：一行状态、一行提示。 */
    val agentLines: List<String> = emptyList()
)
