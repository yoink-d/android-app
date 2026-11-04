package com.maynkudu.yoinkd.core.design

import com.maynkudu.yoinkd.R
import com.maynkudu.yoinkd.core.model.AccentTheme
import com.maynkudu.yoinkd.core.model.Appearance

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

val textFont = FontFamily(
    Font(R.font.stack_sans_text_regular),
    Font(R.font.stack_sans_text_medium, FontWeight.Medium),
    Font(R.font.stack_sans_text_semibold, FontWeight.SemiBold),
    Font(R.font.stack_sans_text_bold, FontWeight.Bold),
)
val headingFont = FontFamily(
    Font(R.font.stack_sans_notch_regular),
    Font(R.font.stack_sans_notch_medium, FontWeight.Medium),
    Font(R.font.stack_sans_notch_semibold, FontWeight.SemiBold),
    Font(R.font.stack_sans_notch_bold, FontWeight.Bold),
)

data class ThemeColors(
    val background: Color,
    val surface: Color,
    val control: Color,
    val text: Color,
    val secondaryText: Color,
    val separator: Color,
    val accent: Color,
    val onAccent: Color,
)

fun themeColors(hue: Double, saturation: Double, dark: Boolean): ThemeColors {
    val sat = saturation.coerceIn(0.0, 0.55)
    val background = hsl(hue, sat * 0.40, if (dark) 0.035 else 0.95)
    val surface = hsl(hue, sat * if (dark) 0.22 else 0.35, if (dark) 0.07 else 0.98)
    val control = hsl(hue, sat * if (dark) 0.20 else 0.36, if (dark) 0.11 else 0.92)
    val backgrounds = listOf(background, surface, control, hsl(0.0, 0.0, if (dark) 0.16 else 0.94))
    fun readable(lightness: Double, saturation: Double): Color {
        var light = lightness
        var color = hsl(hue, saturation, light)
        while (backgrounds.any { contrast(color, it) < 4.5 } && if (dark) light < 1 else light > 0) {
            light = (light + if (dark) 0.01 else -0.01).coerceIn(0.0, 1.0)
            color = hsl(hue, saturation, light)
        }
        return color
    }
    val accent = readable(if (dark) 0.68 else 0.38, sat)
    return ThemeColors(
        background, surface, control,
        readable(if (dark) 0.94 else 0.14, sat * 0.20),
        readable(if (dark) 0.68 else 0.40, sat * 0.16),
        hsl(hue, sat * 0.20, if (dark) 0.22 else 0.80), accent,
        if (contrast(accent, Color.White) >= 4.5) Color.White else Color.Black,
    )
}

internal fun hsl(hue: Double, saturation: Double, lightness: Double): Color {
    val sector = ((hue % 1 + 1) % 1) * 6
    val chroma = (1 - abs(2 * lightness - 1)) * saturation
    val secondary = chroma * (1 - abs(sector % 2 - 1))
    val offset = lightness - chroma / 2
    val channels = when {
        sector < 1 -> Triple(chroma, secondary, 0.0)
        sector < 2 -> Triple(secondary, chroma, 0.0)
        sector < 3 -> Triple(0.0, chroma, secondary)
        sector < 4 -> Triple(0.0, secondary, chroma)
        sector < 5 -> Triple(secondary, 0.0, chroma)
        else -> Triple(chroma, 0.0, secondary)
    }
    return Color((channels.first + offset).toFloat(), (channels.second + offset).toFloat(), (channels.third + offset).toFloat())
}

internal fun luminance(color: Color): Double {
    fun linear(channel: Float): Double = if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
}

fun contrast(first: Color, second: Color): Double {
    val a = luminance(first)
    val b = luminance(second)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

@Composable
fun YoinkTheme(
    appearance: Appearance = Appearance.SYSTEM,
    theme: AccentTheme = AccentTheme.SAGE,
    artwork: ArtworkSeed = ArtworkSeed.Neutral,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val seed = if (theme == AccentTheme.DYNAMIC) artwork else ArtworkSeed(theme.hue, theme.saturation)
    val colors = themeColors(seed.hue, seed.saturation, dark)
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = scheme.copy(
            primary = colors.accent, onPrimary = colors.onAccent,
            background = colors.background, onBackground = colors.text,
            surface = colors.surface, onSurface = colors.text,
            surfaceVariant = colors.control, onSurfaceVariant = colors.secondaryText,
            outline = colors.separator, outlineVariant = colors.separator,
            primaryContainer = colors.control, onPrimaryContainer = colors.text,
            secondary = colors.accent, onSecondary = colors.onAccent,
            tertiary = colors.accent, onTertiary = colors.onAccent,
            tertiaryContainer = colors.control, onTertiaryContainer = colors.text,
            secondaryContainer = colors.control,
            onSecondaryContainer = colors.accent, surfaceContainer = colors.surface,
            surfaceContainerHigh = colors.surface, surfaceContainerHighest = colors.control,
            surfaceContainerLow = colors.surface, surfaceContainerLowest = colors.background,
            surfaceDim = colors.background, surfaceBright = colors.surface, surfaceTint = colors.accent,
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = headingFont, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
            headlineSmall = TextStyle(fontFamily = headingFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleLarge = TextStyle(fontFamily = headingFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
            titleMedium = TextStyle(fontFamily = textFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontFamily = textFont, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = textFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontFamily = textFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
            labelLarge = TextStyle(fontFamily = textFont, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = textFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
            labelSmall = TextStyle(fontFamily = textFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
        ), content = content,
    )
}
