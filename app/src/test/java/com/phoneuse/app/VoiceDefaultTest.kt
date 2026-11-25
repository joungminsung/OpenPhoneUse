package com.phoneuse.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phoneuse.security.SecretBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test

/** Voice defaults and stored choices for dictation and the side-button shortcut. */
class VoiceDefaultTest {
    private class MemoryPrefs : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private val box = object : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(sealed: String): String? = sealed
    }
    private val prefs = MemoryPrefs()
    private val settings = SettingsStore(prefs, box)

    @Test fun freshInstallFollowsTheDevice() = runBlocking {
        assertEquals(VoiceLanguage.SYSTEM, settings.voiceLanguage.first())
    }

    @Test fun savedChoiceStays() = runBlocking {
        settings.setVoiceLanguage(VoiceLanguage.KOREAN)
        assertEquals(VoiceLanguage.KOREAN, settings.voiceLanguage.first())
    }

    @Test fun sideButtonDefaultsToIdeasForThisScreen() = runBlocking {
        assertEquals(AssistAction.SUGGEST, settings.assistAction.first())
    }

    @Test fun sideButtonHonorsExistingPreferences() = runBlocking {
        val key = stringPreferencesKey("assist_action")
        prefs.edit { it[key] = "SUGGEST" }
        assertEquals(AssistAction.SUGGEST, settings.assistAction.first())
        prefs.edit { it[key] = "TYPE" }
        assertEquals(AssistAction.TYPE, settings.assistAction.first())
    }
}
