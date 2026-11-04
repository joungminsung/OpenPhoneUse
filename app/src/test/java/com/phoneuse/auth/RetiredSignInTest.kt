package com.phoneuse.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phoneuse.app.AuthMode
import com.phoneuse.app.ModelBackend
import com.phoneuse.app.SettingsStore
import com.phoneuse.app.chosenBackend
import com.phoneuse.i18n.tr
import com.phoneuse.security.SecretBox
import com.phoneuse.ui.loadAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** An install that upgrades from a build with the older ChatGPT sign-in: the real settings code on a preferences map. */
class RetiredSignInTest {
    private val box = RecordingBox()
    private val prefs = MemoryPrefs()
    private val settings = SettingsStore(prefs, box)
    private val planStore = SettingsPlanStore(settings)
    private val planAuth = PlanAuth(planStore, PlanTokenClient(OkHttpClient()))

    private val oldKey = stringPreferencesKey("secret_chatgpt_tokens")
    private val modeKey = stringPreferencesKey("auth_mode")
    private val oldTokens = """{"idToken":"old-id","accessToken":"old-access","refreshToken":"old-refresh","accountId":"acc","email":"old@b.c"}"""
    private val planTokens = PlanTokens("oaiapp_1", "ID", "AT", "RT", 1_800_000_000L, PlanConfig.SCOPE.split(' '), "user-1", "new@b.c")

    /** In memory: DataStore's file storage can't replace an existing file on a Windows host (Android's rename can). */
    private class MemoryPrefs : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private class RecordingBox : SecretBox {
        val opened = CopyOnWriteArrayList<String>()
        /** The Keystore failing for now: nothing opens, whatever it is. */
        @Volatile var troubled = false
        override fun seal(plain: String) = "sealed:$plain"
        override fun open(sealed: String): String? {
            opened += sealed
            if (troubled) return null
            return sealed.takeIf { it.startsWith("sealed:") }?.removePrefix("sealed:")
        }
    }

    @Test fun signingOutOrAnotherAccountEndsThePlanPauseButARefreshDoesNot() = runBlocking {
        planStore.save(planTokens)
        settings.setPlanPausedUntil(123L)
        // A refresh or a new sign-in of the same account.
        planStore.save(planTokens.copy(accessToken = "AT2", refreshToken = "RT2"))
        assertEquals(123L, settings.planPausedUntil.first())
        planStore.save(planTokens.copy(subject = "user-2"))
        assertEquals(0L, settings.planPausedUntil.first())

        settings.setPlanPausedUntil(123L)
        planStore.save(null)
        assertEquals(0L, settings.planPausedUntil.first())
    }

    private suspend fun seedOldSignIn(value: String = box.seal(oldTokens)) {
        prefs.edit {
            it[oldKey] = value
            it[modeKey] = AuthMode.CHATGPT.name
        }
    }

    @Test fun anOldSignInAloneEndsSignedOutWithItsTokensDeleted() = runBlocking {
        seedOldSignIn()

        settings.dropRetiredSecrets()

        assertNull(prefs.data.first()[oldKey])
        assertEquals(AuthMode.CHATGPT, settings.authMode.first())
        assertNull(settings.chosenBackend(planAuth))
        val account = loadAccount(settings)
        assertFalse(account.chatGptSignedIn)
        assertNull(account.email)
        assertEquals(tr("Sign-in needed", "로그인이 필요해요"), account.summary)
        // The old value was deleted without ever being decrypted.
        assertTrue(box.opened.none { "old-refresh" in it })
    }

    /** Before the cleanup has run (it is started in the background): the old tokens already count for nothing. */
    @Test fun anOldSignInIsIgnoredEvenBeforeItIsDeleted() = runBlocking {
        seedOldSignIn()

        assertNull(settings.chosenBackend(planAuth))
        assertFalse(loadAccount(settings).chatGptSignedIn)
        assertTrue(box.opened.none { "old-refresh" in it })
    }

    @Test fun aValueThisInstallCannotDecryptIsDeletedToo() = runBlocking {
        seedOldSignIn("v1:not-decryptable")

        settings.dropRetiredSecrets()

        assertNull(prefs.data.first()[oldKey])
        assertFalse(loadAccount(settings).chatGptSignedIn)
    }

    @Test fun theCleanupIsSafeToRepeatAndOnAnEmptyStore() = runBlocking {
        settings.dropRetiredSecrets()
        seedOldSignIn()
        settings.dropRetiredSecrets()
        val after = prefs.data.first()
        settings.dropRetiredSecrets()
        assertEquals(after, prefs.data.first())
    }

    @Test fun theOldUsageLimitSwitchesAreDroppedAndOtherSettingsStay() = runBlocking {
        val alert = booleanPreferencesKey("usage_alert_80")
        val fastOff = booleanPreferencesKey("usage_auto_fast_off")
        prefs.edit {
            it[alert] = false
            it[fastOff] = false
        }
        settings.setFastMode(false)
        settings.setRoutineLightModel(true)

        settings.dropRetiredPrefs()

        val after = prefs.data.first()
        assertNull(after[alert])
        assertNull(after[fastOff])
        assertFalse(settings.fastMode.first())
        assertTrue(settings.routineLightModel.first())
        settings.dropRetiredPrefs()
        assertEquals(after, prefs.data.first())
    }

