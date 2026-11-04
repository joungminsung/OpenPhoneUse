package com.phoneuse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSlotTest {
    @Test fun oneRunAtATime() {
        val slot = RunSlot()
        val a = slot.claim { Any() }!!
        assertTrue(slot.running.value)
        var made = false
        assertNull(slot.claim { made = true; Any() })
        assertFalse("a refused claim makes nothing", made)
        assertTrue(slot.release(a))
        assertFalse(slot.running.value)
    }

    /** The finding: an old run's completion arriving after a new run took the slot must not free it. */
    @Test fun anOldCompletionNeverFreesANewerRun() {
        val slot = RunSlot()
        val old = slot.claim { Any() }!!
        var oldCleanup = 0
        assertTrue(slot.release(old) { oldCleanup++ })
        val new = slot.claim { Any() }!!
        // The old job's completion handler fires again (or late): nothing happens to the new run.
        assertFalse(slot.release(old) { oldCleanup++ })
        assertEquals(1, oldCleanup)
        assertTrue(slot.running.value)
        assertNull("a third start is still refused", slot.claim { Any() })
        assertTrue(slot.release(new))
        assertFalse(slot.running.value)
    }

    @Test fun releaseFreesEvenWhenCleanupThrows() {
        val slot = RunSlot()
        val a = slot.claim { Any() }!!
        runCatching { slot.release(a) { error("boom") } }
        assertFalse(slot.running.value)
        assertTrue(slot.claim { Any() } != null)
    }

    @Test fun concurrentClaimsLetExactlyOneIn() {
        val slot = RunSlot()
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..16).map { Thread { if (slot.claim { Any() } != null) winners.incrementAndGet() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
    }
}
