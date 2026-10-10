/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

private val MenuShadowPadding = 32.dp
private val MenuVerticalMargin = 48.dp

/** 阴影在 Surface 裁切之外绘制，透明留白随菜单一起动画。 */
@Composable
internal fun LiquidGlassMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    shape: Shape,
    tint: Color,
    border: BorderStroke,
    fallback: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded
    val visible = expandedState.currentState || expandedState.targetState
    val backdrop = rememberPopupGlassBackdrop(visible, sampleCurrentWindow = true)
    if (backdrop == null) {
        fallback()
        return
    }
    if (!visible) return

    val density = LocalDensity.current
    val maxHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
        .minus(MenuVerticalMargin * 2).coerceAtLeast(48.dp)
    val transformOrigin = remember { mutableStateOf(TransformOrigin.Center) }
    val positionProvider = remember(density) {
        LiquidGlassMenuPositionProvider(
            shadowPadding = with(density) { MenuShadowPadding.roundToPx() },
            verticalMargin = with(density) { MenuVerticalMargin.roundToPx() },
            onTransformOrigin = { transformOrigin.value = it },
        )
    }
    val transition = updateTransition(expandedState, label = "GlassMenu")
    val scale by transition.animateFloat(
        transitionSpec = { spring(dampingRatio = 1f, stiffness = 600f) },
        label = "GlassMenuScale",
    ) { if (it) 1f else 0.8f }
    val alpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 120) },
        label = "GlassMenuAlpha",
    ) { if (it) 1f else 0f }
    val scrollState = rememberScrollState()

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Box(
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                this.transformOrigin = transformOrigin.value
            }.pointerInput(onDismissRequest) {
                detectTapGestures { position ->
                    val padding = MenuShadowPadding.toPx()
                    if (position.x < padding || position.x > size.width - padding ||
                        position.y < padding || position.y > size.height - padding) {
                        onDismissRequest()
                    }
                }
            }.padding(MenuShadowPadding)
        ) {
            Surface(
                modifier = Modifier.heightIn(max = maxHeight)
                    .liquidGlassMenuSurface(backdrop, shape, tint),
                shape = shape,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = border,
            ) {
                Column(
                    modifier.padding(vertical = 8.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(scrollState),
                    content = content,
                )
            }
        }
    }
}

/** 沿用 Material 菜单的锚点顺序，阴影留白不参与菜单位置计算。 */
internal class LiquidGlassMenuPositionProvider(
    private val shadowPadding: Int,
    private val verticalMargin: Int,
    private val onTransformOrigin: (TransformOrigin) -> Unit = {},
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = (popupContentSize.width - shadowPadding * 2).coerceAtLeast(0)
        val height = (popupContentSize.height - shadowPadding * 2).coerceAtLeast(0)
        val start = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - width
        val end = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - width else anchorBounds.left
        val windowX = if (anchorBounds.center.x < windowSize.width / 2) 0 else (windowSize.width - width).coerceAtLeast(0)
        val x = listOf(start, end).firstOrNull { it >= 0 && it + width <= windowSize.width } ?: windowX
        val windowY = if (anchorBounds.center.y < windowSize.height / 2) {
            verticalMargin
        } else {
            windowSize.height - verticalMargin - height
        }
        val y = listOf(anchorBounds.bottom, anchorBounds.top - height, anchorBounds.top - height / 2)
            .firstOrNull { it >= verticalMargin && it + height <= windowSize.height - verticalMargin }
            ?: windowY.coerceAtLeast(verticalMargin)
        val menuBounds = IntRect(x, y, x + width, y + height)
        val pivotX = when {
            menuBounds.left >= anchorBounds.right -> 0f
            menuBounds.right <= anchorBounds.left -> width.toFloat()
            else -> (maxOf(anchorBounds.left, menuBounds.left) + minOf(anchorBounds.right, menuBounds.right)) / 2f - x
        }
        val pivotY = when {
            menuBounds.top >= anchorBounds.bottom -> 0f
            menuBounds.bottom <= anchorBounds.top -> height.toFloat()
            else -> (maxOf(anchorBounds.top, menuBounds.top) + minOf(anchorBounds.bottom, menuBounds.bottom)) / 2f - y
        }
        onTransformOrigin(
            TransformOrigin(
                (shadowPadding + pivotX) / popupContentSize.width.coerceAtLeast(1),
                (shadowPadding + pivotY) / popupContentSize.height.coerceAtLeast(1),
            )
        )
        return IntOffset(x - shadowPadding, y - shadowPadding)
    }
}
