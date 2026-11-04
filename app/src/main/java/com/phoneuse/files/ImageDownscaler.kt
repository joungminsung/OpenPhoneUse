package com.phoneuse.files

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Size math for attached images: they are sent with the long edge at most [MAX_EDGE] px, as JPEG
 * [JPEG_QUALITY]%. Pure, so the Android decoding around it stays thin.
 */
object ImageDownscaler {
    const val MAX_EDGE = 1280
    const val JPEG_QUALITY = 80

    /**
     * The size that fits [width]×[height] into a [maxEdge] square, keeping the aspect ratio. Smaller images keep
     * their size unless [upscale] (a PDF page given in points). Each side is at least 1.
     */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE, upscale: Boolean = false): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= 0) return 1 to 1
        if (longest <= maxEdge && !upscale) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val f = maxEdge.toDouble() / longest
        return (width * f).roundToInt().coerceIn(1, maxEdge) to (height * f).roundToInt().coerceIn(1, maxEdge)
    }

    /** The largest power-of-two decode sample size whose result still has a long edge of at least [minLongEdge]. */
    fun sampleSize(width: Int, height: Int, minLongEdge: Int): Int {
        val longest = max(width, height)
        var sample = 1
        while (longest / (sample * 2) >= minLongEdge) sample *= 2
        return sample
    }
}