    @Test fun aPlanSignInIsUntouched() = runBlocking {
        seedOldSignIn()
        planStore.save(planTokens)
        planStore.saveRegistration(PlanRegistration("oaiapp_1", "user-1", "new@b.c"))
        val host = planStore.hostId()

        settings.dropRetiredSecrets()

        assertNull(prefs.data.first()[oldKey])
        assertEquals(planTokens, planStore.load())
        assertEquals(listOf(PlanRegistration("oaiapp_1", "user-1", "new@b.c")), planStore.registrations())
        assertEquals(host, planStore.hostId())
        val backend = settings.chosenBackend(planAuth) as ModelBackend.OpenAi
        assertSame(planAuth, backend.auth)
        val account = loadAccount(settings)
        assertTrue(account.chatGptSignedIn)
        assertEquals("new@b.c", account.email)
    }

    /** The one install-wide client id of earlier builds becomes a registration, tied to its account when that shows. */
    @Test fun anEarlierInstallWideClientIdBecomesARegistrationOfItsSignIn() = runBlocking {
        planStore.save(planTokens)
        settings.writeSecret(SettingsStore.KEY_PLAN_CLIENT, "oaiapp_1")

        assertEquals(listOf(PlanRegistration("oaiapp_1", "user-1", "new@b.c")), planStore.registrations())
        assertNull(settings.readSecret(SettingsStore.KEY_PLAN_CLIENT))
        assertEquals(1, planStore.registrations().size)
    }

    @Test fun anEarlierClientIdWithoutASignInHasNoAccountYet() = runBlocking {
        settings.writeSecret(SettingsStore.KEY_PLAN_CLIENT, "oaiapp_1")
        assertEquals(listOf(PlanRegistration("oaiapp_1")), planStore.registrations())
    }

    @Test fun registrationsAreKeptPerClientNewestFirstAndForgottenOneByOne() = runBlocking {
        val a = PlanRegistration("oaiapp_1", "user-1", "a@b.c")
        val b = PlanRegistration("oaiapp_2", "user-2", "a@b.c")
        planStore.saveRegistration(a)
        planStore.saveRegistration(b)
        planStore.saveRegistration(a)
        assertEquals(listOf(a, b), planStore.registrations())
        planStore.forgetRegistration("oaiapp_1")
        assertEquals(listOf(b), planStore.registrations())
    }

    @Test fun keystoreTroubleDoesNotOverwriteTheRegistrations() = runBlocking {
        val a = PlanRegistration("oaiapp_1", "user-1", "a@b.c")
        planStore.saveRegistration(a)
        box.troubled = true
        assertEquals(emptyList<PlanRegistration>(), planStore.registrations())
        planStore.saveRegistration(PlanRegistration("oaiapp_2", "user-2"))
        box.troubled = false
        assertEquals(listOf(a), planStore.registrations())
    }

    /** Readable values go in while the old one stays unreadable: that one is lost for good (a key reset), so it is replaced. */
    @Test fun aRegistrationListLostForGoodIsReplaced() = runBlocking {
        val b = PlanRegistration("oaiapp_2", "user-2")
        prefs.edit { it[stringPreferencesKey("secret_" + SettingsStore.KEY_PLAN_REGISTRATIONS)] = "v1:not-decryptable" }
        planStore.saveRegistration(b)
        assertEquals(listOf(b), planStore.registrations())
    }

    @Test fun signingOutKeepsTheRegistration() = runBlocking {
        val a = PlanRegistration("oaiapp_1", "user-1", "a@b.c")
        planStore.save(planTokens)
        planStore.saveRegistration(a)
        planStore.save(null)
        assertNull(planStore.load())
        assertEquals(listOf(a), planStore.registrations())
    }

    @Test fun theFirstPlanWelcomeIsShownOnce() = runBlocking {
        assertTrue(settings.claimPlanWelcome())
        assertFalse(settings.claimPlanWelcome())
    }

    @Test fun thePlanIsOnlyInUseWhenItsScopeWasGranted() = runBlocking {
        planStore.save(planTokens)
        assertTrue(loadAccount(settings).planEnabled)
        planStore.save(planTokens.copy(scopes = listOf("openid", "email")))
        val account = loadAccount(settings)
        assertTrue(account.chatGptSignedIn)
        assertFalse(account.planEnabled)
    }

    @Test fun apiKeysAreUntouched() = runBlocking {
        seedOldSignIn()
        settings.setApiKey("sk-test")
        settings.setProviderKey(AuthMode.CLAUDE, "sk-ant-test")
        settings.setAuthMode(AuthMode.API_KEY)

        settings.dropRetiredSecrets()

        assertEquals("sk-test", settings.apiKey.first())
        assertEquals("sk-ant-test", settings.providerKey(AuthMode.CLAUDE).first())
        assertEquals(AuthMode.API_KEY, settings.authMode.first())
        val backend = settings.chosenBackend(planAuth) as ModelBackend.OpenAi
        assertTrue(backend.auth is ApiKeyAuth)
        assertTrue(loadAccount(settings).hasApiKey)
    }

    @Test fun everyPersistedModeNameStillReads() = runBlocking {
        for (mode in AuthMode.entries) {
            prefs.edit { it[modeKey] = mode.name }
            assertEquals(mode, settings.authMode.first())
        }
    }

    @Test fun anUnknownPersistedModeNameFallsBackWithoutCrashing() = runBlocking {
        for (name in listOf("CODEX", "CHATGPT_CODEX", "chatgpt", "")) {
            prefs.edit { it[modeKey] = name }
            assertEquals(AuthMode.CHATGPT, settings.authMode.first())
            assertTrue(settings.model.first().isNotBlank())
            assertTrue(settings.routineModel.first().isNotBlank())
            assertNull(settings.chosenBackend(planAuth))
            assertFalse(loadAccount(settings).chatGptSignedIn)
        }
    }
}
