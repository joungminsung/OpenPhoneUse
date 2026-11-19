package com.phoneuse.routine

import com.phoneuse.i18n.Lang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val HANGUL = Regex("[가-힣]")

class RoutineJsonEnglishTest {
    private var saved = true

    @Before fun english() {
        saved = Lang.korean
        Lang.korean = false
    }

    @After fun restore() {
        Lang.korean = saved
    }

    private fun error(json: String, fromModel: Boolean = true): String {
        val r = RoutineJson.parse(Json.parseToJsonElement(json).jsonObject, fromModel)
        assertTrue("expected Error, got $r for $json", r is RoutineParse.Error)
        return (r as RoutineParse.Error).reason
    }

    private val ask = """"action":{"type":"ask","prompt":"p"}"""
    private val time = """"trigger":{"type":"time","hour":9,"minute":0}"""
    private val place = """"type":"place","name":"home","lat":37.5,"lng":127.0"""

    @Test fun errorsAreEnglishAndKeepJsonKeys() {
        assertEquals("trigger.hour is missing", error("""{"title":"t","trigger":{"type":"time","minute":0},$ask}"""))
        assertEquals("trigger.hour must be 0–23 (24)", error("""{"title":"t","trigger":{"type":"time","hour":24,"minute":0},$ask}"""))
        assertEquals("title is empty", error("""{"title":" ","trigger":{"type":"time","hour":9,"minute":0},$ask}"""))
        assertEquals("trigger.minute must be 0–59 (60)", error("""{"title":"t","trigger":{"type":"time","hour":9,"minute":60},$ask}"""))
        assertEquals("trigger.radius_m must be at least 150 m (1)", error("""{"title":"t","trigger":{$place,"radius_m":1},$ask}"""))
        assertEquals("trigger.radius_m must be at most 5000 m (9000)", error("""{"title":"t","trigger":{$place,"radius_m":9000},$ask}"""))
        assertEquals("trigger.on must be enter|exit (x)", error("""{"title":"t","trigger":{$place,"on":"x"},$ask}"""))
        assertEquals("trigger.kind must be one of charger|bluetooth|headset|wifi (x)", error("""{"title":"t","trigger":{"type":"device","kind":"x"},$ask}"""))
        assertEquals("Unknown trigger.type: x (time|place|notification|device|before_event)", error("""{"title":"t","trigger":{"type":"x"},$ask}"""))
        assertEquals("A notification trigger needs a package or a keyword", error("""{"title":"t","trigger":{"type":"notification"},$ask}"""))
        assertEquals("trigger.package is not an app package name (x)", error("""{"title":"t","trigger":{"type":"notification","package":"x"},$ask}"""))
        assertEquals("trigger.lat/lng is missing (the place's coordinates)", error("""{"title":"t","trigger":{"type":"place","name":"h"},$ask}"""))
        assertEquals("conditions.time_from and time_to must be given together", error("""{"title":"t",$time,"conditions":{"time_from":"09:00"},$ask}"""))
        assertEquals("trigger.at must be HH:mm (25:00)", error("""{"title":"t","trigger":{"type":"time","at":"25:00"},$ask}"""))
        assertEquals("hour must be a number", error("""{"title":"t","trigger":{"type":"time","hour":"x"},$ask}"""))
        assertEquals("hour must be an integer", error("""{"title":"t","trigger":{"type":"time","hour":1.5},$ask}"""))
        assertEquals("trigger must be an object", error("""{"title":"t","trigger":"x",$ask}"""))
        assertEquals("title must be a string", error("""{"title":{},$time,$ask}"""))
        assertEquals("action.steps must be an array", error("""{"title":"t",$time,"action":{"type":"ask","prompt":"p","steps":"x"}}"""))
        assertEquals("An action.steps item is invalid", error("""{"title":"t",$time,"action":{"type":"ask","prompt":"p","steps":[[1]]}}"""))
        assertEquals("trigger.days items must be mon…sun (\"x\")", error("""{"title":"t","trigger":{"type":"time","hour":9,"days":["x"]},$ask}"""))
        assertEquals("Not a JSON object", (RoutineJson.parse("[1]", fromModel = true) as RoutineParse.Error).reason)
    }

