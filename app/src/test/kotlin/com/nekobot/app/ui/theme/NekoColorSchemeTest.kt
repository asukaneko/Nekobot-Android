package com.nekobot.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 颜色令牌回归：Material3 1.4 起 `surfaceVariant` 属于已废弃的 surface 角色，
 * 若配色表没有真正写入，它会退化为 `Color.Unspecified`（绘制时等于透明），
 * 表现为「卡片/图标底座全都看不见」。这里把默认配色固定住，避免再次退化。
 */
class NekoColorSchemeTest {

    private val probe = Color(0xFFF2F2F2)

    @Test
    fun lightColorSchemeBuilder_keepsSurfaceVariant() {
        val scheme = lightColorScheme(surfaceVariant = probe)
        assertEquals(probe, scheme.surfaceVariant)
        assertNotEquals(Color.Unspecified, scheme.surfaceVariant)
    }

    @Test
    fun darkColorSchemeBuilder_keepsSurfaceVariant() {
        val scheme = darkColorScheme(surfaceVariant = probe)
        assertEquals(probe, scheme.surfaceVariant)
        assertNotEquals(Color.Unspecified, scheme.surfaceVariant)
    }

    @Test
    fun appSchemesExposeVisibleSurfaces() {
        // 浅色：背景与表面、表面与 surfaceVariant 都必须可区分且不透明
        assertEquals(NekoSurfaceTintLight, LightColors.surfaceVariant)
        assertNotEquals(Color.Unspecified, LightColors.surfaceVariant)
        assertEquals(NekoLineLight, LightColors.outline)
        assertEquals(NekoBgLight, LightColors.background)
        assertEquals(NekoSurfaceLight, LightColors.surface)

        assertEquals(NekoSurfaceTintDark, DarkColors.surfaceVariant)
        assertNotEquals(Color.Unspecified, DarkColors.surfaceVariant)
        assertEquals(NekoBgDark, DarkColors.background)
        assertEquals(NekoSurfaceDark, DarkColors.surface)
    }
}
