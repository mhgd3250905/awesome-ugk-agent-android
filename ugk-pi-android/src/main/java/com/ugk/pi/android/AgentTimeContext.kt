package com.ugk.pi.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun interface AgentTimeContextProvider {
    fun currentContext(): AgentTimeContext
}

data class AgentTimeContext(
    val currentTimeText: String,
    val timezoneId: String
) {
    fun prefix(): String {
        return "[Current time: $currentTimeText, timezone: $timezoneId]"
    }
}

object SystemAgentTimeContextProvider : AgentTimeContextProvider {
    override fun currentContext(): AgentTimeContext {
        val timezone = TimeZone.getDefault()
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = timezone
        }
        return AgentTimeContext(
            currentTimeText = formatter.format(Date()),
            timezoneId = timezone.id
        )
    }
}
