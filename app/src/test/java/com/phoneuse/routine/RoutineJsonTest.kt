package com.phoneuse.routine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class RoutineJsonTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun ok(s: String, fromModel: Boolean = true): Routine {
        val r = RoutineJson.parse(obj(s), fromModel)
        assertTrue("expected Ok, got $r", r is RoutineParse.Ok)
        return (r as RoutineParse.Ok).routine
    }

    private fun error(s: String): String {
        val r = RoutineJson.parse(obj(s), fromModel = true)
        assertTrue("expected Error, got $r", r is RoutineParse.Error)
        return (r as RoutineParse.Error).reason
    }

    private val ask = """"action":{"type":"ask","prompt":"오늘 일정 알려줘"}"""

    @Test
    fun parsesEveryTriggerKind() {
        assertEquals(
            Trigger.Time(7, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            ok("""{"title":"아침","trigger":{"type":"time","hour":7,"minute":30,"days":["mon","fri"]},$ask}""").trigger,
        )
        assertEquals(
            Trigger.Place("회사", 37.498, 127.027, 200, PlaceEdge.EXIT),
            ok("""{"title":"퇴근","trigger":{"type":"place","name":"회사","lat":37.498,"lng":127.027,"radius_m":200,"on":"exit"},$ask}""").trigger,
        )
        assertEquals(
            Trigger.Notification("com.kakao.talk", "택배"),
            ok("""{"title":"택배","trigger":{"type":"notification","package":"com.kakao.talk","keyword":"택배"},$ask}""").trigger,
        )
        assertEquals(
            Trigger.Device(DeviceKind.BLUETOOTH_CONNECTED, "내 차"),
            ok("""{"title":"차","trigger":{"type":"device","kind":"bluetooth","detail":"내 차"},$ask}""").trigger,
        )
        for ((wire, kind) in listOf("charger" to DeviceKind.CHARGER_CONNECTED, "headset" to DeviceKind.HEADSET_PLUGGED, "wifi" to DeviceKind.WIFI_CONNECTED)) {
            assertEquals(Trigger.Device(kind), ok("""{"title":"t","trigger":{"type":"device","kind":"$wire"},$ask}""").trigger)
        }
        assertEquals(
            Trigger.BeforeEvent(60),
            ok("""{"title":"출발","trigger":{"type":"before_event","minutes":60},$ask}""").trigger,
        )
    }

    @Test
    fun parsesConditionsAndActions() {
        val r = ok(
            """{"title":"일 모드","trigger":{"type":"device","kind":"charger"},
               "conditions":{"days":["mon","tue","wed","thu","fri"],"time_from":"20:00","time_to":"02:00","only_when_unlocked":true,"cooldown_min":1440},
               "action":{"type":"ask","prompt":"무음으로","needs_device":true,"steps":[{"text":"무음 켜기","device":true},"회의 알려주기"]}}""",
        )
        assertEquals(
            Conditions(
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                timeFrom = LocalTime.of(20, 0),
                timeTo = LocalTime.of(2, 0),
                onlyWhenUnlocked = true,
                cooldownMin = 1440,
            ),
            r.conditions,
        )
        assertEquals(RoutineAction.Ask("무음으로", true, listOf(RoutineStep("무음 켜기", true), RoutineStep("회의 알려주기"))), r.action)
        assertEquals(RoutineAction.Notify("물 마셔요"), ok("""{"title":"t","trigger":{"type":"time","hour":9,"minute":0},"action":{"type":"notify","text":"물 마셔요"}}""").action)
        assertEquals(
            RoutineAction.OpenApp("com.spotify.music", "Spotify"),
            ok("""{"title":"t","trigger":{"type":"device","kind":"headset"},"action":{"type":"open_app","package":"com.spotify.music","name":"Spotify"}}""").action,
        )
    }

    @Test
    fun rejectsRadiusUnder150() {
        val reason = error("""{"title":"회사","trigger":{"type":"place","name":"회사","lat":37.5,"lng":127.0,"radius_m":100},$ask}""")
        assertTrue(reason, reason.contains("150"))
        // 150 itself is fine, and a missing radius takes the default.
        assertEquals(150, (ok("""{"title":"회사","trigger":{"type":"place","name":"회사","lat":37.5,"lng":127.0,"radius_m":150},$ask}""").trigger as Trigger.Place).radiusM)
        assertEquals(Routine.DEFAULT_RADIUS_M, (ok("""{"title":"회사","trigger":{"type":"place","name":"회사","lat":37.5,"lng":127.0},$ask}""").trigger as Trigger.Place).radiusM)
    }

    @Test
    fun rejectsBadInput() {
        error("""{"trigger":{"type":"time","hour":7,"minute":0},$ask}""") // no title
        error("""{"title":"t","trigger":{"type":"time","hour":24,"minute":0},$ask}""")
        error("""{"title":"t","trigger":{"type":"time","hour":7,"minute":0,"days":["someday"]},$ask}""")
        error("""{"title":"t","trigger":{"type":"place","name":"회사","lat":137.5,"lng":127.0},$ask}""")
        error("""{"title":"t","trigger":{"type":"place","name":"회사"},$ask}""")
        // A notification trigger with neither app nor keyword would fire on every notification.
        error("""{"title":"t","trigger":{"type":"notification"},$ask}""")
        error("""{"title":"t","trigger":{"type":"device","kind":"toaster"},$ask}""")
        error("""{"title":"t","trigger":{"type":"before_event","minutes":0},$ask}""")
        error("""{"title":"t","trigger":{"type":"teleport"},$ask}""")
        error("""{"title":"t","trigger":{"type":"time","hour":7,"minute":0},"action":{"type":"ask","prompt":"  "}}""")
        error("""{"title":"t","trigger":{"type":"time","hour":7,"minute":0},"action":{"type":"open_app","package":"not a package"}}""")
        error("""{"title":"t","trigger":{"type":"time","hour":7,"minute":0},"conditions":{"time_from":"25:00"},$ask}""")
    }

    @Test
    fun modelCannotSkipSendConfirmation() {
        val r = ok(
            """{"title":"t","trigger":{"type":"time","hour":7,"minute":0},$ask,"send_without_confirm":true,"id":9,"enabled":false,"conversation_id":3}""",
        )
        assertFalse(r.sendWithoutConfirm)
        assertEquals(0L, r.id)
        assertTrue(r.enabled)
        assertEquals(null, r.conversationId)
    }

    @Test
    fun storageRoundTripKeepsEverything() {
        val r = Routine(
            id = 4,
            title = "일 모드",
            trigger = Trigger.Place("회사", 37.5, 127.03, 300, PlaceEdge.ENTER),
            conditions = Conditions(days = setOf(DayOfWeek.MONDAY), timeFrom = LocalTime.of(7, 0), timeTo = LocalTime.of(10, 30), onlyWhenUnlocked = true, cooldownMin = 0),
            action = RoutineAction.Ask("무음", true, listOf(RoutineStep("무음 켜기", true))),
            enabled = false,
            lastRunAt = 1_700_000_000_000,
            lastResult = "무음으로 했어요",
            conversationId = 12,
            sendWithoutConfirm = true,
            sendTo = "엄마",
            sendBody = "출근했어요",
            sentence = "회사 도착하면 무음",
            cardKey = "k1",
        )
        assertEquals(r, ok(RoutineJson.encode(r).toString(), fromModel = false))
        for (t in listOf(Trigger.Time(23, 0), Trigger.Notification(keyword = "택배"), Trigger.Device(DeviceKind.WIFI_CONNECTED, "home"), Trigger.BeforeEvent(30))) {
            val x = r.copy(trigger = t, action = RoutineAction.Notify("hi"))
            assertEquals(x, ok(RoutineJson.encode(x).toString(), fromModel = false))
        }
        val y = r.copy(action = RoutineAction.OpenApp("com.spotify.music", null))
        assertEquals(y, ok(RoutineJson.encode(y).toString(), fromModel = false))
    }

    @Test
    fun askWithoutNeedsDeviceIsHeldWhileLocked() {
        fun ask(extra: String) = ok("""{"title":"t","trigger":{"type":"time","at":"08:00"},"action":{"type":"ask","prompt":"p"$extra}}""")
        assertTrue(ask("").needsDevice)
        assertFalse(ask(""","needs_device":false""").needsDevice)
        assertTrue(ask(""","needs_device":false,"steps":[{"text":"열기","device":true}]""").needsDevice)
    }
}
