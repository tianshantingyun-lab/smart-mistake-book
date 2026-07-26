package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

val Paper = SmartColors.Paper
val Ink = SmartColors.Ink
val InkSecondary = SmartColors.InkSecondary
val InkMuted = SmartColors.InkMuted
val Jade = SmartColors.Jade
val JadeDark = SmartColors.JadeDark
val JadeActive = SmartColors.JadeActive
val JadeSoft = SmartColors.JadeSoft
val JadeMuted = SmartColors.JadeMuted
val Divider = SmartColors.Divider
val Outline = SmartColors.Outline
val Track = SmartColors.Track
val ErrorWarm = SmartColors.ErrorWarm
val OnJade = SmartColors.OnJade

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
    primary = Jade,
    onPrimary = OnJade,
    primaryContainer = JadeSoft,
    onPrimaryContainer = Ink,
    secondary = JadeDark,
    onSecondary = OnJade,
    secondaryContainer = JadeSoft,
    onSecondaryContainer = Ink,
    tertiary = JadeMuted,
    onTertiary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = JadeSoft,
    onSurfaceVariant = InkSecondary,
    error = ErrorWarm,
    onError = OnJade,
    outline = Outline,
    outlineVariant = Divider,
    scrim = Ink,
)

@Composable
fun SmartMistakeBookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SmartLightColorScheme,
        typography = SmartTypography,
        shapes = SmartShapes,
        content = content,
    )
}
