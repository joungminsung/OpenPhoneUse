package com.phoneuse.ui.cards

import com.phoneuse.i18n.tr
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.LatLng
import com.phoneuse.cards.Place
import com.phoneuse.cards.PlacesCard
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/** Board "Map": a 200dp map with numbered pins above the list; the selected place (first by default) is orange. */
private val PANE_MAP_HEIGHT = 380.dp

@Composable
fun PlacesCardView(card: PlacesCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    // Shared with the card's other copies (chat, result pane, flex mode).
    var selected by rememberCardUi()::placeSelected
    var full by remember { mutableStateOf(false) }
    val sel = selected.coerceIn(card.items.indices)
    CardBox(modifier, padding = PaddingValues(0.dp), label = card.title) {
        // MapView draws past its bounds unless clipped. The unfolded result pane gives the map most of its height (board Unfolded).
        val mapHeight = if (LocalCardStage.current == CardStage.PANE) PANE_MAP_HEIGHT else 200.dp
        Box(Modifier.fillMaxWidth().height(mapHeight).clipToBounds()) {
            PinMap(card.items, card.center, sel, interactive = false, Modifier.fillMaxSize().clipToBounds())
            // Covers the map so the list keeps scrolling over it; a tap opens the large map.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(onClickLabel = tr("Enlarge map", "지도 크게 보기")) { full = true }
                    .semantics { contentDescription = tr("${card.title} map, ${card.items.size} place${if (card.items.size == 1) "" else "s"}", "${card.title} 지도, ${card.items.size}곳") },
            )
            OsmAttribution(Modifier.align(Alignment.BottomStart))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(pu.surface)
                    .border(1.dp, pu.inputBorder, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { full = true }
                    .semantics { contentDescription = tr("Enlarge map", "지도 크게 보기") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(PuIcons.Expand, null, tint = pu.text, modifier = Modifier.size(18.dp))
            }
        }
        card.items.forEachIndexed { i, place ->
            PlaceRow(i, place, i == sel, onSelect = { selected = i }, onAction)
        }
    }
    if (full) {
        FullMap(card, sel, onSelect = { selected = it }, onAction, onDismiss = { full = false })
    }
}

