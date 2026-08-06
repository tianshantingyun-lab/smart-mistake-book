package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The single, paper-like palette used by the visual references. */
object SmartColors {
    val Paper = Color(0xFFFBF7F5)
    val Ink = Color(0xFF17272E)
    val InkSecondary = Color(0xFF4D5E69)
    val InkMuted = Color(0xFF87919B)
    val Jade = Color(0xFF23704A)
    val JadeDark = Color(0xFF286647)
    val JadeActive = Color(0xFF087046)
    val JadeSoft = Color(0xFFDDE7E0)
    val JadeMuted = Color(0xFF83B39A)
    val Divider = Color(0xFFD8DAD8)
    val Outline = Color(0xFFC8CED1)
    val Track = Color(0xFFE2E0DE)
    val ErrorWarm = Color(0xFFE36F27)
    val OnJade = Color(0xFFFFFDFC)
}

object SmartDarkColors {
    val Paper = Color(0xFF111B1D)
    val Ink = Color(0xFFEAF2EE)
    val InkSecondary = Color(0xFFB6C5C0)
    val InkMuted = Color(0xFF8A9994)
    val Jade = Color(0xFF82D1A8)
    val JadeDark = Color(0xFF66B78F)
    val JadeActive = Color(0xFF95E1BC)
    val JadeSoft = Color(0xFF1D3B30)
    val JadeMuted = Color(0xFF5FA181)
    val Divider = Color(0xFF35433E)
    val Outline = Color(0xFF475852)
    val Track = Color(0xFF2A3632)
    val ErrorWarm = Color(0xFFF2AC71)
    val OnJade = Color(0xFF102018)
}

internal data class SmartPalette(
    val paper: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val jade: Color,
    val jadeDark: Color,
    val jadeActive: Color,
    val jadeSoft: Color,
    val jadeMuted: Color,
    val divider: Color,
    val outline: Color,
    val track: Color,
    val errorWarm: Color,
    val onJade: Color,
)

private val LightSmartPalette =
    SmartPalette(
        paper = SmartColors.Paper,
        ink = SmartColors.Ink,
        inkSecondary = SmartColors.InkSecondary,
        inkMuted = SmartColors.InkMuted,
        jade = SmartColors.Jade,
        jadeDark = SmartColors.JadeDark,
        jadeActive = SmartColors.JadeActive,
        jadeSoft = SmartColors.JadeSoft,
        jadeMuted = SmartColors.JadeMuted,
        divider = SmartColors.Divider,
        outline = SmartColors.Outline,
        track = SmartColors.Track,
        errorWarm = SmartColors.ErrorWarm,
        onJade = SmartColors.OnJade,
    )

private val DarkSmartPalette =
    SmartPalette(
        paper = SmartDarkColors.Paper,
        ink = SmartDarkColors.Ink,
        inkSecondary = SmartDarkColors.InkSecondary,
        inkMuted = SmartDarkColors.InkMuted,
        jade = SmartDarkColors.Jade,
        jadeDark = SmartDarkColors.JadeDark,
        jadeActive = SmartDarkColors.JadeActive,
        jadeSoft = SmartDarkColors.JadeSoft,
        jadeMuted = SmartDarkColors.JadeMuted,
        divider = SmartDarkColors.Divider,
        outline = SmartDarkColors.Outline,
        track = SmartDarkColors.Track,
        errorWarm = SmartDarkColors.ErrorWarm,
        onJade = SmartDarkColors.OnJade,
    )

private val LocalSmartPalette = staticCompositionLocalOf { LightSmartPalette }

val Paper: Color
    @Composable get() = LocalSmartPalette.current.paper
val Ink: Color
    @Composable get() = LocalSmartPalette.current.ink
val InkSecondary: Color
    @Composable get() = LocalSmartPalette.current.inkSecondary
val InkMuted: Color
    @Composable get() = LocalSmartPalette.current.inkMuted
val Jade: Color
    @Composable get() = LocalSmartPalette.current.jade
val JadeDark: Color
    @Composable get() = LocalSmartPalette.current.jadeDark
val JadeActive: Color
    @Composable get() = LocalSmartPalette.current.jadeActive
val JadeSoft: Color
    @Composable get() = LocalSmartPalette.current.jadeSoft
val JadeMuted: Color
    @Composable get() = LocalSmartPalette.current.jadeMuted
val Divider: Color
    @Composable get() = LocalSmartPalette.current.divider
