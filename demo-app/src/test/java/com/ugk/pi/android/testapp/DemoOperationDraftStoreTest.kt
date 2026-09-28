package com.ugk.pi.android.testapp

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DemoOperationDraftStoreTest {
    @Test fun checkableObservationRoundTripsWhileLegacyNodesRemainUnknown() = withStore { _, store ->
        val oldNode = DemoOperationNode("0.1", null, "TextView", "Label", null, listOf(0, 0, 10, 10), false, false, false)
        val draft = sample().copy(frames = listOf(DemoOperationFrame("f", 10, "frame-${UUID.randomUUID()}.jpg", "settings", 100, 100, 1,
            nodes = listOf(oldNode, oldNode.copy(path = "0.2", checkable = false), oldNode.copy(path = "0.3", checkable = true)))))
        store.create(draft)
        val restored = store.read(draft.id)!!.frames.single().nodes
        assertNull(restored[0].checkable)
        assertEquals(false, restored[1].checkable)
        assertEquals(true, restored[2].checkable)
    }

    @Test fun scrollMetadataRoundTripsAndLegacyMissingDeltasStayUnknown() = withStore { _, store ->
        val legacy = DemoOperationEvent(1, 12, 4096, "settings", null, null, null, listOf(1, 2, 3, 4))
        val observed = legacy.copy(id = 2, scrollDeltaX = 0, scrollDeltaY = 0, scrollX = 0, scrollY = 42, fromIndex = 1, toIndex = 8)
        val draft = sample().copy(events = listOf(legacy, observed))
        store.create(draft)
        val restored = store.read(draft.id)!!
        assertEquals(draft.events, restored.events)
        assertFalse(restored.events[0].isZeroMovementScrollNotification())
        assertTrue(restored.events[1].isZeroMovementScrollNotification())
    }

    @Test fun checkpointRoundTripRetainsEvidenceAndRecoveryMarksInterrupted() = withStore { root, store ->
        val draft = sample().copy(events = listOf(DemoOperationEvent(1, 12, 1, "settings", "Button", "settings:id/item", "Display", listOf(1, 2, 3, 4))),
            frames = listOf(DemoOperationFrame("frame1", 11, "frame-${UUID.randomUUID()}.jpg", "settings", 100, 200, 10,
                nodes = listOf(DemoOperationNode("0.1", "settings:id/item", "Button", "Display", null, listOf(1, 2, 3, 4), true, false, false)),
                treeTruncated = true)))
        store.create(draft)
        assertTrue(File(root, "${draft.id}/active").isFile)
        assertEquals(draft, store.read(draft.id))
        val restarted = DemoOperationDraftStore(root)
        restarted.recover()
        val recovered = restarted.read(draft.id)!!
        assertEquals("interrupted", recovered.status)
        assertNotNull(recovered.endedAt)
        assertEquals(draft.events, recovered.events)
        assertEquals(draft.frames, recovered.frames)
        assertFalse(File(root, "${draft.id}/active").exists())
        assertEquals(1, recovered.gaps.size)
        restarted.recover()
        assertEquals(recovered, restarted.read(draft.id))
    }

    @Test fun corruptedDraftIsNotOverwrittenOrDeletedByRecovery() = withStore { root, store ->
        val draft = sample(); store.create(draft)
        val target = File(root, "${draft.id}/draft.json")
        target.writeText("corrupt user data")
        assertTrue(runCatching { store.write(draft) }.isFailure)
        DemoOperationDraftStore(root).recover()
        assertEquals("corrupt user data", target.readText())
        assertTrue(File(target.parentFile, "active").exists())
    }

    @Test fun activeDraftCannotBeDeletedAndFramesCannotEscapeDirectory() = withStore { root, store ->
        val draft = sample(); store.create(draft)
        assertTrue(store.delete(draft.id).isFailure)
        assertNull(store.frameFile(draft.id, "../../private.jpg"))
        assertNull(store.frameFile("../other", "frame-${UUID.randomUUID()}.jpg"))
        val name = "frame-${UUID.randomUUID()}.jpg"
        store.saveFrame(draft.id, name, byteArrayOf(1, 2, 3))
        assertTrue(runCatching { store.saveFrame(draft.id, name, byteArrayOf(4)) }.isFailure)
        store.write(draft.copy(endedAt = 20, status = "draft"))
        assertTrue(store.delete(draft.id).isSuccess)
        assertFalse(File(root, draft.id).exists())
    }

    @Test fun capacityRejectsNewDraftWithoutCleaningExistingAssets() = withStore { root, store ->
        repeat(DemoOperationLimits.MAX_DRAFTS) { store.create(sample()) }
        assertTrue(runCatching { store.create(sample()) }.isFailure)
        assertEquals(DemoOperationLimits.MAX_DRAFTS, root.listFiles()!!.size)
    }

    @Test fun lateInitializationDoesNotInterruptDraftCreatedBySameStore() = withStore { _, store ->
        val draft = sample(); store.create(draft)
        store.recover()
        assertEquals(draft, store.read(draft.id))
    }

    @Test fun missingActiveMarkerStillRecoversAnUnfinishedDraft() = withStore { root, store ->
        val draft = sample(); store.create(draft)
        assertTrue(File(root, "${draft.id}/active").delete())
        val restarted = DemoOperationDraftStore(root)
        val recovered = restarted.read(draft.id)!!
        assertEquals("interrupted", recovered.status)
        assertNotNull(recovered.endedAt)
    }

    @Test fun finishedDraftWithStaleMarkerRemainsFinishedAndDeletable() = withStore { root, store ->
        val finished = sample().copy(endedAt = 20, status = "draft")
        store.create(finished) // Models final JSON committed before active marker deletion.
        assertTrue(File(root, "${finished.id}/active").exists())
        val restarted = DemoOperationDraftStore(root)
        assertEquals(finished, restarted.read(finished.id))
        assertFalse(File(root, "${finished.id}/active").exists())
        assertTrue(restarted.delete(finished.id).isSuccess)
    }

    @Test fun recoveryCleansOnlyUnreferencedCollectorFramesInReadableDrafts() = withStore { root, store ->
        val id = UUID.randomUUID().toString()
        val retainedName = "frame-${UUID.randomUUID()}.jpg"
        val orphanName = "frame-${UUID.randomUUID()}.jpg"
        val tempName = "frame-${UUID.randomUUID()}.jpg.tmp"
        val draft = sample().copy(id = id, frames = listOf(DemoOperationFrame("kept", 11, retainedName, "settings", 100, 200, 1)))
        store.create(draft)
        val dir = File(root, id)
        listOf(retainedName, orphanName, tempName, "user-photo.jpg", "frame-not-a-uuid.jpg", "user-notes.json.tmp")
            .forEach { File(dir, it).writeBytes(byteArrayOf(1)) }
        val corrupt = sample(); store.create(corrupt)
        val corruptDir = File(root, corrupt.id)
        File(corruptDir, "draft.json").writeText("corrupt")
        File(corruptDir, orphanName).writeBytes(byteArrayOf(2))

        DemoOperationDraftStore(root).recover()

        listOf(retainedName, "user-photo.jpg", "frame-not-a-uuid.jpg", "user-notes.json.tmp")
            .forEach { assertTrue("Preserve $it", File(dir, it).exists()) }
        assertFalse(File(dir, orphanName).exists())
        assertFalse(File(dir, tempName).exists())
        assertTrue(File(corruptDir, orphanName).exists())
        assertEquals("corrupt", File(corruptDir, "draft.json").readText())
    }

    private fun sample() = DemoOperationDraft(UUID.randomUUID().toString(), "设置演示", 10)
    private fun withStore(block: (File, DemoOperationDraftStore) -> Unit) {
        val root = Files.createTempDirectory("operation-store-test").toFile()
        try { block(root, DemoOperationDraftStore(root)) } finally { root.deleteRecursively() }
    }
}
