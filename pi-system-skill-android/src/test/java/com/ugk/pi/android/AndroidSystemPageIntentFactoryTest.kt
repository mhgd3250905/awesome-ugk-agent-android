package com.ugk.pi.android

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSystemPageIntentFactoryTest {
    @Test
    fun buildsPackageScopedAppDetailsIntent() {
        val spec = AndroidSystemPageIntentFactory.specFor(
            target = "app_details",
            packageName = "com.example.app"
        )

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, spec?.action)
        assertEquals("com.example.app", spec?.packageUri)
    }

    @Test
    fun buildsBluetoothSettingsIntent() {
        val spec = AndroidSystemPageIntentFactory.specFor(
            target = "bluetooth",
            packageName = "com.example.app"
        )

        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, spec?.action)
    }

    @Test
    fun rejectsUnknownTarget() {
        assertNull(
            AndroidSystemPageIntentFactory.intentFor(
                target = "start_any_intent",
                packageName = "com.example.app"
            )
        )
    }

    @Test
    fun exposesWhitelistedTargets() {
        assertTrue(AndroidSystemPageIntentFactory.supportedTargets.contains("notifications"))
        assertTrue(AndroidSystemPageIntentFactory.supportedTargets.contains("battery_optimization"))
        assertTrue(AndroidSystemPageIntentFactory.supportedTargets.contains("exact_alarm"))
    }
}
