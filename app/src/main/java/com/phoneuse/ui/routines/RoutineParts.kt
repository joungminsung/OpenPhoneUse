package com.phoneuse.ui.routines

import com.phoneuse.i18n.tr
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.phoneuse.routine.RoutineStep
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.pu
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.ln

/** Board RoutineBuilder's place map: the point and its radius as a dashed-edge orange circle. Not interactive. */
@Composable
fun RadiusMap(lat: Double, lng: Double, radiusM: Int, modifier: Modifier = Modifier) {
    val dark = pu.dark
    val accent = pu.accent.toArgb()
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                setMultiTouchControls(false)
                isTilesScaledToDpi = true
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                setOnTouchListener { _, _ -> true }
            }
        },
        update = { map ->
            map.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
            for (o in map.overlays.toList()) if (o is Marker) o.onDetach(map)
            map.overlays.clear()
            if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                val center = GeoPoint(lat, lng)
                map.overlays += Polygon(map).apply {
                    points = Polygon.pointsAsCircle(center, radiusM.toDouble())
                    fillPaint.color = (accent and 0x00FFFFFF) or 0x1F000000
                    outlinePaint.color = accent
                    outlinePaint.strokeWidth = 3f * map.resources.displayMetrics.density / 2
                    setOnClickListener { _, _, _ -> true }
                }
                map.overlays += Marker(map).apply {
                    position = center
                    icon = BitmapDrawable(map.resources, dot(map.resources.displayMetrics.density, accent))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ -> true }
                }
                // About 200m fills a 128dp-high card at zoom 16; each doubling of the radius is one level out.
                map.controller.setZoom((16.0 - ln(radiusM / 200.0) / ln(2.0)).coerceIn(11.0, 18.0))
                map.controller.setCenter(center)
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
        modifier = modifier,
    )
}

private fun dot(density: Float, color: Int): Bitmap {
    val size = (16 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = color
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3 * density, p)
    return bmp
}

/** The boards' `.cap`: a small bold grey section label. */
@Composable
internal fun Cap(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 2.dp, top = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pu.textSub)
}

/** The boards' `.chip` (34dp, filled when on). */
@Composable
internal fun Chip(text: String, on: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    com.phoneuse.ui.cards.Pill(text, on, if (enabled) onClick else ({}))
}

/** The boards' `.act`: a small grey step tag on a routine row. */
@Composable
internal fun ActTag(text: String) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(6.dp)).background(pu.bubble).padding(horizontal = 8.dp, vertical = 3.dp),
        fontSize = 12.sp,
        color = pu.text2,
        maxLines = 1,
    )
}

/** A row of [ActTag]s. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActTags(tags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in tags) ActTag(t)
    }
}

/** Numbered steps with the "폰 조작" tag (board RoutineBuilder `.step`). */
@Composable
internal fun Steps(steps: List<RoutineStep>) {
    Column {
        steps.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(pu.bubble), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = pu.text)
                }
                Text(s.text, Modifier.padding(start = 10.dp).weight(1f), fontSize = 14.sp, color = pu.text)
                if (s.device) Text(tr("Phone action", "폰 조작"), fontSize = 12.sp, color = pu.textSub)
            }
        }
    }
}

/** A label/value row of the place card (`.row`). */
@Composable
internal fun InfoRow(label: String, value: String, note: String? = null, mono: Boolean = false, top: Boolean = true) {
    Column {
        if (top) Box(Modifier.fillMaxWidth().height(1.dp).background(pu.lineSoft))
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp, color = pu.text)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pu.text, style = if (mono) MonoStyle else TextStyle.Default, maxLines = 1)
            if (note != null) Text(" $note", fontSize = 12.sp, color = pu.textSub)
        }
    }
}

/** A single-line input in the boards' style (white, 1dp border, 12dp corners). */
@Composable
internal fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    singleLine: Boolean = true,
) {
    val c = pu
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.inputBorder, shape)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, fontSize = 14.5.sp, color = c.textSub)
        BasicTextField(
            value,
            onChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontFamily = PlexSans, fontSize = 14.5.sp, color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "200m", "1.5km". */
internal fun radiusText(m: Int): String = if (m < 1000) "${m}m" else "%.1fkm".format(m / 1000.0)
