package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.chat.Attachment
import com.phoneuse.chat.AttachmentFiles
import com.phoneuse.chat.AttachmentKind
import com.phoneuse.files.AttachmentStore
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the four tiles of the attach sheet do; see [rememberAttachActions]. */
class AttachActions(
    val camera: () -> Unit,
    val album: () -> Unit,
    val screen: () -> Unit,
    val file: () -> Unit,
    /** True while picked items are being copied in. */
    val busy: () -> Boolean,
)

/**
 * The launchers behind the sheet. Picked items are copied into app storage by [store] and handed to [onAdded];
 * [room] is how many more fit in the draft. Problems are shown as toasts.
 */
@Composable
fun rememberAttachActions(store: AttachmentStore, room: () -> Int, onAdded: (List<Attachment>) -> Unit): AttachActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentRoom by rememberUpdatedState(room)
    val currentOnAdded by rememberUpdatedState(onAdded)
    var busy by remember { mutableIntStateOf(0) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun importing(block: suspend () -> List<Attachment>) {
        scope.launch {
            busy++
            try {
                val added = block()
                if (added.isNotEmpty()) currentOnAdded(added)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A provider that throws must not take the app down.
                toast(tr("Couldn't attach", "첨부하지 못했어요"))
            } finally {
                busy--
            }
        }
    }

    fun images(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val take = uris.take(currentRoom().coerceAtLeast(0))
        if (take.size < uris.size) toast(FULL)
        importing {
            take.mapNotNull { store.importImage(it) }.also { if (it.size < take.size) toast(tr("Couldn't read the photo", "사진을 읽지 못했어요")) }
        }
    }

    val many = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Attachment.MAX_PER_MESSAGE)) { images(it) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> images(listOfNotNull(uri)) }
    var shot by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = shot?.let(::File) ?: return@rememberLauncherForActivityResult
        shot = null
        if (!saved) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        importing { listOfNotNull(store.importCameraShot(file)).also { if (it.isEmpty()) toast(tr("Couldn't read the photo", "사진을 읽지 못했어요")) } }
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing {
            when (val r = store.importDocument(uri)) {
                is AttachmentStore.Result.Added -> r.attachments.also { r.notice?.let(::toast) }
                is AttachmentStore.Result.Refused -> emptyList<Attachment>().also { toast(r.message) }
            }
        }
    }

    fun ifRoom(block: () -> Unit) {
        if (currentRoom() <= 0) toast(FULL) else block()
    }

    return remember(store) {
        AttachActions(
            camera = {
                ifRoom {
                    val (file, uri) = store.cameraTarget()
                    // A shot whose result never came back is not needed any more.
                    shot?.let { File(it).delete() }
                    shot = file.path
                    try {
                        camera.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        shot = null
                        file.delete()
                        toast(tr("No camera app found", "카메라 앱이 없어요"))
                    }
                }
            },
            album = {
                ifRoom {
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (currentRoom() >= 2) many.launch(request) else one.launch(request)
                }
            },
            screen = {
                ifRoom {
                    importing {
                        when (val r = store.recentScreen()) {
                            is AttachmentStore.Result.Added -> r.attachments
                            is AttachmentStore.Result.Refused -> emptyList<Attachment>().also { toast(r.message) }
                        }
                    }
                }
            },
            file = {
                ifRoom {
                    try {
                        document.launch(DOCUMENT_TYPES)
                    } catch (e: ActivityNotFoundException) {
                        toast(tr("No file app found", "파일 앱이 없어요"))
                    }
                }
            },
            busy = { busy > 0 },
        )
    }
}

private val FULL get() = tr("You can attach up to ${Attachment.MAX_PER_MESSAGE} at a time", "한 번에 ${Attachment.MAX_PER_MESSAGE}개까지 붙일 수 있어요")

private val DOCUMENT_TYPES = arrayOf(
    "text/*", "application/pdf", "application/json", "application/xml", "application/octet-stream",
)

