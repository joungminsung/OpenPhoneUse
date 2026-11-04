package com.phoneuse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTargetsTest {
    @Test fun everyTargetHasItsOwnDataUri() {
        // PendingIntents that differ only in extras are one PendingIntent; the data URI keeps them apart.
        val uris = listOf(
            LaunchTargets.uri(LaunchTargets.Kind.CONVERSATION, "3"),
            LaunchTargets.uri(LaunchTargets.Kind.CONVERSATION, "41"),
            LaunchTargets.uri(LaunchTargets.Kind.ROUTINE, "3"),
            LaunchTargets.uri(LaunchTargets.Kind.REMINDER, "3"),
            LaunchTargets.uri(LaunchTargets.Kind.ROUTINES_BROKEN, "0"),
            LaunchTargets.uri(LaunchTargets.Kind.SUMMARY, "0"),
        )
        assertEquals(uris.size, uris.toSet().size)
        assertEquals("phoneuse://conversation/3", uris[0])
        assertEquals("phoneuse://routine/3", uris[2])
    }

    @Test fun requestCodesDifferPerKindForTheSameId() {
        val codes = LaunchTargets.Kind.entries.map { LaunchTargets.requestCode(it, 3) }
        assertEquals(codes.size, codes.toSet().size)
        assertNotEquals(LaunchTargets.requestCode(LaunchTargets.Kind.CONVERSATION, 3), LaunchTargets.requestCode(LaunchTargets.Kind.CONVERSATION, 41))
    }

    /** Seals by reversing and tagging; opens only its own output (stands in for the Keystore box). */
    private class FakeBox : com.phoneuse.security.SecretBox {
        override fun seal(plain: String) = "sealed:" + plain.reversed()
        override fun open(sealed: String) = sealed.removePrefix("sealed:").takeIf { sealed.startsWith("sealed:") }?.reversed()
    }

    @Test fun theKeyIsStoredSealedAndReused() {
        // The HMAC key is a secret, so only its sealed form is stored.
        val box = FakeBox()
        val (key, stored) = LaunchToken.loadKey(null, box) { ByteArray(32) { 5 } }
        assertTrue(stored!!.startsWith("sealed:"))
        assertFalse(stored.contains(java.util.Base64.getEncoder().encodeToString(key)))
        val (again, toStore) = LaunchToken.loadKey(stored, box) { error("must reuse the stored key") }
        assertTrue(again.contentEquals(key))
        assertEquals(null, toStore)
        // A value that no longer opens (Keystore reset, an old plain key): a new key, stored sealed.
        val (fresh, replaced) = LaunchToken.loadKey("AAAA", box) { ByteArray(32) { 9 } }
        assertTrue(fresh.contentEquals(ByteArray(32) { 9 }))
        assertTrue(replaced!!.startsWith("sealed:"))
    }

    @Test fun tokensProveTheIntentCameFromUs() {
        // MainActivity is exported; another app can send it a nudge extra, but not a token for it.
        val ours = LaunchToken(ByteArray(32) { it.toByte() })
        val theirs = LaunchToken(ByteArray(32) { 7 })
        val token = ours.sign(LaunchToken.nudge("parcel-1", 0))
        assertTrue(ours.verify(LaunchToken.nudge("parcel-1", 0), token))
        assertFalse(ours.verify(LaunchToken.nudge("parcel-1", 1), token))
        assertFalse(ours.verify(LaunchToken.nudge("parcel-2", 0), token))
        assertFalse(ours.verify(LaunchToken.nudge("parcel-1", 0), theirs.sign(LaunchToken.nudge("parcel-1", 0))))
        assertFalse(ours.verify(LaunchToken.nudge("parcel-1", 0), null))
        assertFalse(ours.verify(LaunchToken.conversation(3), ours.sign(LaunchToken.conversation(41))))
        assertTrue(ours.verify(LaunchToken.conversation(41), ours.sign(LaunchToken.conversation(41))))
    }
}
