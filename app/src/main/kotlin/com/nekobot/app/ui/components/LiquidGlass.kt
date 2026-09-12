package com.nekobot.app.ui.components

import android.app.ActivityManager
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（iOS Liquid Glass）采样工具。
 *
 * 原理与 `io.github.kyant0:backdrop` 一致，只是用项目现有的 Compose 1.7 + 原生 RenderEffect 手写：
 * 1. [glassBackdropSource] 把下层页面内容录进一个离屏 [GraphicsLayer]（玻璃的“采样源”）；
 * 2. [GlassPane] 在玻璃区域内按 root 坐标把该离屏层画回来，并串上 RenderEffect 链：
 *    高斯模糊 → 饱和度（vibrancy）→ AGSL 边缘折射（API 33+）；
 * 3. 再叠加极淡的中性染色、顶部内阴影与高光描边，形成透明玻璃的厚度感。
 *
 * 采样节点比玻璃形状外扩余量（模糊 + 折射峰值），且着色器会把采样点 clamp 回
 * 被录制的页面范围内，保证边缘不会取到图层外的空像素（那是边缘杂色/蓝线的根源）。
 */

/** 交互（按压 / 拖动）时折射强度的放大倍率上限。 */
private const val LiquidRefractionBoost = 1.8f

/** 液态玻璃是否可用：需要 API 31+（RenderEffect）；低内存设备回退静态样式以免掉帧。 */
fun isLiquidGlassAvailable(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return activityManager?.isLowRamDevice != true
}

/** 组合期读取一次液态玻璃可用性。 */
@Composable
fun rememberLiquidGlassAvailable(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { isLiquidGlassAvailable(context) }
}

/**
 * 玻璃采样源：持有一块离屏图层，图层内容是「玻璃背后」的页面渲染结果。
 * 由 [rememberGlassBackdrop] 创建，挂到被采样的内容上（[glassBackdropSource]），再交给玻璃层使用。
 */
class GlassBackdrop internal constructor(
    internal val layer: GraphicsLayer,
) {
    /** 被采样内容在 root 坐标系中的位置与尺寸，用于把离屏层对齐回玻璃区域。 */
    internal var sourcePositionInRoot: Offset = Offset.Zero
    internal var sourceSizeInRoot: IntSize = IntSize.Zero
}

/** 创建并记住一个玻璃采样源。 */
@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * 把当前节点的内容注册为玻璃采样源：内容先被录进离屏层，再原样绘制到画布（外观不变）。
 */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { coordinates ->
        backdrop.sourcePositionInRoot = coordinates.positionInRoot()
        backdrop.sourceSizeInRoot = coordinates.size
    }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * 液态玻璃面板：采样 [backdrop] 并绘制模糊 / 折射 / 高光。玻璃本身保持中性透明，
 * 不混入主题色；选中态等语义靠上层内容（图标颜色等）表达。
 *
 * @param cornerRadius 圆角半径；等于高度一半时即为胶囊。
 * @param blur 高斯模糊半径，越大越“毛玻璃”。
 * @param saturation 饱和度提升（iOS vibrancy），1f 表示不变。
 * @param refraction 静止时的边缘折射深度，0dp 表示不做折射。
 * @param dispersion 色散强度（RGB 分离）；默认 0f，非 0 时边缘可能出现彩色描边。
 * @param tint 中性玻璃底色，建议透明或极淡的白色/黑色。
 * @param rimColors 高光描边的竖直渐变（上 → 下），空列表表示不画描边。
 * @param innerShadowAlpha 顶部内阴影强度，营造玻璃厚度。
 * @param liquidProgress 0f=静止，1f=正在按压/拖动：玻璃变透明、折射增强，呈液态。
 * @param scaleBoost 按压/拖动时的最大额外放大比例（0f 表示不放大）。
 * @param tintFade 交互时底色的淡出比例：1f 表示完全透明（如选中指示器），0.85f 表示留一点底色。
 * @param rimRestAlpha 静止时高光描边的透明度倍率（可用它把静止态的描边压得很淡）。
 */