/** The ChatPhoto board's sheet above the composer: 카메라 / 앨범 / 방금 화면 / 파일. */
@Composable
fun AttachSheet(actions: AttachActions, modifier: Modifier = Modifier) {
    val c = pu
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AttachTile(PuIcons.Camera, tr("Camera", "카메라"), actions.camera, Modifier.weight(1f))
        AttachTile(PuIcons.Photo, tr("Album", "앨범"), actions.album, Modifier.weight(1f))
        AttachTile(PuIcons.Screen, tr("Last screen", "방금 화면"), actions.screen, Modifier.weight(1f))
        AttachTile(PuIcons.File, tr("File", "파일"), actions.file, Modifier.weight(1f))
    }
}

@Composable
private fun AttachTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    val c = pu
    Column(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.bg)
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1)
    }
}

/** The draft's attachments above the composer; each can be removed. */
@Composable
fun PendingAttachments(list: List<Attachment>, busy: Boolean, onRemove: (Attachment) -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { a ->
            Box(Modifier.padding(top = 6.dp, end = 6.dp)) {
                if (a.kind == AttachmentKind.IMAGE) {
                    AttachmentImage(a, Modifier.size(64.dp), RoundedCornerShape(12.dp))
                } else {
                    FileChip(a.name.orEmpty(), Modifier.height(64.dp).widthIn(max = 180.dp))
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 0.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(c.primary)
                        .clickable(role = Role.Button, onClick = { onRemove(a) })
                        .semantics { contentDescription = tr("Remove attachment", "첨부 빼기") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PuIcons.Close, null, tint = c.onPrimary, modifier = Modifier.size(12.dp))
                }
            }
        }
        if (busy) {
            item(key = "busy") {
                Box(
                    Modifier.padding(top = 6.dp).size(64.dp).clip(RoundedCornerShape(12.dp)).background(c.sunken),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("…", fontSize = 15.sp, color = c.textSub)
                }
            }
        }
    }
}

/** A text file as a chip: file icon and name. */
@Composable
fun FileChip(name: String, modifier: Modifier = Modifier) {
    val c = pu
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(PuIcons.File, null, tint = c.textSub, modifier = Modifier.size(18.dp))
        Text(name, fontSize = 13.5.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A stored attachment image, cropped to fill; a plain tile with the photo icon while loading or once gone. */
@Composable
fun AttachmentImage(attachment: Attachment, modifier: Modifier, shape: RoundedCornerShape, maxEdge: Dp = 176.dp) {
    val c = pu
    val px = with(LocalDensity.current) { maxEdge.roundToPx() }
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, attachment.file, px) {
        val file = AttachmentFiles.resolve(attachment, File(context.filesDir, AttachmentFiles.DIR)) ?: return@produceState
        value = withContext(Dispatchers.IO) { AttachmentStore.thumbnail(file.path, px)?.asImageBitmap() }
    }
    val label = attachment.name ?: tr("Attached photo", "첨부한 사진")
    Box(
        modifier.clip(shape).background(c.sunken).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(PuIcons.Photo, null, tint = c.textSub, modifier = Modifier.size(22.dp))
        }
    }
}

/** The photos and files of a user message, right-aligned above its bubble (ChatPhoto board). */
@Composable
fun UserAttachments(list: List<Attachment>) {
    val images = list.filter { it.kind == AttachmentKind.IMAGE }
    val files = list.filter { it.kind == AttachmentKind.TEXT }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.size == 1) {
            AttachmentImage(images[0], Modifier.size(176.dp, 140.dp), RoundedCornerShape(16.dp))
        } else if (images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in images) AttachmentImage(a, Modifier.size(76.dp), RoundedCornerShape(12.dp), maxEdge = 76.dp)
            }
        }
        for (f in files) FileChip(f.name.orEmpty(), Modifier.widthIn(max = 260.dp).heightIn(min = 44.dp))
    }
}
