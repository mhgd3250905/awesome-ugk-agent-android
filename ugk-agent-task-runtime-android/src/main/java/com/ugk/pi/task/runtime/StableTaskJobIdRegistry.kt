package com.ugk.pi.task.runtime

import android.content.SharedPreferences

/** A platform JobScheduler entry; foreign services still reserve their IDs. */
internal data class PendingTaskJob(
    val taskId: String?,
    val jobId: Int,
    val isAgentTaskJob: Boolean = true,
    val isRunning: Boolean = false
)

internal interface TaskJobIdAssignmentStore {
    fun readAssignments(): Map<String, Int>
    fun saveAssignment(taskId: String, jobId: Int)
    fun removeAssignment(taskId: String): Boolean
}

internal interface TerminalTaskJobIdReleaser {
    fun beginTerminalJobIdRelease(taskId: String, runningJobId: Int): Set<Int>
    fun completeTerminalJobIdRelease(taskId: String, jobIds: Set<Int>)
}

/**
 * Assigns a stable, unique JobScheduler ID to each task. String.hashCode()
 * remains the starting point for compatibility, but collisions are resolved
 * against persisted assignments and platform jobs before scheduling.
 */
internal class StableTaskJobIdRegistry(
    private val assignments: TaskJobIdAssignmentStore,
    private val seedForTask: (String) -> Int = { it.hashCode() and Int.MAX_VALUE },
    private val pendingJobs: () -> List<PendingTaskJob>
) {
    /**
     * Serializes a full platform operation with allocation and release. The
     * lock is process-wide because Android can construct multiple schedulers.
     */
    fun <T> withExclusiveAccess(block: () -> T): T = synchronized(allocationLock) { block() }

    fun idFor(taskId: String): Int = synchronized(allocationLock) {
        val stored = assignments.readAssignments()
        val pending = pendingJobs()
        stored[taskId]?.let { currentId ->
            if (currentId !in blockedJobIds &&
                stored.none { (otherTaskId, jobId) -> otherTaskId != taskId && jobId == currentId }
                && pending.none {
                    it.jobId == currentId && (!it.isAgentTaskJob || it.taskId != taskId)
                }
            ) {
                return@synchronized currentId
            }
        }

        val legacyJob = pending.lastOrNull {
            it.isAgentTaskJob && !it.isRunning && it.taskId == taskId
        }
        if (legacyJob != null && stored.none { (otherTaskId, jobId) ->
                otherTaskId != taskId && jobId == legacyJob.jobId
            } && legacyJob.jobId !in blockedJobIds
        ) {
            assignments.saveAssignment(taskId, legacyJob.jobId)
            return@synchronized legacyJob.jobId
        }

        val occupiedIds = stored.values.toMutableSet().apply {
            pending.forEach { add(it.jobId) }
            addAll(blockedJobIds)
        }
        val jobId = firstAvailableId(seedForTask(taskId) and Int.MAX_VALUE, occupiedIds)
        assignments.saveAssignment(taskId, jobId)
        jobId
    }

    /** Includes mapped IDs and legacy pending IDs so cancellation survives upgrades. */
    fun jobIdsFor(taskId: String): Set<Int> = synchronized(allocationLock) {
        val stored = assignments.readAssignments()
        val pending = pendingJobs()
        buildSet {
            stored[taskId]?.let { assignedId ->
                val mappedElsewhere = stored.any { (otherTaskId, jobId) ->
                    otherTaskId != taskId && jobId == assignedId
                }
                val occupiedByOtherJob = pending.any {
                    it.jobId == assignedId && (!it.isAgentTaskJob || it.taskId != taskId)
                }
                if (!mappedElsewhere && !occupiedByOtherJob) {
                    add(assignedId)
                }
            }
            pending.asSequence()
                .filter { it.isAgentTaskJob && it.taskId == taskId }
                .forEach { add(it.jobId) }
        }
    }

    fun release(taskId: String): Boolean = synchronized(allocationLock) {
        val taskJobs = pendingJobs().filter { it.isAgentTaskJob && it.taskId == taskId }
        val ids = buildSet {
            addAll(jobIdsFor(taskId))
            taskJobs.forEach { add(it.jobId) }
        }
        val removed = assignments.removeAssignment(taskId)
        if (!removed) {
            // SharedPreferences.commit() may have updated the in-memory view
            // before failing to flush disk. Do not let another task reuse an
            // ID while persistent state may still refer to the old owner.
            unavailableJobIds.addAll(ids)
        } else {
            // JobScheduler.cancel() may stop a running job asynchronously.
            // Keep its ID unavailable until AgentTaskJobService observes the
            // canceled coroutine's final cleanup.
            retiringIds.addAll(taskJobs.asSequence().filter { it.isRunning }.map { it.jobId })
        }
        removed
    }

    /**
     * Reserves all IDs for a terminal task. Its persisted mapping remains in
     * place until JobService calls [completeRelease] after jobFinished(), so a
     * process death before completion cannot expose the ID for reuse.
     */
    fun beginRelease(
        taskId: String,
        runningJobId: Int
    ): Set<Int> = synchronized(allocationLock) {
        val pendingForTask = pendingJobs().filter { it.isAgentTaskJob && it.taskId == taskId }
        val ids = buildSet {
            assignments.readAssignments()[taskId]?.let(::add)
            pendingForTask.forEach { add(it.jobId) }
            add(runningJobId)
        }
        retiringIds.addAll(ids)
        ids
    }

    fun completeRelease(taskId: String, jobIds: Set<Int>) = synchronized(allocationLock) {
        var removed = false
        try {
            removed = assignments.removeAssignment(taskId)
        } finally {
            if (!removed) unavailableJobIds.addAll(jobIds)
            retiringIds.removeAll(jobIds)
        }
    }

    private fun firstAvailableId(seed: Int, occupiedIds: Set<Int>): Int {
        var candidate = seed
        do {
            if (candidate !in occupiedIds) return candidate
            candidate = if (candidate == Int.MAX_VALUE) 0 else candidate + 1
        } while (candidate != seed)
        error("No JobScheduler IDs remain for Agent tasks.")
    }

    companion object {
        // Multiple scheduler instances may be created by foreground and
        // background runtime factories in the same process.
        val allocationLock = Any()
        /** IDs whose preference removal failed remain blocked for this process. */
        val unavailableJobIds = mutableSetOf<Int>()
        /** IDs remain blocked between mapping removal and JobService completion. */
        val retiringIds = mutableSetOf<Int>()
        private val runningAgentJobs = mutableMapOf<Int, RunningAgentJob>()

        fun trackRunningJob(taskId: String, jobId: Int, token: Any) = synchronized(allocationLock) {
            runningAgentJobs[jobId] = RunningAgentJob(taskId, token)
        }

        fun finishRunningJob(jobId: Int, token: Any) = synchronized(allocationLock) {
            val current = runningAgentJobs[jobId]
            if (current?.token === token) {
                runningAgentJobs.remove(jobId)
                retiringIds.remove(jobId)
            }
        }

        fun runningJobs(): List<PendingTaskJob> = synchronized(allocationLock) {
            runningAgentJobs.map { (jobId, job) ->
                PendingTaskJob(job.taskId, jobId, isAgentTaskJob = true, isRunning = true)
            }
        }
    }

    private data class RunningAgentJob(val taskId: String, val token: Any)

    private val blockedJobIds: Set<Int>
        get() = synchronized(allocationLock) { unavailableJobIds + retiringIds }
}

/** Preferences are committed before JobScheduler.schedule() to keep IDs stable across process death. */
internal class SharedPreferencesTaskJobIdAssignmentStore(
    private val preferences: SharedPreferences
) : TaskJobIdAssignmentStore {
    override fun readAssignments(): Map<String, Int> = preferences.all.mapNotNull { (key, value) ->
        if (key.startsWith(TASK_PREFIX) && value is Int) {
            key.removePrefix(TASK_PREFIX) to value
        } else {
            null
        }
    }.toMap()

    override fun saveAssignment(taskId: String, jobId: Int) {
        check(preferences.edit().putInt(key(taskId), jobId).commit()) {
            "Unable to persist the JobScheduler ID for Agent task $taskId."
        }
    }

    override fun removeAssignment(taskId: String): Boolean =
        preferences.edit().remove(key(taskId)).commit()

    private fun key(taskId: String): String = "$TASK_PREFIX$taskId"

    private companion object {
        const val TASK_PREFIX = "task:"
    }
}
