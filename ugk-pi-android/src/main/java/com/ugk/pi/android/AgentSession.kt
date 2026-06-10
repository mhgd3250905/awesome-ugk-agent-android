package com.ugk.pi.android

data class AgentSession(
    val id: String,
    val messages: MutableList<AgentMessage> = mutableListOf()
)
