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
}
