package com.ugk.pi.android

import kotlinx.coroutines.sync.Mutex

data class AgentSession(
    val id: String,
    val messages: MutableList<AgentMessage> = mutableListOf()
) {
    /**
     * Guards the mutable conversation history while a runtime is collecting a
     * run for this session. It is intentionally not a constructor property so
     * the existing data-class shape and equality semantics remain unchanged.
     */
    internal val runGate = Mutex()
}
