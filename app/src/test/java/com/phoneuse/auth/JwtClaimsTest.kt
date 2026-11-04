package com.phoneuse.auth

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JwtClaimsTest {
    private val jwt = fakeJwt("""{"email":"a@b.c","exp":2000000000}""")

    @Test fun readsThePayloadClaims() {
        val claims = JwtClaims.claims(jwt)!!
        assertEquals("a@b.c", claims["email"]!!.jsonPrimitive.content)
        assertEquals("2000000000", claims["exp"]!!.jsonPrimitive.content)
        assertNull(claims["sub"])
    }

    @Test fun twoSegmentsIsNull() {
        val parts = jwt.split(".")
        assertNull(JwtClaims.claims(parts[0] + "." + parts[1]))
    }

    @Test fun brokenBase64IsNull() {
        assertNull(JwtClaims.claims("aGVsbG8.@@@not-base64@@@.sig"))
    }

    @Test fun nonJsonPayloadIsNull() {
        assertNull(JwtClaims.claims("aGVsbG8.aGVsbG8.sig")) // payload is "hello"
    }
}
