package com.nekobot.app.ui.adaptive

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 无障碍辅助 Modifier 扩展函数集合。
 *
 * 提供常用的语义修饰，方便各屏幕统一接入无障碍支持。
 */

/**
 * 为列表项提供汇总描述。
 *
 * TalkBack 会朗读此描述，而非逐个子元素朗读，
 * 适用于复杂列表项（含图标、标题、副标题等）。
 *
 * @param description 列表项的汇总描述文本
 */
fun Modifier.listItemSemantics(description: String): Modifier =
    this.semantics(mergeDescendants = true) {
        contentDescription = description
    }

/**
 * 为流式更新区域提供 liveRegion，使 TalkBack 在内容变化时自动播报。
 *
 * 适用于 AI 流式回复、实时状态更新等动态内容区域。
 *
 * @param polite true 时使用 Polite 模式（非紧急播报），
 *               false 时使用 Assertive 模式（立即打断播报）
 */
fun Modifier.liveRegion(polite: Boolean = true): Modifier =
    this.semantics {
        liveRegion = if (polite) LiveRegionMode.Polite else LiveRegionMode.Assertive
    }

/**
 * 确保触觉目标尺寸至少 48dp，符合 Material 无障碍最小触摸目标规范。
 *
 * 在视觉元素小于 48dp 时，扩大可点击区域而不改变视觉尺寸。
 */
fun Modifier.minTouchTarget(): Modifier =
    this.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
