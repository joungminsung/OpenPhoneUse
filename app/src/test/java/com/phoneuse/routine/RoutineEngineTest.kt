package com.phoneuse.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutineEngineTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    // Monday 2026-11-02 08:00.
    private val now = ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, seoul)
    private val idle = EngineState()

    private fun routine(
        trigger: Trigger = Trigger.Time(8, 0),
        action: RoutineAction = RoutineAction.Ask("브리핑", needsDevice = false),
        conditions: Conditions = Conditions(),
        lastRunAt: Long? = null,
        id: Long = 1,
    ) = Routine(id = id, title = "t", trigger = trigger, conditions = conditions, action = action, lastRunAt = lastRunAt)

    private val due = RoutineEvent.TimeDue(1)

    @Test
    fun runsWhenEverythingAllows() {
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(), due, now, idle))
    }

    @Test
    fun deferredWhenRunnerBusy() {
        val busy = EngineState(runnerBusy = true)
        assertEquals(Decision.DEFER, RoutineEngine.shouldRun(routine(), due, now, busy))
        assertEquals(Decision.DEFER, RoutineEngine.shouldRun(routine(action = RoutineAction.OpenApp("com.spotify.music")), due, now, busy))
        // A plain notification needs neither the model nor the screen: it goes out at once.
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(action = RoutineAction.Notify("물 마셔요")), due, now, busy))
        // Deferred runs wait up to 30 minutes for the runner.
        assertTrue(RoutineEngine.stillDue(queuedAt = 0, now = 30 * 60_000L))
        assertFalse(RoutineEngine.stillDue(queuedAt = 0, now = 30 * 60_000L + 1))
    }

    @Test
    fun lockedRoutineNotifies() {
        val locked = EngineState(locked = true)
        val device = routine(action = RoutineAction.Ask("무음으로", needsDevice = true))
        assertEquals(Decision.NOTIFY_LOCKED, RoutineEngine.shouldRun(device, due, now, locked))
        // Even while the runner is busy, a locked phone asks for an unlock first.
        assertEquals(Decision.NOTIFY_LOCKED, RoutineEngine.shouldRun(device, due, now, locked.copy(runnerBusy = true)))
        assertEquals(Decision.NOTIFY_LOCKED, RoutineEngine.shouldRun(routine(action = RoutineAction.OpenApp("com.spotify.music")), due, now, locked))
        // Without the device, a run while locked is fine.
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(), due, now, locked))
        // "잠금 풀려 있을 때" only: skipped, not a nudge.
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(routine(conditions = Conditions(onlyWhenUnlocked = true)), due, now, locked))
    }

    @Test
    fun cooldownSkipsRepeat() {
        val charger = Trigger.Device(DeviceKind.CHARGER_CONNECTED)
        val plug = RoutineEvent.DeviceChanged(DeviceKind.CHARGER_CONNECTED, null)
        val tenMinutesAgo = now.minusMinutes(10).toInstant().toEpochMilli()
        assertEquals(Decision.SKIP_COOLDOWN, RoutineEngine.shouldRun(routine(charger, lastRunAt = tenMinutesAgo), plug, now, idle))
        val hourAgo = now.minusMinutes(61).toInstant().toEpochMilli()
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(charger, lastRunAt = hourAgo), plug, now, idle))
        // No cooldown at all.
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(charger, conditions = Conditions(cooldownMin = 0), lastRunAt = tenMinutesAgo), plug, now, idle))
        // Notification- and device-triggered runs that call the model wait at least 5 minutes, even with no cooldown.
        val twoMinutesAgo = now.minusMinutes(2).toInstant().toEpochMilli()
        val noCooldown = Conditions(cooldownMin = 0)
        assertEquals(Decision.SKIP_COOLDOWN, RoutineEngine.shouldRun(routine(charger, conditions = noCooldown, lastRunAt = twoMinutesAgo), plug, now, idle))
        assertEquals(
            Decision.RUN,
            RoutineEngine.shouldRun(routine(charger, action = RoutineAction.Notify("x"), conditions = noCooldown, lastRunAt = twoMinutesAgo), plug, now, idle),
        )
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(conditions = noCooldown, lastRunAt = twoMinutesAgo), due, now, idle))
        // "하루 한 번": once per calendar day, not 24 hours.
        val onceADay = Conditions(cooldownMin = Conditions.ONCE_A_DAY)
        val earlierToday = now.withHour(1).toInstant().toEpochMilli()
        val lateYesterday = now.minusDays(1).withHour(23).toInstant().toEpochMilli()
        assertEquals(Decision.SKIP_COOLDOWN, RoutineEngine.shouldRun(routine(charger, conditions = onceADay, lastRunAt = earlierToday), plug, now, idle))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(charger, conditions = onceADay, lastRunAt = lateYesterday), plug, now, idle))
        // Running it by hand ignores the cooldown.
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(charger, lastRunAt = tenMinutesAgo), RoutineEvent.Manual, now, idle))
    }

    @Test
    fun conditionsNarrowDaysAndHours() {
        val charger = Trigger.Device(DeviceKind.CHARGER_CONNECTED)
        val plug = RoutineEvent.DeviceChanged(DeviceKind.CHARGER_CONNECTED, null)
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(routine(charger, conditions = Conditions(days = setOf(DayOfWeek.SATURDAY))), plug, now, idle))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(charger, conditions = Conditions(days = setOf(DayOfWeek.MONDAY))), plug, now, idle))
        // 20:00–02:00 wraps past midnight: 08:00 is outside, 01:00 inside.
        val evening = Conditions(timeFrom = LocalTime.of(20, 0), timeTo = LocalTime.of(2, 0))
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(routine(charger, conditions = evening), plug, now, idle))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(charger, conditions = evening), plug, now.withHour(1), idle))
        val morning = Conditions(timeFrom = LocalTime.of(7, 0), timeTo = LocalTime.of(10, 0))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(routine(charger, conditions = morning), plug, now, idle))
    }

    @Test
    fun notificationTriggerMatchesPackageAndKeyword() {
        fun posted(pkg: String, title: String?, text: String?) = RoutineEvent.NotificationPosted(pkg, title, text, 0)
        val parcel = routine(Trigger.Notification("com.samsung.android.messaging", "택배"))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(parcel, posted("com.samsung.android.messaging", "CJ대한통운", "고객님의 택배가 오늘 도착합니다"), now, idle))
        // Another app, or the right app without the keyword: no match.
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(parcel, posted("com.kakao.talk", "CJ", "택배 도착"), now, idle))
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(parcel, posted("com.samsung.android.messaging", "엄마", "저녁 먹자"), now, idle))
        // Keyword alone: any app, case and spacing ignored, title counts.
        val keyword = routine(Trigger.Notification(keyword = "Delivery"))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(keyword, posted("com.coupang.mobile", "DELIVERY 완료", null), now, idle))
        val spaced = routine(Trigger.Notification(keyword = "택배 도착"))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(spaced, posted("x.y", null, "택배도착 알림"), now, idle))
        // Package alone: every notification of that app.
        val app = routine(Trigger.Notification("com.kakao.talk"))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(app, posted("com.kakao.talk", "민수", "ㅎㅇ"), now, idle))
        // The app's own notifications never start a routine (its results would loop).
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(keyword, posted(EngineState().ownPackage, "delivery", null), now, idle))
    }

    @Test
    fun placeExitFiresOnlyAfterEnter() {
        val leave = routine(Trigger.Place("회사", 37.5, 127.0, 200, PlaceEdge.EXIT), id = 7)
        val exit = RoutineEvent.PlaceCrossed(7, PlaceEdge.EXIT)
        // Never seen inside (e.g. the geofence was just registered while away): no "left the office".
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(leave, exit, now, idle))
        // Entering does not match an exit routine.
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(leave, RoutineEvent.PlaceCrossed(7, PlaceEdge.ENTER), now, idle))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(leave, exit, now, EngineState(insidePlaces = setOf(7))))
        // Another routine's geofence: no match.
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(leave, RoutineEvent.PlaceCrossed(8, PlaceEdge.EXIT), now, EngineState(insidePlaces = setOf(7, 8))))
        val arrive = routine(Trigger.Place("회사", 37.5, 127.0, 200, PlaceEdge.ENTER), id = 7)
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(arrive, RoutineEvent.PlaceCrossed(7, PlaceEdge.ENTER), now, idle))
        // The host's inside set follows every crossing.
        assertEquals(setOf(7L, 3L), RoutineEngine.afterCrossing(setOf(3), 7, PlaceEdge.ENTER))
        assertEquals(setOf(3L), RoutineEngine.afterCrossing(setOf(3, 7), 7, PlaceEdge.EXIT))
    }

    @Test
    fun deviceTriggersMatchKindAndDetail() {
        val car = routine(Trigger.Device(DeviceKind.BLUETOOTH_CONNECTED, "내 차"))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(car, RoutineEvent.DeviceChanged(DeviceKind.BLUETOOTH_CONNECTED, "내 차 오디오"), now, idle))
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(car, RoutineEvent.DeviceChanged(DeviceKind.BLUETOOTH_CONNECTED, "Galaxy Buds"), now, idle))
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(car, RoutineEvent.DeviceChanged(DeviceKind.BLUETOOTH_CONNECTED, null), now, idle))
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(car, RoutineEvent.DeviceChanged(DeviceKind.HEADSET_PLUGGED, null), now, idle))
        val anyWifi = routine(Trigger.Device(DeviceKind.WIFI_CONNECTED))
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(anyWifi, RoutineEvent.DeviceChanged(DeviceKind.WIFI_CONNECTED, "home"), now, idle))
    }

    @Test
    fun timeAndEventMatchTheirRoutine() {
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(routine(), RoutineEvent.TimeDue(2), now, idle))
        val before = routine(Trigger.BeforeEvent(60), id = 5)
        val soon = RoutineEvent.EventSoon(5, "치과", "강남역", now.plusHours(1).toInstant().toEpochMilli())
        assertEquals(Decision.RUN, RoutineEngine.shouldRun(before, soon, now, idle))
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(before, soon.copy(routineId = 6), now, idle))
        // A disabled routine never runs on a trigger.
        assertEquals(Decision.SKIP_CONDITION, RoutineEngine.shouldRun(routine().copy(enabled = false), due, now, idle))
    }

    @Test
    fun deferredRunsRecheckDaysWindowAndUnlockedOnly() {
        val r = routine(conditions = Conditions(timeFrom = LocalTime.of(9, 0), timeTo = LocalTime.of(18, 0), onlyWhenUnlocked = true))
        val locked = idle.copy(locked = true)
        assertFalse(RoutineEngine.conditionsStillHold(r, now, idle))
        val inside = now.withHour(10)
        assertTrue(RoutineEngine.conditionsStillHold(r, inside, idle))
        assertFalse(RoutineEngine.conditionsStillHold(r, inside, locked))
        assertFalse(RoutineEngine.conditionsStillHold(r, now.withHour(18).withMinute(20), idle))
    }
}
