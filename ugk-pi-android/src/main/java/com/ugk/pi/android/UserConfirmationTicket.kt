package com.ugk.pi.android

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The exact protected operation and input shown to the user for approval. */
data class UserConfirmationTarget(
    val toolName: String,
    val input: JsonObject
)

/** A short-lived, operation-bound authorization produced by the confirmation Tool. */
data class UserConfirmationTicket(
    val version: Int,
    val sessionId: String,
    val toolName: String,
    val inputFingerprint: String,
    val nonce: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("version", version)
        put("sessionId", sessionId)
        put("toolName", toolName)
        put("inputFingerprint", inputFingerprint)
        put("nonce", nonce)
        put("issuedAtEpochMillis", issuedAtEpochMillis)
        put("expiresAtEpochMillis", expiresAtEpochMillis)
    }

    internal fun isStructurallyValid(nowEpochMillis: Long): Boolean {
        return version == CURRENT_VERSION &&
            sessionId.isNotBlank() &&
            toolName.isNotBlank() &&
            SHA256_FINGERPRINT.matches(inputFingerprint) &&
            isValidNonce(nonce) &&
            issuedAtEpochMillis >= 0L &&
            expiresAtEpochMillis > issuedAtEpochMillis &&
            nowEpochMillis >= issuedAtEpochMillis &&
            nowEpochMillis < expiresAtEpochMillis
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_TTL_MILLIS = 120_000L
        private val SHA256_FINGERPRINT = Regex("sha256:[0-9a-f]{64}")
        private val NONCE = Regex("[A-Za-z0-9_-]{22,}")
        private val SECURE_RANDOM = SecureRandom()

        internal fun isValidNonce(value: String): Boolean {
            if (!NONCE.matches(value)) return false
            return runCatching {
                val decoded = decodeBase64Url(value)
                decoded.size >= 16 &&
                    encodeBase64Url(decoded) == value
            }.getOrDefault(false)
        }

        internal fun issue(
            sessionId: String,
            target: UserConfirmationTarget,
            issuedAtEpochMillis: Long,
            nonce: String
        ): UserConfirmationTicket {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            require(target.toolName.isNotBlank()) { "target.toolName must not be blank" }
            require(issuedAtEpochMillis >= 0L) { "issuedAtEpochMillis must not be negative" }
            require(isValidNonce(nonce)) { "nonce must be a URL-safe value containing at least 128 bits" }
            val expiresAtEpochMillis = Math.addExact(
                issuedAtEpochMillis,
                DEFAULT_TTL_MILLIS
            )
            return UserConfirmationTicket(
                version = CURRENT_VERSION,
                sessionId = sessionId,
                toolName = target.toolName,
                inputFingerprint = UserConfirmationInputFingerprint.sha256(target.input),
                nonce = nonce,
                issuedAtEpochMillis = issuedAtEpochMillis,
                expiresAtEpochMillis = expiresAtEpochMillis
            )
        }

        internal fun randomNonce(random: SecureRandom = SECURE_RANDOM): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return encodeBase64Url(bytes)
        }

        private fun encodeBase64Url(bytes: ByteArray): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val result = StringBuilder((bytes.size * 8 + 5) / 6)
            var buffer = 0
            var bitsInBuffer = 0
            bytes.forEach { byte ->
                buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                bitsInBuffer += 8
                while (bitsInBuffer >= 6) {
                    bitsInBuffer -= 6
                    result.append(alphabet[(buffer shr bitsInBuffer) and 0x3f])
                    buffer = if (bitsInBuffer == 0) {
                        0
                    } else {
                        buffer and ((1 shl bitsInBuffer) - 1)
                    }
                }
            }
            if (bitsInBuffer > 0) {
                result.append(alphabet[(buffer shl (6 - bitsInBuffer)) and 0x3f])
            }
            return result.toString()
        }

        private fun decodeBase64Url(value: String): ByteArray {
            require(value.isNotEmpty() && value.length % 4 != 1) {
                "Invalid base64url nonce"
            }
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val result = ByteArray(value.length * 6 / 8)
            var buffer = 0
            var bitsInBuffer = 0
            var resultIndex = 0
            value.forEach { character ->
                val digit = alphabet.indexOf(character)
                require(digit >= 0) { "Invalid base64url nonce" }
                buffer = (buffer shl 6) or digit
                bitsInBuffer += 6
                while (bitsInBuffer >= 8) {
                    bitsInBuffer -= 8
                    result[resultIndex++] = ((buffer shr bitsInBuffer) and 0xff).toByte()
                    buffer = if (bitsInBuffer == 0) {
                        0
                    } else {
                        buffer and ((1 shl bitsInBuffer) - 1)
                    }
                }
            }
            require(bitsInBuffer == 0 || (buffer and ((1 shl bitsInBuffer) - 1)) == 0) {
                "Invalid base64url nonce"
            }
            return result
        }
    }
}

/** Shared canonical-json-v1 implementation for confirmation targets and checks. */
object UserConfirmationInputFingerprint {
    const val CANONICAL_JSON_VERSION = "canonical-json-v1"

    fun canonicalJsonV1(value: JsonElement): String = value.toCanonicalJson()

    fun sha256(value: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            canonicalJsonV1(value).toByteArray(StandardCharsets.UTF_8)
        )
        return "sha256:" + digest.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun JsonElement.toCanonicalJson(): String = when (this) {
        JsonNull -> "null"
        is JsonObject -> entries
            .sortedWith { left, right -> compareUnicodeCodePoints(left.key, right.key) }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, child) ->
                "${encodeString(key)}:${child.toCanonicalJson()}"
            }

        is JsonArray -> joinToString(separator = ",", prefix = "[", postfix = "]") { child ->
            child.toCanonicalJson()
        }

        is JsonPrimitive -> when {
            isString -> encodeString(content)
            content == "true" || content == "false" -> content
            else -> normalizeNumber(content)
        }
    }

    private fun encodeString(value: String): String = JsonPrimitive(value).toString()

    private fun normalizeNumber(raw: String): String {
        require(JSON_NUMBER.matches(raw)) { "Invalid JSON number" }
        val number = runCatching { BigDecimal(raw) }
            .getOrElse { throw IllegalArgumentException("Invalid JSON number", it) }
            .stripTrailingZeros()
        require(number.scale() <= MAX_NUMBER_DIGITS) { "JSON number is too large to canonicalize" }
        require(number.precision() - number.scale() <= MAX_NUMBER_DIGITS) {
            "JSON number is too large to canonicalize"
        }
        if (number.signum() == 0) return "0"
        return number.toPlainString()
    }

    private fun compareUnicodeCodePoints(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftCodePoint = left.codePointAt(leftIndex)
            val rightCodePoint = right.codePointAt(rightIndex)
            if (leftCodePoint != rightCodePoint) {
                return leftCodePoint.compareTo(rightCodePoint)
            }
            leftIndex += Character.charCount(leftCodePoint)
            rightIndex += Character.charCount(rightCodePoint)
        }
        return left.length.compareTo(right.length)
    }

    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private const val MAX_NUMBER_DIGITS = 100_000
}
