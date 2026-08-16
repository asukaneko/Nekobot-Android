package com.nekobot.app.ui.components

import com.nekobot.app.ui.components.withoutBorder as border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * AI 提供商 Logo 图标。
 *
 * Logo 资源位于 assets/providers/（与 nbot Web 端同一套 SVG），
 * 按 provider 字段 → baseUrl → 模型名 的顺序推断厂商，无法识别时用 custom。
 * 图标统一放在浅色圆角底板上，保证深色/浅色主题下品牌色都清晰。
 */

private const val ASSET_PREFIX = "file:///android_asset/providers/"

/** provider 字段（小写）→ Logo 文件名，与 nbot Web 端 getProviderLogoSvg 的映射保持一致 */
private val PROVIDER_LOGO_MAP = mapOf(
    "openai" to "openai.svg",
    "opencode" to "opencode.svg",
    "opencode-go" to "opencode-go.svg",
    "azure" to "openai.svg",
    "siliconflow" to "openai.svg",
    "anthropic" to "claude.svg",
    "claude" to "claude.svg",
    "google" to "gemini.svg",
    "gemini" to "gemini.svg",
    "deepseek" to "deepseek.svg",
    "zhipu" to "zai.svg",
    "glm" to "zai.svg",
    "z.ai" to "zai.svg",
    "zai" to "zai.svg",
    "minimax" to "minimax.svg",
    "grok" to "xai.svg",
    "xai" to "xai.svg",
    "qwen" to "qwen.svg",
    "dashscope" to "qwen.svg",
    "tongyi" to "qwen.svg",
    "xiaomi" to "xiaomi.svg",
    "mimo" to "xiaomi.svg",
    "custom" to "custom.svg"
)

/** baseUrl 包含的关键字 → Logo 文件名（provider 字段缺失时兜底） */
private val BASE_URL_HINTS = listOf(
    "openai.com" to "openai.svg",
    "opencode.ai/zen/go" to "opencode-go.svg",
    "opencode.ai" to "opencode.svg",
    "deepseek" to "deepseek.svg",
    "anthropic" to "claude.svg",
    "googleapis" to "gemini.svg",
    "generativelanguage" to "gemini.svg",
    "bigmodel" to "zai.svg",
    "zhipu" to "zai.svg",
    "z.ai" to "zai.svg",
    "minimax" to "minimax.svg",
    "x.ai" to "xai.svg",
    "dashscope" to "qwen.svg",
    "aliyuncs" to "qwen.svg",
    "siliconflow" to "openai.svg",
    "xiaomi" to "xiaomi.svg",
    "mimo" to "xiaomi.svg"
)

/** 模型名包含的关键字 → Logo 文件名（最后兜底） */
private val MODEL_NAME_HINTS = listOf(
    "gpt" to "openai.svg",
    "openai" to "openai.svg",
    "deepseek" to "deepseek.svg",
    "claude" to "claude.svg",
    "gemini" to "gemini.svg",
    "glm" to "zai.svg",
    "chatglm" to "zai.svg",
    "qwen" to "qwen.svg",
    "qvq" to "qwen.svg",
    "grok" to "xai.svg",
    "minimax" to "minimax.svg",
    "abab" to "minimax.svg",
    "mimo" to "xiaomi.svg"
)

/** 推断模型对应的提供商 Logo 资源路径 */
fun providerLogoAsset(provider: String?, baseUrl: String?, model: String?): String {
    provider?.trim()?.lowercase()?.let { p ->
        PROVIDER_LOGO_MAP[p]?.let { return ASSET_PREFIX + it }
        // provider 可能带后缀（如 "openai_compatible"），做包含匹配
        PROVIDER_LOGO_MAP.entries.firstOrNull { (key, _) -> p.contains(key) }
            ?.let { return ASSET_PREFIX + it.value }
    }
    baseUrl?.lowercase()?.let { url ->
        BASE_URL_HINTS.firstOrNull { (hint, _) -> hint in url }
            ?.let { return ASSET_PREFIX + it.second }
    }
    model?.lowercase()?.let { m ->
        MODEL_NAME_HINTS.firstOrNull { (hint, _) -> hint in m }
            ?.let { return ASSET_PREFIX + it.second }
    }
    return ASSET_PREFIX + "custom.svg"
}

/**
 * 提供商 Logo：浅色圆角底板 + 居中 SVG。
 * @param size 底板边长，Logo 内部留约 28%  padding
 */
@Composable
fun ProviderLogo(
    provider: String?,
    baseUrl: String?,
    model: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Color(0xFFF4F6F8))
            .border(
                width = 1.dp,
                color = Color(0x14000000),
                shape = RoundedCornerShape(size * 0.28f)
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = providerLogoAsset(provider, baseUrl, model),
            contentDescription = provider,
            modifier = Modifier
                .padding(size * 0.16f)
                .size(size)
        )
    }
}

/**
 * 模型卡片上的小型信息标签（协议 / 用途等）。
 * @param accent true 使用主题色高亮，false 使用中性灰底
 */
@Composable
fun ModelInfoChip(
    text: String,
    accent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val color = if (accent) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        color = color.copy(alpha = if (accent) 0.09f else 0.055f),
        contentColor = color,
        shape = RoundedCornerShape(7.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
