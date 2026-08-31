package com.ugk.pi.android

import java.util.concurrent.ConcurrentHashMap

interface SessionStore {
    suspend fun getOrCreate(sessionId: String): AgentSession
    suspend fun save(session: AgentSession)
}

class InMemorySessionStore : SessionStore {
    // A concurrent map keeps parallel getOrCreate callers on a single
    // AgentSession instance (and therefore a single run gate) per id.
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    override suspend fun getOrCreate(sessionId: String): AgentSession {
        return sessions.computeIfAbsent(sessionId) { AgentSession(sessionId) }
    }

    override suspend fun save(session: AgentSession) {
        sessions[session.id] = session
    }
}
