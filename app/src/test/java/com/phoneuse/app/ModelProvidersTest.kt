package com.phoneuse.app

import org.junit.Assert.assertEquals
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
}