@Composable
fun GlassPane(
    backdrop: GlassBackdrop,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    blur: Dp = 16.dp,
    saturation: Float = 1.25f,
    refraction: Dp = 12.dp,
    dispersion: Float = 0f,
    tint: Color = Color.Transparent,
    rimColors: List<Color> = emptyList(),
    innerShadowAlpha: Float = 0.06f,
    liquidProgress: () -> Float = { 0f },
    scaleBoost: Float = 0f,
    tintFade: Float = 0.85f,
    rimRestAlpha: Float = 1f,
) {
    val shape = RoundedCornerShape(cornerRadius)
    // 采样余量按折射峰值预留：交互时折射会临时放大，取样点仍要落在节点内部。
    val margin = blur + refraction * LiquidRefractionBoost
    val rimBrush = remember(rimColors) {
        if (rimColors.isEmpty()) null else Brush.verticalGradient(rimColors)
    }
    BoxWithConstraints(
        modifier
            .graphicsLayer {
                if (scaleBoost > 0f) {
                    val scale = 1f + scaleBoost * liquidProgress()
                    scaleX = scale
                    scaleY = scale
                }
            }
            .clip(shape)
    ) {
        GlassSamplingLayer(
            backdrop = backdrop,
            cornerRadius = cornerRadius,
            margin = margin,
            blur = blur,
            saturation = saturation,
            refraction = refraction,
            dispersion = dispersion,
            liquidProgress = liquidProgress,
            modifier = Modifier
                .offset(x = -margin, y = -margin)
                .requiredSize(
                    width = maxWidth + margin * 2,
                    height = maxHeight + margin * 2,
                ),
        )
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    // 读动画值发生在绘制阶段，不触发重组。
                    val progress = liquidProgress()
                    if (tint.alpha > 0f) {
                        // 交互时玻璃几乎完全透明，露出被折射的背景（iOS 的“液态”手感）。
                        drawRect(color = tint, alpha = 1f - tintFade * progress)
                    }
                    if (innerShadowAlpha > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = innerShadowAlpha),
                                    Color.Transparent,
                                ),
                                endY = size.height * 0.65f,
                            )
                        )
                    }
                    if (rimBrush != null) {
                        val inset = 0.5.dp.toPx()
                        val radius = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
                        drawRoundRect(
                            brush = rimBrush,
                            topLeft = Offset(inset, inset),
                            size = Size(
                                width = (size.width - inset * 2).coerceAtLeast(0f),
                                height = (size.height - inset * 2).coerceAtLeast(0f),
                            ),
                            cornerRadius = CornerRadius(radius, radius),
                            style = Stroke(width = 1.dp.toPx()),
                            // 交互时描边更亮，像被手指“点亮”；静止时可用 rimRestAlpha 压淡。
                            alpha = (rimRestAlpha + (1.6f - rimRestAlpha) * progress)
                                .coerceIn(0f, 1f),
                        )
                    }
                }
        )
    }
}

