package com.phoneuse.ui.settings

import com.phoneuse.i18n.tr
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import com.phoneuse.app.AssistAction
import com.phoneuse.app.ControlSpeed
import com.phoneuse.app.FontSize
import com.phoneuse.app.QuietHours
import com.phoneuse.app.ScreenContent
import com.phoneuse.app.UnfoldedLayout
import com.phoneuse.app.VoiceLanguage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.BuildConfig
import com.phoneuse.agent.AnswerLength
import com.phoneuse.agent.Personalization
import com.phoneuse.app.AuthMode
import com.phoneuse.app.ReasoningEffort
import com.phoneuse.app.ModelProviders
import com.phoneuse.app.SettingsStore
import com.phoneuse.app.ThemeMode
import com.phoneuse.ui.chat.DeleteDialog
import com.phoneuse.ui.chat.EFFORT_OPTIONS
import com.phoneuse.ui.chat.FAST_MODE_LABEL
import com.phoneuse.ui.chat.REASONING_EFFORT_LABEL
import com.phoneuse.ui.chat.modelDisplayName
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.PuRadio
import com.phoneuse.ui.theme.PuSwitch
import com.phoneuse.ui.theme.Segmented
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Who the app talks to the model as. */
data class AccountInfo(
    val mode: AuthMode = AuthMode.CHATGPT,
    val chatGptSignedIn: Boolean = false,
    val email: String? = null,
    val hasApiKey: Boolean = false,
    /** The sign-in was granted the ChatGPT plan's scope; without it it is only an identity. */
    val planEnabled: Boolean = false,
) {
    /** The drawer's line under 설정. */
    val summary: String
        get() = when (mode) {
            AuthMode.CHATGPT -> if (chatGptSignedIn) tr("Signed in to ChatGPT", "ChatGPT 로그인됨") + (email?.let { " · $it" } ?: "") else tr("Sign-in needed", "로그인이 필요해요")
            AuthMode.API_KEY -> if (hasApiKey) tr("Using API key", "API 키 사용 중") else tr("Enter an API key", "API 키를 넣어 주세요")
            AuthMode.CLAUDE, AuthMode.GROK, AuthMode.GEMINI -> ModelProviders.label(mode) + " · " +
                if (hasApiKey) tr("Using API key", "API 키 사용 중") else tr("Enter an API key", "API 키를 넣어 주세요")
        }
}

val THEME_OPTIONS get() = listOf(ThemeMode.SYSTEM to tr("Follow system", "시스템 따라"), ThemeMode.LIGHT to tr("Light", "라이트"), ThemeMode.DARK to tr("Dark", "다크"))

