package com.phoneuse.agent

/**
 * Where the Set-of-Mark labels of a marked screenshot go: an outline around each element and a small numbered tag
 * at one of its corners, the first corner whose tag covers no tag already placed. Pure geometry (no Android), so it
 * is tested on the JVM; [com.phoneuse.service.MarkPainter] draws it.
 */
object MarkLayout {
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlaps(o: Box): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    }

    class Tag(val index: Int, val label: String, val box: Box, val tag: Box, val pad: Float)

    /** Tag text height for a [width] x [height] image: readable at the sizes models look at (about 1/60 of the long edge). */
    fun textSize(width: Int, height: Int): Float = (maxOf(width, height) / 60f).coerceIn(11f, 28f)

    /** [scale]: image pixels per display pixel; [measure] the drawn width of a label. */
    fun place(marks: List<ScreenMark>, scale: Float, width: Int, height: Int, measure: (String) -> Float): List<Tag> {
        val size = textSize(width, height)
        val pad = size / 6
        val tagH = size + 2 * pad
        val placed = ArrayList<Tag>(marks.size)
        for (m in marks) {
            val b = m.bounds
            val box = Box(
                (b.left * scale).coerceIn(0f, width.toFloat()), (b.top * scale).coerceIn(0f, height.toFloat()),
                (b.right * scale).coerceIn(0f, width.toFloat()), (b.bottom * scale).coerceIn(0f, height.toFloat()),
            )
            if (box.right - box.left < 1f || box.bottom - box.top < 1f) continue
            val label = m.index.toString()
            val tagW = measure(label) + 2 * pad
            val corners = listOf(
                box.left to box.top, box.right - tagW to box.top,
                box.left to box.bottom - tagH, box.right - tagW to box.bottom - tagH,
                box.left to box.top - tagH,
            ).map { (x, y) ->
                val l = x.coerceIn(0f, maxOf(0f, width - tagW))
                val t = y.coerceIn(0f, maxOf(0f, height - tagH))
                Box(l, t, l + tagW, t + tagH)
            }
            val tag = corners.firstOrNull { c -> placed.none { it.tag.overlaps(c) } } ?: corners.first()
            placed += Tag(m.index, label, box, tag, pad)
        }
        return placed
    }
}
