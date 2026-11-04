package com.phoneuse.ui.digest

import com.phoneuse.i18n.tr
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.phone.DigestBucket
import com.phoneuse.phone.DigestClassifier
import com.phoneuse.phone.NotificationStore
import com.phoneuse.phone.NotificationTool
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.phone.SmsParsers
import com.phoneuse.phone.StoredNotification
import com.phoneuse.ui.chat.DeleteDialog
import com.phoneuse.ui.settings.PermissionKey
import com.phoneuse.ui.settings.Permissions
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Texts and prompts of the digest screen; pure so they can be tested. Prompts name an item by its group and
 * position (and our own clock text), never by notification text, which is third-party.
 */
object DigestText {
    const val HOURS = 24
    val ROUTINE_PROMPT get() = tr("Make a routine that shows my notification summary every evening at 9", "매일 저녁 9시에 알림 요약을 보여주는 루틴 만들어줘")

    fun lead(total: Int, reply: Int): String =
        if (reply > 0) tr("Grouped $total notifications. The $reply above need a look.", "알림 ${total}개를 묶었어요. 꼭 볼 건 위의 ${reply}개예요.") else tr("Grouped $total notifications.", "알림 ${total}개를 묶었어요.")

    fun replyPrompt(index: Int, at: String) =
        tr("Write a draft reply to notification ${index + 1} ($at) under '${DigestBucket.REPLY.label}' in the notification summary", "알림 요약의 '${DigestBucket.REPLY.label}' ${index + 1}번 알림($at)에 보낼 답장 초안 써줘")

    fun trackPrompt(index: Int) = tr("Where is parcel ${index + 1} under '${DigestBucket.ARRIVAL.label}' in the notification summary?", "알림 요약의 '${DigestBucket.ARRIVAL.label}' ${index + 1}번 택배 어디쯤 왔는지 알려줘")

    /** The sum of the payments the parsers read; null when none could be read. */
    fun moneyTotal(items: List<StoredNotification>): Long? =
        items.mapNotNull { SmsParsers.payment(it.text)?.amount }.takeIf { it.isNotEmpty() }?.sum()

    /** "₩48,300" */
    fun won(amount: Long): String = "₩" + "%,d".format(java.util.Locale.US, amount)

    /** "쿠팡 4 · Play 스토어 2": the rest by app, largest first, at most three. */
    fun restLine(items: List<StoredNotification>): String =
        items.groupingBy { it.appLabel }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3).joinToString(" · ") { "${it.key} ${it.value}" }

    /** A parcel line: "CJ대한통운 배송 출발", else the notification's own title. */
    fun parcelLine(n: StoredNotification): String =
        SmsParsers.parcel(n.text)?.let { "${it.carrier} ${it.status}" } ?: n.title.ifBlank { n.appLabel }
}

/**
 * Board "Digest": the last day's notifications in the classifier's groups. 답장 쓰기 and 추적 start a new
 * conversation through [onPrompt] (card-originated, so nothing is sent without a confirmation); 모두 지우기 clears
 * the rest after a dialog; 루틴으로 만들기 asks the agent for an evening routine.
 */
