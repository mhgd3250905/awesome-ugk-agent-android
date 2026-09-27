package com.ugk.pi.task.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StableTaskJobIdRegistryTest {
    @Test
    fun `colliding task hashes get distinct IDs that survive registry recreation`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val store = InMemoryAssignments()
        val firstRegistry = StableTaskJobIdRegistry(store) { emptyList() }

        val firstId = firstRegistry.idFor("Aa")
        val secondId = firstRegistry.idFor("BB")

        assertNotEquals(firstId, secondId)
        assertEquals(firstId, StableTaskJobIdRegistry(store) { emptyList() }.idFor("Aa"))
        assertEquals(secondId, StableTaskJobIdRegistry(store) { emptyList() }.idFor("BB"))
    }

    @Test
    fun `an existing pending job ID is adopted when migrating`() {
        val store = InMemoryAssignments()
        val pending = listOf(PendingTaskJob(taskId = "legacy", jobId = 81))
        val registry = StableTaskJobIdRegistry(store) { pending }

        assertEquals(81, registry.idFor("legacy"))
        assertEquals(mapOf("legacy" to 81), store.values)
    }

    @Test
    fun `foreign service jobs reserve IDs but are never migrated or canceled as Agent tasks`() {
        val store = InMemoryAssignments()
        val registry = StableTaskJobIdRegistry(
            assignments = store,
            seedForTask = { 81 }
        ) {
            listOf(
                PendingTaskJob(
                    taskId = "legacy",
                    jobId = 81,
                    isAgentTaskJob = false
                )
            )
        }

        assertEquals(82, registry.idFor("legacy"))
        assertEquals(setOf(82), registry.jobIdsFor("legacy"))
    }

    @Test
    fun `foreign service job blocks a stale Agent mapping from reuse and cancellation`() {
        val store = InMemoryAssignments(mapOf("task" to 99))
        val registry = StableTaskJobIdRegistry(
            assignments = store,
            seedForTask = { 99 }
        ) {
            listOf(
                PendingTaskJob(
                    taskId = "task",
                    jobId = 99,
                    isAgentTaskJob = false
                )
            )
        }

        assertEquals(100, registry.idFor("task"))
        assertEquals(setOf(100), registry.jobIdsFor("task"))
    }

    @Test
    fun `allocator skips IDs owned by stored mappings and pending jobs`() {
        val store = InMemoryAssignments(mapOf("stored" to ("Aa".hashCode() and Int.MAX_VALUE)))
        val pending = listOf(PendingTaskJob(taskId = "pending", jobId = "Aa".hashCode() + 1))
        val registry = StableTaskJobIdRegistry(store) { pending }

        val allocated = registry.idFor("Aa")

        assertNotEquals("Aa".hashCode() and Int.MAX_VALUE, allocated)
        assertNotEquals("Aa".hashCode() + 1, allocated)
    }

    @Test
    fun `cancellation finds only this task's assigned and legacy pending IDs`() {
        val store = InMemoryAssignments(mapOf("task" to 101, "other" to 202))
        val registry = StableTaskJobIdRegistry(store) {
            listOf(
                PendingTaskJob(taskId = "task", jobId = 303),
                PendingTaskJob(taskId = "other", jobId = 404)
            )
        }

        assertEquals(setOf(101, 303), registry.jobIdsFor("task"))
        assertTrue(registry.release("task"))

        assertTrue("release must leave other task mappings intact", "other" in store.values)
    }

    @Test
    fun `terminal job ID stays reserved until JobService finishes it`() {
        val store = InMemoryAssignments()
        val registry = StableTaskJobIdRegistry(
            assignments = store,
            seedForTask = { 777_777 }
        ) { emptyList() }
        val runningId = registry.idFor("terminal")

        val release = registry.beginRelease("terminal", runningId)
        assertEquals(runningId, store.values["terminal"])
        val concurrentTaskId = registry.idFor("another-task")
        assertNotEquals(runningId, concurrentTaskId)

        registry.completeRelease("terminal", release)
        assertFalse("completed task mapping should be removed", "terminal" in store.values)
        assertEquals(runningId, registry.idFor("reallocated"))
    }

    @Test
    fun `failed preference removal never makes the ID reusable in this process`() {
        val store = InMemoryAssignments()
        val registry = StableTaskJobIdRegistry(
            assignments = store,
            seedForTask = { 123_456_789 }
        ) { emptyList() }
        val firstId = registry.idFor("task")
        store.failRemoval = true

        assertFalse(registry.release("task"))
        assertNotEquals(firstId, registry.idFor("task"))
    }

    @Test
    fun `failed terminal mapping removal keeps the completed job ID unavailable`() {
        val store = InMemoryAssignments()
        val registry = StableTaskJobIdRegistry(
            assignments = store,
            seedForTask = { 123_456_788 }
        ) { emptyList() }
        val runningId = registry.idFor("terminal")
        val release = registry.beginRelease("terminal", runningId)
        store.failRemoval = true

        registry.completeRelease("terminal", release)

        assertNotEquals(runningId, registry.idFor("new-task"))
    }

    @Test
    fun `canceling a running job keeps its ID reserved until its service callback exits`() {
        val store = InMemoryAssignments()
        val registry = StableTaskJobIdRegistry(
            assignments = store,
            seedForTask = { 909_091 },
            pendingJobs = { StableTaskJobIdRegistry.runningJobs() }
        )
        val runningId = registry.idFor("running-task")
        val token = Any()
        StableTaskJobIdRegistry.trackRunningJob("running-task", runningId, token)
        try {
            assertTrue(registry.release("running-task"))
            assertNotEquals(runningId, registry.idFor("new-task"))

            StableTaskJobIdRegistry.finishRunningJob(runningId, token)
            assertEquals(runningId, registry.idFor("later-task"))
        } finally {
            StableTaskJobIdRegistry.finishRunningJob(runningId, token)
        }
    }

    @Test
    fun `schedule transaction lock covers ID allocation through platform scheduling`() {
        val registry = StableTaskJobIdRegistry(
            assignments = InMemoryAssignments(),
            seedForTask = { 888_888 }
        ) { emptyList() }
        val firstInside = CountDownLatch(1)
        val letFirstFinish = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondInside = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Int> {
                registry.withExclusiveAccess {
                    val id = registry.idFor("Aa")
                    firstInside.countDown()
                    check(letFirstFinish.await(2, TimeUnit.SECONDS))
                    id
                }
            }
            assertTrue(firstInside.await(2, TimeUnit.SECONDS))

            val second = executor.submit<Int> {
                secondStarted.countDown()
                registry.withExclusiveAccess {
                    val id = registry.idFor("BB")
                    secondInside.countDown()
                    id
                }
            }
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFalse("second schedule must wait while the first platform operation is active",
                secondInside.await(100, TimeUnit.MILLISECONDS))

            letFirstFinish.countDown()
            assertNotEquals(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS))
        } finally {
            letFirstFinish.countDown()
            executor.shutdownNow()
        }
    }

    private class InMemoryAssignments(
        initial: Map<String, Int> = emptyMap()
    ) : TaskJobIdAssignmentStore {
        val values = initial.toMutableMap()
        var failRemoval = false

        override fun readAssignments(): Map<String, Int> = values.toMap()

        override fun saveAssignment(taskId: String, jobId: Int) {
            values[taskId] = jobId
        }

        override fun removeAssignment(taskId: String): Boolean {
            values.remove(taskId)
            return !failRemoval
        }
    }
}
