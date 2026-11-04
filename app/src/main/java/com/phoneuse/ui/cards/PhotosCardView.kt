package com.phoneuse.ui.cards

import com.phoneuse.i18n.tr
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.PhotosCard
import com.phoneuse.cards.isPhotoUri
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val COLUMNS = 3
private const val WIDE_COLUMNS = 4
private const val COLLAPSED = 9

/** At least this wide (the unfolded screen's result panel), the grid has 4 columns and shows every photo. */
private val WIDE = 560.dp

/**
 * Board "Photos": filter chips, a 3-column grid with selection, then album / send (share sheet) / wallpaper.
 * Board "UnfoldedPhotos" when wide: 4 columns, nothing collapsed, the album button twice as wide.
 */
@Composable
fun PhotosCardView(card: PhotosCard, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    // Shared with the card's other copies (chat, result pane, flex mode).
    val ui = rememberCardUi()
    var filter by ui::photoFilter
    var selected by ui::photoSelected
    var expanded by ui::photosExpanded
    val shown = card.filters.getOrNull(filter)?.uris ?: card.uris
    val selectedSet = selected.toSet()

    BoxWithConstraints(modifier.fillMaxWidth()) {
    val wide = maxWidth >= WIDE
    val columns = if (wide) WIDE_COLUMNS else COLUMNS
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (card.filters.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(tr("All ${card.uris.size}", "전체 ${card.uris.size}"), filter == -1, { filter = -1 }, height = 36.dp)
                card.filters.forEachIndexed { i, f -> Pill("${f.label} ${f.uris.size}", filter == i, { filter = i }, height = 36.dp) }
            }
        }
        val more = shown.size - COLLAPSED + 1
        val tiles = if (wide || expanded || shown.size <= COLLAPSED) shown else shown.take(COLLAPSED - 1)
        Column(Modifier.semantics { contentDescription = card.title }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val cells: List<String?> = if (tiles.size < shown.size) tiles + null else tiles
            for (row in cells.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (uri in row) {
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            if (uri == null) {
                                MoreTile(more) { expanded = true }
                            } else {
                                val number = card.uris.indexOf(uri) + 1
                                PhotoTile(uri, number, uri in selectedSet) {
                                    selected = if (uri in selectedSet) selected - uri else selected + uri
                                }
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                Text(tr("${selected.size}", "${selected.size}장"), style = MonoStyle, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(tr(" selected", " 선택됨"), fontSize = 13.5.sp, color = c.textSub)
            }
            val all = shown.all { it in selectedSet }
            Text(
                if (all) tr("Deselect", "선택 해제") else tr("Select all", "모두 선택"),
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { selected = if (all) selected - shown.toSet() else (selected + shown).distinct() }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.accentText,
            )
        }
        // Nothing selected acts on the shown photos.
        val target = if (selectedSet.isEmpty()) shown.toSet() else selectedSet
        // A stored card is not re-validated, so an empty filter can still reach here: then there is nothing to act on.
        if (target.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(tr("Make album", "앨범 만들기"), { onAction(CardActions.makeAlbum(card, target)) }, Modifier.weight(if (wide) 2f else 1f), primary = true, height = 48.dp)
            CardButton(tr("Send", "보내기"), { onAction(CardActions.sendPhotos(card, target)) }, Modifier.weight(1f), height = 48.dp)
            CardButton(
                tr("Wallpaper", "배경화면"),
                {
                    val n = card.uris.indexOfFirst { it in target } + 1
                    onAction(CardAction.Prompt(tr("Set photo #$n as my wallpaper", "사진 ${n}번을 배경화면으로 해줘")))
                },
                Modifier.weight(1f),
                height = 48.dp,
            )
        }
    }
    }
}

@Composable
private fun PhotoTile(uri: String, number: Int, selected: Boolean, onToggle: () -> Unit) {
    val c = pu
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(c.bubble)
            .clickable(onClickLabel = if (selected) tr("Deselect", "선택 해제") else tr("Select", "선택")) { onToggle() }
            .semantics { contentDescription = tr("Photo $number", "사진 $number") + if (selected) tr(", selected", ", 선택됨") else "" },
    ) {
        val image = rememberThumbnail(uri)
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(PuIcons.Photo, null, tint = c.textSub.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center).size(22.dp))
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .border(2.dp, Color.White, CircleShape)
                    .clip(CircleShape)
                    .background(c.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(PuIcons.Check, null, tint = c.onPrimary, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun MoreTile(more: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(pu.primary)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = tr("Show $more more", "나머지 ${more}장 더 보기") },
        contentAlignment = Alignment.Center,
    ) {
        Text("+$more", style = MonoStyle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pu.onPrimary)
    }
}

/** A MediaStore thumbnail; null while loading or when it cannot be read (deleted, no permission). */
@Composable
private fun rememberThumbnail(uri: String): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        // Only MediaStore / Photo Picker URIs are opened with the app's identity.
        if (!isPhotoUri(uri)) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.loadThumbnail(Uri.parse(uri), Size(THUMB_PX, THUMB_PX), null).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    return image
}

private const val THUMB_PX = 320
