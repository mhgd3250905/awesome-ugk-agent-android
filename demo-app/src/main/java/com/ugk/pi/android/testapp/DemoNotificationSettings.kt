package com.ugk.pi.android.testapp

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.ugk.pi.attention.AgentNotificationConfig

/** The existing channel identity is shared by delivery and permission guidance. */
internal object DemoNotificationSettings {
    const val CHANNEL_ID = "ugk_agent_alerts_high_v1"

    fun config() = AgentNotificationConfig(
        channelId = CHANNEL_ID,
        channelName = "Agent 醒目提醒",
        channelDescription = "Agent 请求发送的通知与重要提醒",
        smallIconResId = R.drawable.ic_agent_notification,
        importance = NotificationManager.IMPORTANCE_HIGH
    )

    /** Never create or replace a channel while inspecting the user's settings. */
    fun isChannelBlocked(context: Context, channelId: String = CHANNEL_ID): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channelId)
        return channel?.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun channelSettingsIntent(context: Context, channelId: String = CHANNEL_ID): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
}
