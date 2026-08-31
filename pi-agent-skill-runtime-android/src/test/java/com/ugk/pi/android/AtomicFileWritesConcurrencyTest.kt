package com.ugk.pi.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * `writeTextAtomically` is used by concurrent Agent runs (a foreground chat
 * and a background scheduled task both flush `memory_write` results). The
 * temporary file it writes used a fixed name derived from the target, so two
 * writers shared one temp path; the rename-then-copy fallback could then
 * delete the target outright:
 *
 *   writer B finishes writing the shared temp file and pauses;
 *   writer A overwrites the temp and renames it onto the target;
 *   writer B resumes, its rename fails (temp gone), the fallback deletes the
 *   target and its copy re-throws — the target is gone and B fails.
 *
 * The interleaving is orchestrated deterministically through the
 * [writeTextAtomically] `performWrite` seam.
 */
class AtomicFileWritesConcurrencyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `overlapping writes never delete the target file`() {
        val target = temporaryFolder.newFolder("skills").resolve("facts.md")
        val contentA = "A".repeat(2048)
        val contentB = "B".repeat(2048)

        val bWroteTemp = CountDownLatch(1)
        val aWroteTemp = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)

        val writerA = thread(name = "writer-a") {
            try {
                writeTextAtomically(
                    target = target,
                    text = contentA,
                    performWrite = { temporary, text ->
                        // A only starts once B's bytes are in the temp file.
                        assertTrue(bWroteTemp.await(5, TimeUnit.SECONDS))
                        writeTemporaryText(temporary, text)
                        aWroteTemp.countDown()
                        // A returns immediately: its rename moves the shared
                        // temp file away from under B.
                    }
                )
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        val writerB = thread(name = "writer-b") {
            try {
                writeTextAtomically(
                    target = target,
                    text = contentB,
                    performWrite = { temporary, text ->
                        writeTemporaryText(temporary, text)
                        bWroteTemp.countDown()
                        // B stays inside its write step while A renames the
                        // shared temp file onto the target.
                        assertTrue(aWroteTemp.await(5, TimeUnit.SECONDS))
                        assertTrue(releaseB.await(5, TimeUnit.SECONDS))
                    }
                )
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }

        // Give A's rename a moment to land before B is released into its
        // (broken) fallback path.
        assertTrue(aWroteTemp.await(5, TimeUnit.SECONDS))
        Thread.sleep(150)
        releaseB.countDown()
        writerA.join(TimeUnit.SECONDS.toMillis(10))
        writerB.join(TimeUnit.SECONDS.toMillis(10))

        assertTrue(
            "the target file must survive overlapping writes, failure=${failure.get()}",
            target.isFile
        )
        val finalText = target.readText(Charsets.UTF_8)
        assertTrue(
            "surviving content must be one complete write, was ${finalText.length} chars",
            finalText == contentA || finalText == contentB
        )
    }

    @Test
    fun `many concurrent writers leave a complete file and no temp residue`() {
        val target = temporaryFolder.newFolder("skills").resolve("profile.md")
        val writers = (1..8).map { index ->
            thread(name = "writer-$index") {
                repeat(25) { round ->
                    writeTextAtomically(target, "writer-$index-round-$round-${"x".repeat(512)}")
                }
            }
        }
        writers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertTrue("target must exist after the storm", target.isFile)
        val text = target.readText(Charsets.UTF_8)
        assertTrue("content must be a complete write, was ${text.length} chars", text.length > 512)
        val residue = target.parentFile!!.listFiles()!!
            .filter { it.name != target.name }
            .map { it.name }
        assertEquals(
            "no temporary residue may survive concurrent writes",
            emptyList<String>(),
            residue
        )
    }
}
