package com.ugk.pi.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

class InMemorySessionStoreTest {

    /**
     * A start gate parks every coroutine before the store call so the whole
     * batch enters getOrCreate at the same moment: concurrent creators must
     * still observe exactly one AgentSession per id (each session owns a
     * single run gate, so duplicate instances would break run mutual
     * exclusion).
     */
    @Test
    fun `concurrent getOrCreate resolves to a single session instance`() = runBlocking {
        repeat(100) { round ->
            val store = InMemorySessionStore()
            val coroutineCount = 32
            val startGate = CountDownLatch(1)
            val instances = Collections.newSetFromMap(ConcurrentHashMap<AgentSession, Boolean>())
            val jobs = (0 until coroutineCount).map {
                async(Dispatchers.Default) {
                    startGate.await()
                    store.getOrCreate("session-$round")
                }
            }

            startGate.countDown()
            instances.addAll(jobs.awaitAll())

            assertEquals("round $round created multiple instances", 1, instances.size)
        }
    }

    @Test
    fun `getOrCreate returns the saved instance after save`() = runBlocking {
        val store = InMemorySessionStore()
        store.getOrCreate("s1")
        val replacement = AgentSession("s1")

        store.save(replacement)

        assertTrue(store.getOrCreate("s1") === replacement)
    }
}
