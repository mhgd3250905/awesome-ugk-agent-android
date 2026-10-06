package com.ugk.pi.android.testapp

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Keeps the exact bytes of a durable value the app could not read, before any
 * write is allowed to replace it.
 *
 * Both stores in this layer hold a whole collection in one value and save by
 * read-modify-write, so a value that decodes to "the user has nothing" is
 * replaced by the next ordinary save. Repairing the records themselves is a
 * separate concern (see `salvageStoredConversationArray`); this type only
 * guarantees that losing them is never silent and never total.
 *
 * The name is derived from the content, so relaunching onto the same damaged
 * value keeps one copy instead of piling up duplicates.
 */
internal object DemoUnreadableSnapshotArchive {
    /** Directory name, asserted as a literal by `DemoUnreadableSnapshotTest`. */
    const val DIRECTORY_NAME = "snapshot-recovery"

    /** Bounds the copy itself: a store that grew past this is not worth filling the disk with. */
    private const val MAX_ARCHIVED_BYTES = 4 * 1024 * 1024

    /** Oldest copies are pruned so a repeatedly damaging store cannot grow without limit. */
    private const val MAX_ARCHIVES_PER_STORE = 3

    private const val TAG = "UnreadableSnapshot"

    /**
     * @return the file holding [raw], or null when nothing could be kept. A null
     * result is logged with the byte count: the caller deliberately does not fail,
     * because refusing to start would be the worse outcome for the user.
     */
    fun preserve(filesDir: File, prefix: String, raw: String): File? = runCatching {
        val bytes = raw.toByteArray()
        if (bytes.size > MAX_ARCHIVED_BYTES) {
            Log.w(TAG, "$prefix snapshot is ${bytes.size} bytes, above the ${MAX_ARCHIVED_BYTES} " +
                "recovery limit; nothing was archived")
            return@runCatching null
        }
        val directory = File(filesDir, DIRECTORY_NAME)
        if (!directory.isDirectory && !directory.mkdirs()) {
            return@runCatching null
        }
        val target = File(directory, "$prefix-${fingerprint(bytes)}.json")
        if (target.isFile && runCatching { target.readText() == raw }.getOrDefault(false)) {
            return@runCatching target
        }
        val temporary = File(directory, "${target.name}.part")
        temporary.writeBytes(bytes)
        DemoAtomicFileOps.move(temporary, target, replaceExisting = true)
        pruneOldest(directory, prefix, target)
        target
    }.getOrElse {
        Log.w(TAG, "Unable to archive the unreadable $prefix snapshot (${raw.toByteArray().size} bytes)", it)
        null
    }

    private fun pruneOldest(directory: File, prefix: String, keep: File) {
        val excess = directory.listFiles { file -> file.isFile && file.name.startsWith("$prefix-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_ARCHIVES_PER_STORE - 1)
            ?.filter { it != keep }
            .orEmpty()
        excess.forEach { stale -> runCatching { stale.delete() } }
    }

    private fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(16)
}
