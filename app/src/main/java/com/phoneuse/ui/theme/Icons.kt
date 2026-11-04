package com.phoneuse.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons copied from the design boards' SVGs (24×24 viewBox, round caps). Tint them with `Icon(tint = …)`. */
object PuIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
        "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}" +
            "h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}Z"

    private fun stroke(name: String, width: Float, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Menu by lazy { stroke("menu", 2f, "M4 8h16M4 16h10") }
    val Plus by lazy { stroke("plus", 2f, "M12 5v14M5 12h14") }
    val ChevronDown by lazy { stroke("down", 2.2f, "m6 9 6 6 6-6") }
    val ChevronUp by lazy { stroke("up", 2f, "m6 15 6-6 6 6") }
    val ChevronRight by lazy { stroke("right", 2f, "m9 6 6 6-6 6") }
    val Back by lazy { stroke("back", 2.2f, "m15 5-7 7 7 7") }
    val Check by lazy { stroke("check", 2.2f, "m5 12 5 5 9-10") }
    val Alert by lazy { stroke("alert", 2.2f, "M12 7v6M12 17h.01") }
    val Mic by lazy { stroke("mic", 2f, rect(9f, 3f, 6f, 11f, 3f), "M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21") }
    val Send by lazy { stroke("send", 2.4f, "M12 19V5M5.5 11.5 12 5l6.5 6.5") }
    val Step by lazy { stroke("step", 2f, "M5 12h14M13 6l6 6-6 6") }
    val Search by lazy { stroke("search", 2f, circle(11f, 11f, 6f), "m20 20-4-4") }
    val Pin by lazy {
        stroke("pin", 2.2f, "M12 21s-6.5-6.2-6.5-11a6.5 6.5 0 0 1 13 0c0 4.8-6.5 11-6.5 11z", circle(12f, 10f, 2.2f))
    }
    val Route by lazy {
        stroke("route", 2.2f, circle(6f, 18f, 2.2f), circle(18f, 6f, 2.2f), "M8.2 18H15a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h6.8")
    }
    val Photo by lazy { stroke("photo", 2.2f, rect(3.5f, 4.5f, 17f, 15f, 2.5f), "m3.5 16 5-4.5 4 3.5 3-2.5 5 4") }
    val Lines by lazy { stroke("lines", 2.2f, "M4 6h16M4 12h10M4 18h7") }
    val Calendar by lazy { stroke("calendar", 2.2f, rect(3.5f, 5f, 17f, 15f, 2.5f), "M3.5 10h17M8 3v4M16 3v4") }
    val Folder by lazy {
        stroke("folder", 2.2f, "M3.5 7.5a2 2 0 0 1 2-2h4l2 2.5h7a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")
    }
    val Question by lazy {
        stroke("question", 1.9f, circle(12f, 12f, 9f), "M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6M12 17h.01")
    }
    val Lock by lazy { stroke("lock", 1.9f, rect(5f, 11f, 14f, 10f, 2f), "M8 11V7a4 4 0 0 1 8 0v4") }
    val Plane by lazy { stroke("plane", 1.9f, "M21 3 10 14M21 3l-7 18-4-7-7-4z") }
    val Trash by lazy {
        stroke("trash", 1.9f, "M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3")
    }
    val Card by lazy { stroke("card", 1.9f, rect(2.5f, 5f, 19f, 14f, 2.5f), "M2.5 10h19M6.5 15h4") }
    val Shield by lazy { stroke("shield", 1.9f, "M12 3 5 6v5c0 4.5 3 8.5 7 10 4-1.5 7-5.5 7-10V6z") }
    val Copy by lazy { stroke("copy", 2f, rect(9f, 9f, 11f, 11f, 2f), "M5 15V6a2 2 0 0 1 2-2h8") }
    val Phone by lazy {
        stroke("phone", 2.1f, "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z")
    }
    val Expand by lazy { stroke("expand", 2.2f, "M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7") }
    val Close by lazy { stroke("close", 2.2f, "M6 6l12 12M18 6 6 18") }

    // Attach sheet (ChatPhoto board).
    val Camera by lazy { stroke("camera", 2.1f, "M4 8h3l2-3h6l2 3h3v11H4z", circle(12f, 13f, 3.5f)) }
    val Screen by lazy { stroke("screen", 2.1f, rect(6.5f, 2.5f, 11f, 19f, 2.5f), "M10.5 18.5h3") }
    val File by lazy { stroke("file", 2.1f, "M6 3h8l4 4v14H6z", "M14 3v4h4") }
    val Gear by lazy {
        stroke(
            "gear", 1.8f, circle(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21" +
                "a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 " +
                ".3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 " +
                "2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 " +
                "1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        )
    }

    /** Filled lightning bolt (fast mode). */
    val Bolt by lazy {
        ImageVector.Builder("bolt", 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes("M13 2 4 14h7l-1 8 9-12h-7z"), fill = SolidColor(Color.Black))
            .build()
    }

    /** Filled "⋯" (more actions). */
    val More by lazy {
        ImageVector.Builder("more", 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(circle(5f, 12f, 1.8f) + circle(12f, 12f, 1.8f) + circle(19f, 12f, 1.8f)), fill = SolidColor(Color.Black))
            .build()
    }
}
