package com.phoneuse.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoutineStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun routine(title: String, trigger: Trigger = Trigger.Time(8, 0)) =
        Routine(title = title, trigger = trigger, action = RoutineAction.Notify("x"))

    @Test
    fun aFailedWriteFromTheScreenReportsFalseAndChangesNothing() {
        // A full or read-only disk must not throw into a click handler.
        val file = tmp.root.resolve("routines.json")
        val store = RoutineStore(file)
        val a = store.save(routine("아침"))
        // The temporary file's path is taken by a directory, so the next write fails.
        java.io.File(file.path + ".new").mkdirs()
        org.junit.Assert.assertFalse(store.userEdit { update(a.id) { it.copy(enabled = false) } })
        assertEquals(true, store.get(a.id)?.enabled)
        java.io.File(file.path + ".new").delete()
        org.junit.Assert.assertTrue(store.userEdit { update(a.id) { it.copy(enabled = false) } })
        assertEquals(false, store.get(a.id)?.enabled)
    }

    @Test
    fun survivesReopening() {
        val file = tmp.root.resolve("routines.json")
        val store = RoutineStore(file)
        val a = store.save(routine("아침"))
        val b = store.save(routine("회사", Trigger.Place("회사", 37.5, 127.0)))
        assertEquals(listOf(1L, 2L), listOf(a.id, b.id))
        store.update(a.id) { it.copy(enabled = false, lastRunAt = 5, lastResult = "됐어요") }
        val reminder = store.addReminder(1000, "약 먹기")
        store.inside = setOf(b.id)
        store.checkedAt = 42
        store.fires = listOf(EventFire(b.id, 10, "치과", "강남", 20))
        store.calendarAt = 99

        // Reboot: a new process reads the same file.
        val again = RoutineStore(file)
        assertEquals(store.all(), again.all())
        assertEquals(false, again.get(a.id)?.enabled)
        assertEquals(listOf(reminder), again.reminders())
        assertEquals(setOf(b.id), again.inside)
        assertEquals(42L, again.checkedAt)
        assertEquals(store.fires, again.fires)
        assertEquals(99L, again.calendarAt)
        // Ids keep growing past everything stored.
        assertEquals(4L, again.save(routine("밤")).id)
    }

    @Test
    fun deferredRunsAndGeofenceStateSurviveReopening() {
        val file = tmp.root.resolve("routines.json")
        val store = RoutineStore(file)
        val runs = listOf(
            DeferredRun(1, RoutineEvent.TimeDue(1), 10),
            DeferredRun(2, RoutineEvent.PlaceCrossed(2, PlaceEdge.EXIT), 11),
            DeferredRun(3, RoutineEvent.NotificationPosted("com.x", "CJ", "택배", 5), 12),
            DeferredRun(4, RoutineEvent.DeviceChanged(DeviceKind.BLUETOOTH_CONNECTED, "내 차"), 13),
            DeferredRun(5, RoutineEvent.EventSoon(5, "치과", "강남", 99), 14),
            DeferredRun(6, RoutineEvent.Manual, 15),
        )
        store.deferred = runs
        store.fenceKey = "1:37.5:127.0:200"
        store.fencesAt = 77
        store.placesPaused = true
        val again = RoutineStore(file)
        assertEquals(runs, again.deferred)
        assertEquals("1:37.5:127.0:200", again.fenceKey)
        assertEquals(77L, again.fencesAt)
        assertEquals(true, again.placesPaused)
        assertEquals(true, again.paused.value)
    }

    @Test
    fun deleteDropsItsState() {
        val store = RoutineStore(tmp.root.resolve("r.json"))
        val a = store.save(routine("회사", Trigger.Place("회사", 37.5, 127.0)))
        store.inside = setOf(a.id)
        store.fires = listOf(EventFire(a.id, 1, "", "", 2))
        store.delete(a.id)
        assertNull(store.get(a.id))
        assertEquals(emptySet<Long>(), store.inside)
        assertEquals(emptyList<EventFire>(), store.fires)
        assertEquals(emptyList<Routine>(), store.routines.value)
    }

    @Test
    fun remindersAreRemovedOnceDelivered() {
        val store = RoutineStore(tmp.root.resolve("r.json"))
        val one = store.addReminder(1, "a")
        val two = store.addReminder(2, "b")
        store.removeReminders(setOf(one.id))
        assertEquals(listOf(two), RoutineStore(tmp.root.resolve("r.json")).reminders())
    }

    @Test
    fun brokenFileIsMovedAsideNotOverwritten() {
        val file = tmp.root.resolve("routines.json")
        file.writeText("{not json")
        val broken = RoutineStore(file) { 1234 }
        assertEquals(emptyList<Routine>(), broken.all())
        // Kept for the user, with the time in its name; the next write starts a new file.
        val aside = tmp.root.resolve("routines.broken-1234.json")
        assertEquals(aside, broken.brokenAside)
        assertEquals("{not json", aside.readText())
        broken.save(routine("새로"))
        assertEquals("{not json", aside.readText())
        assertEquals(listOf("새로"), RoutineStore(file).all().map { it.title })
        assertNull(RoutineStore(file).brokenAside)
    }

    @Test
    fun unreadableRoutineIsSkipped() {
        val file = tmp.root.resolve("r.json")
        // An unreadable routine is skipped, the rest kept.
        file.writeText("""{"routines":[{"id":3,"title":"ok","trigger":{"type":"time","hour":8,"minute":0},"action":{"type":"notify","text":"x"}},{"id":4,"title":"bad","trigger":{"type":"place","name":"x","lat":1,"lng":1,"radius_m":10},"action":{"type":"notify","text":"x"}}]}""")
        val store = RoutineStore(file)
        assertEquals(listOf(3L), store.all().map { it.id })
        assertEquals(4L, store.save(routine("new")).id)
    }
}