/**
 * The Settings board, limited to what works today: account, AI, phone permissions, phone-control locks,
 * theme, delete all conversations, version. [resumeTick] changes whenever the activity resumes, so
 * permission states are re-read after returning from system settings.
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    http: OkHttpClient,
    account: AccountInfo,
    model: String,
    fast: Boolean,
    effort: ReasoningEffort,
    theme: ThemeMode,
    resumeTick: Int,
    webSearch: Boolean,
    memory: MemorySettings,
    onAccountChanged: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenMemory: () -> Unit,
    /** Wipes everything; returns the line to show (what failed, if anything). */
    onDeleteAll: suspend () -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Board Connectors; [connectorsSummary] is its sub line (what is connected). */
    onOpenConnectors: () -> Unit = {},
    connectorsSummary: String = "",
    /** Board Digest (알림 요약). */
    onOpenDigest: () -> Unit = {},
    /** Board NudgeSettings (알아서 챙기기). */
    onOpenNudges: () -> Unit = {},
    /** "대화 내보내기": writes the JSON to Downloads and returns the file name; throws when it could not. */
    onExport: suspend () -> String = { error("export unavailable") },
    /** How many conversations a retention of this many days would delete right now (asked before shortening). */
    countExpired: suspend (Int) -> Int = { 0 },
) {
    val c = pu
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var permissions by remember { mutableStateOf(Permissions.check(context)) }
    LaunchedEffect(resumeTick) { permissions = Permissions.check(context) }
    var themeDialog by remember { mutableStateOf(false) }
    var deleteAllDialog by remember { mutableStateOf(false) }
    var styleDialog by remember { mutableStateOf(false) }
    var aboutDialog by remember { mutableStateOf(false) }
    var choice by rememberSaveable { mutableStateOf<Choice?>(null) }
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    // A shorter retention that would delete conversations at once: (days, how many) until confirmed.
    var shorten by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val retention by settings.retentionDays.collectAsState(0)
    val confirmSend by settings.confirmSend.collectAsState(true)
    val riskJudge by settings.riskJudge.collectAsState(true)
    val assistAction by settings.assistAction.collectAsState(AssistAction.SUGGEST)
    val speed by settings.controlSpeed.collectAsState(ControlSpeed.NORMAL)
    val summaryMinute by settings.summaryMinute.collectAsState(SettingsStore.DEFAULT_SUMMARY_MINUTE)
    val quiet by settings.quietHours.collectAsState(QuietHours())
    val fontSize by settings.fontSize.collectAsState(FontSize.DEFAULT)
    val unfolded by settings.unfoldedLayout.collectAsState(UnfoldedLayout.SIDE_BY_SIDE)
    val voice by settings.voiceLanguage.collectAsState(VoiceLanguage.KOREAN)
    val readAloud by settings.readAloud.collectAsState(false)
    val screenContent by settings.screenContent.collectAsState(ScreenContent.ALL)

    if (permissionsOpen) {
        BackHandler { permissionsOpen = false }
        PermissionsPage(permissions, onBack = { permissionsOpen = false }, modifier = modifier)
        return
    }
    if (licensesOpen) {
        BackHandler { licensesOpen = false }
        LicensesPage(onBack = { licensesOpen = false }, modifier = modifier)
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack)
            Text(tr("Settings", "설정"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.width(44.dp))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
        ) {
            AccountCard(settings, http, account, scope = scope, onAccountChanged = onAccountChanged, onOpenUsage = onOpenUsage)

            Section("AI")
            Group {
                ValueRow(tr("Default model", "기본 모델"), modelDisplayName(model), onClick = onOpenModelSheet, first = true)
                if (ModelProviders.supportsFast(account.mode)) SwitchRow(FAST_MODE_LABEL, tr("Faster answers, uses your plan or credits faster", "답이 빨라지고 요금제나 크레딧을 더 빨리 써요"), fast) { v -> scope.launch { settings.setFastMode(v) } }
                ValueRow(REASONING_EFFORT_LABEL, EFFORT_OPTIONS.first { it.first == effort }.second, onClick = onOpenModelSheet)
                SwitchRow(tr("Web search", "웹 검색"), tr("Looks things up on the web when needed and shows sources", "필요하면 웹에서 찾아보고 출처를 보여줘요"), webSearch) { v -> scope.launch { settings.setWebSearch(v) } }
                SubRow(tr("Tone and name", "말투와 호칭"), styleSummary(memory.personalization), onClick = { styleDialog = true })
                SubRow(
                    tr("Tell me about you", "나에 대해 알려주기"),
                    memory.personalization.about.trim().ifEmpty { tr("Job, where you live, interests", "직업, 사는 곳, 관심사") },
                    onClick = { aboutDialog = true },
                )
            }

            Section(tr("Memory and history", "기억과 기록"))
            Group {
                SubRow(
                    tr("Manage memory", "메모리 관리"),
                    if (memory.enabled) tr("${memory.count} things remembered", "기억하고 있는 것 ${memory.count}개") else tr("Off · ${memory.count} things remembered", "꺼짐 · 기억하고 있는 것 ${memory.count}개"),
                    onClick = onOpenMemory,
                    first = true,
                )
                SwitchRow(tr("Use memory", "기억 사용"), tr("Uses what it remembered when answering", "답할 때 기억해 둔 것을 참고해요"), memory.enabled) { v -> scope.launch { settings.setMemoryEnabled(v) } }
                SwitchRow(
                    tr("Remember from chats automatically", "대화에서 자동으로 기억"),
                    tr("Saves new things you share, like family, places and tastes", "가족, 장소, 취향처럼 새로 알려준 것을 저장해요"),
                    memory.autoRemember,
                    enabled = memory.enabled,
                ) { v -> scope.launch { settings.setAutoRemember(v) } }
                ValueRow(tr("Keep chat history", "대화 기록 보관"), label(RETENTION_LABELS, retention), onClick = { choice = Choice.RETENTION })
            }

            Section(tr("Connections", "연결"))
            Group {
                SubRow(tr("Connectors", "커넥터"), connectorsSummary.ifEmpty { tr("Google, Notion, MCP servers", "Google, Notion, MCP 서버") }, onClick = onOpenConnectors, first = true)
                val missing = permissions.count { !it.granted }
                ValueRow(
                    tr("Phone permissions", "폰 권한"),
                    if (missing == 0) tr("All allowed", "모두 허용") else tr("$missing needed", "${missing}개 필요"),
                    onClick = { permissionsOpen = true },
                    sub = tr("Accessibility, files, notifications, location", "접근성, 파일, 알림, 위치"),
                    dot = if (missing == 0) c.success else c.accentText,
                )
            }

            Section(tr("Phone control", "폰 조작"))
            Group {
                // Money, security and deletion are the user's always-confirm scope.
                LockRow(tr("Confirm before payments and transfers", "결제 · 송금 전 확인"), first = true)
                LockRow(tr("You enter passwords yourself", "비밀번호 입력은 직접"))
                LockRow(tr("Confirm before deleting", "삭제 전 확인"))
                SwitchRow(
                    tr("Confirm before sending messages", "메시지 보내기 전 확인"),
                    tr("Asks before sending messages or mail, calling, posting, or changing alarms and wallpaper. A call, alarm or timer you asked for yourself, or a text you dictated word for word, goes ahead without asking. Off: still confirms in tasks that read SMS or outside content", "메시지·메일·전화·게시, 알람·배경화면을 바꾸기 전에 물어봐요. 직접 요청한 전화·알람·타이머와 내용까지 그대로 불러 준 문자는 묻지 않고 진행해요. 꺼도 문자(SMS)나 외부 내용을 읽은 작업에서는 확인해요"),
                    confirmSend,
                ) { v -> scope.launch { settings.setConfirmSend(v) } }
                SwitchRow(
                    tr("AI safety check", "AI 안전 검사"),
                    tr("The AI judges from the screen whether a step pays, sends or deletes, and asks you then. Off: keyword checks only", "AI가 조작하면서 화면을 보고 결제·전송·삭제인지 직접 판단해 그때 확인을 받아요. 끄면 키워드 검사만 해요"),
                    riskJudge,
                ) { v -> scope.launch { settings.setRiskJudge(v) } }
                ValueRow(tr("Press and hold side button", "측면 버튼 길게 누르기"), label(ASSIST_LABELS, assistAction), onClick = { choice = Choice.ASSIST })
                ValueRow(tr("Control speed", "조작 속도"), label(SPEED_LABELS, speed), onClick = { choice = Choice.SPEED })
            }

            Section(tr("Notifications and routines", "알림과 루틴"))
            Group {
                SubRow(tr("Notification summary", "알림 요약"), tr("Sorts incoming notifications into replies, money and parcels", "받은 알림을 답장 필요 · 돈 · 택배로 묶어 봐요"), onClick = onOpenDigest, first = true)
                SubRow(tr("Proactive help", "알아서 챙기기"), tr("Tells you before you ask · briefing, leaving, replies, anniversaries", "물어보기 전에 먼저 알려줘요 · 브리핑, 출발, 답장, 기념일"), onClick = onOpenNudges)
                ValueRow(tr("Daily summary", "오늘 요약 받기"), summaryLabel(summaryMinute), onClick = { choice = Choice.SUMMARY }, mono = summaryMinute >= 0)
                ValueRow(tr("Quiet hours", "방해하지 않는 시간"), quietLabel(quiet), onClick = { choice = Choice.QUIET }, mono = quiet.on)
            }

            Section(tr("Display and voice", "화면과 음성"))
            Group {
                ValueRow(tr("Theme", "테마"), THEME_OPTIONS.first { it.first == theme }.second, onClick = { themeDialog = true }, first = true)
                ValueRow(tr("Text size", "글자 크기"), label(FONT_LABELS, fontSize), onClick = { choice = Choice.FONT })
                ValueRow(tr("On the unfolded screen", "펼친 화면에서"), label(UNFOLDED_LABELS, unfolded), onClick = { choice = Choice.UNFOLDED })
                ValueRow(tr("Speak by voice", "음성으로 말하기"), label(VOICE_LABELS, voice), onClick = { choice = Choice.VOICE })
                SwitchRow(tr("Read answers aloud", "답 소리 내어 읽기"), tr("Reads the answer when a task finishes", "작업이 끝나면 답을 읽어 줘요"), readAloud) { v -> scope.launch { settings.setReadAloud(v) } }
            }

            Section(tr("Privacy", "개인정보"))
            Group {
                ValueRow(tr("Screen content sent to the model", "모델에 보내는 화면 내용"), label(SCREEN_LABELS, screenContent), onClick = { choice = Choice.SCREEN }, first = true)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.Button, enabled = !exporting) {
                            exporting = true
                            scope.launch {
                                val message = try {
                                    tr("Saved to Downloads · ${onExport()}", "다운로드 폴더에 저장했어요 · ${onExport()}")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    tr("Couldn't export", "내보내지 못했어요")
                                } finally {
                                    exporting = false
                                }
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Export chats", "대화 내보내기"), Modifier.weight(1f), fontSize = 15.sp, color = c.text)
                    Text(if (exporting) tr("Saving…", "저장 중…") else "JSON ›", fontSize = 13.5.sp, color = c.textSub)
                }
                RowDivider(false)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(role = Role.Button) { deleteAllDialog = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Delete all chats and memory", "모든 대화와 기억 삭제"), fontSize = 15.sp, color = c.danger)
                }
            }

            Section(tr("About", "정보"))
            Group {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Version", "버전"), Modifier.weight(1f), fontSize = 15.sp, color = c.text)
                    Text(BuildConfig.VERSION_NAME, style = MonoStyle, fontSize = 13.5.sp, color = c.textSub)
                }
                SubRow(tr("Open-source licenses", "오픈소스 라이선스"), tr("Libraries bundled in this app", "이 앱에 들어 있는 라이브러리"), onClick = { licensesOpen = true })
            }
        }
    }

    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            containerColor = c.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text(tr("Theme", "테마"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
            text = {
                Column {
                    for ((mode, label) in THEME_OPTIONS) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(selected = mode == theme, role = Role.RadioButton) {
                                    scope.launch { settings.setThemeMode(mode) }
                                    themeDialog = false
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PuRadio(mode == theme)
                            Spacer(Modifier.width(12.dp))
                            Text(label, fontSize = 15.sp, color = c.text)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { themeDialog = false }) { Text(tr("Close", "닫기"), color = c.text) }
            },
        )
    }
    choice?.let { open ->
        val close = { choice = null }
        when (open) {
            Choice.RETENTION -> ChoiceDialog(
                tr("Keep chat history", "대화 기록 보관"), RETENTION_LABELS, retention, close,
                note = tr("Chats older than this, counted from the last message, are cleared every day. Deleted chats can't be restored.", "마지막 대화 뒤로 이 기간이 지난 대화는 매일 정리돼요. 지운 대화는 되돌릴 수 없어요."),
            ) { v ->
                scope.launch {
                    val shorter = v > 0 && (retention <= 0 || v < retention)
                    val count = if (shorter) countExpired(v) else 0
                    if (count > 0) shorten = v to count else settings.setRetentionDays(v)
                }
            }
            Choice.ASSIST -> ChoiceDialog(
                tr("Press and hold side button", "측면 버튼 길게 누르기"), ASSIST_LABELS, assistAction, close,
                note = tr("‘Ideas for this screen’ sends the text on screen to a light model, which suggests things to do. Nothing happens before you press.", "‘이 화면으로 할 일’은 지금 화면의 글자를 가벼운 모델에 보내 할 만한 일을 골라 줘요. 누르기 전에는 아무것도 하지 않아요."),
            ) { v -> scope.launch { settings.setAssistAction(v) } }
            Choice.SPEED -> ChoiceDialog(
                tr("Control speed", "조작 속도"), SPEED_LABELS, speed, close,
                note = tr("How long it waits for the screen to change. If it keeps mis-tapping on a slow phone or app, try ‘Slow’.", "화면이 바뀌기를 기다리는 시간이에요. 느린 폰이나 앱에서 자꾸 헛누르면 ‘천천히’로 바꿔 보세요."),
            ) { v -> scope.launch { settings.setControlSpeed(v) } }
            Choice.SUMMARY -> ChoiceDialog(
                tr("Daily summary", "오늘 요약 받기"), SUMMARY_OPTIONS.map { it to summaryLabel(it) }, summaryMinute, close,
                note = tr("Counts the day's notifications as replies, money and parcels.", "하루 동안 온 알림을 답장 필요 · 돈 · 택배로 세어 알려줘요."),
            ) { v -> scope.launch { settings.setSummaryMinute(v) } }
            Choice.QUIET -> ChoiceDialog(
                tr("Quiet hours", "방해하지 않는 시간"), QUIET_OPTIONS.map { it to quietLabel(it) }, if (quiet.on) quiet else QUIET_OPTIONS.first(), close,
                note = tr("During this time, OpenPhoneUse notifications arrive without sound or vibration.", "이 시간에는 OpenPhoneUse 알림이 소리와 진동 없이 와요."),
            ) { v -> scope.launch { settings.setQuietHours(v) } }
            Choice.FONT -> ChoiceDialog(tr("Text size", "글자 크기"), FONT_LABELS, fontSize, close) { v -> scope.launch { settings.setFontSize(v) } }
            Choice.UNFOLDED -> ChoiceDialog(tr("On the unfolded screen", "펼친 화면에서"), UNFOLDED_LABELS, unfolded, close) { v -> scope.launch { settings.setUnfoldedLayout(v) } }
            Choice.VOICE -> ChoiceDialog(tr("Speak by voice", "음성으로 말하기"), VOICE_LABELS, voice, close) { v -> scope.launch { settings.setVoiceLanguage(v) } }
            Choice.SCREEN -> ChoiceDialog(
                tr("Screen content sent to the model", "모델에 보내는 화면 내용"), SCREEN_LABELS, screenContent, close,
                note = tr("With ‘Text only’, no screenshots are sent, just the text and structure of the screen. Screens made only of images get harder to handle.", "‘글자만’이면 화면 캡처는 보내지 않고 화면의 글자와 구조만 보내요. 그림으로만 된 화면은 다루기 어려워져요."),
            ) { v -> scope.launch { settings.setScreenContent(v) } }
        }
    }
    shorten?.let { (days, count) ->
        AlertDialog(
            onDismissRequest = { shorten = null },
            containerColor = c.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text(tr("Delete $count chats?", "대화 ${count}개를 지울까요?"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
            text = {
                Text(
                    tr("Shortening the retention to ‘${label(RETENTION_LABELS, days)}’ deletes $count older chats right away. This can't be undone.", "보관 기간을 ‘${label(RETENTION_LABELS, days)}’로 줄이면 그보다 오래된 대화 ${count}개가 바로 지워져요. 되돌릴 수 없어요."),
                    fontSize = 14.sp, lineHeight = 20.sp, color = c.textSub,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    shorten = null
                    scope.launch { settings.setRetentionDays(days) }
                }) { Text(tr("Delete and change", "지우고 바꾸기"), color = c.danger, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            },
            dismissButton = {
                TextButton(onClick = { shorten = null }) { Text(tr("Cancel", "취소"), color = c.text, fontSize = 15.sp) }
            },
        )
    }
    if (deleteAllDialog) {
        DeleteDialog(
            title = tr("Delete all chats and memory?", "모든 대화와 기억을 지울까요?"),
            body = tr("Saved chats and messages, memories, app tips, 'About me', notification history, routines and usage will all be deleted. ", "저장된 대화와 메시지, 기억, 앱 조작 요령, '나에 대해', 알림 기록, 루틴과 사용량이 전부 지워져요. ") +
                tr("This can't be undone. Sign-ins for connected services stay (you can disconnect them on the Connections screen).", "되돌릴 수 없어요. 연결한 서비스의 로그인은 남아요(연결 화면에서 끊을 수 있어요)."),
            confirm = tr("Delete all", "모두 삭제"),
            onConfirm = {
                deleteAllDialog = false
                scope.launch {
                    val line = onDeleteAll()
                    Toast.makeText(context, line, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { deleteAllDialog = false },
        )
    }
    if (styleDialog) {
        StyleDialog(memory.personalization, onSave = { p -> scope.launch { settings.setPersonalization(p) } }, onDismiss = { styleDialog = false })
    }
    if (aboutDialog) {
        AboutDialog(memory.personalization.about, onSave = { text ->
            scope.launch { settings.setPersonalization(memory.personalization.copy(about = text)) }
        }, onDismiss = { aboutDialog = false })
    }
}

/** What the settings screen shows and changes about memory and personalization. */
data class MemorySettings(
    val enabled: Boolean = true,
    val autoRemember: Boolean = true,
    val count: Int = 0,
    val personalization: Personalization = Personalization(),
)

/** Board Settings' sub line, e.g. "‘지민님’ · 존댓말 · 짧게". */
fun styleSummary(p: Personalization): String =
    listOfNotNull(
        p.callName.trim().takeIf { it.isNotEmpty() }?.let { "‘$it’" },
        if (p.polite) tr("Formal", "존댓말") else tr("Casual", "반말"),
        p.length.label,
    ).joinToString(" · ")

@Composable
private fun SubRow(title: String, sub: String, onClick: () -> Unit, first: Boolean = false) {
    val c = pu
    RowDivider(first)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.text)
            Text(sub, fontSize = 12.5.sp, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = 13.5.sp, color = c.textSub)
    }
}

@Composable
private fun DialogField(value: String, onChange: (String) -> Unit, placeholder: String, minHeight: Dp, singleLine: Boolean, label: String) {
    val c = pu
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .border(1.dp, c.inputBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = c.textSub)
        BasicTextField(
            value,
            onChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, lineHeight = 22.sp, color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

@Composable
private fun StyleDialog(initial: Personalization, onSave: (Personalization) -> Unit, onDismiss: () -> Unit) {
    val c = pu
    var name by rememberSaveable { mutableStateOf(initial.callName) }
    var polite by rememberSaveable { mutableStateOf(initial.polite) }
    var length by rememberSaveable { mutableStateOf(initial.length) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(tr("Tone and name", "말투와 호칭"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("Name to call you", "부를 이름"), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = c.textSub)
                DialogField(name, { name = it.take(30) }, tr("e.g. Alex", "예: 지민님"), 48.dp, singleLine = true, label = tr("Name to call you", "부를 이름"))
                Text(tr("Tone", "말투"), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = c.textSub)
                Segmented(listOf(true to tr("Formal", "존댓말"), false to tr("Casual", "반말")), polite, { polite = it })
                Text(tr("Answer length", "답 길이"), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = c.textSub)
                Segmented(AnswerLength.entries.map { it to it.label }, length, { length = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(callName = name.trim(), polite = polite, length = length))
                onDismiss()
            }) { Text(tr("Save", "저장"), color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "취소"), color = c.text, fontSize = 15.sp) }
        },
    )
}

@Composable
private fun AboutDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val c = pu
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(tr("Tell me about you", "나에 대해 알려주기"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Always used when answering. Write only what you want me to know, like your job, where you live or your interests.", "답할 때 늘 참고해요. 직업, 사는 곳, 관심사처럼 알려주고 싶은 것만 써 주세요."), fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub)
                DialogField(
                    text,
                    { text = it.take(Personalization.ABOUT_MAX) },
                    tr("e.g. I work as a developer in Seattle. I hike on weekends.", "예: 판교에서 개발자로 일해요. 주말엔 등산을 다녀요."),
                    140.dp,
                    singleLine = false,
                    label = tr("About you", "나에 대해"),
                )
                Text(
                    "${text.length} / ${Personalization.ABOUT_MAX}",
                    Modifier.fillMaxWidth(),
                    style = MonoStyle,
                    fontSize = 12.sp,
                    color = c.textSub,
                    textAlign = TextAlign.End,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text.trim())
                onDismiss()
            }) { Text(tr("Save", "저장"), color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel", "취소"), color = c.text, fontSize = 15.sp) }
        },
    )
}


@Composable
private fun AccountCard(
    settings: SettingsStore,
    http: OkHttpClient,
    account: AccountInfo,
    scope: CoroutineScope,
    onAccountChanged: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    val c = pu
    val context = LocalContext.current
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var keyText by rememberSaveable { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var planWelcome by rememberSaveable { mutableStateOf(false) }
    val planStore = remember(settings) { com.phoneuse.auth.SettingsPlanStore(settings) }
    val savedAccounts by androidx.compose.runtime.produceState(emptyList<com.phoneuse.auth.PlanRegistration>(), account, loginJob) {
        // Off the main thread: reading them decrypts with the Keystore.
        value = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { planStore.registrations() } }.getOrDefault(value)
    }
    LaunchedEffect(account.mode) {
        keyText = settings.providerKey(account.mode).first()
        keySaved = false
    }
    val openUsage = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.phoneuse.auth.PlanConfig.MANAGE_USAGE_URL))) }
        Unit
    }
    // [chosen] null registers a new account; [consent] asks the signed-in account for the plan permission again.
    val startSignIn: (com.phoneuse.auth.PlanRegistration?, Boolean) -> Unit = { chosen, consent ->
        error = null
        loginJob = scope.launch {
            try {
                val current = if (consent) planStore.load() else null
                val selected = current?.let { com.phoneuse.auth.PlanRegistration(it.clientId, it.subject, it.email) } ?: chosen
                val signed = com.phoneuse.auth.PlanLogin(http, planStore).login(selected, consent, current?.idToken) { url ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: ActivityNotFoundException) {
                        // Ends the sign-in now; otherwise it waits for a callback that can't come until the loopback times out.
                        throw com.phoneuse.auth.AuthException(tr("Couldn't open the browser", "브라우저를 열 수 없어요"))
                    }
                }
                // A run that started while the browser was up (a routine) doesn't go on under the new sign-in.
                if (account.chatGptSignedIn) com.phoneuse.app.AppGraph.runner.stop()
                onAccountChanged()
                if (signed.canUsePlan && settings.claimPlanWelcome()) planWelcome = true
                Toast.makeText(context, tr("Signed in", "로그인했어요"), Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.phoneuse.auth.AuthException) {
                error = e.message ?: tr("Couldn't sign in. Please try again.", "로그인하지 못했어요. 다시 시도해 주세요.")
            } catch (e: Exception) {
                error = tr("Couldn't sign in. Please try again.", "로그인하지 못했어요. 다시 시도해 주세요.")
            } finally {
                loginJob = null
            }
        }
    }

    if (planWelcome) {
        AlertDialog(
            onDismissRequest = { planWelcome = false },
            containerColor = c.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text(tr("You're using your ChatGPT plan", "ChatGPT 요금제를 사용하고 있어요"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
            text = {
                Text(
                    tr("Eligible AI requests in this app use your ChatGPT plan. You can manage usage in ChatGPT settings. ", "이 앱의 AI 요청은 ChatGPT 요금제를 사용해요. 사용량은 ChatGPT 설정에서 관리할 수 있어요. ") + backgroundUseNote(),
                    fontSize = 14.sp, lineHeight = 20.sp, color = c.text2,
                )
            },
            confirmButton = { TextButton(onClick = { planWelcome = false }) { Text(tr("Got it", "확인"), color = c.text, fontSize = 15.sp) } },
            dismissButton = { TextButton(onClick = openUsage) { Text(tr("Manage usage", "사용량 관리"), color = c.textSub, fontSize = 15.sp) } },
        )
    }

    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val choose: (AuthMode) -> Unit = { mode ->
            scope.launch {
                settings.setAuthMode(mode)
                onAccountChanged()
            }
        }
        // OpenAI on the first row, the other providers (each with its own API key) on the second.
        Segmented(
            listOf(AuthMode.CHATGPT to tr("ChatGPT subscription", "ChatGPT 구독"), AuthMode.API_KEY to tr("OpenAI API key", "OpenAI API 키")),
            account.mode,
            choose,
        )
        Segmented(listOf(AuthMode.CLAUDE, AuthMode.GROK, AuthMode.GEMINI).map { it to ModelProviders.label(it) }, account.mode, choose)
        when (account.mode) {
            AuthMode.CHATGPT -> {
                if (loginJob != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = c.text, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Finish signing in in your browser", "브라우저에서 로그인을 마쳐 주세요"), Modifier.weight(1f), fontSize = 14.sp, color = c.text2)
                        PuButton(tr("Cancel", "취소"), { loginJob?.cancel() }, kind = ButtonKind.OUTLINE, height = 40.dp, radius = 12.dp, fontSize = 14)
                    }
                } else if (account.chatGptSignedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("ChatGPT account", "ChatGPT 계정"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text)
                            Text(account.email ?: tr("Signed in", "로그인됨"), fontSize = 12.5.sp, color = c.textSub)
                            if (account.planEnabled) {
                                Text(
                                    tr("Using ChatGPT plan · Manage usage", "ChatGPT 요금제 사용 중 · 사용량 관리"),
                                    Modifier.clickable { openUsage() },
                                    fontSize = 12.5.sp, color = c.textSub, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                )
                            }
                            Text(
                                tr("Use a different account", "다른 계정 사용"),
                                Modifier
                                    .clickable(enabled = !signingOut, role = Role.Button) {
                                        // Like Sign out, a run holding this sign-in stops. The sign-in itself stays
                                        // until the new one is done, and if that fails or is cancelled (PlanLogin).
                                        com.phoneuse.app.AppGraph.runner.stop()
                                        startSignIn(null, false)
                                    }
                                    .padding(vertical = 6.dp),
                                fontSize = 12.5.sp, color = c.textSub, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            )
                        }
                        PuButton(tr("Sign out", "로그아웃"), {
                            signingOut = true
                            // Not tied to this screen: the revocation ends in bounded time and its outcome must reach the user.
                            scope.launch(NonCancellable) {
                                // A run still holding the old sign-in stops; its refresh no longer saves back
                                // over an emptied store either (PlanAuth).
                                com.phoneuse.app.AppGraph.runner.stop()
                                // The account's registration stays, so signing in again does not register it again.
                                val confirmed = com.phoneuse.auth.PlanAuth(planStore, com.phoneuse.auth.PlanTokenClient(http)).signOut()
                                signingOut = false
                                onAccountChanged()
                                if (!confirmed) {
                                    val notice = tr(
                                        "Signed out on this phone, but OpenAI didn't confirm it ended the session. You can disconnect the app in ChatGPT Settings.",
                                        "이 폰에서는 로그아웃했지만 OpenAI에서 세션 종료를 확인하지 못했어요. ChatGPT 설정에서 앱 연결을 해제할 수 있어요.",
                                    )
                                    error = notice
                                    Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
                                }
                            }
                        }, kind = ButtonKind.OUTLINE, height = 40.dp, radius = 12.dp, enabled = !signingOut, loading = signingOut, fontSize = 14)
                    }
                    if (!account.planEnabled) {
                        // Declined or not granted: the sign-in stays, and the choice is this or an API key above.
                        Text(
                            tr("ChatGPT plan use isn't allowed for this sign-in. Allow it, or choose an API key above.", "이 로그인은 ChatGPT 요금제 사용이 허용되지 않았어요. 허용하거나 위에서 API 키를 선택해 주세요."),
                            fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub,
                        )
                        PuButton(tr("Allow ChatGPT plan use", "ChatGPT 요금제 사용 허용"), { startSignIn(null, true) }, Modifier.fillMaxWidth(), enabled = !signingOut)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("ChatGPT account", "ChatGPT 계정"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text)
                        Text(tr("Use your ChatGPT plan: the AI requests of this app use the usage included in your ChatGPT plan, managed in ChatGPT settings. You sign in in the browser.", "ChatGPT 요금제 사용: 이 앱의 AI 요청은 ChatGPT 요금제에 포함된 사용량을 쓰고, ChatGPT 설정에서 관리해요. 브라우저에서 로그인해요.") + " " + backgroundUseNote(), fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub)
                    }
                    // A saved account signs in under its own registration; any other account registers its own.
                    for (saved in savedAccounts) {
                        PuButton(tr("Continue with ChatGPT", "ChatGPT로 계속하기") + " · " + saved.label(savedAccounts), { startSignIn(saved, false) }, Modifier.fillMaxWidth())
                    }
                    if (savedAccounts.isEmpty()) {
                        PuButton(tr("Continue with ChatGPT", "ChatGPT로 계속하기"), { startSignIn(null, false) }, Modifier.fillMaxWidth())
                    } else {
                        PuButton(tr("Use a different ChatGPT account", "다른 ChatGPT 계정 사용"), { startSignIn(null, false) }, Modifier.fillMaxWidth(), kind = ButtonKind.OUTLINE)
                    }
                    // The consent page says this for every app registered through the open-source flow.
                    Text(
                        tr("This app registers itself with OpenAI the first time you sign in, so OpenAI's consent page may say it hasn't verified this app. Continue only if you trust it.", "이 앱은 처음 로그인할 때 OpenAI에 직접 등록해서, OpenAI 동의 화면에 이 앱이 검증되지 않았다고 나올 수 있어요. 믿을 수 있는 경우에만 계속해 주세요."),
                        fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub,
                    )
                }
                error?.let { Text(it, fontSize = 12.5.sp, color = c.danger) }
            }
            AuthMode.API_KEY, AuthMode.CLAUDE, AuthMode.GROK, AuthMode.GEMINI -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val title = if (account.mode == AuthMode.API_KEY) tr("OpenAI API key", "OpenAI API 키") else ModelProviders.label(account.mode) + tr(" API key", " API 키")
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.text)
                    Text(tr("The key is stored encrypted on this device.", "키는 기기 안에 암호화해서 저장해요."), fontSize = 12.5.sp, color = c.textSub)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val fieldShape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(fieldShape)
                            .border(1.dp, c.inputBorder, fieldShape)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (keyText.isEmpty()) Text(ModelProviders.keyHint(account.mode), fontSize = 15.sp, color = c.textSub)
                        BasicTextField(
                            keyText,
                            { keyText = it; keySaved = false },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            textStyle = TextStyle(fontFamily = PlexSans, fontSize = 15.sp, color = c.text),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    PuButton(if (keySaved) tr("Saved", "저장됨") else tr("Save", "저장"), {
                        scope.launch {
                            settings.setProviderKey(account.mode, keyText)
                            keySaved = true
                            onAccountChanged()
                        }
                    }, height = 44.dp, radius = 12.dp, fontSize = 14)
                }
            }
        }
        // Board Settings: the way to the Usage screen.
        UsageSummary(onOpenUsage)
    }
}

