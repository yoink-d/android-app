package com.maynkudu.yoinkd.core.design

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ArtworkSeed(val hue: Double, val saturation: Double) {
    companion object { val Neutral = ArtworkSeed(0.0, 0.0) }
}

private data class Cluster(var red: Double = 0.0, var green: Double = 0.0,
    var blue: Double = 0.0, var count: Int = 0)

private fun rgbHsl(red: Double, green: Double, blue: Double): Triple<Double, Double, Double> {
    val high = max(red, max(green, blue))
    val low = min(red, min(green, blue))
    val delta = high - low
    val light = (high + low) / 2
    if (delta == 0.0) return Triple(0.0, 0.0, light)
    val saturation = delta / (1 - abs(2 * light - 1))
    val sector = when (high) {
        red -> (green - blue) / delta + if (green < blue) 6 else 0
        green -> (blue - red) / delta + 2
        else -> (red - green) / delta + 4
    }
    return Triple(sector / 6, saturation, light)
}

// Android returns straight ARGB pixels. The clustering thresholds come from YoinksCore.
fun extractArtworkSeed(pixels: IntArray): ArtworkSeed {
    val clusters = sortedMapOf<Int, Cluster>()
    var visible = 0
    var useful = 0
    for (pixel in pixels) {
        if ((pixel ushr 24) / 255.0 < 0.75) continue
        visible++
        val red = (pixel ushr 16 and 255) / 255.0
        val green = (pixel ushr 8 and 255) / 255.0
        val blue = (pixel and 255) / 255.0
        val hsl = rgbHsl(red, green, blue)
        val light = luminance(Color(red.toFloat(), green.toFloat(), blue.toFloat()))
        if (hsl.second < 0.14 || hsl.third !in 0.12..0.88 || light !in 0.025..0.85) continue
        useful++
        val key = ((red * 15).toInt() shl 8) or ((green * 15).toInt() shl 4) or (blue * 15).toInt()
        clusters.getOrPut(key) { Cluster() }.apply {
            this.red += red; this.green += green; this.blue += blue; count++
        }
    }
    if (visible == 0 || useful.toDouble() / visible < 0.025) return ArtworkSeed.Neutral
    val minimum = max(2, (visible * 0.004).toInt())
    val selected = clusters.values.filter { it.count >= minimum }.map {
        val hsl = rgbHsl(it.red / it.count, it.green / it.count, it.blue / it.count)
        hsl to sqrt(it.count.toDouble()) * (1 - abs(hsl.second - 0.55) * 0.6) * (1 - abs(hsl.third - 0.5))
    }.maxByOrNull { it.second }?.first ?: return ArtworkSeed.Neutral
    return ArtworkSeed(selected.first, selected.second.coerceIn(0.26, 0.55))
}
