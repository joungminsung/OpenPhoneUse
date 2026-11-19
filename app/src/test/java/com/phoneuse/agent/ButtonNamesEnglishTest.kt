package com.phoneuse.agent

import com.phoneuse.cards.CardJson
import com.phoneuse.cards.PermissionCard
import com.phoneuse.cards.RoutineCard
import com.phoneuse.connectors.GoogleTools
import com.phoneuse.i18n.Lang
import com.phoneuse.phone.ConfirmGate
import com.phoneuse.phone.ConfirmKind
import com.phoneuse.phone.RunTrigger
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ButtonNamesEnglishTest {
    private val HANGUL = Regex("[가-힣]")
    private var saved = true

    @Before fun english() { saved = Lang.korean; Lang.korean = false }
    @After fun restore() { Lang.korean = saved }

    @Test fun descriptionsNameNoKoreanButton() {
        val specs = PhoneToolSpecs.all + RoutineModule.SPECS
        for (n in listOf("calendar_add", "photos_album", "make_routine")) {
            val d = specs.first { it.name == n }.description
            // make_routine carries Korean user-phrase examples, which are not button names.
            assertFalse(n + ": " + d, d.contains("[그대로 저장]") || d.contains("[앨범 만들기]") || d.contains("[루틴 저장]"))
        }
    }

    @Test fun confirmRefusalNamesTheEnglishButton() {
        val r = ConfirmGate.refusal(ConfirmKind.EVENT, RunTrigger.Other, tainted = false)!!
        assertTrue(r, r.contains("[Save as is]"))
        assertFalse(r, HANGUL.containsMatchIn(r))
        val a = ConfirmGate.refusal(ConfirmKind.ALBUM, RunTrigger.Other, tainted = false)!!
        assertTrue(a, a.contains("[Make album]"))
    }

    @Test fun historySummariesNameTheEnglishButtons() {
        val p = CardJson.summary(PermissionCard("Location", listOf("x")))
        assertTrue(p, p.contains("[Allow permission]"))
        assertFalse(p, HANGUL.containsMatchIn(p))
        val r = CardJson.summary(RoutineCard(kotlinx.serialization.json.Json.parseToJsonElement("""{"title":"Brief","trigger":{"type":"time","hour":7,"minute":30},"action":{"type":"ask","prompt":"brief me"}}""") as kotlinx.serialization.json.JsonObject))
        assertTrue(r, r.contains("[Save routine]"))
        assertFalse(r, HANGUL.containsMatchIn(r))
    }

    @Test fun googleToolsUseTheEnglishConnectorTag() {
        val d = GoogleTools(com.phoneuse.connectors.GoogleApi(okhttp3.OkHttpClient(), token = { "t" })).specs
        assertTrue(d.isNotEmpty() && d.all { it.description.startsWith("[Google connector] ") })
    }
}
