package com.phoneuse.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {
    @Test
    fun defaultModelIsTerra() {
        assertEquals("gpt-6.1-terra", SettingsStore.DEFAULT_MODEL)
        assertEquals(SettingsStore.DEFAULT_MODEL, MODEL_PRESETS.first())
    }

    @Test
    fun dailySummaryDoesNotLandOnTheSpendingNudge() {
        // Two proactive notifications in the same minute; the summary keeps an hour away by default.
        val spending = com.phoneuse.proactive.ProactiveEngine.SPENDING_AT.let { it.hour * 60 + it.minute }
        org.junit.Assert.assertTrue(kotlin.math.abs(SettingsStore.DEFAULT_SUMMARY_MINUTE - spending) >= 60)
        org.junit.Assert.assertTrue(SettingsStore.DEFAULT_SUMMARY_MINUTE in com.phoneuse.ui.settings.SUMMARY_OPTIONS)
    }
}