val Outline: Color
    @Composable get() = LocalSmartPalette.current.outline
val Track: Color
    @Composable get() = LocalSmartPalette.current.track
val ErrorWarm: Color
    @Composable get() = LocalSmartPalette.current.errorWarm
val OnJade: Color
    @Composable get() = LocalSmartPalette.current.onJade

object SmartDimens {
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val SpacingScale = listOf(Space8, Space12, Space16, Space24)

    val PhoneContentHorizontalPadding = 16.dp
    val LargeScreenContentHorizontalPadding = 24.dp
    val LargeScreenMinimumWidth = 600.dp

    // Kept as the phone default for screens that do not use the responsive root containers.
    val ContentHorizontalPadding = PhoneContentHorizontalPadding

    val PrimaryControlHeight = 52.dp
    val MinimumTouchTarget = 48.dp
    val SmallIconSize = 22.dp
    val IconSize = 24.dp
    val ComposerHeight = 52.dp
    val BottomBarHeight = 64.dp
    val CallToActionRadius = 10.dp
    val SurfaceRadius = 8.dp
    val ChipRadius = 6.dp
    val MaximumContentWidth = 600.dp

    fun contentHorizontalPadding(availableWidth: Dp): Dp =
        if (availableWidth >= LargeScreenMinimumWidth) {
            LargeScreenContentHorizontalPadding
        } else {
            PhoneContentHorizontalPadding
        }
}

val SmartTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
)

val SmartShapes = Shapes(
    extraSmall = RoundedCornerShape(SmartDimens.ChipRadius),
    small = RoundedCornerShape(SmartDimens.SurfaceRadius),
    medium = RoundedCornerShape(SmartDimens.SurfaceRadius),
    large = RoundedCornerShape(SmartDimens.CallToActionRadius),
    extraLarge = RoundedCornerShape(16.dp),
)

private val SmartLightColorScheme = lightColorScheme(
    primary = SmartColors.Jade,
    onPrimary = SmartColors.OnJade,
    primaryContainer = SmartColors.JadeSoft,
    onPrimaryContainer = SmartColors.Ink,
    secondary = SmartColors.JadeDark,
    onSecondary = SmartColors.OnJade,
    secondaryContainer = SmartColors.JadeSoft,
    onSecondaryContainer = SmartColors.Ink,
    tertiary = SmartColors.JadeMuted,
    onTertiary = SmartColors.Ink,
    background = SmartColors.Paper,
    onBackground = SmartColors.Ink,
    surface = SmartColors.Paper,
    onSurface = SmartColors.Ink,
    surfaceVariant = SmartColors.JadeSoft,
    onSurfaceVariant = SmartColors.InkSecondary,
    error = SmartColors.ErrorWarm,
    onError = SmartColors.OnJade,
    outline = SmartColors.Outline,
    outlineVariant = SmartColors.Divider,
    scrim = SmartColors.Ink,
)

private val SmartDarkColorScheme =
    darkColorScheme(
        primary = SmartDarkColors.Jade,
        onPrimary = SmartDarkColors.OnJade,
        primaryContainer = SmartDarkColors.JadeSoft,
        onPrimaryContainer = SmartDarkColors.Ink,
        secondary = SmartDarkColors.JadeDark,
        onSecondary = SmartDarkColors.OnJade,
        secondaryContainer = SmartDarkColors.JadeSoft,
        onSecondaryContainer = SmartDarkColors.Ink,
        tertiary = SmartDarkColors.JadeMuted,
        onTertiary = SmartDarkColors.Ink,
        background = SmartDarkColors.Paper,
        onBackground = SmartDarkColors.Ink,
        surface = SmartDarkColors.Paper,
        onSurface = SmartDarkColors.Ink,
        surfaceVariant = SmartDarkColors.JadeSoft,
        onSurfaceVariant = SmartDarkColors.InkSecondary,
        error = SmartDarkColors.ErrorWarm,
        onError = SmartDarkColors.OnJade,
        outline = SmartDarkColors.Outline,
        outlineVariant = SmartDarkColors.Divider,
        scrim = SmartDarkColors.Ink,
    )

@Composable
fun SmartMistakeBookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkSmartPalette else LightSmartPalette
    val colorScheme = if (darkTheme) SmartDarkColorScheme else SmartLightColorScheme
    CompositionLocalProvider(LocalSmartPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SmartTypography,
            shapes = SmartShapes,
            content = content,
        )
    }
}
