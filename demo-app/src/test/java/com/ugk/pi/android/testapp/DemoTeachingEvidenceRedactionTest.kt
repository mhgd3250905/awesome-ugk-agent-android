package com.ugk.pi.android.testapp

import com.ugk.pi.android.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Teaching evidence is persisted text, and this guard is the only thing keeping a credential
 * out of it. Two claims this round first made about *why* it failed were measured and found
 * false - see `build/review-evidence/r16-lowercase-locale-probe2.log`, where Kotlin's
 * no-argument `lowercase()` folds like `Locale.ROOT` under a `tr-TR` default locale, so no
 * device locale was ever involved. What the sweep does pin is the fold's determinism, and what
 * was actually leaking is the marker list: it knew `apikey` and nothing else, so a parameter
 * named `api_key` matched no marker in any language and its value was written out verbatim.
 *
 * The other direction matters just as much: an over-broad redaction silently destroys the
 * evidence a teaching run exists to collect, so ordinary parameters are asserted preserved.
 */
class DemoTeachingEvidenceRedactionTest {

    /** The load-bearing arm: the underscored spelling was never matched by the old list. */
    @Test
    fun theUnderscoredCredentialKeyIsRedacted() {
        listOf("api_key", "API_KEY", " Api_Key ")
            .forEach { key ->
                val scrubbed = DemoTeachingEvidence.input(
                    ToolCall("c1", "open_android_settings_page", buildJsonObject { put(key, "sk-live-value") })
                ).toString()

                assertFalse("key=$key leaked: $scrubbed", scrubbed.contains("sk-live-value"))
            }
    }

    /** The fold is deterministic across device locales; that is a control, not the bug. */
    @Test
    fun credentialShapedKeysAreRedactedUnderEveryDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            listOf("en-US", "tr-TR", "az").forEach { tag ->
                Locale.setDefault(Locale.forLanguageTag(tag))
                listOf(
                    "APIKEY" to "sk-live-ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                    "api_key" to "sk-live-token-value",
                    "Password" to "hunter2",
                    "AccessToken" to "eyJhbGciOi",
                    "client_SECRET" to "shhh"
                ).forEach { (key, secret) ->
                    val scrubbed = DemoTeachingEvidence.input(
                        ToolCall("c1", "open_android_settings_page", buildJsonObject { put(key, secret) })
                    ).toString()

                    assertFalse(
                        "locale=$tag key=$key leaked into the transcript: $scrubbed",
                        scrubbed.contains(secret)
                    )
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** Control: the guard must not swallow ordinary parameters, or teaching loses its evidence. */
    @Test
    fun anOrdinaryParameterIsStillRecorded() {
        val scrubbed = DemoTeachingEvidence.input(
            ToolCall("c1", "screen_perform_action", buildJsonObject { put("snapshotId", "snapshot-7") })
        ).toString()

        assertTrue("ordinary evidence disappeared: $scrubbed", scrubbed.contains("snapshot-7"))
    }

    @Test
    fun aNestedCredentialIsRedactedAndItsSiblingIsKept() {
        val scrubbed = DemoTeachingEvidence.input(
            ToolCall(
                "c1",
                "launch_android_app",
                buildJsonObject {
                    put("packageName", "com.example.app")
                    put(
                        "auth",
                        buildJsonObject {
                            put("ApiKey", "sk-nested-secret")
                            put("region", "eu-west")
                        }
                    )
                }
            )
        ).toString()

        assertFalse("nested secret survived: $scrubbed", scrubbed.contains("sk-nested-secret"))
        assertTrue("the sibling value was over-redacted: $scrubbed", scrubbed.contains("com.example.app"))
        assertTrue("the benign sibling inside auth was over-redacted: $scrubbed", scrubbed.contains("eu-west"))
    }

    @Test
    fun terminalAndClipboardToolsAreNeverRecordedAtAll() {
        listOf("terminal_bash", "clipboard_read_text").forEach { name ->
            val scrubbed = DemoTeachingEvidence.input(
                ToolCall("c1", name, buildJsonObject { put("command", "printenv") })
            )

            assertFalse(
                "command text must not be persisted for $name: $scrubbed",
                (scrubbed as JsonObject).toString().contains("printenv")
            )
        }
    }
}
