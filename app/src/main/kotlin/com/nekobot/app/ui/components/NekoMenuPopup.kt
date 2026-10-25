package com.nekobot.app.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** 面板外留白：只用来判断"点在面板外"，不再绘制阴影。 */
private val MenuHaloPadding = 8.dp
private val MenuVerticalMargin = 24.dp
private val MenuCornerRadius = 14.dp

/**
 * 三点 / ＋ 号菜单的统一弹层。
 *
 * 纯色面板 + 1dp 细线，没有阴影也没有光晕：靠细线和层次区分，不再出现投影。
 * 弹出动画沿用菜单的缩放 + 淡入，长菜单可滚动。
 */
@Composable
internal fun NekoMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded
    if (!expandedState.currentState && !expandedState.targetState) return

    val density = LocalDensity.current
    val maxHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
        .minus(MenuVerticalMargin * 2).coerceAtLeast(48.dp)
    val transformOrigin = remember { mutableStateOf(TransformOrigin.Center) }
    val positionProvider = remember(density) {
        NekoMenuPositionProvider(
            shadowPadding = with(density) { MenuHaloPadding.roundToPx() },
            verticalMargin = with(density) { MenuVerticalMargin.roundToPx() },
            onTransformOrigin = { transformOrigin.value = it },
        )
    }
    val transition = updateTransition(expandedState, label = "NekoMenu")
    val scale by transition.animateFloat(
        transitionSpec = { spring(dampingRatio = 1f, stiffness = 700f) },
        label = "NekoMenuScale",
    ) { if (it) 1f else 0.88f }
    val alpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 110) },
        label = "NekoMenuAlpha",
    ) { if (it) 1f else 0f }
    val scrollState = rememberScrollState()

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                    this.transformOrigin = transformOrigin.value
                }
                .pointerInput(onDismissRequest) {
                    detectTapGestures { position ->
                        val padding = MenuHaloPadding.toPx()
                        if (position.x < padding || position.x > size.width - padding ||
                            position.y < padding || position.y > size.height - padding
                        ) {
                            onDismissRequest()
                        }
                    }
                }
                .padding(MenuHaloPadding)
        ) {
            Surface(
                modifier = modifier.heightIn(max = maxHeight),
                shape = RoundedCornerShape(MenuCornerRadius),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    Modifier
                        .padding(vertical = 6.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(scrollState),
                    content = content,
                )
            }
        }
    }
}
