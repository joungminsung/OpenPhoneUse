package com.phoneuse.ui.routines

import com.phoneuse.i18n.tr
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.app.AppGraph
import com.phoneuse.phone.AndroidLocationSource
import com.phoneuse.phone.AndroidPhone
import com.phoneuse.phone.GeoPlace
import com.phoneuse.phone.LocationReply
import com.phoneuse.phone.LocationTool
import com.phoneuse.routine.ActionKind
import com.phoneuse.routine.DeviceKind
import com.phoneuse.routine.PlaceEdge
import com.phoneuse.routine.PlaceResolver
import com.phoneuse.routine.Routine
import com.phoneuse.routine.RoutineDraft
import com.phoneuse.routine.RoutineParse
import com.phoneuse.routine.RoutineSystem
import com.phoneuse.routine.RoutineText
import com.phoneuse.routine.TriggerKind
import com.phoneuse.ui.cards.CardButton
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.PuSwitch
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek

private val CLOCK = Regex("""([01]?\d|2[0-3]):([0-5]\d)""")
private val RADII = listOf(150, 200, 300, 500, 1000)
private val EVENT_MINUTES = listOf(15, 30, 60, 90, 120)

/**
 * Board RoutineBuilder: 언제 (five trigger chips and each one's fields; a place on the map with its radius),
 * 조건 (평일만, 하루 한 번, 잠금 풀려 있을 때), 할 일, and [한 번 해보기] / [루틴 저장]. Editing a saved routine adds 삭제.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineBuilderScreen(
    initial: RoutineDraft,
    placesSupported: Boolean,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = pu
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val system = remember(context) { RoutineSystem.get(context) }
    var d by remember(initial) { mutableStateOf(initial) }
    var clock by remember(initial) { mutableStateOf(RoutineText.clock(initial.hour, initial.minute)) }
    var placeResults by remember { mutableStateOf<List<GeoPlace>>(emptyList()) }
    var picking by remember { mutableStateOf<((String, String) -> Unit)?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val editingSaved = d.base?.id?.takeIf { it != 0L }
    var placeNote by remember(initial) { mutableStateOf<String?>(null) }
    // A template's "회사": look it up in the user's place memories (or the map) right away.
    LaunchedEffect(initial) {
        if (initial.trigger == TriggerKind.PLACE && !initial.hasPlace && initial.placeName.isNotBlank() && placesSupported) {
            val found = try {
                PlaceResolver(AndroidPhone.geocoder(AppGraph.http), AppGraph.memories).resolve(initial.placeName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (found != null && !d.hasPlace) {
                d = d.copy(lat = found.lat, lng = found.lng)
                placeNote = listOfNotNull(found.from, found.address).joinToString(" · ")
            }
        }
    }

    fun built(): Routine? = when (val p = d.toRoutine()) {
        is RoutineParse.Ok -> p.routine
        is RoutineParse.Error -> {
            Toast.makeText(context, p.reason, Toast.LENGTH_SHORT).show()
            null
        }
    }

    fun searchPlace() {
        val q = d.placeName.trim()
        if (q.isEmpty()) return
        scope.launch {
            placeResults = try {
                AndroidPhone.geocoder(AppGraph.http).search(q, null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (placeResults.isEmpty()) Toast.makeText(context, tr("Couldn't find '$q' on the map. Try an address", "'$q'을 지도에서 못 찾았어요. 주소로 찾아보세요"), Toast.LENGTH_SHORT).show()
        }
    }

    fun here() {
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { LocationTool(AndroidLocationSource(context)).get() }) {
                is LocationReply.Ok -> d = d.copy(lat = r.lat, lng = r.lng, placeName = d.placeName.ifBlank { tr("Here", "여기") })
                LocationReply.PermissionNeeded -> Toast.makeText(context, tr("Location permission is needed", "위치 권한이 필요해요"), Toast.LENGTH_SHORT).show()
                is LocationReply.Failed -> Toast.makeText(context, tr("Couldn't get your location", "위치를 못 잡았어요"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(PuIcons.Close, tr("Close", "닫기"), onClose)
            Text(if (editingSaved != null) tr("Edit routine", "루틴 고치기") else tr("New routine", "새 루틴"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            if (editingSaved != null) IconBtn(PuIcons.Trash, tr("Delete routine", "루틴 삭제"), { confirmDelete = true }) else Spacer(Modifier.width(44.dp))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            d.sentence?.let { Text("“$it”", fontSize = 13.sp, color = c.textSub) }
            Field(d.title, { d = d.copy(title = it) }, tr("Name (e.g. Work mode)", "이름 (예: 일 모드)"), Modifier.fillMaxWidth())

            Cap(tr("When", "언제"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in TriggerKind.entries) Chip(k.label, d.trigger == k, { d = d.copy(trigger = k) })
            }
            when (d.trigger) {
                TriggerKind.TIME -> Section {
                    Field(
                        clock,
                        { v ->
                            clock = v
                            CLOCK.matchEntire(v.trim())?.let { m -> d = d.copy(hour = m.groupValues[1].toInt(), minute = m.groupValues[2].toInt()) }
                        },
                        "07:30",
                        Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (day in DayOfWeek.entries) {
                            Chip(RoutineText.dayLabel(day), day in d.timeDays, {
                                d = d.copy(timeDays = if (day in d.timeDays) d.timeDays - day else d.timeDays + day)
                            })
                        }
                    }
                    Text(if (d.timeDays.isEmpty()) tr("Every day if no days are picked", "요일을 고르지 않으면 매일") else RoutineText.days(d.timeDays), fontSize = 12.5.sp, color = c.textSub)
                }
                TriggerKind.PLACE -> if (!placesSupported) {
                    Text(tr("Not available on this device · place routines need Google Play services", "이 기기에서 못 씀 · 장소 루틴은 Google Play 서비스가 필요해요"), fontSize = 13.sp, color = c.danger)
                } else {
                    val lat = d.lat
                    val lng = d.lng
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp)),
                    ) {
                        if (lat != null && lng != null) RadiusMap(lat, lng, d.radiusM, Modifier.fillMaxWidth().height(128.dp))
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Field(d.placeName, { d = d.copy(placeName = it) }, tr("Place name or address", "장소 이름이나 주소"), Modifier.weight(1f))
                                Spacer(Modifier.width(6.dp))
                                CardButton(tr("Search", "찾기"), { searchPlace() }, height = 44.dp)
                            }
                            CardButton(tr("Use where I am now", "지금 있는 곳으로"), { here() }, Modifier.fillMaxWidth(), height = 40.dp)
                            for (p in placeResults.take(5)) {
                                Text(
                                    "${p.name} · ${p.address}",
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
                                        d = d.copy(lat = p.lat, lng = p.lng, placeName = d.placeName.ifBlank { p.name })
                                        placeNote = tr("Map search · ${p.address}", "지도 검색 · ${p.address}")
                                        placeResults = emptyList()
                                    }.padding(vertical = 8.dp, horizontal = 4.dp),
                                    fontSize = 13.5.sp,
                                    color = c.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (lat == null) Text(tr("Search for a place or use your current location", "장소를 찾거나 지금 위치를 골라 주세요"), fontSize = 12.5.sp, color = c.textSub)
                            placeNote?.let { Text(it, fontSize = 12.5.sp, color = c.textSub) }
                            Text(tr("Arrive / leave", "도착 / 떠날 때"), fontSize = 13.sp, color = c.textSub)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Chip(tr("On arrival", "도착하면"), d.edge == PlaceEdge.ENTER, { d = d.copy(edge = PlaceEdge.ENTER) })
                                Chip(tr("On leaving", "떠나면"), d.edge == PlaceEdge.EXIT, { d = d.copy(edge = PlaceEdge.EXIT) })
                            }
                            Text(tr("Range", "범위"), fontSize = 13.sp, color = c.textSub)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (r in RADII) Chip(radiusText(r), d.radiusM == r, { d = d.copy(radiusM = r) })
                            }
                        }
                    }
                }
                TriggerKind.NOTIFICATION -> Section {
                    AppPickRow(
                        label = d.notifPackage.takeIf { it.isNotEmpty() }?.let { appLabel(context, it) ?: it } ?: tr("All apps", "모든 앱"),
                        onPick = { picking = { _, pkg -> d = d.copy(notifPackage = pkg) } },
                        onClear = if (d.notifPackage.isNotEmpty()) ({ d = d.copy(notifPackage = "") }) else null,
                    )
                    Field(d.notifKeyword, { d = d.copy(notifKeyword = it) }, tr("Contains (e.g. parcel)", "들어 있는 말 (예: 택배)"), Modifier.fillMaxWidth())
                    Text(tr("Notification text is written by someone else, so this routine always confirms before sending a message.", "알림 내용은 다른 사람이 쓴 글이라, 이 루틴은 메시지를 보낼 때 항상 확인해요."), fontSize = 12.5.sp, color = c.textSub)
                }
                TriggerKind.DEVICE -> Section {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((k, label) in listOf(
                            DeviceKind.CHARGER_CONNECTED to tr("Charging starts", "충전 시작"), DeviceKind.HEADSET_PLUGGED to tr("Headphones", "이어폰"),
                            DeviceKind.BLUETOOTH_CONNECTED to tr("Bluetooth (car, earbuds)", "블루투스 (차·이어폰)"), DeviceKind.WIFI_CONNECTED to "Wi-Fi",
                        )) {
                            Chip(label, d.deviceKind == k, { d = d.copy(deviceKind = k) })
                        }
                    }
                    if (d.deviceKind == DeviceKind.BLUETOOTH_CONNECTED || d.deviceKind == DeviceKind.WIFI_CONNECTED) {
                        Field(
                            d.deviceDetail,
                            { d = d.copy(deviceDetail = it) },
                            if (d.deviceKind == DeviceKind.WIFI_CONNECTED) tr("Wi-Fi name (leave blank for any Wi-Fi)", "Wi-Fi 이름 (비우면 아무 Wi-Fi)") else tr("Device name (leave blank for any device)", "기기 이름 (비우면 아무 기기)"),
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                TriggerKind.BEFORE_EVENT -> Section {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in EVENT_MINUTES) Chip(tr("${RoutineText.minutes(m)} before", "${RoutineText.minutes(m)} 전"), d.eventMinutes == m, { d = d.copy(eventMinutes = m) })
                    }
                    Text(tr("Only events with a location count. The calendar is re-read once a day and when events change.", "장소가 있는 일정만 봐요. 캘린더는 하루 한 번과 일정이 바뀔 때 다시 읽어요."), fontSize = 12.5.sp, color = c.textSub)
                }
            }

            Cap(tr("Conditions", "조건"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(tr("Weekdays only", "평일만"), d.weekdaysOnly, { d = d.copy(weekdaysOnly = !d.weekdaysOnly) })
                Chip(tr("Once a day", "하루 한 번"), d.onceADay, { d = d.copy(onceADay = !d.onceADay) })
                Chip(tr("Only when unlocked", "잠금 풀려 있을 때"), d.onlyUnlocked, { d = d.copy(onlyUnlocked = !d.onlyUnlocked) })
            }

            Cap(tr("What to do", "할 일"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in ActionKind.entries) Chip(k.label, d.action == k, { d = d.copy(action = k) })
            }
            when (d.action) {
                ActionKind.ASK -> Section {
                    Field(d.prompt, { d = d.copy(prompt = it, steps = emptyList()) }, tr("What to do (e.g. silence the phone and tell me today's meetings)", "할 일 (예: 무음으로 하고 오늘 회의 알려줘)"), Modifier.fillMaxWidth().heightIn(min = 72.dp), singleLine = false)
                    if (d.steps.isNotEmpty()) Steps(d.steps)
                    ToggleRow(tr("Needs phone control (opens and operates other apps)", "폰 조작 필요 (다른 앱을 열어 조작)"), d.needsDevice) { d = d.copy(needsDevice = it) }
                    if (d.needsDevice) Text(tr("If the phone is locked, it doesn't run and sends a 'Run after unlocking' notification instead.", "폰이 잠겨 있으면 실행하지 않고 '잠금 해제 후 실행' 알림을 보내요."), fontSize = 12.5.sp, color = c.textSub)
                    ToggleRow(tr("Send without confirming", "확인 없이 보내기"), d.sendWithoutConfirm) { d = d.copy(sendWithoutConfirm = it) }
                    if (d.sendWithoutConfirm) {
                        Field(d.sendTo, { d = d.copy(sendTo = it) }, tr("Recipient (exactly as shown on screen)", "받는 사람 (화면에 보이는 이름 그대로)"), Modifier.fillMaxWidth())
                        Field(d.sendBody, { d = d.copy(sendBody = it) }, tr("Message (only when it is exactly this text)", "보낼 내용 (이 글 그대로일 때만)"), Modifier.fillMaxWidth().heightIn(min = 56.dp), singleLine = false)
                    }
                    Text(
                        if (d.sendWithoutConfirm) {
                            tr("Sends without a confirmation card only to this recipient with exactly this text. Other people or text, runs started by a notification or event, SMS (send_sms), and payments or security still need confirmation.", "이 받는 사람에게 이 내용 그대로 보낼 때만 확인 카드 없이 보내요. 다른 사람·다른 내용, 알림이나 일정으로 시작한 실행, 문자(send_sms), 결제·보안은 그래도 확인해요.")
                        } else {
                            tr("Shows a confirmation card before sending a message.", "메시지를 보내기 전에 확인 카드를 보여줘요.")
                        },
                        fontSize = 12.5.sp,
                        color = c.textSub,
                    )
                }
                ActionKind.NOTIFY -> Section {
                    Field(d.notifyText, { d = d.copy(notifyText = it) }, tr("Notification text (doesn't call the model)", "알림 내용 (모델을 부르지 않아요)"), Modifier.fillMaxWidth())
                }
                ActionKind.OPEN_APP -> Section {
                    AppPickRow(
                        label = d.appLabel.ifEmpty { d.appPackage.ifEmpty { tr("Choose app", "앱 고르기") } },
                        onPick = { picking = { label, pkg -> d = d.copy(appPackage = pkg, appLabel = label) } },
                        onClear = null,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PuButton(tr("Try once", "한 번 해보기"), {
                built()?.let {
                    system.tryOnce(it)
                    Toast.makeText(context, tr("Running once", "한 번 실행할게요"), Toast.LENGTH_SHORT).show()
                }
            }, Modifier.weight(1f), kind = ButtonKind.OUTLINE, height = 50.dp, radius = 14.dp, fontSize = 14)
            PuButton(tr("Save routine", "루틴 저장"), {
                built()?.let {
                    scope.launch { if (system.edit { save(it) }) onSaved() else Toast.makeText(context, tr("Couldn't save", "저장하지 못했어요"), Toast.LENGTH_SHORT).show() }
                }
            }, Modifier.weight(2f), kind = ButtonKind.PRIMARY, height = 50.dp, radius = 14.dp, fontSize = 15)
        }
    }

    picking?.let { onPicked ->
        AppPicker(onDismiss = { picking = null }) { label, pkg ->
            onPicked(label, pkg)
            picking = null
        }
    }
    if (confirmDelete && editingSaved != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete this routine?", "루틴을 지울까요?")) },
            text = { Text(tr("‘${d.title}’ will be deleted. Chat history stays.", "‘${d.title}’ 루틴이 지워져요. 대화 기록은 남아요.")) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    scope.launch { if (system.edit { delete(editingSaved) }) onSaved() else Toast.makeText(context, tr("Couldn't save", "저장하지 못했어요"), Toast.LENGTH_SHORT).show() }
                }) { Text(tr("Delete", "삭제")) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(tr("Cancel", "취소")) } },
        )
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = pu.text)
        PuSwitch(on, onChange, label)
    }
}

@Composable
private fun AppPickRow(label: String, onPick: () -> Unit, onClear: (() -> Unit)?) {
    val c = pu
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.inputBorder, shape)
            .clickable(role = Role.Button, onClickLabel = tr("Choose app", "앱 고르기"), onClick = onPick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("App", "앱"), fontSize = 14.sp, color = c.textSub)
        Text(label, Modifier.weight(1f).padding(start = 10.dp), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onClear != null) Text(tr("All apps", "모든 앱"), Modifier.clickable(role = Role.Button, onClick = onClear).padding(4.dp), fontSize = 13.sp, color = c.textSub)
    }
}

/** Installed apps to choose from, filtered by name. */
@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    val apps = remember(context) { launchableApps(context) }
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.first.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Choose app", "앱 고르기")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(query, { query = it }, tr("App name", "앱 이름"), Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(shown, key = { it.second }) { (label, pkg) ->
                        Text(
                            label,
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(label, pkg) }.padding(vertical = 10.dp),
                            fontSize = 15.sp,
                            color = pu.text,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(tr("Close", "닫기")) } },
    )
}
