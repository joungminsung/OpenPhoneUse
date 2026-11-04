package com.phoneuse.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenUriPolicyTest {
    private val own = "com.phoneuse"

    @Test fun ordinaryLinksAndIntentsPass() {
        assertNull(OpenUriPolicy.refusal("https://map.naver.com/p/search/카페", own))
        assertNull(OpenUriPolicy.refusal("android.settings.WIFI_SETTINGS", own))
        assertNull(OpenUriPolicy.refusal("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end", own))
        assertNull(OpenUriPolicy.refusal("content://media/external/images/media/1", own))
    }

    @Test fun ourOwnFilesAndComponentsAreRefused() {
        // Our attachment provider: never handed to another app.
        assertNotNull(OpenUriPolicy.refusal("content://com.phoneuse.attachments/camera/a.jpg", own))
        assertNotNull(OpenUriPolicy.refusal("CONTENT://COM.PHONEUSE.ATTACHMENTS/x", own))
        assertNotNull(
            OpenUriPolicy.refusal("intent:content://com.phoneuse.attachments/a.jpg#Intent;launchFlags=0x1;component=com.evil/.Grab;end", own),
        )
        // Our non-exported activities (the a11y service runs as our uid).
        assertNotNull(OpenUriPolicy.refusal("intent:#Intent;component=com.phoneuse/.routine.RoutineRunActivity;end", own))
        assertNotNull(OpenUriPolicy.refusal("intent:#Intent;component=com.phoneuse/com.phoneuse.ui.MainActivity;end", own))
        assertNotNull(OpenUriPolicy.refusal("intent:#Intent;action=x;package=com.phoneuse;end", own))
        assertNotNull(OpenUriPolicy.refusal("intent:#Intent;SEL;component=com.phoneuse/.X;end", own))
    }

    @Test fun settingsPagesClearTheSettingsTaskOtherLinksDoNot() {
        // NEW_TASK alone would just bring back the Settings page already open.
        assertEquals(0x10008000, OpenUriPolicy.launchFlags("android.settings.DISPLAY_SETTINGS"))
        assertEquals(0x10000000, OpenUriPolicy.launchFlags("android.intent.action.VIEW"))
        assertEquals(0x10000000, OpenUriPolicy.launchFlags(null))
    }
}
