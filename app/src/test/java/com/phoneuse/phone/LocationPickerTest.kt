package com.phoneuse.phone

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPickerTest {
    /** [fine] false: like Android with "approximate" only, any GPS read throws SecurityException. */
    private class FakeProviders(
        val providers: List<String> = listOf("fused", "network", "gps"),
        var fine: Boolean = true,
        val last: Map<String, ProviderFix> = emptyMap(),
        val current: Map<String, ProviderFix?> = emptyMap(),
        val refuse: Set<String> = emptySet(),
        val slow: Set<String> = emptySet(),
    ) : LocationProviders {
        val asked = mutableListOf<String>()
        val currentAsked = mutableListOf<String>()

        override fun enabled() = providers

        override fun fineGranted() = fine

        private fun check(p: String) {
            asked += p
            if (p in refuse || (p == "gps" && !fine)) throw SecurityException("no $p")
        }

        override fun lastKnown(provider: String): ProviderFix? {
            check(provider)
            return last[provider]
        }

        override suspend fun current(provider: String): ProviderFix? {
            currentAsked += provider
            check(provider)
            if (provider in slow) delay(60_000)
            return current[provider]
        }
    }

    private fun fix(lat: Double, ageMs: Long) = ProviderFix(lat, 127.0, 20f, ageMs)

    @Test
    fun approximateOnlySkipsGpsAndStillAnswers() = runBlocking {
        val api = FakeProviders(fine = false, current = mapOf("network" to fix(37.1, 0)))
        val got = LocationPicker(api).pick()
        assertEquals(37.1, got!!.lat, 0.0)
        assertFalse(api.asked.toString(), "gps" in api.asked)
    }

    @Test
    fun refusingProviderIsSkippedNotAPermissionError() = runBlocking {
        // fused throws SecurityException; network still answers.
        val api = FakeProviders(refuse = setOf("fused"), current = mapOf("network" to fix(37.2, 0)))
        assertEquals(37.2, LocationPicker(api).pick()!!.lat, 0.0)
    }

    @Test
    fun freshestLastKnownAcrossProviders() = runBlocking {
        val api = FakeProviders(last = mapOf("gps" to fix(1.0, 90_000), "network" to fix(2.0, 30_000), "passive" to fix(3.0, 60_000)))
        val got = LocationPicker(api).pick()!!
        assertEquals(2.0, got.lat, 0.0)
        assertEquals(30L, got.ageS)
        // A recent fix answers without asking for a new one.
        assertEquals(emptyList<String>(), api.currentAsked)
    }

    @Test
    fun fusedEmptyFallsToNetworkThenStaleLastKnown() = runBlocking {
        val api = FakeProviders(current = mapOf("fused" to null, "network" to fix(5.0, 0)))
        assertEquals(5.0, LocationPicker(api).pick()!!.lat, 0.0)
        // Nothing fresh anywhere: the old fix, with its age.
        val stale = FakeProviders(last = mapOf("network" to fix(6.0, 30 * 60_000L)))
        val got = LocationPicker(stale).pick()!!
        assertEquals(6.0, got.lat, 0.0)
        assertEquals(1800L, got.ageS)
        assertNull(LocationPicker(FakeProviders()).pick())
    }

    @Test
    fun oneTimeoutBudgetForAllProviders() = runBlocking {
        val api = FakeProviders(slow = setOf("fused", "network", "gps"), last = mapOf("network" to fix(7.0, 600_000)))
        val got = LocationPicker(api, timeoutMs = 50).pick()!!
        assertEquals(7.0, got.lat, 0.0)
    }

    @Test
    fun staleFixIsReportedAsSuch() = runBlocking {
        val source = object : LocationSource {
            override fun hasPermission() = true
            override fun isEnabled() = true
            override suspend fun current() = LocationFix(37.0, 127.0, 30f, ageS = 1500)
        }
        val reply = LocationTool(source).get() as LocationReply.Ok
        assertTrue(reply.stale)
        assertTrue(reply.text, reply.text.contains("\"age_s\":1500") && reply.text.contains("\"stale\":true") && reply.text.contains("25분 전"))
        val fresh = LocationReply.Ok(37.0, 127.0, 30f, null, ageS = 5)
        assertFalse(fresh.stale)
        assertFalse(fresh.text.contains("stale"))
    }
}
