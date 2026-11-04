package com.phoneuse.ui.cards

import com.phoneuse.i18n.tr
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.cards.CardAction
import com.phoneuse.cards.CardActions
import com.phoneuse.cards.CardSpec
import com.phoneuse.cards.DigestCard
import com.phoneuse.cards.DraftCard
import com.phoneuse.cards.EventCard
import com.phoneuse.cards.ParcelCard
import com.phoneuse.cards.PermissionCard
import com.phoneuse.cards.PermissionFlow
import com.phoneuse.cards.PhotosCard
import com.phoneuse.cards.PlacesCard
import com.phoneuse.cards.ResultsCard
import com.phoneuse.cards.RouteCard
import com.phoneuse.cards.RoutineCard
import com.phoneuse.cards.StatCard
import com.phoneuse.cards.isPhotoUri
import com.phoneuse.phone.MapsIntents
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.ui.chat.copyToClipboard
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.pu

/**
 * A result card from show_card (boards: Map, Route, Calendar, Photos, Search, ChatWriting, Digest, Parcel).
 * [stateKey] (PaneCard.key) names the card across its places on screen: with [LocalCardStates] every copy
 * uses the same [CardUiState], so selection and filters carry over between the chat, the unfolded result pane
 * and flex mode.
 */
@Composable
fun ResultCard(card: CardSpec, onAction: (CardAction) -> Unit, modifier: Modifier = Modifier, stateKey: String? = null) {
    val shared = stateKey?.let { LocalCardStates.current?.get(it) }
    CompositionLocalProvider(LocalCardUiState provides shared) {
        CardContent(card, onAction, modifier)
    }
}

@Composable
private fun CardContent(card: CardSpec, onAction: (CardAction) -> Unit, modifier: Modifier) {
    when (card) {
        is PlacesCard -> PlacesCardView(card, onAction, modifier)
        is RouteCard -> RouteCardView(card, onAction, modifier)
        is EventCard -> EventCardView(card, onAction, modifier)
        is PhotosCard -> PhotosCardView(card, onAction, modifier)
        is ResultsCard -> ResultsCardView(card, onAction, modifier)
        is DraftCard -> DraftCardView(card, onAction, modifier)
        is DigestCard -> DigestCardView(card, onAction, modifier)
        is StatCard -> StatCardView(card, modifier)
        is ParcelCard -> ParcelCardView(card, onAction, modifier)
        is PermissionCard -> PermissionCardView(card, onAction, modifier)
        is RoutineCard -> RoutineCardView(card, onAction, modifier)
    }
}

/**
 * Runs the actions the app handles itself; [CardAction.Prompt] goes to [onPrompt]. [CardAction.RequestPermission]
 * asks from this Activity; once allowed, a prompt lets the agent carry on with what needed it.
 */
@Composable
fun rememberCardActionRunner(onPrompt: (String) -> Unit): (CardAction) -> Unit {
    val context = LocalContext.current
    // The caller's lambda is new on every recomposition; read the latest one instead of re-keying.
    val prompt by rememberUpdatedState(onPrompt)
    // Saveable: if the process dies while the system dialog is up, the result still finds its request.
    var pending by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val asked = PermissionFlow.restore(pending) ?: return@rememberLauncherForActivityResult
        pending = null
        val activity = context.findActivity()
        val canAskAgain = activity != null && asked.permissions.any { activity.shouldShowRequestPermissionRationale(it) }
        when (PermissionFlow.after(result, canAskAgain)) {
            PermissionFlow.Outcome.GRANTED -> prompt(CardActions.permissionGranted(asked.label).text)
            PermissionFlow.Outcome.DENIED -> Toast.makeText(context, tr("${asked.label} permission wasn't allowed", "${asked.label} 권한을 허용하지 않았어요"), Toast.LENGTH_SHORT).show()
            PermissionFlow.Outcome.OPEN_SETTINGS -> openPermissionSettings(context, asked)
        }
    }
    // A special access is granted on a Settings screen; back from it, the agent carries on only if it was.
    var pendingAccess by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val label = pendingAccess ?: return@rememberLauncherForActivityResult
        pendingAccess = null
        if (PhoneUseNotificationListener.enabled(context)) {
            prompt(CardActions.permissionGranted(label).text)
        } else {
            Toast.makeText(context, tr("$label permission wasn't turned on", "$label 권한을 켜지 않았어요"), Toast.LENGTH_SHORT).show()
        }
    }
    return remember(context, launcher, settingsLauncher) {
        { action ->
            runCardAction(context, action, onRequestPermission = { request ->
                if (PermissionFlow.settingsPage(request) == PermissionFlow.SettingsPage.NOTIFICATION_LISTENER) {
                    Toast.makeText(context, tr("Turn on OpenPhoneUse in the list", "목록에서 OpenPhoneUse를 켜 주세요"), Toast.LENGTH_LONG).show()
                    try {
                        pendingAccess = request.label
                        settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        pendingAccess = null
                        openPermissionSettings(context, request)
                    }
                } else if (PermissionFlow.settingsOnly(request)) {
                    openPermissionSettings(context, request)
                } else {
                    pending = PermissionFlow.save(request)
                    launcher.launch(request.permissions.toTypedArray())
                }
            }) { prompt(it) }
        }
    }
}

