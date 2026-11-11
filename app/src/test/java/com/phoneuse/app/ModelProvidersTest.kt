package com.phoneuse.app

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProvidersTest {
    @Test fun fastModeIsForTheChatGptPlanAndAnOpenAiApiKey() {
        assertEquals(listOf(AuthMode.CHATGPT, AuthMode.API_KEY), AuthMode.entries.filter { ModelProviders.supportsFast(it) })
    }

    @Test fun astraIsOfferedWithTheChatGptPlanAndWithAnApiKey() {
        for (mode in listOf(AuthMode.CHATGPT, AuthMode.API_KEY)) {
            assertTrue(ModelProviders.presets(mode).contains("gpt-6-astra"))
            assertTrue(ModelProviders.presets(mode).contains("gpt-6.1-terra"))
        }
    }

    @Test fun aNewerListedModelBecomesTheDefaultUnlessOneWasPicked() {
        val prefs = mutablePreferencesOf()
        assertEquals("gpt-6.1-terra", ModelProviders.chosenModel(prefs, AuthMode.CHATGPT))
        val (default, light) = ModelProviders.upgrades(AuthMode.CHATGPT, listOf("gpt-6.1-terra", "gpt-6.2-terra", "gpt-6-luna", "gpt-7-luna"))
        assertEquals("gpt-6.2-terra", default)
        assertEquals("gpt-7-luna", light)
        prefs[ModelProviders.autoPref(AuthMode.CHATGPT)] = default!!
        prefs[ModelProviders.autoLightPref(AuthMode.CHATGPT)] = light!!
        assertEquals("gpt-6.2-terra", ModelProviders.chosenModel(prefs, AuthMode.CHATGPT))
        assertEquals("gpt-7-luna", ModelProviders.routineModel(prefs, AuthMode.CHATGPT, light = true))
        prefs[ModelProviders.modelPref(AuthMode.CHATGPT)] = "gpt-6-astra"
        assertEquals("gpt-6-astra", ModelProviders.chosenModel(prefs, AuthMode.CHATGPT))
    }

    @Test fun grokAndGeminiHaveNoLightModel() {
        assertEquals(null, ModelProviders.lightModel(mutablePreferencesOf(), AuthMode.GROK))
        assertEquals(null, ModelProviders.lightModel(mutablePreferencesOf(), AuthMode.GEMINI))
    }

    @Test fun anOpenAiKeyListsTheGptChatgptAndOFamiliesOnly() {
        for (id in listOf("gpt-6.1-terra", "chatgpt-6-latest", "o5", "o4-mini")) assertTrue(id, ModelProviders.openAiChatModel(id))
        for (id in listOf("text-embedding-3-large", "whisper-1", "dall-e-3", "gpt-6-transcribe", "gpt-6-realtime", "omni-moderation-latest", "gpt-image-2", "gpt-6-tts"))
            assertFalse(id, ModelProviders.openAiChatModel(id))
    }
}
