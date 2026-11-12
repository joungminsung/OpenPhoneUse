package com.phoneuse.ui.connectors

import com.phoneuse.phone.Declared
import com.phoneuse.i18n.tr
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.connectors.Connectors
import com.phoneuse.connectors.GoogleOAuth
import com.phoneuse.mcp.McpException
import com.phoneuse.mcp.McpPreset
import com.phoneuse.mcp.McpServer
import com.phoneuse.ui.theme.ButtonKind
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.PlexSans
import com.phoneuse.ui.theme.PuButton
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.PuSwitch
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One "폰 안" tile: a phone data source and whether the app may read it. */
private data class PhoneSource(val title: String, val state: String?, val granted: Boolean, val open: (Context) -> Unit)

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun appDetails(context: Context) =
    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, tr("Couldn't open the settings screen", "설정 화면을 열 수 없어요"), Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, tr("Couldn't open the settings screen", "설정 화면을 열 수 없어요"), Toast.LENGTH_SHORT).show()
    }
}

/** The phone sources of board Connectors; each is asked for the first time a tool needs it. */
private fun phoneSources(context: Context): List<PhoneSource> {
    val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    val photos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val location = granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    // Sources whose permission this build leaves out (see Declared) are not listed.
    return listOfNotNull(
        PhoneSource(tr("Contacts", "연락처"), null, granted(context, Manifest.permission.READ_CONTACTS), ::appDetails)
            .takeIf { Declared.has(context, Manifest.permission.READ_CONTACTS) },
        PhoneSource(tr("Calendar", "캘린더"), null, granted(context, Manifest.permission.READ_CALENDAR), ::appDetails),
        PhoneSource(tr("SMS", "문자"), null, granted(context, Manifest.permission.READ_SMS), ::appDetails)
            .takeIf { Declared.has(context, Manifest.permission.READ_SMS) },
        PhoneSource(tr("Notifications", "알림"), null, listeners.contains(context.packageName + "/")) {
            open(it, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        PhoneSource(tr("Photos", "사진"), null, granted(context, photos), ::appDetails),
        PhoneSource(tr("Location", "위치"), if (location) tr("While in use", "쓸 때만") else null, location, ::appDetails),
    )
}

private fun openBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, tr("Couldn't open the browser", "브라우저를 열 수 없어요"), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Board Connectors: phone sources (permission state), accounts (Google; Notion as an MCP preset; Outlook and
 * Spotify not yet available), and MCP servers added by address. [resumeTick] re-reads permission states.
 */
@Composable
fun ConnectorsScreen(connectors: Connectors, resumeTick: Int, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = pu
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(phoneSources(context)) }
    LaunchedEffect(resumeTick) { sources = phoneSources(context) }
    LaunchedEffect(Unit) {
        try {
            connectors.store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(context, tr("Couldn't read the connector settings", "커넥터 설정을 읽지 못했어요"), Toast.LENGTH_SHORT).show()
        }
    }
    val servers by connectors.store.servers.collectAsState()
    val google by connectors.store.google.collectAsState()
    // What each server last answered: "도구 n개", "로그인 필요", or a failure.
    val status = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var googleDialog by remember { mutableStateOf(false) }
    var serverDialog by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }

    fun describe(e: Exception): String = when {
        e is McpException && e.status == 401 -> tr("Sign-in needed", "로그인 필요")
        else -> e.message ?: tr("Couldn't connect", "연결하지 못했어요")
    }

    /** Runs [block] as the one connector action in progress ([key] shows its spinner). */
    fun act(key: String, block: suspend () -> Unit) {
        if (job != null) return
        busy = key
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, describe(e), Toast.LENGTH_LONG).show()
            } finally {
                busy = null
                job = null
            }
        }
    }

    suspend fun loginServer(id: String) {
        try {
            status[id] = tr("${connectors.login(id) { openBrowser(context, it) }} tools", "도구 ${connectors.login(id) { openBrowser(context, it) }}개")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status[id] = tr("Sign-in needed", "로그인 필요")
            throw Exception(tr("Couldn't sign in. You can connect with a token instead (${e.message})", "로그인하지 못했어요. 토큰으로 연결할 수 있어요 (${e.message})"))
        }
    }

    suspend fun add(address: String, preset: String?) {
        val (server, count) = connectors.addServer(address, null, preset)
        if (count >= 0) {
            status[server.id] = tr("$count tools", "도구 ${count}개")
        } else {
            status[server.id] = tr("Sign-in needed", "로그인 필요")
            loginServer(server.id)
        }
    }

    // Check each enabled server once when the screen opens.
    LaunchedEffect(servers.map { it.id to it.enabled }) {
        for (s in servers) {
            if (!s.enabled || s.id in status) continue
            launch {
                status[s.id] = try {
                    tr("${connectors.testServer(s.id)} tools", "도구 ${connectors.testServer(s.id)}개")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    describe(e)
                }
            }
        }
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
            Text(tr("Connectors", "커넥터"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.width(44.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr("The more you connect, the more it answers directly without operating the screen. Sending and deleting are always confirmed, even when connected.", "연결할수록 화면을 조작하지 않고 바로 답해요. 보내기·삭제는 연결해도 항상 확인받아요."),
                Modifier.padding(horizontal = 4.dp),
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                color = c.textSub,
            )

            SectionLabel(tr("On your phone", "폰 안"))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sources.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { s -> PhoneTile(s, Modifier.weight(1f)) { s.open(context) } }
                    }
                }
            }

            SectionLabel(tr("Accounts", "계정"), top = 8)
            Card {
                AccountRow(
                    letter = "G",
                    title = "Google",
                    sub = google?.takeIf { it.connected }?.let { tr("Connected", "연결됨") + (it.email?.let { e -> " · $e" } ?: "") } ?: tr("Read mail and drafts · search Drive", "메일 읽기·초안 · 드라이브 검색"),
                    button = if (google?.connected == true) tr("Manage", "관리") else tr("Connect", "연결"),
                    primary = google?.connected != true,
                    first = true,
                ) { googleDialog = true }
                for (preset in McpPreset.entries) {
                    val server = servers.firstOrNull { it.preset == preset.key }
                    val presetUrl = preset.url
                    AccountRow(
                        letter = preset.title.take(1),
                        title = preset.title,
                        sub = when {
                            presetUrl == null -> tr("Coming soon", "준비 중")
                            server != null -> status[server.id]?.let { "${preset.subtitle} · $it" } ?: preset.subtitle
                            else -> preset.subtitle
                        },
                        button = when {
                            presetUrl == null -> tr("Coming soon", "준비 중")
                            server != null -> tr("Manage", "관리")
                            else -> tr("Connect", "연결")
                        },
                        primary = server == null && presetUrl != null,
                        enabled = presetUrl != null,
                        loading = busy == preset.key,
                        dim = presetUrl == null,
                    ) {
                        when {
                            server != null -> serverDialog = server.id
                            presetUrl != null -> act(preset.key) { add(presetUrl, preset.key) }
                        }
                    }
                }
            }

            SectionLabel(tr("Add your own", "직접 추가"), top = 8)
            val custom = servers.filter { it.preset == null }
            if (custom.isNotEmpty()) {
                Card {
                    custom.forEachIndexed { i, s ->
                        AccountRow(
                            letter = s.name.take(1).uppercase(),
                            title = s.name,
                            sub = listOfNotNull(if (s.enabled) status[s.id] else tr("Off", "꺼짐"), s.url.removePrefix("https://")).joinToString(" · "),
                            button = tr("Manage", "관리"),
                            primary = false,
                            first = i == 0,
                        ) { serverDialog = s.id }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(url, { url = it.trim() }, tr("MCP server address https://…", "MCP 서버 주소 https://…"), tr("MCP server address", "MCP 서버 주소"), Modifier.weight(1f), keyboard = KeyboardType.Uri)
                PuButton(
                    tr("Add", "추가"),
                    { act("add") { add(url, null).also { url = "" } } },
                    height = 46.dp,
                    radius = 12.dp,
                    fontSize = 14,
                    enabled = Connectors.isAllowedUrl(url),
                    loading = busy == "add",
                )
            }
            Text(
                tr("Use an MCP server's tools right in chat. If the server asks you to sign in, a browser opens. Tools that send, delete or pay are confirmed every time.", "MCP 서버의 도구를 대화에서 바로 써요. 서버가 로그인을 요구하면 브라우저가 열려요. 보내기·삭제·결제 도구는 쓸 때마다 확인받아요."),
                Modifier.padding(horizontal = 4.dp),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = c.textSub,
            )
            if (job != null && busy != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(tr("When the browser opens, finish signing in", "브라우저가 열리면 로그인을 마쳐 주세요"), Modifier.weight(1f), fontSize = 12.5.sp, color = c.text2)
                    TextButton(onClick = { job?.cancel() }) { Text(tr("Cancel", "취소"), color = c.text, fontSize = 13.sp) }
                }
            }
        }
    }

    if (googleDialog) {
        GoogleDialog(
            connected = google?.connected == true,
            email = google?.email,
            savedClientId = google?.clientId.orEmpty(),
            busy = busy == "google",
            onConnect = { id, secret ->
                act("google") {
                    connectors.connectGoogle(id, secret) { openBrowser(context, it) }
                    Toast.makeText(context, tr("Connected Google", "Google을 연결했어요"), Toast.LENGTH_SHORT).show()
                    googleDialog = false
                }
            },
            onCancel = { job?.cancel() },
            onDisconnect = {
                act("google") { connectors.google.disconnect() }
                googleDialog = false
            },
            onDismiss = { googleDialog = false },
        )
    }
    serverDialog?.let { id ->
        val server = servers.firstOrNull { it.id == id }
        if (server == null) {
            serverDialog = null
        } else {
            ServerDialog(
                server = server,
                status = status[id],
                busy = busy == id,
                onEnabled = { v -> act(id) { connectors.setEnabled(id, v) } },
                onLogin = { act(id) { loginServer(id) } },
                onToken = { token ->
                    act(id) {
                        connectors.setToken(id, token)
                        status[id] = try {
                            tr("${connectors.testServer(id)} tools", "도구 ${connectors.testServer(id)}개")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            describe(e)
                        }
                    }
                },
                onTest = {
                    act(id) {
                        status[id] = try {
                            tr("${connectors.testServer(id)} tools", "도구 ${connectors.testServer(id)}개")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            describe(e)
                        }
                    }
                },
                onRemove = {
                    act(id) {
                        connectors.removeServer(id)
                        status.remove(id)
                    }
                    serverDialog = null
                },
                onDismiss = { serverDialog = null },
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String, top: Int = 6) {
    Text(
        title,
        Modifier.padding(start = 6.dp, end = 6.dp, top = top.dp),
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        color = pu.textSub,
    )
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(pu.surface).border(1.dp, pu.line, shape), content = content)
}

@Composable
private fun PhoneTile(s: PhoneSource, modifier: Modifier, onClick: () -> Unit) {
    val c = pu
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(46.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClickLabel = tr("${s.title} permissions", "${s.title} 권한 설정"), onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.title, Modifier.weight(1f), fontSize = 14.sp, color = c.text)
        when {
            s.state != null -> Text(s.state, fontSize = 12.sp, color = c.textSub)
            s.granted -> {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(c.success))
                Spacer(Modifier.width(5.dp))
                Text(tr("Allowed", "허용"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.success)
            }
            else -> Text(tr("Asks when needed", "쓸 때 물어봐요"), fontSize = 12.sp, color = c.textSub)
        }
    }
}

