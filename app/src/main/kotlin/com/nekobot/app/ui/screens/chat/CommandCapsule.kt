package com.nekobot.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nekobot.app.R

/**
 * 内置命令的彩色胶囊样式。
 *
 * 目前仅 /goal、/spec 使用胶囊渲染（输入框内替换命令字符、发送后用户气泡同步展示）；
 * 每种命令有独立的图标、渐变底色与多语言标签，方便后续扩展更多命令。
 */
internal enum class CommandCapsuleKind {
    GOAL,
    SPEC;

    /** 胶囊上显示的多语言文本资源 id（如“目标 / Goal / 目標 / 목표”）。 */
    val labelResId: Int
        get() = when (this) {
            GOAL -> R.string.command_capsule_goal
            SPEC -> R.string.command_capsule_spec
        }

    val icon: ImageVector
        get() = when (this) {
            GOAL -> Icons.Filled.Flag
            SPEC -> Icons.Filled.Checklist
        }

    /** 胶囊渐变起始色（深色系，保证白色文字可读）。 */
    val startColor: Color
        get() = when (this) {
            GOAL -> Color(0xFF00A86B)
            SPEC -> Color(0xFF7C4DFF)
        }

    /** 胶囊渐变结束色。 */
    val endColor: Color
        get() = when (this) {
            GOAL -> Color(0xFF007A4D)
            SPEC -> Color(0xFF5A2FCB)
        }
}

/** 文本开头匹配到的命令胶囊；[token] 保留用户在原文中的实际写法（大小写）。 */
internal data class CommandCapsuleMatch(
    val kind: CommandCapsuleKind,
    val token: String,
    val tokenRange: IntRange
)

/**
 * 匹配输入 / 消息文本开头的 /goal、/spec 命令（与 [com.nekobot.app.data.local.LocalSlashCommands.parse]
 * 的识别方式一致：命令以空格或换行结尾），未匹配返回 null。
 */
internal fun matchCommandCapsule(text: String): CommandCapsuleMatch? {
    if (!text.startsWith('/')) return null
    var tokenEnd = text.length
    for (i in text.indices) {
        val c = text[i]
        if (c == ' ' || c == '\n') {
            tokenEnd = i
            break
        }
    }
    if (tokenEnd <= 0) return null
    val token = text.substring(0, tokenEnd)
    val kind = when (token.lowercase()) {
        "/goal" -> CommandCapsuleKind.GOAL
        "/spec" -> CommandCapsuleKind.SPEC
        else -> null
    } ?: return null
    return CommandCapsuleMatch(kind = kind, token = token, tokenRange = 0 until tokenEnd)
}

/**
 * 输入框的视觉变换：把开头的 /goal、/spec 命令字符（含其后一个空格）从编辑区隐藏，
 * 由装饰层（decorationBox / prefix）在同一位置绘制彩色胶囊，从而“命令字符变为胶囊”。
 *
 * 光标与选区仍使用原文本坐标；命中区域被折叠为显示区起点偏移 0。
 */
internal fun commandCapsuleVisualTransformation(): VisualTransformation = VisualTransformation { text ->
    val match = matchCommandCapsule(text.text)
        ?: return@VisualTransformation TransformedText(text, OffsetMapping.Identity)
    // 连命令后的一个空格一起隐藏，避免胶囊与参数之间出现多余前导空格
    var hiddenLength = match.token.length
    if (hiddenLength < text.text.length && text.text[hiddenLength] == ' ') hiddenLength++
    val display = AnnotatedString(text.text.drop(hiddenLength))
    val mapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int =
            if (offset <= hiddenLength) 0 else offset - hiddenLength

        override fun transformedToOriginal(offset: Int): Int =
            if (offset <= 0) 0 else offset + hiddenLength
    }
    TransformedText(display, mapping)
}

/**
 * 命令胶囊组件：圆角胶囊 + 命令图标 + 多语言标签。
 *
 * [translucent] 为 true 时使用半透明白底（适合放在已着色的气泡/背景上），
 * 否则使用命令自身的渐变底色（适合放在输入框等浅色背景上）。
 */
@Composable
internal fun CommandCapsuleChip(
    kind: CommandCapsuleKind,
    modifier: Modifier = Modifier,
    translucent: Boolean = false,
    showIcon: Boolean = true
) {
    val label = stringResource(kind.labelResId)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (translucent) {
                    Color.White.copy(alpha = 0.28f)
                } else {
                    Brush.horizontalGradient(listOf(kind.startColor, kind.endColor))
                }
            )
            .padding(horizontal = if (showIcon) 12.dp else 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showIcon) {
            Icon(
                imageVector = kind.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}