/** When Android will not show the dialog: the notification or app-details settings page. */
private fun openPermissionSettings(context: Context, request: CardAction.RequestPermission) {
    Toast.makeText(context, tr("Allow ${request.label} permission in Settings", "설정에서 ${request.label} 권한을 허용해 주세요"), Toast.LENGTH_LONG).show()
    val settings = when (PermissionFlow.settingsPage(request)) {
        PermissionFlow.SettingsPage.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        PermissionFlow.SettingsPage.APP_DETAILS, PermissionFlow.SettingsPage.NOTIFICATION_LISTENER ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    start(context, settings)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun runCardAction(
    context: Context,
    action: CardAction,
    onRequestPermission: (CardAction.RequestPermission) -> Unit = {},
    onPrompt: (String) -> Unit,
) {
    when (action) {
        is CardAction.Dial -> start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.phone, null)))
        is CardAction.Navigate -> if (action.lat in -90.0..90.0 && action.lng in -180.0..180.0) {
            val intent = MapsIntents.route(context, action.lat, action.lng, action.name)
            // A map app that refuses the deep link falls back to the web.
            if (!tryStart(context, intent)) {
                start(
                    context,
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${action.lat},${action.lng}")),
                )
            }
        }
        // Stored cards are not re-validated, so the scheme is checked here too.
        is CardAction.OpenUrl -> if (action.url.startsWith("https://") || action.url.startsWith("http://")) {
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
        }
        is CardAction.CopyText -> copyToClipboard(context, action.text)
        is CardAction.Prompt -> onPrompt(action.text)
        is CardAction.SharePhotos -> sharePhotos(context, action.uris)
        // Stored cards are not re-validated: only Android runtime permissions are asked for.
        is CardAction.RequestPermission -> if (action.permissions.all { it.startsWith("android.permission.") }) {
            onRequestPermission(action)
        }
    }
}

/** The system share sheet with read access to the photos for the app the user picks. */
private fun sharePhotos(context: Context, uris: List<String>) {
    val photos = uris.filter { isPhotoUri(it) }.map { Uri.parse(it) }
    if (photos.isEmpty()) return
    val send = if (photos.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, photos[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(photos))
    }
    send.type = "image/*"
    send.clipData = ClipData.newRawUri(null, photos[0]).also { clip -> photos.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    start(context, Intent.createChooser(send, tr("Send photos", "사진 보내기")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

private fun tryStart(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

private fun start(context: Context, intent: Intent) {
    if (!tryStart(context, intent)) Toast.makeText(context, tr("No app can open this", "열 수 있는 앱이 없어요"), Toast.LENGTH_SHORT).show()
}

internal val CardShape = RoundedCornerShape(18.dp)

/** The boards' result card surface: white, 1dp line, 18dp corners. */
@Composable
internal fun CardBox(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(pu.surface)
            .border(1.dp, pu.line, CardShape)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Where a card is drawn: in the chat, or large in the unfolded result pane / flex mode's top half. */
enum class CardStage { INLINE, PANE }

val LocalCardStage = staticCompositionLocalOf { CardStage.INLINE }

/**
 * In flex mode's top half: the labels of the card buttons drawn in the bottom half instead (FlexButton.replaces).
 * Only those are left out; any other button the card draws stays in the card.
 */
val LocalMovedCardButtons = staticCompositionLocalOf { emptySet<String>() }

/** 44dp card button (`.sm` / `.nx` on the boards). */
@Composable
internal fun CardButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    height: Dp = 44.dp,
) {
    if (text in LocalMovedCardButtons.current) return
    PuButton(
        text,
        onClick,
        modifier,
        kind = if (primary) ButtonKind.PRIMARY else ButtonKind.OUTLINE,
        height = height,
        radius = 12.dp,
        fontSize = 14,
    )
}

/** A rounded pill (tone chips, photo filters); filled with the text colour when [on]. */
@Composable
internal fun Pill(text: String, on: Boolean, onClick: () -> Unit, height: Dp = 34.dp) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        Modifier
            .height(height)
            .clip(shape)
            .background(if (on) pu.primary else pu.surface)
            .border(1.dp, if (on) pu.primary else pu.inputBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) pu.onPrimary else pu.text, maxLines = 1)
    }
}

/** "280m · 4분" */
internal fun distanceText(meters: Double?, walkMin: Int?): String? {
    val d = meters?.let { if (it < 1000) "${it.toInt()}m" else "%.1fkm".format(it / 1000) }
    val w = walkMin?.let { tr("$it min", "${it}분") }
    return listOfNotNull(d, w).joinToString(" · ").ifEmpty { null }
}
