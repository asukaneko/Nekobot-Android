package app.lianxu.independent

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object UiSpace {
    val page=20.dp;val small=8.dp;val cluster=4.dp;val group=20.dp
    val action=48.dp;val radius=20.dp;val bubble=14.dp
}
internal val BrandTypeface=FontFamily(Font(R.font.lianxu_brand))
internal val BrandStyle=TextStyle(fontFamily=BrandTypeface,fontSize=30.sp,lineHeight=43.sp)
internal val PageTitleStyle=TextStyle(fontFamily=BrandTypeface,fontSize=24.sp,lineHeight=34.sp)
private val LightColors=lightColorScheme(
    primary=Color(0xFF6B5847),onPrimary=Color.White,
    primaryContainer=Color(0xFFE7DED4),onPrimaryContainer=Color(0xFF3C3027),
    secondary=Color(0xFF74665B),onSecondary=Color.White,
    secondaryContainer=Color(0xFFECE3D8),onSecondaryContainer=Color(0xFF514235),
    tertiary=Color(0xFF806B61),onTertiary=Color.White,
    tertiaryContainer=Color(0xFFF0E4DE),onTertiaryContainer=Color(0xFF47352D),
    background=Color(0xFFFAF8F6),onBackground=Color(0xFF28231F),
    surface=Color(0xFFFFFCFA),onSurface=Color(0xFF28231F),onSurfaceVariant=Color(0xFF756B62),
    surfaceContainerLowest=Color(0xFFFFFCFA),surfaceContainerLow=Color(0xFFF6F2EE),surfaceContainer=Color(0xFFF0EBE5),
    surfaceContainerHigh=Color(0xFFE9E2DA),surfaceContainerHighest=Color(0xFFDFD6CC),
    outline=Color(0xFF958A7F),outlineVariant=Color(0xFFE5DED7),surfaceTint=Color.Transparent
)
private val DarkColors=darkColorScheme(
    primary=Color(0xFFD0B9A3),onPrimary=Color(0xFF392B21),
    primaryContainer=Color(0xFF483C32),onPrimaryContainer=Color(0xFFF0E2D5),
    secondary=Color(0xFFCCBBAA),onSecondary=Color(0xFF352C25),
    secondaryContainer=Color(0xFF453C34),onSecondaryContainer=Color(0xFFE9DCD0),
    tertiary=Color(0xFFD1BBB2),onTertiary=Color(0xFF3B2B26),
    tertiaryContainer=Color(0xFF483931),onTertiaryContainer=Color(0xFFEEDDD5),
    background=Color(0xFF1C1A18),onBackground=Color(0xFFECE4DE),
    surface=Color(0xFF24211E),onSurface=Color(0xFFECE4DE),onSurfaceVariant=Color(0xFFC1B5AA),
    surfaceContainerLowest=Color(0xFF191715),surfaceContainerLow=Color(0xFF1F1D1B),surfaceContainer=Color(0xFF2E2925),
    surfaceContainerHigh=Color(0xFF38312B),surfaceContainerHighest=Color(0xFF433A33),
    outline=Color(0xFFA4978A),outlineVariant=Color(0xFF4C433B),surfaceTint=Color.Transparent
)
@Composable internal fun LianxuTheme(dark:Boolean=isSystemInDarkTheme(),content:@Composable ()->Unit){
    MaterialTheme(colorScheme=if(dark)DarkColors else LightColors,typography=Typography(
        titleLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.Medium),
        titleMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium),
        titleSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium),
        bodyLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=25.sp),
        bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=22.sp),
        bodySmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=12.sp,lineHeight=18.sp),
        labelLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium),
        labelMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=12.sp,lineHeight=18.sp),
        labelSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=11.sp,lineHeight=16.sp)
    ),content=content)
}
internal val Paper:Color @Composable get()=MaterialTheme.colorScheme.surface
internal val Ink:Color @Composable get()=MaterialTheme.colorScheme.onSurface
internal val Accent:Color @Composable get()=MaterialTheme.colorScheme.secondaryContainer
internal val GreenText:Color @Composable get()=MaterialTheme.colorScheme.primary
/** 角色资料列表图标：像微信那样各用一种柔和的颜色，不用绿色。 */
internal object ProfileIconColors {
    val edit=Color(0xFF5B8DEF)
    val memory=Color(0xFFF0A04B)
    val learning=Color(0xFF9A7FE3)
    val relationship=Color(0xFFEE6F6F)
    val companion=Color(0xFFF08DB5)
}
internal val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
internal val Line:Color @Composable get()=MaterialTheme.colorScheme.outlineVariant
internal val Outline:Color @Composable get()=MaterialTheme.colorScheme.outline
internal val Chrome:Color @Composable get()=MaterialTheme.colorScheme.surfaceContainerLow
internal val ChatBackground:Color @Composable get()=MaterialTheme.colorScheme.background
internal val SentBubble:Color @Composable get()=MaterialTheme.colorScheme.primaryContainer
internal val SelectedBackground:Color @Composable get()=MaterialTheme.colorScheme.secondaryContainer
internal val ChatPageBackground:Color @Composable get()=MaterialTheme.colorScheme.surfaceContainer
internal val ChatBarBackground:Color @Composable get()=Chrome
internal val ChatUserBubble:Color @Composable get()=SentBubble
internal val ChatUserText:Color @Composable get()=MaterialTheme.colorScheme.onPrimaryContainer
internal val ChatRoleBubble:Color @Composable get()=Paper
internal val ChatRoleText:Color @Composable get()=Ink
internal val ChatSendButton:Color @Composable get()=MaterialTheme.colorScheme.primary
internal val ChatInputBorder:Color @Composable get()=Outline
@Composable internal fun freshButtonColors()=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=MaterialTheme.colorScheme.onSecondaryContainer)