@Composable
private fun UsageSummary(onOpenUsage: () -> Unit) {
    val c = pu
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = tr("View usage", "사용량 보기"), onClick = onOpenUsage)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Usage", "사용량"), Modifier.weight(1f), fontSize = 14.sp, color = c.text)
        Text("›", fontSize = 13.5.sp, color = c.textSub)
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 6.dp),
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        color = pu.textSub,
    )
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(pu.surface).border(1.dp, pu.line, shape),
        content = content,
    )
}

@Composable
private fun RowDivider(first: Boolean) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(pu.lineSoft))
}

/** A row with its current value on the right; [sub] goes under the title, [dot] before the value, [mono] for times. */
@Composable
private fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    first: Boolean = false,
    sub: String? = null,
    dot: Color? = null,
    mono: Boolean = false,
) {
    val c = pu
    RowDivider(first)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.text)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(value, style = if (mono) MonoStyle else TextStyle.Default, fontSize = 13.5.sp, color = c.textSub)
        Text(" ›", fontSize = 13.5.sp, color = c.textSub)
    }
}

/** Said wherever the ChatGPT plan is offered: a routine runs on its own schedule, with nobody tapping. */
private fun backgroundUseNote(): String =
    tr("Routines can also run in the background and use your plan without you tapping anything.", "루틴은 백그라운드에서도 실행되어 직접 누르지 않아도 요금제를 사용할 수 있어요.")

