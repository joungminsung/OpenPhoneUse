package com.phoneuse.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionFlowTest {
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val location = CardAction.RequestPermission(listOf(fine, coarse), "위치")

    @Test
    fun anyGrantIsEnough() {
        // "Approximate": coarse only.
        assertEquals(PermissionFlow.Outcome.GRANTED, PermissionFlow.after(mapOf(fine to false, coarse to true), canAskAgain = false))
    }

    @Test
    fun permanentDenialOpensSettings() {
        val denied = mapOf(fine to false, coarse to false)
        // The dialog can still come back: just a denial.
        assertEquals(PermissionFlow.Outcome.DENIED, PermissionFlow.after(denied, canAskAgain = true))
        // "Don't ask again" (or denied twice): no dialog left, so the settings page.
        assertEquals(PermissionFlow.Outcome.OPEN_SETTINGS, PermissionFlow.after(denied, canAskAgain = false))
        // Dismissed without an answer: not treated as permanent.
        assertEquals(PermissionFlow.Outcome.DENIED, PermissionFlow.after(emptyMap(), canAskAgain = false))
        assertEquals(PermissionFlow.SettingsPage.APP_DETAILS, PermissionFlow.settingsPage(location))
    }

    @Test
    fun notificationsGoToTheirSettingsPage() {
        val below13 = CardActions.requestPermission(PermissionCard("알림", emptyList(), PermissionCard.SETTINGS_NOTIFICATIONS))
        assertTrue(PermissionFlow.settingsOnly(below13))
        assertEquals(PermissionFlow.SettingsPage.NOTIFICATIONS, PermissionFlow.settingsPage(below13))
        val from13 = CardAction.RequestPermission(listOf("android.permission.POST_NOTIFICATIONS"), "알림")
        assertFalse(PermissionFlow.settingsOnly(from13))
        assertEquals(PermissionFlow.SettingsPage.NOTIFICATIONS, PermissionFlow.settingsPage(from13))
        // A card with nothing to ask and nowhere to go is invalid.
        assertEquals("permissions가 비었어요", PermissionCard("x", emptyList()).validate())
    }

    @Test
    fun requestSurvivesSaveAndRestore() {
        assertEquals(location, PermissionFlow.restore(PermissionFlow.save(location)))
        val withSettings = CardAction.RequestPermission(emptyList(), "알림", PermissionCard.SETTINGS_NOTIFICATIONS)
        assertEquals(withSettings, PermissionFlow.restore(PermissionFlow.save(withSettings)))
        assertNull(PermissionFlow.restore(null))
        assertNull(PermissionFlow.restore(listOf("x")))
    }
}