@Composable
fun DigestScreen(onBack: () -> Unit, onPrompt: (String) -> Boolean, resumeTick: Int, modifier: Modifier = Modifier) {
    val c = pu
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { NotificationStore.get(context) }
    val version by store.changes.collectAsState()
    val access by produceState(PhoneUseNotificationListener.enabled(context), resumeTick) {
        value = PhoneUseNotificationListener.enabled(context)
    }
    val list by produceState<List<StoredNotification>?>(null, version, resumeTick) {
        value = try {
            store.recent(DigestText.HOURS)
        } catch (e: Exception) {
            emptyList()
        }
    }
    val groups = remember(list) { DigestClassifier.group(list.orEmpty()) }
    val now = remember(list) { ZonedDateTime.now() }
    var clearDialog by remember { mutableStateOf(false) }

    fun prompt(text: String) {
        if (!onPrompt(text)) Toast.makeText(context, tr("You can do this once the current task finishes", "지금 작업이 끝나면 할 수 있어요"), Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack)
            Text(tr("Notification summary", "알림 요약"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.width(44.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val items = list
            when {
                !access && items.isNullOrEmpty() -> AccessCard { Permissions.open(context, PermissionKey.NOTIFICATION_ACCESS) }
                items == null -> Unit
                items.isEmpty() -> Lead(tr("No notifications in the last ${DigestText.HOURS} hours.", "최근 ${DigestText.HOURS}시간 동안 받은 알림이 없어요."))
                else -> {
                    Lead(DigestText.lead(groups.values.sumOf { it.size }, groups[DigestBucket.REPLY]?.size ?: 0))
                    if (!access) AccessCard { Permissions.open(context, PermissionKey.NOTIFICATION_ACCESS) }
                    groups[DigestBucket.REPLY]?.let { reply ->
                        Group(DigestBucket.REPLY.label, reply.size, labelColor = c.accentText) {
                            reply.take(NotificationTool.MAX_PER_GROUP).forEachIndexed { i, n ->
                                val at = NotificationTool.clockText(n.postedAt, now)
                                ItemRow(n.title.ifBlank { n.appLabel }, at, n.text, tr("Write reply", "답장 쓰기")) { prompt(DigestText.replyPrompt(i, at)) }
                            }
                        }
                    }
                    groups[DigestBucket.MONEY]?.let { money -> MoneyGroup(money) }
                    groups[DigestBucket.ARRIVAL]?.let { arrival ->
                        Group(DigestBucket.ARRIVAL.label, arrival.size) {
                            arrival.take(NotificationTool.MAX_PER_GROUP).forEachIndexed { i, n ->
                                ItemRow(null, NotificationTool.clockText(n.postedAt, now), DigestText.parcelLine(n), tr("Track", "추적"), strong = true) {
                                    prompt(DigestText.trackPrompt(i))
                                }
                            }
                        }
                    }
                    groups[DigestBucket.OTHER]?.let { rest -> RestGroup(rest) { clearDialog = true } }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Want this summary\nevery evening at 9?", "매일 저녁 9시에 이렇게\n정리해 드릴까요?"), Modifier.weight(1f), fontSize = 12.5.sp, lineHeight = 19.sp, color = c.textSub)
            PuButton(tr("Make it a routine", "루틴으로 만들기"), { prompt(DigestText.ROUTINE_PROMPT) }, height = 44.dp, radius = 12.dp, fontSize = 14)
        }
    }

    if (clearDialog) {
        val rest = groups[DigestBucket.OTHER].orEmpty()
        DeleteDialog(
            title = tr("Clear the other ${rest.size} notifications?", "나머지 알림 ${rest.size}개를 지울까요?"),
            body = tr("Clears them from the summary and the notification shade. Notifications that need a reply, money and parcels stay.", "알림 요약과 알림창에서 지워요. 답장 필요·돈·택배 알림은 그대로 둬요."),
            confirm = tr("Clear all", "모두 지우기"),
            onConfirm = {
                clearDialog = false
                val keys = rest.map { it.key }
                scope.launch {
                    try {
                        store.remove(keys)
                        PhoneUseNotificationListener.cancel(keys)
                    } catch (e: Exception) {
                        Toast.makeText(context, tr("Couldn't clear the notifications", "알림을 지우지 못했어요"), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { clearDialog = false },
        )
    }
}

@Composable
private fun Lead(text: String) {
    Text(text, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp), fontSize = 15.sp, lineHeight = 24.sp, color = pu.text)
}

@Composable
private fun AccessCard(onOpen: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(tr("Notification access is needed", "알림 읽기 권한이 필요해요"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        Text(tr("Turn on OpenPhoneUse in Settings and I'll group your incoming notifications. They stay on your phone for 7 days.", "설정에서 OpenPhoneUse를 켜면 받은 알림을 묶어 보여줘요. 알림은 폰 안에만 7일 동안 둬요."), fontSize = 13.sp, color = c.textSub)
        PuButton(tr("Allow access", "권한 허용"), onOpen, Modifier.fillMaxWidth().padding(top = 10.dp), height = 44.dp, radius = 12.dp, fontSize = 14)
    }
}

@Composable
private fun Group(
    label: String,
    count: Int,
    labelColor: androidx.compose.ui.graphics.Color = pu.text,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = pu
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).semantics { contentDescription = label },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$label · $count", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = labelColor)
            trailing?.invoke()
        }
        content()
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun ItemRow(who: String?, at: String, text: String, action: String, strong: Boolean = false, onAction: () -> Unit) {
    val c = pu
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    if (who != null) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(who) }
                        append(" ")
                    }
                    withStyle(SpanStyle(fontFamily = MonoStyle.fontFamily, fontSize = 12.sp, color = c.textSub)) { append(at) }
                },
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text, fontSize = 13.sp, color = if (strong) c.text else c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PuButton(action, onAction, kind = ButtonKind.OUTLINE, height = 34.dp, radius = 10.dp, fontSize = 13)
    }
}

/** 돈 나간 것: the total of the payments read, then merchant and amount per line. */
@Composable
private fun MoneyGroup(items: List<StoredNotification>) {
    val c = pu
    val total = DigestText.moneyTotal(items)
    Group(
        DigestBucket.MONEY.label,
        items.size,
        trailing = total?.let { t -> { Text(DigestText.won(t), style = MonoStyle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text) } },
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in items.take(NotificationTool.MAX_PER_GROUP)) {
                val p = SmsParsers.payment(n.text)
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        p?.merchant ?: n.title.ifBlank { n.appLabel },
                        Modifier.weight(1f),
                        fontSize = 13.sp,
                        color = c.textSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(p?.let { "%,d".format(java.util.Locale.US, it.amount) } ?: n.appLabel, style = MonoStyle, fontSize = 13.sp, color = c.text, maxLines = 1)
                }
            }
        }
    }
}

/** 나머지: dashed and transparent, counted by app, with 모두 지우기. */
@Composable
private fun RestGroup(items: List<StoredNotification>, onClear: () -> Unit) {
    val c = pu
    val line = c.line
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .drawBehind {
                val w = 1.dp.toPx()
                drawRoundRect(
                    line,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .semantics { contentDescription = DigestBucket.OTHER.label }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${DigestBucket.OTHER.label} · ${items.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.textSub)
            Text(DigestText.restLine(items), fontSize = 12.5.sp, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PuButton(tr("Clear all", "모두 지우기"), onClear, kind = ButtonKind.OUTLINE, height = 34.dp, radius = 10.dp, fontSize = 13)
    }
}
