package com.ugk.pi.android

import android.content.Intent
import android.provider.MediaStore
import android.app.SearchManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppIntentFactoryTest {
    @Test
    fun buildsCameraCaptureIntent() {
        val spec = AndroidAppIntentFactory.specFor("camera_capture", emptyMap())

        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, spec?.action)
    }

    @Test
    fun buildsCommonParameterizedIntents() {
        assertEquals(
            Intent.ACTION_DIAL,
            AndroidAppIntentFactory.specFor("dial_phone", mapOf("phone_number" to "12345"))?.action
        )
        assertEquals(
            "tel:12345",
            AndroidAppIntentFactory.specFor("dial_phone", mapOf("phone_number" to "12345"))?.dataUri
        )
        assertEquals(
            Intent.ACTION_SENDTO,
            AndroidAppIntentFactory.specFor("send_email", mapOf("to" to "a@example.com"))?.action
        )
        assertEquals(
            Intent.ACTION_SEND,
            AndroidAppIntentFactory.specFor("share_text", mapOf("text" to "hello"))?.action
        )
        assertEquals(
            Intent.ACTION_WEB_SEARCH,
            AndroidAppIntentFactory.specFor("web_search", mapOf(SearchManager.QUERY to "android"))?.action
        )
    }

    @Test
    fun rejectsUnknownTargets() {
        assertNull(AndroidAppIntentFactory.intentFor("unrestricted_action"))
    }

    @Test
    fun onlyAllowsHttpAndHttpsForOpenUrl() {
        assertEquals(
            "http://127.0.0.1:8787/weather.html",
            AndroidAppIntentFactory.specFor(
                "open_url",
                mapOf("url" to "http://127.0.0.1:8787/weather.html")
            )?.dataUri
        )
        assertNull(AndroidAppIntentFactory.intentFor("open_url", mapOf("url" to "file:///data/data/app.html")))
        assertNull(AndroidAppIntentFactory.intentFor("open_url", mapOf("url" to "javascript:alert(1)")))
        assertNull(AndroidAppIntentFactory.intentFor("open_url", mapOf("url" to "https://user:secret@example.com")))
    }

    @Test
    fun exposesWhitelistedTargets() {
        val expectedTargets = setOf(
            "camera_capture",
            "video_capture",
            "pick_image",
            "record_audio",
            "dial_phone",
            "send_sms",
            "send_email",
            "open_url",
            "open_map",
            "share_text",
            "web_search",
            "open_app_market"
        )

        assertTrue(AndroidAppIntentFactory.supportedTargets.containsAll(expectedTargets))
    }

    @Test
    fun openMapAcceptsMultiWordQueries() {
        val spec = AndroidAppIntentFactory.specFor("open_map", mapOf("query" to "coffee shop"))

        assertEquals(Intent.ACTION_VIEW, spec?.action)
        assertEquals("geo:0,0?q=coffee%20shop", spec?.dataUri)
    }

    @Test
    fun openMapEncodesNonAsciiQueries() {
        assertEquals(
            "geo:0,0?q=%E5%8C%97%E4%BA%AC%E7%81%AB%E8%BD%A6%E7%AB%99",
            AndroidAppIntentFactory.specFor("open_map", mapOf("query" to "北京火车站"))?.dataUri
        )
    }

    @Test
    fun openMapRejectsControlCharactersInQuery() {
        assertNull(AndroidAppIntentFactory.specFor("open_map", mapOf("query" to "caf\u0000e")))
        assertNull(AndroidAppIntentFactory.specFor("open_map", mapOf("query" to "   ")))
    }

    @Test
    fun dialPhoneRejectsUriRestructuringInput() {
        assertEquals(
            "tel:+1 (555) 123-4567",
            AndroidAppIntentFactory.specFor(
                "dial_phone",
                mapOf("phone_number" to "+1 (555) 123-4567")
            )?.dataUri
        )
        assertNull(
            AndroidAppIntentFactory.specFor(
                "dial_phone",
                mapOf("phone_number" to "123;call?to=456")
            )
        )
        assertNull(AndroidAppIntentFactory.specFor("dial_phone", mapOf("phone_number" to "\u0001")))
    }

    @Test
    fun sendEmailRejectsHeaderInjectionRecipients() {
        assertNull(
            AndroidAppIntentFactory.specFor(
                "send_email",
                mapOf("to" to "a@b.com?bcc=attacker@c.com")
            )
        )
        assertNull(AndroidAppIntentFactory.specFor("send_email", mapOf("to" to "not an email")))
    }
}