/** "폰 권한" (board Settings → 연결): each permission with what it is for and the way to grant it. */
@Composable
private fun PermissionsPage(permissions: List<PermissionStatus>, onBack: () -> Unit, modifier: Modifier) {
    val c = pu
    val context = LocalContext.current
    Column(modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack)
            Text(tr("Phone permissions", "폰 권한"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.width(44.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
        ) {
            Group {
                permissions.forEachIndexed { i, p ->
                    PermissionRow(p, first = i == 0) { Permissions.open(context, p.key) }
                }
            }
        }
    }
}

/** A choice list in a dialog: picking one saves it and closes; [note] explains the setting under the title. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    note: String? = null,
    onSelect: (T) -> Unit,
) {
    val c = pu
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (note != null) {
                    Text(note, Modifier.padding(bottom = 8.dp), fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub)
                }
                for ((value, label) in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = value == selected, role = Role.RadioButton) {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PuRadio(value == selected)
                        Spacer(Modifier.width(12.dp))
                        Text(label, fontSize = 15.sp, color = c.text)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close", "닫기"), color = c.text) }
        },
    )
}

@Composable
private fun SwitchRow(
    title: String,
    sub: String,
    checked: Boolean,
    first: Boolean = false,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val c = pu
    RowDivider(first)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.text)
            Text(sub, fontSize = 12.5.sp, color = c.textSub)
        }
        PuSwitch(checked, onChange, title, enabled = enabled)
    }
}

@Composable
private fun LockRow(title: String, first: Boolean = false) {
    val c = pu
    RowDivider(first)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), fontSize = 15.sp, color = c.text)
        Text(
            tr("Always", "항상"),
            Modifier.clip(RoundedCornerShape(6.dp)).background(c.sunken).padding(horizontal = 7.dp, vertical = 2.dp),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.textSub,
        )
    }
}

@Composable
private fun PermissionRow(p: PermissionStatus, first: Boolean, onOpen: () -> Unit) {
    val c = pu
    RowDivider(first)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (p.granted) PuIcons.Check else PuIcons.Alert,
                contentDescription = if (p.granted) tr("Allowed", "허용됨") else tr("Needed", "필요"),
                tint = if (p.granted) c.success else c.accentText,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 15.sp, color = c.text)
                Text(
                    if (p.granted) tr("Allowed · ${p.purpose}", "허용됨 · ${p.purpose}") else p.purpose,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = c.textSub,
                )
            }
            Spacer(Modifier.width(8.dp))
            PuButton(tr("Open", "열기"), onOpen, kind = if (p.granted) ButtonKind.OUTLINE else ButtonKind.PRIMARY, height = 36.dp, radius = 12.dp, fontSize = 13)
        }
        val hint = p.hint
        if (hint != null && (!p.granted || p.key == PermissionKey.ASSISTANT)) {
            Text(
                hint,
                Modifier
                    .padding(start = 28.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.sunken)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = c.text2,
            )
        }
    }
}