@Composable
private fun AccountRow(
    letter: String,
    title: String,
    sub: String,
    button: String,
    primary: Boolean,
    first: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    val c = pu
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(c.lineSoft))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.bg), contentAlignment = Alignment.Center) {
            Text(letter, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (dim) c.textSub else c.text)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(sub, fontSize = 12.5.sp, color = c.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        PuButton(
            button,
            onClick,
            kind = if (primary) ButtonKind.PRIMARY else ButtonKind.OUTLINE,
            height = 36.dp,
            radius = 10.dp,
            fontSize = 13,
            enabled = enabled,
            loading = loading,
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val c = pu
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .heightIn(min = 46.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.inputBorder, shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, fontSize = 14.5.sp, color = c.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = PlexSans, fontSize = 14.5.sp, color = c.text),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

@Composable
private fun GoogleDialog(
    connected: Boolean,
    email: String?,
    savedClientId: String,
    busy: Boolean,
    onConnect: (String, String?) -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = pu
    var clientId by remember { mutableStateOf(savedClientId) }
    var secret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(tr("Connect Google", "Google 연결"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (connected) {
                    Text(tr("Connected", "연결됨") + (email?.let { " · $it" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.success)
                    Text(tr("Uses Gmail reading and drafts, and Drive search. Sending mail is always confirmed.", "Gmail 읽기·초안, 드라이브 검색을 써요. 메일 보내기는 항상 확인받아요."), fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub)
                }
                Text(
                    GoogleOAuth.GUIDE,
                    Modifier.clip(RoundedCornerShape(10.dp)).background(c.sunken).padding(horizontal = 10.dp, vertical = 8.dp),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = c.text2,
                )
                Field(clientId, { clientId = it.trim() }, tr("Client ID (…apps.googleusercontent.com)", "클라이언트 ID (…apps.googleusercontent.com)"), tr("Google client ID", "Google 클라이언트 ID"))
                Field(secret, { secret = it.trim() }, if (connected) tr("Client secret (only to change it)", "클라이언트 보안 비밀번호 (바꿀 때만)") else tr("Client secret", "클라이언트 보안 비밀번호"), tr("Google client secret", "Google 클라이언트 보안 비밀번호"), secret = true)
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Finish signing in to Google in your browser", "브라우저에서 Google 로그인을 마쳐 주세요"), Modifier.weight(1f), fontSize = 13.sp, color = c.text2)
                        TextButton(onClick = onCancel) { Text(tr("Cancel", "취소"), color = c.text, fontSize = 13.sp) }
                    }
                }
                PuButton(
                    if (connected) tr("Reconnect", "다시 연결") else tr("Connect with Google", "Google로 연결"),
                    { onConnect(clientId, secret.ifEmpty { null }) },
                    Modifier.fillMaxWidth(),
                    enabled = GoogleOAuth.looksLikeClientId(clientId),
                    loading = busy,
                )
                if (connected) PuButton(tr("Disconnect", "연결 끊기"), onDisconnect, Modifier.fillMaxWidth(), kind = ButtonKind.OUTLINE, enabled = !busy)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "닫기"), color = c.text, fontSize = 15.sp) } },
    )
}

