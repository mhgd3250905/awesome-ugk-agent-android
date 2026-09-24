package com.ugk.pi.attention

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** The host owns its channel identity, icon, manifest permission and permission flow. */
data class AgentNotificationConfig(
    val channelId: String,
    val channelName: String,
    val smallIconResId: Int,
    val channelDescription: String? = null,
    val importance: Int = NotificationManager.IMPORTANCE_DEFAULT
) {
    init {
        require(channelId.isNotBlank()) { "channelId must not be blank" }
        require(channelName.isNotBlank()) { "channelName must not be blank" }
        require(smallIconResId != 0) { "smallIconResId must be a valid resource ID" }
        require(importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_HIGH) {
            "importance must be between IMPORTANCE_MIN and IMPORTANCE_HIGH"
        }
    }
}

data class AttentionMessage(val title: String, val body: String)

enum class NotificationDeliveryStatus {
    POSTED,
    PERMISSION_DENIED,
    DISABLED,
    CHANNEL_BLOCKED,
    FAILED
}

data class NotificationDelivery(
    val status: NotificationDeliveryStatus,
    val notificationId: Int? = null
)

/** Publishes an immediate, user-visible app notification without scheduling work. */
class AndroidNotificationPublisher(context: Context, private val config: AgentNotificationConfig) {
    private val appContext = context.applicationContext ?: context
    private val nextId = AtomicInteger(SystemClock.elapsedRealtime().toInt() and Int.MAX_VALUE)

    fun publish(message: AttentionMessage): NotificationDelivery {
        if (message.title.isBlank() || message.body.isBlank()) {
            return NotificationDelivery(NotificationDeliveryStatus.FAILED)
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return NotificationDelivery(NotificationDeliveryStatus.PERMISSION_DENIED)
        }

        return try {
            val manager = appContext.getSystemService(NotificationManager::class.java)
                ?: return NotificationDelivery(NotificationDeliveryStatus.FAILED)
            if (!manager.areNotificationsEnabled()) {
                return NotificationDelivery(NotificationDeliveryStatus.DISABLED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        config.channelId,
                        config.channelName,
                        config.importance
                    ).apply { description = config.channelDescription }
                )
                if (manager.getNotificationChannel(config.channelId)?.importance ==
                    NotificationManager.IMPORTANCE_NONE
                ) {
                    return NotificationDelivery(NotificationDeliveryStatus.CHANNEL_BLOCKED)
                }
            }

            val id = nextId.incrementAndGet() and Int.MAX_VALUE
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(appContext, config.channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(appContext).apply {
                    setPriority(
                        when (config.importance) {
                            NotificationManager.IMPORTANCE_HIGH -> Notification.PRIORITY_HIGH
                            NotificationManager.IMPORTANCE_DEFAULT -> Notification.PRIORITY_DEFAULT
                            NotificationManager.IMPORTANCE_LOW -> Notification.PRIORITY_LOW
                            else -> Notification.PRIORITY_MIN
                        }
                    )
                }
            }
            builder
                .setSmallIcon(config.smallIconResId)
                .setContentTitle(message.title.take(MAX_TITLE_CHARS))
                .setContentText(message.body.take(MAX_BODY_CHARS))
                .setStyle(Notification.BigTextStyle().bigText(message.body.take(MAX_BODY_CHARS)))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)

            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let { intent ->
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        appContext,
                        id,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            manager.notify(id, builder.build())
            NotificationDelivery(NotificationDeliveryStatus.POSTED, id)
        } catch (_: RuntimeException) {
            NotificationDelivery(NotificationDeliveryStatus.FAILED)
        }
    }

    private companion object {
        const val MAX_TITLE_CHARS = 120
        const val MAX_BODY_CHARS = 2_048
    }
}