@Composable
private fun PlaceRow(index: Int, place: Place, selected: Boolean, onSelect: () -> Unit, onAction: (CardAction) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(pu.lineSoft))
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) pu.accentSoft.copy(alpha = 0.45f).compositeOver(pu.surface) else pu.surface)
                .clickable(onClickLabel = tr("Select ${place.name}", "${place.name} 선택"), onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NumberBadge(index + 1, selected)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    place.name,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = pu.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PlaceMeta(place, selected)
                if (selected && !place.address.isNullOrBlank()) {
                    Text(place.address, fontSize = 12.5.sp, color = pu.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) {
                for (action in CardActions.forPlace(place)) {
                    when (action) {
                        is CardAction.Dial -> Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(pu.surface)
                                .border(1.dp, pu.inputBorder, RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { onAction(action) }
                                .semantics { contentDescription = tr("Call ${place.name}", "${place.name}에 전화") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(PuIcons.Phone, null, tint = pu.text, modifier = Modifier.size(19.dp))
                        }
                        else -> CardButton(tr("Directions", "길찾기"), { onAction(action) }, primary = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceMeta(place: Place, selected: Boolean) {
    val distance = distanceText(place.distanceM, place.walkMin)
    val status = place.statusText?.takeIf { it.isNotBlank() }
    if (distance == null && status == null) return
    val c = pu
    val open = status != null && OPEN_WORDS.any { it in status }
    Text(
        buildAnnotatedString {
            if (distance != null) {
                withStyle(
                    SpanStyle(
                        fontFamily = MonoStyle.fontFamily,
                        fontFeatureSettings = MonoStyle.fontFeatureSettings,
                        color = if (selected) c.text else c.textSub,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                ) { append(distance) }
            }
            if (distance != null && status != null) append(" · ")
            if (status != null) {
                withStyle(SpanStyle(color = if (open) c.success else c.textSub, fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal)) {
                    append(status)
                }
            }
        },
        fontSize = 13.sp,
        color = c.textSub,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val OPEN_WORDS = listOf("영업 중", "영업중", "24시간", "open", "Open")

@Composable
private fun NumberBadge(n: Int, highlighted: Boolean) {
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(if (highlighted) pu.accent else pu.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = if (highlighted) androidx.compose.ui.graphics.Color.White else pu.onPrimary)
    }
}

/** "© OpenStreetMap contributors", required by the OSM tile usage policy wherever its tiles are shown. */
@Composable
private fun OsmAttribution(modifier: Modifier = Modifier) {
    Text(
        "© OpenStreetMap contributors",
        modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(pu.surface.copy(alpha = 0.8f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        fontSize = 9.sp,
        lineHeight = 11.sp,
        color = pu.textSub,
    )
}

/** The large, movable map with the selected place underneath. */
@Composable
private fun FullMap(
    card: PlacesCard,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAction: (CardAction) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(pu.bg)) {
            PinMap(card.items, card.center, selected, interactive = true, Modifier.fillMaxSize(), onPinClick = onSelect)
            OsmAttribution(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(bottom = 104.dp))
            Row(
                Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(CircleShape).background(pu.surface)) {
                    IconBtn(PuIcons.Close, tr("Close map", "지도 닫기"), onDismiss)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    card.title,
                    Modifier.clip(RoundedCornerShape(12.dp)).background(pu.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = pu.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CardBox(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                padding = PaddingValues(0.dp),
            ) {
                PlaceRow(selected, card.items[selected], true, onSelect = {}, onAction)
            }
        }
    }
}

/**
 * osmdroid map with numbered pins (the selected one orange, drawn on top) and, when [me] is given, the blue
 * "my location" dot. Not interactive in the card; the dialog version pans and zooms.
 */
@Composable
fun PinMap(
    places: List<Place>,
    me: LatLng?,
    selected: Int,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    onPinClick: (Int) -> Unit = {},
) {
    val dark = pu.dark
    val accent = pu.accent.toArgb()
    val ink = pu.primary.toArgb()
    val onInk = pu.onPrimary.toArgb()
    val dot = pu.agentDot.toArgb()
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                setMultiTouchControls(interactive)
                isTilesScaledToDpi = true
                minZoomLevel = 3.0
                maxZoomLevel = 19.0
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false
                fit(this, places.filter { valid(it.lat, it.lng) }.map { GeoPoint(it.lat, it.lng) } + listOfNotNull(me?.takeIf { valid(it.lat, it.lng) }?.let { GeoPoint(it.lat, it.lng) }))
            }
        },
        update = { map ->
            // Follows a live light/dark switch (the activity handles uiMode itself, so the view is not recreated).
            map.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
            for (o in map.overlays.toList()) if (o is Marker) o.onDetach(map)
            map.overlays.clear()
            val ctx = map.context
            if (me != null && valid(me.lat, me.lng)) {
                map.overlays += Marker(map).apply {
                    position = GeoPoint(me.lat, me.lng)
                    icon = BitmapDrawable(ctx.resources, meDot(ctx, dot))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setInfoWindow(null)
                    title = tr("My location", "내 위치")
                    setOnMarkerClickListener { _, _ -> true }
                }
            }
            // The selected pin goes last so it is drawn on top.
            val order = places.indices.filter { it != selected } + listOfNotNull(selected.takeIf { it in places.indices })
            for (i in order) {
                val p = places[i]
                // Stored cards are decoded without re-validation.
                if (!valid(p.lat, p.lng)) continue
                map.overlays += Marker(map).apply {
                    position = GeoPoint(p.lat, p.lng)
                    icon = BitmapDrawable(ctx.resources, pin(ctx, i + 1, if (i == selected) accent else ink, if (i == selected) android.graphics.Color.WHITE else onInk))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setInfoWindow(null)
                    title = p.name
                    setOnMarkerClickListener { _, _ ->
                        onPinClick(i)
                        true
                    }
                }
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
        modifier = modifier,
    )
}

private fun valid(lat: Double, lng: Double) = lat in -90.0..90.0 && lng in -180.0..180.0

/** Centers on one point, or fits all of them once the view has a size. */
private fun fit(map: MapView, points: List<GeoPoint>) {
    if (points.isEmpty()) return
    map.controller.setZoom(16.0)
    map.controller.setCenter(points.first())
    if (points.size < 2) return
    map.addOnFirstLayoutListener { v, _, _, _, _ ->
        val m = v as MapView
        val box = BoundingBox.fromGeoPointsSafe(points)
        val pad = (40 * m.resources.displayMetrics.density).toInt()
        m.zoomToBoundingBox(box, false, pad)
        if (m.zoomLevelDouble > 17.0) m.controller.setZoom(17.0)
    }
}

/** A teardrop pin with a number, 30×38dp, anchored at its tip. */
private fun pin(ctx: Context, n: Int, fill: Int, textColor: Int): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val w = 30 * d
    val h = 38 * d
    val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = w / 2 - 1.5f * d
    val cx = w / 2
    val cy = r + 1.5f * d
    val path = Path().apply {
        addCircle(cx, cy, r, Path.Direction.CW)
        moveTo(cx - r * 0.55f, cy + r * 0.8f)
        lineTo(cx, h - 1f * d)
        lineTo(cx + r * 0.55f, cy + r * 0.8f)
        close()
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = android.graphics.Color.WHITE
        strokeJoin = Paint.Join.ROUND
    }
    c.drawPath(path, stroke)
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 13 * d
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    c.drawText("$n", cx, cy - (text.descent() + text.ascent()) / 2, text)
    return bmp
}

/** The "my location" dot: a 7dp blue dot with a white ring and a soft 15dp halo. */
private fun meDot(ctx: Context, color: Int): Bitmap {
    val d = ctx.resources.displayMetrics.density
    val size = (30 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val mid = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = color
    paint.alpha = 41
    c.drawCircle(mid, mid, 15 * d, paint)
    paint.color = android.graphics.Color.WHITE
    c.drawCircle(mid, mid, 8.25f * d, paint)
    paint.color = color
    c.drawCircle(mid, mid, 7 * d - 1.25f * d, paint)
    return bmp
}
