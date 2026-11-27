package com.earshot.app.ui.theme

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/*
 * Glow's tint: the artwork's main colour, darkened until white text on it has at least 5:1
 * contrast. Same rule as the web player (web/src/appearance.ts).
 */

private val cache = ConcurrentHashMap<String, Color>()

/** A stable colour made from text, used until the artwork loads or when it can't be read. */
fun fallbackTint(key: String): Color {
    var h = 0
    for (c in key) h = h * 31 + c.code
    val hue = ((h % 360) + 360) % 360
    return darkenForWhiteText(Color(AndroidColor.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.42f))))
}

@Composable
fun rememberArtColor(url: String?, fallbackKey: String = url ?: "earshot"): Color {
    val context = LocalContext.current
    val key = url ?: fallbackKey
    val color by produceState(cache[key] ?: fallbackTint(key), key) {
        cache[key]?.let {
            value = it
            return@produceState
        }
        value = fallbackTint(key)
        if (url != null) {
            artColor(context, url)?.let {
                cache[key] = it
                value = it
            }
        }
    }
    return color
}

private suspend fun artColor(context: Context, url: String): Color? = try {
    val request = ImageRequest.Builder(context).data(url).size(24, 24).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request)
    if (result is SuccessResult) {
        withContext(Dispatchers.Default) { average(result.image.toBitmap(24, 24)) }
    } else {
        null
    }
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    null
}

/** Averages the pixels, weighting colourful ones more so grey borders and white text don't wash it out. */
private fun average(bitmap: android.graphics.Bitmap): Color {
    val px = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var w = 0.0
    for (p in px) {
        val pr = AndroidColor.red(p)
        val pg = AndroidColor.green(p)
        val pb = AndroidColor.blue(p)
        val weight = 1.0 + (maxOf(pr, pg, pb) - minOf(pr, pg, pb)) / 16.0
        r += pr * weight
        g += pg * weight
        b += pb * weight
        w += weight
    }
    return darkenForWhiteText(Color((r / w).toFloat() / 255f, (g / w).toFloat() / 255f, (b / w).toFloat() / 255f))
}

fun darkenForWhiteText(c: Color): Color {
    var out = c
    var i = 0
    while (i < 30 && 1.05f / (out.luminance() + 0.05f) < 5f) {
        out = Color(out.red * 0.88f, out.green * 0.88f, out.blue * 0.88f)
        i++
    }
    return out
}
