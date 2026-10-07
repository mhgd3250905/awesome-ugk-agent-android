package com.ugk.pi.task.runtime

import com.ugk.pi.schedule.skill.AgentTask
import com.ugk.pi.schedule.skill.AgentTaskAction
import com.ugk.pi.schedule.skill.AgentTaskSchedule
import com.ugk.pi.schedule.skill.AgentTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * The read-only paths of the task store. `upsert` learned in an earlier round that an
 * unreadable record is not an empty record - it copies the bytes aside before replacing
 * them. `list()` and `get()` never learned it: both went through a decoder that answers
 * `emptyList()` for anything it cannot parse, so a torn record is reported to the model as
 * "No scheduled tasks found." with `ok: true`, the boot re-arm loop iterates over nothing
 * and every declared SCHEDULED task silently stops firing, and the bytes are only copied
 * aside if some later `upsert` happens to run first.
 *
 * One rule, read at every arm: absent / empty-by-itself / unreadable / parsed-but-unusable.
 */
class TaskStoreUnreadableReadPathTest {

    /** Control, green on main: no stored value is honestly empty and needs no archive. */
    @Test
    fun anAbsentRecordReadsAsEmptyWithoutAnyArchive() {
        val backedUp = AtomicReference<String?>(null)
        val store = store(readRaw = { null }, backedUp = backedUp)

        assertEquals(emptyList<AgentTask>(), store.list())
        assertNull(backedUp.get())
    }

    /** Control, green on main: the store's own way of writing "no tasks". */
    @Test
    fun anEmptyArrayIsAnHonestEmpty() {
        val backedUp = AtomicReference<String?>(null)
        val store = store(readRaw = { "[]" }, backedUp = backedUp)

        assertEquals(emptyList<AgentTask>(), store.list())
        assertNull(backedUp.get())
    }

    /** Control, green on main: a well-formed record is read back whole. */
    @Test
    fun aReadableRecordKeepsEveryTask() {
        val tasks = listOf(task("task_1"), task("task_2"))
        val encoded = AgentTaskJsonCodec.encode(tasks)
        val store = store(readRaw = { encoded }, backedUp = AtomicReference(null))

        assertEquals(listOf("task_1", "task_2"), store.list().map { it.id })
        assertNotNull(store.get("task_2"))
    }

    @Test
    fun anUnreadableRecordIsCopiedAsideByTheReadOnlyPathToo() {
        val torn = "{ not json"
        val backedUp = AtomicReference<String?>(null)
        val store = store(readRaw = { torn }, backedUp = backedUp)

        store.list()

        assertEquals(
            "a payload list() could not read has to be preserved before anything can replace it",
            torn,
            backedUp.get()
        )
    }

    @Test
    fun getAlsoRefusesToReadAnUnreadableRecordAsNoSuchTask() {
        val torn = "{ not json"
        val backedUp = AtomicReference<String?>(null)
        val store = store(readRaw = { torn }, backedUp = backedUp)

        assertNull(store.get("task_1"))

        assertEquals(
            "a lookup that cannot be answered must preserve the bytes as list() does - the two " +
                "arms read the same record and must not differ about it",
            torn,
            backedUp.get()
        )
    }

    /**
     * The record is a JSON array, so a half-written tail leaves the earlier tasks intact.
     * Reading that as "no tasks" is what erases them: the next save rewrites the whole
     * record from the empty list.
     */
    @Test
    fun aTornTailStillReturnsEveryCompleteTaskBeforeIt() {
        val full = AgentTaskJsonCodec.encode(listOf(task("task_1"), task("task_2")))
        val torn = full.substring(0, full.length - 7)
        val store = store(readRaw = { torn }, backedUp = AtomicReference(null))

        val tasks = store.list()

        assertEquals(
            "the complete record in a torn array has to come back, not be discarded: $torn",
            listOf("task_1"),
            tasks.map { it.id }
        )
    }

    /**
     * The rule has to be one rule. `upsert` already archived; it did the same read through
     * a different function and lost the tasks it could have kept, so saving one new task
     * over a torn record dropped the declared ones.
     */
    @Test
    fun savingOneTaskOverATornRecordKeepsTheTasksThatWereReadable() {
        val backing = AtomicReference(
            AgentTaskJsonCodec.encode(listOf(task("task_1"), task("task_2"))).let {
                it.substring(0, it.length - 7)
            }
        )
        val backedUp = AtomicReference<String?>(null)
        val store = store(
            readRaw = { backing.get() },
            backedUp = backedUp,
            writeRaw = { backing.set(it) }
        )

        store.upsert(task("task_3"))

        assertEquals(
            "the unreadable bytes must be archived before the rewrite",
            true,
            backedUp.get() != null
        )
        assertEquals(
            "a new task must not cost the tasks that were still readable: ${backing.get()}",
            // task_2 is the record the cut landed inside, so it is genuinely incomplete and
            // only task_1 survives; the point of the case is that the *readable* record and
            // the new one both end up stored, where the old code stored only the new one.
            listOf("task_1", "task_3"),
            checkNotNull(AgentTaskJsonCodec.decodeOrNull(backing.get())).map { it.id }
        )
    }

    /** A record that yields no task at all while not saying it is empty is not an empty. */
    @Test
    fun anArrayOfUnusableElementsIsNotReadAsAnHonestEmpty() {
        val backedUp = AtomicReference<String?>(null)
        val store = store(readRaw = { "[{\"notATask\":true}]" }, backedUp = backedUp)

        val tasks = store.list()

        assertTrue(tasks.isEmpty())
        assertNotNull(
            "an array that parsed but produced nothing usable still holds bytes the app cannot use",
            backedUp.get()
        )
    }

    private fun store(
        readRaw: () -> String?,
        backedUp: AtomicReference<String?>,
        writeRaw: (String) -> Unit = { }
    ): TaskRecordStore = TaskRecordStore(
        readRaw = readRaw,
        writeRaw = writeRaw,
        writeBackup = { backedUp.set(it) }
    )

    private fun task(id: String): AgentTask = AgentTask(
        id = id,
        sessionId = "session_1",
        title = "提醒 $id",
        schedule = AgentTaskSchedule.OneShot(1_600_000_000_000L),
        action = AgentTaskAction.NotifyUser("该休息了"),
        status = AgentTaskStatus.SCHEDULED,
        createdAtMillis = 1_599_999_000_000L,
        updatedAtMillis = 1_599_999_000_000L,
        nextRunAtMillis = 1_600_000_000_000L
    )
}