/** 玻璃的采样层：把离屏的页面内容按 root 坐标对齐画回，并施加 RenderEffect 链。 */
@Composable
private fun GlassSamplingLayer(
    backdrop: GlassBackdrop,
    cornerRadius: Dp,
    margin: Dp,
    blur: Dp,
    saturation: Float,
    refraction: Dp,
    dispersion: Float,
    liquidProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var positionInRoot by remember { mutableStateOf(Offset.Zero) }

    val marginPx = with(density) { margin.toPx() }
    val refractionPx = with(density) { refraction.toPx() }
    // 效果链只在尺寸/形状/模糊变化时重建；动画只改 shader uniform，避免逐帧编译 AGSL。
    val handle = remember(sizePx, marginPx, cornerRadius, blur, saturation) {
        if (sizePx == IntSize.Zero) {
            null
        } else {
            val widthPx = sizePx.width.toFloat()
            val heightPx = sizePx.height.toFloat()
            // 采样节点比玻璃形状四周各外扩 marginPx，因此玻璃形状是居中且更小的那个矩形。
            val glassWidthPx = (widthPx - marginPx * 2f).coerceAtLeast(1f)
            val glassHeightPx = (heightPx - marginPx * 2f).coerceAtLeast(1f)
            buildLiquidGlassEffect(
                centerX = widthPx / 2f,
                centerY = heightPx / 2f,
                halfWidthPx = glassWidthPx / 2f,
                halfHeightPx = glassHeightPx / 2f,
                radiusPx = with(density) { cornerRadius.toPx() },
                blurPx = with(density) { blur.toPx() },
                saturation = saturation,
                refractionPx = refractionPx,
                dispersion = dispersion,
            )
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { sizePx = it }
            .onGloballyPositioned { coordinates ->
                positionInRoot = coordinates.positionInRoot()
            }
            // clip = true：把采样层的渲染范围限制在自身矩形内，
            // 既保证模糊/折射只处理玻璃附近的小块区域，也避免全屏重绘。
            .graphicsLayer {
                renderEffect = handle?.effect
                clip = true
                handle?.shader?.let { shader ->
                    val progress = liquidProgress()
                    // 交互时折射更强，玻璃“活”起来。
                    shader.setFloatUniform(
                        "refraction",
                        refractionPx * (1f + (LiquidRefractionBoost - 1f) * progress),
                    )
                    shader.setFloatUniform("dispersion", dispersion)
                    // 把取样范围限制在真正被录制到的页面区域内，杜绝取到空像素产生的描边杂色。
                    if (backdrop.sourceSizeInRoot != IntSize.Zero) {
                        val minX = backdrop.sourcePositionInRoot.x - positionInRoot.x
                        val minY = backdrop.sourcePositionInRoot.y - positionInRoot.y
                        shader.setFloatUniform("validMin", minX, minY)
                        shader.setFloatUniform(
                            "validMax",
                            minX + backdrop.sourceSizeInRoot.width,
                            minY + backdrop.sourceSizeInRoot.height,
                        )
                    }
                }
            }
            .drawWithContent {
                // effect 尚未就绪（首帧还没有测量结果）时不画，避免闪出一帧未模糊的清晰内容。
                if (handle?.effect != null) {
                    val translateX = backdrop.sourcePositionInRoot.x - positionInRoot.x
                    val translateY = backdrop.sourcePositionInRoot.y - positionInRoot.y
                    translate(translateX, translateY) {
                        drawLayer(backdrop.layer)
                    }
                }
            }
    )
}

/** 玻璃效果句柄：RenderEffect 与其内部的 AGSL 着色器（后者用于逐帧更新 uniform）。 */
private class GlassEffectHandle(
    val effect: ComposeRenderEffect?,
    val shader: RuntimeShader?,
)

/**
 * 组装玻璃的渲染效果链：模糊 → 饱和度 → 边缘折射（AGSL，API 33+）。
 * 任一环节不支持时自动降级到已支持的效果；全部不支持时返回空句柄（调用方直接不设 effect）。
 */
private fun buildLiquidGlassEffect(
    centerX: Float,
    centerY: Float,
    halfWidthPx: Float,
    halfHeightPx: Float,
    radiusPx: Float,
    blurPx: Float,
    saturation: Float,
    refractionPx: Float,
    dispersion: Float,
): GlassEffectHandle {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return GlassEffectHandle(effect = null, shader = null)
    }

    var effect: RenderEffect? = null
    if (blurPx > 0f) {
        effect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
    }
    if (saturation > 0f && saturation != 1f) {
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        val saturationEffect =
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
        effect = effect?.let { RenderEffect.createChainEffect(saturationEffect, it) }
            ?: saturationEffect
    }

    var shader: RuntimeShader? = null
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        refractionPx > 0f &&
        halfWidthPx > 0f &&
        halfHeightPx > 0f
    ) {
        shader = runCatching {
            RuntimeShader(LENS_AGSL).apply {
                setFloatUniform("center", centerX, centerY)
                setFloatUniform("halfSize", halfWidthPx, halfHeightPx)
                setFloatUniform(
                    "radius",
                    radiusPx.coerceAtMost(minOf(halfWidthPx, halfHeightPx)),
                )
                setFloatUniform("refraction", refractionPx)
                setFloatUniform("dispersion", dispersion)
                // 初始给一个极大的有效范围，等布局测量后再收敛到真实页面范围。
                setFloatUniform("validMin", -100000f, -100000f)
                setFloatUniform("validMax", 100000f, 100000f)
            }
        }.getOrNull()
        val lensEffect = shader?.let {
            runCatching { RenderEffect.createRuntimeShaderEffect(it, "content") }.getOrNull()
        }
        if (lensEffect != null) {
            effect = effect?.let { RenderEffect.createChainEffect(lensEffect, it) } ?: lensEffect
        } else {
            shader = null
        }
    }
    return GlassEffectHandle(effect = effect?.let { it.asComposeRenderEffect() }, shader = shader)
}

/**
 * 边缘折射着色器：按圆角矩形 SDF 计算到边缘的距离，用抛物线剖面把采样点朝玻璃外侧推
 * （边缘处位移为 0，中段最大），使玻璃边缘像真实厚玻璃一样“接住”外侧背景。
 * 采样点会被 clamp 进 [validMin, validMax]（被录制的页面范围），避免取到图层外的空像素。
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float2 center;
uniform float2 halfSize;
uniform float radius;
uniform float refraction;
uniform float dispersion;
uniform float2 validMin;
uniform float2 validMax;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 coord) {
    float2 p = coord - center;
    float d = sdRoundRect(p, halfSize, radius);
    // depth: 0 在边缘（含外侧），1 到达折射深度
    float depth = clamp(-d / max(refraction, 1.0), 0.0, 1.0);
    // 抛物线剖面：边缘处位移为 0（与玻璃外侧无缝衔接、不产生彩色描边），中段折射最强
    float amount = refraction * 4.0 * depth * (1.0 - depth);
    // 边缘法线：对 SDF 做有限差分，避免依赖导数指令
    float gx = sdRoundRect(p + float2(1.0, 0.0), halfSize, radius) - d;
    float gy = sdRoundRect(p + float2(0.0, 1.0), halfSize, radius) - d;
    float2 dir = normalize(float2(gx, gy) + float2(0.0001, 0.0001));
    float2 base = clamp(coord + dir * amount, validMin, validMax);
    float2 spread = dir * (amount * dispersion);
    half4 centerSample = content.eval(base);
    half4 warmSample = content.eval(clamp(base + spread, validMin, validMax));
    half4 coolSample = content.eval(clamp(base - spread, validMin, validMax));
    return half4(warmSample.r, centerSample.g, coolSample.b, centerSample.a);
}
"""
