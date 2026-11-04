package com.phoneuse.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.phoneuse.agent.MarkLayout
import com.phoneuse.agent.ScreenMark

/** Draws Set-of-Mark labels (see [MarkLayout]) onto a screenshot, in place. */
object MarkPainter {
    /** Outline and tag colours, cycled by element number so neighbours differ. */
    private val COLORS = intArrayOf(
        Color.rgb(230, 25, 75), Color.rgb(0, 130, 200), Color.rgb(60, 160, 60), Color.rgb(245, 130, 48),
        Color.rgb(145, 30, 180), Color.rgb(0, 128, 128), Color.rgb(170, 110, 40), Color.rgb(128, 0, 0),
    )

    /** [scale]: bitmap pixels per display pixel (the shot may have been scaled down). */
    fun paint(bitmap: Bitmap, marks: List<ScreenMark>, scale: Float) {
        if (!bitmap.isMutable) return
        val canvas = Canvas(bitmap)
        val textSize = MarkLayout.textSize(bitmap.width, bitmap.height)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            isFakeBoldText = true
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1.5f, textSize / 9)
        }
        val fill = Paint().apply { style = Paint.Style.FILL }
        val tags = MarkLayout.place(marks, scale, bitmap.width, bitmap.height) { text.measureText(it) }
        for (t in tags) {
            val c = COLORS[t.index % COLORS.size]
            outline.color = c
            canvas.drawRect(RectF(t.box.left, t.box.top, t.box.right, t.box.bottom), outline)
        }
        // Tags after all outlines, so no outline crosses a number.
        for (t in tags) {
            fill.color = COLORS[t.index % COLORS.size]
            canvas.drawRect(RectF(t.tag.left, t.tag.top, t.tag.right, t.tag.bottom), fill)
            canvas.drawText(t.label, t.tag.left + t.pad, t.tag.bottom - t.pad - text.descent() / 2, text)
        }
    }
}
