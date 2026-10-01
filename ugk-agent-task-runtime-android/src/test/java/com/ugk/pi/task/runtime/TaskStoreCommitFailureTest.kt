package com.ugk.pi.task.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.ugk.pi.schedule.skill.AgentTask
import com.ugk.pi.schedule.skill.AgentTaskAction
import com.ugk.pi.schedule.skill.AgentTaskSchedule
import com.ugk.pi.schedule.skill.AgentTaskStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Reproducers for the round-7 review: `AndroidAgentTaskStore` throws away the
 * Boolean returned by `SharedPreferences.Editor.commit()`, so a write that never
 * reached the disk is reported to the caller as durable.
 *
 * The sibling store in the same module refuses to do this -
 * `SharedPreferencesTaskJobIdAssignmentStore` wraps its `commit()` in `check(...)`
 * and documents the exact hazard. A cancelled reminder is the user-visible
 * consequence: `agent_task_cancel` answers "cancelled", the file still says
 * SCHEDULED, and the next restore/convergence pass re-arms a task the user
 * deleted.
 */
class TaskStoreCommitFailureTest {
    @Test
    fun upsertFailsLoudlyWhenTheCommitDoesNotReachDisk() = runBlocking {
        val preferences = FailingCommitPreferences()
        val store = AndroidAgentTaskStore(contextWith(preferences))
        val armed = task("task_1", AgentTaskStatus.SCHEDULED, nextRunAtMillis = 61_000L)
        store.upsert(armed)

        val cancelled = armed.copy(
            status = AgentTaskStatus.CANCELLED,
            nextRunAtMillis = null,
            updatedAtMillis = 70_000L
        )
        try {
            store.upsert(cancelled)
            fail("a write that did not reach disk must not be reported as persisted")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "the failure must name what could not be persisted, got: ${expected.message}",
                expected.message?.contains("Agent task record") == true
            )
        }

        assertNotNull(
            "the durable record must still be readable rather than half-applied",
            preferences.stored
        )
    }

    @Test
    fun commitFailureDoesNotSilentlyResurrectACancelledTask() = runBlocking {
        // The user-visible chain: cancel fails to persist, a second store
        // instance (the boot receiver / job service builds its own) reads the
        // untouched record, and the task is still considered live.
        val preferences = FailingCommitPreferences()
        val first = AndroidAgentTaskStore(contextWith(preferences))
        first.upsert(task("task_1", AgentTaskStatus.SCHEDULED, nextRunAtMillis = 61_000L))

        val cancelAttempted = runCatching {
            first.upsert(
                task("task_1", AgentTaskStatus.CANCELLED, nextRunAtMillis = null)
            )
        }

        val reloaded = AndroidAgentTaskStore(contextWith(preferences))
        val durable = reloaded.get("task_1")
        assertNotNull("the task must still exist after a refused write", durable)
        if (cancelAttempted.isSuccess) {
            fail(
                "cancel reported success while disk still holds " +
                    "${durable!!.status}; the user's cancellation is lost"
            )
        }
        assertEquals(AgentTaskStatus.SCHEDULED, durable!!.status)
    }

    @Test
    fun aSuccessfulCommitStillPersistsAcrossInstances() = runBlocking {
        // Control for the other direction: the guard must not make durable
        // writes fail.
        val preferences = FailingCommitPreferences(flushSucceeds = true)
        val store = AndroidAgentTaskStore(contextWith(preferences))
        store.upsert(task("task_1", AgentTaskStatus.CANCELLED, nextRunAtMillis = null))

        val reloaded = AndroidAgentTaskStore(contextWith(preferences))
        assertEquals(AgentTaskStatus.CANCELLED, reloaded.get("task_1")!!.status)
        assertTrue(preferences.commitCalls > 0)
    }

    private fun task(id: String, status: AgentTaskStatus, nextRunAtMillis: Long?) = AgentTask(
        id = id,
        sessionId = "session-1",
        title = "提醒",
        schedule = AgentTaskSchedule.OneShot(runAtMillis = 61_000L),
        action = AgentTaskAction.NotifyUser(message = "该走了"),
        status = status,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        nextRunAtMillis = nextRunAtMillis
    )

    private fun contextWith(preferences: FailingCommitPreferences): Context =
        object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                preferences.handle
        }

    /**
     * A `SharedPreferences` whose later `commit()` calls return false, the way
     * the platform answers a failed disk flush once a record already exists.
     * `stored` only changes on a successful flush, so what `getString` returns
     * mirrors what a real device would read back after process death.
     */
    private class FailingCommitPreferences(private val flushSucceeds: Boolean = false) {
        var stored: String? = null
        var commitCalls = 0
        private var pending: String? = null

        private fun flushWouldLand(): Boolean =
            flushSucceeds || stored == null

        private val editor: SharedPreferences by lazy {
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java, SharedPreferences.Editor::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "putString" -> {
                        pending = args?.get(1) as? String
                        editor
                    }

                    "remove", "clear" -> editor
                    "commit" -> {
                        commitCalls++
                        val landed = flushWouldLand()
                        if (landed) stored = pending
                        landed
                    }

                    "apply" -> {
                        if (flushWouldLand()) stored = pending
                        null
                    }

                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences
        }

        val handle: SharedPreferences by lazy {
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "getString" -> stored
                    "edit" -> editor
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences
        }
    }
}
