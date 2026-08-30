package com.ugk.pi.android.testapp

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ugk.pi.android.AndroidAppIntentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidIntentDataTypeInstrumentedTest {
    @Test
    fun intentKeepsDataUriWhenItSetsMimeType() {
        val intent = AndroidAppIntentSpec(
            action = Intent.ACTION_PICK,
            dataUri = "content://media/external/images/media",
            type = "image/*"
        ).toIntent()

        assertEquals("content://media/external/images/media", intent.data.toString())
        assertEquals("image/*", intent.type)
    }

    @Test
    fun intentWithOnlyDataKeepsDataAndHasNoType() {
        val intent = AndroidAppIntentSpec(
            action = Intent.ACTION_VIEW,
            dataUri = "https://example.test/document"
        ).toIntent()

        assertEquals("https://example.test/document", intent.data.toString())
        assertNull(intent.type)
    }

    @Test
    fun intentWithOnlyTypeKeepsTypeAndHasNoData() {
        val intent = AndroidAppIntentSpec(
            action = Intent.ACTION_SEND,
            type = "text/plain"
        ).toIntent()

        assertNull(intent.data)
        assertEquals("text/plain", intent.type)
    }

    @Test
    fun intentWithNeitherDataNorTypeKeepsBothEmpty() {
        val intent = AndroidAppIntentSpec(action = Intent.ACTION_MAIN).toIntent()

        assertNull(intent.data)
        assertNull(intent.type)
    }
}
