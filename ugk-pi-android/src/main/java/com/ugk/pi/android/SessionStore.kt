package com.ugk.pi.android

interface SessionStore {
    suspend fun getOrCreate(sessionId: String): AgentSession
    suspend fun save(session: AgentSession)
}

class InMemorySessionStore : SessionStore {
    private val sessions = mutableMapOf<String, AgentSession>()

    override suspend fun getOrCreate(sessionId: String): AgentSession {
        return sessions.getOrPut(sessionId) { AgentSession(sessionId) }
    }

    override suspend fun save(session: AgentSession) {
        sessions[session.id] = session
    }
}
