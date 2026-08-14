package com.ugk.pi.android

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserConfirmationInputFingerprintTest {
    @Test
    fun canonicalJsonSortsObjectKeysPreservesArrayOrderAndNormalizesNumbers() {
        val value = Json.parseToJsonElement(
            """{"b":1.0,"a":[2,1],"n":-0,"e":1e+3}"""
        )

        assertEquals(
            """{"a":[2,1],"b":1,"e":1000,"n":0}""",
            UserConfirmationInputFingerprint.canonicalJsonV1(value)
        )
    }

    @Test
    fun objectKeyOrderProducesTheSameFingerprint() {
        val first = Json.parseToJsonElement("""{"url":"https://example.com","target":"open_url"}""")
        val second = Json.parseToJsonElement("""{"target":"open_url","url":"https://example.com"}""")

        assertEquals(
            UserConfirmationInputFingerprint.sha256(first),
            UserConfirmationInputFingerprint.sha256(second)
        )
    }

    @Test
    fun arraysRemainOrderSensitive() {
        val first = Json.parseToJsonElement("""{"items":["a","b"]}""")
        val second = Json.parseToJsonElement("""{"items":["b","a"]}""")

        org.junit.Assert.assertNotEquals(
            UserConfirmationInputFingerprint.sha256(first),
            UserConfirmationInputFingerprint.sha256(second)
        )
    }

    @Test
    fun canonicalJsonUsesStandardBooleanAndNullLiterals() {
        val value = Json.parseToJsonElement(
            """{"falseValue":false,"nullValue":null,"trueValue":true}"""
        )

        assertEquals(
            """{"falseValue":false,"nullValue":null,"trueValue":true}""",
            UserConfirmationInputFingerprint.canonicalJsonV1(value)
        )
    }

    @Test
    fun generatedNonceContainsAtLeast128BitsAndIsUrlSafe() {
        val nonce = UserConfirmationTicket.randomNonce()

        assertTrue(nonce.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(UserConfirmationTicket.isValidNonce(nonce))
        assertTrue(UserConfirmationTicket.isValidNonce("AAECAwQFBgcICQoLDA0ODw"))
    }
}
