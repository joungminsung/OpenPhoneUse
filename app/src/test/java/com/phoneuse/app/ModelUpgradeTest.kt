package com.phoneuse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelUpgradeTest {
    @Test fun picksTheHighestVersionOfTheSameFamily() {
        val listed = listOf("gpt-6-luna", "gpt-6.1-terra", "gpt-6.2-terra", "gpt-6.2-terra-mini", "gpt-6.1-sol")
        assertEquals("gpt-6.2-terra", ModelUpgrade.newest("gpt-6.1-terra", listed))
    }

    @Test fun aWholeNumberAboveBeatsAPointRelease() {
        assertEquals("gpt-7-terra", ModelUpgrade.newest("gpt-6.1-terra", listOf("gpt-6.9-terra", "gpt-7-terra", "gpt-6.1-terra")))
    }

    @Test fun claudeKeepsItsTier() {
        val listed = listOf("claude-opus-5-5", "claude-sonnet-6", "claude-opus-5-6", "claude-haiku-6-1")
        assertEquals("claude-opus-5-6", ModelUpgrade.newest("claude-opus-5-5", listed))
        assertEquals("claude-haiku-6-1", ModelUpgrade.newest("claude-haiku-5-5", listed))
    }

    @Test fun datesAreNotVersions() {
        // A dated snapshot of the same version is not newer, and "0709" is a date, not version 709.
        assertNull(ModelUpgrade.newest("claude-opus-5-5", listOf("claude-opus-5-5-20261001")))
        assertEquals("grok-4.1", ModelUpgrade.newest("grok-4", listOf("grok-4-0709", "grok-4.1", "grok-4-fast-reasoning")))
        assertEquals("grok-5", ModelUpgrade.newest("grok-4", listOf("grok-4-0709", "grok-5")))
    }

    @Test fun previewsAndOtherVariantsAreAnotherFamily() {
        assertNull(ModelUpgrade.newest("gpt-6.1-terra", listOf("gpt-6.3-terra-preview", "gpt-7-terra-mini", "gpt-7-sol")))
    }

    @Test fun nothingNewerMeansNull() {
        assertNull(ModelUpgrade.newest("gpt-6.1-terra", listOf("gpt-6-terra", "gpt-6.1-terra")))
        assertNull(ModelUpgrade.newest("gpt-6.1-terra", emptyList()))
        // An alias without a number already follows the newest on the provider's side.
        assertNull(ModelUpgrade.newest("gemini-flash-latest", listOf("gemini-flash-latest", "gemini-3-flash")))
    }

    @Test fun geminiIdsMayCarryTheModelsPrefix() {
        assertEquals("models/gemini-3.5-flash", ModelUpgrade.newest("gemini-3-flash", listOf("models/gemini-3-flash", "models/gemini-3.5-flash")))
    }
}