    @Test fun noErrorHasKorean() {
        val t = "\"t\""
        val long = "x".repeat(2000)
        val steps = (1..11).joinToString(",") { "\"s\"" }
        val bad = listOf(
            """{$time,$ask}""",
            """{"title":"$long",$time,$ask}""",
            """{"title":$t,$ask}""",
            """{"title":$t,$time}""",
            """{"title":$t,"trigger":{"type":"time","minute":0},$ask}""",
            """{"title":$t,"trigger":{"type":"time","hour":24},$ask}""",
            """{"title":$t,"trigger":{"type":"time","hour":9,"minute":60},$ask}""",
            """{"title":$t,"trigger":{"type":"place","name":" ","lat":1,"lng":1},$ask}""",
            """{"title":$t,"trigger":{"type":"place","name":"h","lng":1},$ask}""",
            """{"title":$t,"trigger":{"type":"place","name":"h","lat":1},$ask}""",
            """{"title":$t,"trigger":{"type":"place","name":"h","lat":99,"lng":1},$ask}""",
            """{"title":$t,"trigger":{$place,"radius_m":1},$ask}""",
            """{"title":$t,"trigger":{$place,"radius_m":99999},$ask}""",
            """{"title":$t,"trigger":{$place,"on":"x"},$ask}""",
            """{"title":$t,"trigger":{"type":"notification"},$ask}""",
            """{"title":$t,"trigger":{"type":"notification","package":"x"},$ask}""",
            """{"title":$t,"trigger":{"type":"device","kind":"x"},$ask}""",
            """{"title":$t,"trigger":{"type":"before_event"},$ask}""",
            """{"title":$t,"trigger":{"type":"before_event","minutes":99999},$ask}""",
            """{"title":$t,"trigger":{"type":"x"},$ask}""",
            """{"title":$t,$time,"conditions":{"cooldown_min":-1},$ask}""",
            """{"title":$t,$time,"conditions":{"time_from":"09:00"},$ask}""",
            """{"title":$t,$time,"conditions":{"time_from":"x","time_to":"10:00"},$ask}""",
            """{"title":$t,$time,"action":{"type":"ask","prompt":" "}}""",
            """{"title":$t,$time,"action":{"type":"ask","prompt":"$long$long"}}""",
            """{"title":$t,$time,"action":{"type":"notify","text":" "}}""",
            """{"title":$t,$time,"action":{"type":"open_app"}}""",
            """{"title":$t,$time,"action":{"type":"open_app","package":"x"}}""",
            """{"title":$t,$time,"action":{"type":"x"}}""",
            """{"title":$t,$time,"action":{"type":"ask","prompt":"p","steps":"x"}}""",
            """{"title":$t,$time,"action":{"type":"ask","prompt":"p","steps":[$steps]}}""",
            """{"title":$t,$time,"action":{"type":"ask","prompt":"p","steps":[[1]]}}""",
            """{"title":$t,"trigger":{"type":"time","hour":9,"days":"x"},$ask}""",
            """{"title":$t,"trigger":{"type":"time","hour":9,"days":["x"]},$ask}""",
            """{"title":$t,$time,"conditions":{"days":"x"},$ask}""",
            """{"title":$t,"trigger":{"type":"time","at":"25:00"},$ask}""",
            """{"title":{},$time,$ask}""",
            """{"title":$t,"trigger":"x",$ask}""",
            """{"title":$t,"trigger":{"type":"time","hour":"x"},$ask}""",
            """{"title":$t,"trigger":{"type":"time","hour":1.5},$ask}""",
            """{"title":$t,$time,"action":{"type":"ask","prompt":"p","needs_device":"x"}}""",
        )
        for (json in bad) {
            val r = error(json)
            assertFalse("$json -> $r", HANGUL.containsMatchIn(r))
        }
        // Stored-only fields (read when not fromModel).
        for (json in listOf("""{"title":$t,$time,$ask,"id":"x"}""", """{"title":$t,$time,$ask,"enabled":"x"}""")) {
            val r = error(json, fromModel = false)
            assertFalse("$json -> $r", HANGUL.containsMatchIn(r))
        }
        val notObject = (RoutineJson.parse("[1]", fromModel = true) as RoutineParse.Error).reason
        assertFalse(HANGUL.containsMatchIn(notObject))
    }

    @Test fun koreanMessagesAreUnchanged() {
        Lang.korean = true
        assertEquals("trigger.hour가 없어요", error("""{"title":"t","trigger":{"type":"time","minute":0},$ask}"""))
        assertEquals("trigger.hour는 0–23이어야 해요 (24)", error("""{"title":"t","trigger":{"type":"time","hour":24},$ask}"""))
    }
}
