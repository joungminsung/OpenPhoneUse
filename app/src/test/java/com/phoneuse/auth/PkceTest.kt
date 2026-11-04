package com.phoneuse.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test fun rfc7636AppendixBVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun generatedVerifierIsValid() {
        val codes = Pkce.generate()
        assertTrue(codes.verifier.length in 43..128)
        assertTrue(codes.verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(Pkce.challengeOf(codes.verifier), codes.challenge)
        assertNotEquals(codes.verifier, Pkce.generate().verifier)
    }
}
