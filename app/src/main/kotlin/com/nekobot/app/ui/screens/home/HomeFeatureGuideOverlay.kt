package com.nekobot.app.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.HomeStyle
import com.nekobot.app.ui.components.GlassCard
import com.nekobot.app.ui.components.NekoPrimaryAction
import com.nekobot.app.ui.components.NekoSecondaryAction
import com.nekobot.app.ui.components.nekoCaptionStyle
import com.nekobot.app.ui.components.nekoRowSubtitleStyle
import com.nekobot.app.ui.components.nekoRowTitleStyle

/**
 * 首页功能引导（非弹窗）：一层深色遮罩盖住首页，遮罩之上只放两块实心说明卡。
 *
 * - 上图卡带向上箭头，指向顶栏：说明「搜索、全部会话、底栏」没变；
 * - 下图卡带向下箭头，指向三张模式卡：说明新增的三个模式；
 * - 最底部是一整块选择面板，只承载「用哪一版做首页」这一件事。
 *
 * 只出现一次：选择或点「稍后再选」后写入
 * [com.nekobot.app.data.local.PrefsManager.homeStyleChosen]。
 */
@Composable
fun HomeFeatureGuideOverlay(
    onChoose: (HomeStyle) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 底部导航栏的避让高度：引导内容不能压在导航栏上。 */
    bottomClearance: Dp = 0.dp
) {
    // 引导期间返回键只收起引导，不退出页面
    BackHandler(onBack = onDismiss)
    Box(
        modifier = modifier
            .fillMaxSize()
            // 深色遮罩：让底下的页面退成剪影，说明卡才读得清
            .background(Color.Black.copy(alpha = 0.68f))
            .blockGuideTouches()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomClearance + 12.dp)
        ) {
            // 让开顶栏，箭头紧贴在顶栏下方
            Spacer(Modifier.height(48.dp))
            GuideCallout(
                arrowUp = true,
                title = stringResource(R.string.home_guide_top_title),
                desc = stringResource(R.string.home_guide_top_desc)
            )
            Spacer(Modifier.weight(0.38f))
            // 指向三张模式卡
            GuideCallout(
                arrowUp = false,
                title = stringResource(R.string.home_guide_cards_title),
                desc = stringResource(R.string.home_guide_cards_desc)
            )
            Spacer(Modifier.weight(0.62f))
            GuideChoice(onChoose = onChoose, onDismiss = onDismiss)
        }
    }
}

/** 一条说明：箭头 + 实心文本卡，箭头朝向它要指的那块界面。 */
@Composable
private fun GuideCallout(
    arrowUp: Boolean,
    title: String,
    desc: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (arrowUp) GuideArrow(pointsUp = true)
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(16.dp), clip = false),
            cornerRadius = 16,
            borderWidth = 1,
            containerColor = MaterialTheme.colorScheme.surface,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
        ) {
            Text(
                text = title,
                style = nekoRowTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = desc,
                style = nekoRowSubtitleStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!arrowUp) GuideArrow(pointsUp = false)
    }
}

@Composable
private fun GuideArrow(pointsUp: Boolean) {
    Icon(
        imageVector = if (pointsUp) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(30.dp)
    )
}

/** 底部选择面板：整块实心卡，避免按钮和页面内容叠在一起。 */
@Composable
private fun GuideChoice(
    onChoose: (HomeStyle) -> Unit,
    onDismiss: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(18.dp), clip = false),
        cornerRadius = 18,
        borderWidth = 1,
        containerColor = MaterialTheme.colorScheme.surface,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.home_guide_choose_title),
                style = nekoRowTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            NekoPrimaryAction(
                text = stringResource(R.string.home_guide_choose_modes),
                onClick = { onChoose(HomeStyle.MODE_CARDS) }
            )
            NekoSecondaryAction(
                text = stringResource(R.string.home_guide_choose_sessions),
                onClick = { onChoose(HomeStyle.SESSIONS) }
            )
            Text(
                text = stringResource(R.string.home_guide_note),
                style = nekoCaptionStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.home_guide_skip),
                    style = nekoCaptionStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 吃掉浮层范围内的所有指针事件：
 * 下层的会话列表不会滚动，首页的横向翻页也不会被误触发；
 * 浮层内部的按钮属于子节点，先一步拿到事件，仍然可以正常点击。
 */
private fun Modifier.blockGuideTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
