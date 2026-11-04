package com.phoneuse.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParcelCardTest {
    @Test fun parcelCardParsesAndSummarises() {
        val r = CardJson.parse(
            """{"kind":"parcel","title":"무선 이어폰","status":"배송 출발","carrier":"CJ대한통운","tracking":"123456789012",
               "source":"문자에서 송장 찾음","events":[{"at":"17:42","text":"동네 배송 출발"},{"at":"06:10","text":"지역 터미널 도착"}]}""",
        )
        val card = (r as CardParse.Ok).card as ParcelCard
        assertEquals("배송 출발", card.status)
        assertEquals(2, card.events.size)
        val summary = CardJson.summary(card)
        assertEquals("택배: 배송 출발 · CJ대한통운 123456789012, 단계 2개 17:42 06:10", summary)
        // Stored and decoded back.
        assertEquals(listOf(card), CardJson.decodeList(CardJson.encodeList(listOf(card))))
    }

    @Test fun parcelCardRejectsUnknownStage() {
        val r = CardJson.parse("""{"kind":"parcel","title":"이어폰","status":"shipping"}""")
        assertTrue(r is CardParse.Error)
        assertNotNull(ParcelCard("x", "도착").copy(title = " ").validate())
    }

    @Test fun notificationAccessCardOpensItsSettingsPage() {
        val card = PermissionCard("알림 읽기", emptyList(), PermissionCard.SETTINGS_NOTIFICATION_LISTENER)
        assertNull(card.validate())
        val request = CardActions.requestPermission(card)
        assertTrue(PermissionFlow.settingsOnly(request))
        assertEquals(PermissionFlow.SettingsPage.NOTIFICATION_LISTENER, PermissionFlow.settingsPage(request))
        // Survives process death under the Settings screen like any other request.
        assertEquals(request, PermissionFlow.restore(PermissionFlow.save(request)))
        // A runtime card still needs its permissions.
        assertNotNull(PermissionCard("위치", emptyList()).validate())
        val sms = CardActions.requestPermission(PermissionCard("문자 읽기", listOf("android.permission.READ_SMS")))
        assertEquals(PermissionFlow.SettingsPage.APP_DETAILS, PermissionFlow.settingsPage(sms))
        // The model cannot show a permission card itself.
        assertTrue(CardJson.parse("""{"kind":"permission","label":"x","permissions":[]}""") is CardParse.Error)
    }
}
