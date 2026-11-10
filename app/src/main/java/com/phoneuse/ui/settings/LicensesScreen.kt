package com.phoneuse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneuse.R
import com.phoneuse.i18n.tr
import com.phoneuse.ui.theme.IconBtn
import com.phoneuse.ui.theme.MonoStyle
import com.phoneuse.ui.theme.PuIcons
import com.phoneuse.ui.theme.pu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "오픈소스 라이선스" (Settings → 정보): the libraries bundled in this build and the licences they come under. */
@Composable
internal fun LicensesPage(onBack: () -> Unit, modifier: Modifier) {
    val c = pu
    val context = LocalContext.current
    // null while loading; an empty list also covers a file that could not be read.
    var notices by remember { mutableStateOf<LicenseNotices?>(null) }
    LaunchedEffect(Unit) {
        notices = withContext(Dispatchers.IO) {
            try {
                Licenses.parse(context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                LicenseNotices(emptyList(), emptyList())
            }
        }
    }
    var opened by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(PuIcons.Back, tr("Back", "뒤로"), onBack)
            Text(tr("Open-source licenses", "오픈소스 라이선스"), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.width(44.dp))
        }
        val list = notices
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
        ) {
            Text(
                tr(
                    "OpenPhoneUse's own code is under the MIT License. This build also bundles the libraries below, each under its own license.",
                    "OpenPhoneUse의 코드는 MIT 라이선스예요. 이 앱에는 아래 라이브러리도 들어 있고, 각자의 라이선스를 따라요.",
                ),
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp), fontSize = 12.5.sp, lineHeight = 18.sp, color = c.textSub,
            )
            if (list == null) return@Column
            if (list.libraries.isEmpty()) {
                Text(tr("Couldn't load the list.", "목록을 불러오지 못했어요."), Modifier.padding(6.dp), fontSize = 14.sp, color = c.textSub)
                return@Column
            }
            SectionTitle(tr("Libraries · ${list.libraries.size}", "라이브러리 · ${list.libraries.size}개"))
            LicenseGroup {
                list.libraries.forEachIndexed { i, lib -> LibraryRow(lib, first = i == 0, texts = list.texts) }
            }
            SectionTitle(tr("License texts", "라이선스 전문"))
            LicenseGroup {
                list.texts.forEachIndexed { i, license ->
                    val open = opened == license.id
                    LicenseDivider(first = i == 0)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, enabled = license.text.isNotEmpty()) { opened = if (open) null else license.id }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(license.name, fontSize = 15.sp, color = c.text)
                        if (license.text.isEmpty()) {
                            Text(license.url, fontSize = 12.5.sp, color = c.textSub)
                        } else if (open) {
                            Text(license.text, Modifier.padding(top = 6.dp), style = MonoStyle, fontSize = 11.5.sp, lineHeight = 16.sp, color = c.textSub)
                        } else {
                            Text(tr("Tap to read", "눌러서 보기"), fontSize = 12.5.sp, color = c.textSub)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 6.dp),
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        color = pu.textSub,
    )
}

@Composable
private fun LibraryRow(lib: LicensedLibrary, first: Boolean, texts: List<LicenseText>) {
    val c = pu
    LicenseDivider(first)
    Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lib.name, Modifier.weight(1f, fill = false), fontSize = 15.sp, color = c.text)
            if (lib.version.isNotEmpty()) Text("  " + lib.version, style = MonoStyle, fontSize = 12.5.sp, color = c.textSub)
        }
        val licenseNames = lib.licenseIds.joinToString(", ") { id -> texts.firstOrNull { it.id == id }?.name ?: id }
        Text(listOf(lib.author, licenseNames).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 12.5.sp, lineHeight = 17.sp, color = c.textSub)
    }
}

// Same look as the Settings groups, which are private to that file.
@Composable
private fun LicenseGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(pu.surface).border(1.dp, pu.line, shape), content = content)
}

@Composable
private fun LicenseDivider(first: Boolean) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(pu.lineSoft))
}
