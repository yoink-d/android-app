package com.maynkudu.yoinkd.core

import androidx.compose.ui.graphics.Color
import com.maynkudu.yoinkd.core.design.ArtworkSeed
import com.maynkudu.yoinkd.core.design.contrast
import com.maynkudu.yoinkd.core.design.extractArtworkSeed
import com.maynkudu.yoinkd.core.design.themeColors
import com.maynkudu.yoinkd.core.model.AccentTheme
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PaletteTest {
    @Test fun fixedThemeSeedsMatchThePinnedIosSource() {
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { File(it, "reference/ios").isDirectory }
        val source = File(root, "reference/ios/Packages/YoinksCore/Sources/YoinksCore/AccentTheme.swift").readText()
        for (theme in AccentTheme.entries.filter { it != AccentTheme.NEUTRAL && it != AccentTheme.DYNAMIC }) {
            val values = Regex("case \\.${theme.name.lowercase()}: AccentPalette\\(hue: ([0-9.]+), saturation: ([0-9.]+)\\)")
                .find(source)!!.groupValues
            assertEquals(values[1].toDouble(), theme.hue, 0.0)
            assertEquals(values[2].toDouble(), theme.saturation, 0.0)
        }
    }

    @Test fun textAndControlsMeetTheOriginalContrastThreshold() {
        for (dark in listOf(false, true)) for (theme in AccentTheme.entries) {
            val colors = themeColors(theme.hue, theme.saturation, dark)
            for (background in listOf(colors.background, colors.surface, colors.control,
                Color(if (dark) 0.16f else 0.94f, if (dark) 0.16f else 0.94f, if (dark) 0.16f else 0.94f))) {
                for (text in listOf(colors.text, colors.secondaryText, colors.accent))
                    assertTrue("${theme.name}, dark=$dark", contrast(text, background) >= 4.5)
            }
            assertTrue(contrast(colors.accent, colors.onAccent) >= 4.5)
        }
    }

    @Test fun neutralArtworkFallsBackAndDarkPixelsDoNotDominate() {
        assertEquals(ArtworkSeed.Neutral, extractArtworkSeed(IntArray(100) { 0xffffffff.toInt() }))
        assertEquals(ArtworkSeed.Neutral, extractArtworkSeed(IntArray(100) { 0x00808080 }))
        val pixels = IntArray(200) { if (it < 10) 0xff0000ff.toInt() else 0xff000000.toInt() }
        val seed = extractArtworkSeed(pixels)
        assertEquals(2.0 / 3, seed.hue, 0.001)
        assertEquals(0.55, seed.saturation, 0.001)
    }
}
