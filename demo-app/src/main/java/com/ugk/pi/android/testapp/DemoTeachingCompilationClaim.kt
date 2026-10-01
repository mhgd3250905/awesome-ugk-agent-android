package com.ugk.pi.android.testapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The durable `compiling` claim on a teaching record, extracted from the Activity's coroutine.
 *
 * The claim is a durability invariant rather than UI state: while it is held the record cannot be
 * recompiled, resumed or deleted, and only a process restart (`DemoTeachingStore.recover`) clears it.
 * Keeping claim and release here lets JVM tests drive the exits that the Android `uiScope.launch`
 * body cannot reach on the host — in particular a `Throwable` that the Activity's
 * `catch (error: Exception)` deliberately does not handle.
 */
internal object DemoTeachingCompilationClaim {
    /** Takes the claim and returns the record to compile. Nothing suspends between the two. */
    fun claim(store: DemoTeachingStore, id: String): DemoTeachingRecord {
        store.update(id) {
            check(it.status != "active" && it.compilationStatus != "compiling") { "教学记录正在使用，请稍后重试" }
            it.copy(compilationStatus = "compiling")
        }
        return checkNotNull(store.read(id)) { "教学记录无法读取，原文件已保留" }
    }

    /**
     * Runs [block] while holding the claim and releases it on every exit, including a `Throwable`
     * that the caller's `catch (error: Exception)` does not handle. The claim is taken before the
     * `try`, so a refused claim never releases someone else's.
     */
    suspend fun <T> withClaim(store: DemoTeachingStore, id: String, block: suspend (DemoTeachingRecord) -> T): T {
        val record = withContext(Dispatchers.IO) { claim(store, id) }
        try {
            return block(record)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { release(store, id) }
        }
    }

    /**
     * Releases a claim the caller holds. Status-guarded and idempotent, so it is safe from `finally`:
     * a guide saved in the meantime is never relabelled failed, and a claim owned by another
     * compilation is never stolen (callers only release after [claim] returned).
     */
    fun release(store: DemoTeachingStore, id: String) {
        // Success already moved the record to "completed"; rewriting it there would re-encode and
        // fsync up to 4 MB and would bump updatedAt, which searchGuides ranks results by.
        if (store.read(id)?.compilationStatus != "compiling") return
        runCatching {
            store.update(id) {
                if (it.compilationStatus == "compiling") it.copy(compilationStatus = "failed") else it
            }
        }.onFailure { error ->
            // A claim that could not be released leaves the record unusable until the next process
            // start, so the leak is recorded instead of silently swallowed.
            runCatching {
                store.appendCompilationDiagnostic(id, buildJsonObject {
                    put("event", "claim_release_failed")
                    put("stage", "compilation")
                    put("failureCode", "CLAIM_RELEASE_FAILED")
                    put("failureDetail", (error.message ?: error.javaClass.simpleName).take(200))
                })
            }
        }
    }
}