@Composable
private fun ServerDialog(
    server: McpServer,
    status: String?,
    busy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onToken: (String?) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = pu
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = RoundedCornerShape(22.dp),
        title = { Text(server.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(server.url, fontSize = 12.5.sp, color = c.textSub)
                val auth = when {
                    server.oauth != null -> tr("Signed in", "로그인됨")
                    server.token != null -> tr("Using token", "토큰 사용 중")
                    else -> tr("No authentication", "인증 없음")
                }
                Text(listOfNotNull(status, auth).joinToString(" · "), fontSize = 13.5.sp, color = c.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Enabled", "사용"), Modifier.weight(1f), fontSize = 15.sp, color = c.text)
                    PuSwitch(server.enabled, onEnabled, tr("Enable ${server.name}", "${server.name} 사용"))
                }
                PuButton(tr("Sign in with browser", "브라우저로 로그인"), onLogin, Modifier.fillMaxWidth(), loading = busy)
                Text(tr("For servers that can't sign in, paste an access token to connect.", "로그인이 안 되는 서버는 발급받은 토큰을 붙여 넣어 연결해요."), fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub)
                Field(token, { token = it.trim() }, tr("Bearer token", "Bearer 토큰"), tr("${server.name} token", "${server.name} 토큰"), secret = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PuButton(tr("Connect with token", "토큰으로 연결"), { onToken(token) }, Modifier.weight(1f), kind = ButtonKind.OUTLINE, enabled = token.isNotEmpty() && !busy)
                    PuButton(tr("Check again", "다시 확인"), onTest, Modifier.weight(1f), kind = ButtonKind.OUTLINE, enabled = !busy)
                }
                PuButton(tr("Delete", "삭제"), onRemove, Modifier.fillMaxWidth(), kind = ButtonKind.DANGER, enabled = !busy)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close", "닫기"), color = c.text, fontSize = 15.sp) } },
    )
}
