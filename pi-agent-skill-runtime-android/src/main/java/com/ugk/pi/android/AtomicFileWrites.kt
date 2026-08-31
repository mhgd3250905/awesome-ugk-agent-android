package com.ugk.pi.android

import java.io.File
import java.io.IOException
import java.lang.reflect.Array as ReflectArray

/**
 * Atomic text write for agent-owned state files. Content is written to a
 * same-directory temporary file first and renamed onto the target, so an
 * interrupted write can never leave a truncated file at the target path.
 * [performWrite] is injectable so tests can simulate a crash between the
 * temporary write and the rename.
 */
internal fun writeTextAtomically(
    target: File,
    text: String,
    performWrite: (File, String) -> Unit = ::writeTemporaryText
) {
    val parent = target.parentFile
        ?: throw IOException("Target file has no parent directory: '${target.name}'.")
    // Every writer stages into its own uniquely named file: a fixed
    // "<name>.tmp" made concurrent writers share one staging path, so one
    // writer's rename could move away the very file another writer was
    // still filling. createTempFile pre-creates the empty file, which the
    // default writeText step (and test injections) simply overwrite.
    val temporary = File.createTempFile(temporaryPrefixFor(target), ".tmp", parent)
    try {
        performWrite(temporary, text)
        if (!replaceOnto(temporary, target)) {
            throw IOException("Failed to move temporary file onto '${target.name}'.")
        }
    } finally {
        // Only this writer's unique staging file is ever touched here; after
        // a successful move it no longer exists and the delete is a no-op.
        if (temporary.exists()) temporary.delete()
    }
}

/** createTempFile rejects prefixes shorter than three characters. */
private fun temporaryPrefixFor(target: File): String {
    val prefix = "${target.name}."
    return if (prefix.length < 3) prefix.padEnd(3, '_') else prefix
}

/** Default write step; internal so failure-injection tests can reuse it. */
internal fun writeTemporaryText(temporary: File, text: String) {
    temporary.writeText(text, Charsets.UTF_8)
}

/**
 * File.renameTo is atomic on Android's filesystem and keeps API 24
 * compatibility. The reflective JVM fallback replaces existing targets on
 * Windows, where renameTo cannot. There is deliberately no delete-then-copy
 * last resort: when both moves fail we surface the failure and leave the
 * target untouched, because deleting the target first could destroy a file
 * another concurrent writer had just landed (and the copy would then fail
 * anyway, losing the target outright).
 */
internal fun replaceOnto(temporary: File, target: File): Boolean {
    if (temporary.renameTo(target)) return true
    return moveReflectively(temporary, target)
}

private fun moveReflectively(temporary: File, target: File): Boolean {
    return runCatching {
        val filesClass = Class.forName("java.nio.file.Files")
        val pathMethod = File::class.java.getMethod("toPath")
        val moveMethod = filesClass.methods.first {
            it.name == "move" && it.parameterTypes.size == 3 && it.parameterTypes[2].isArray
        }
        val copyOptionClass = Class.forName("java.nio.file.CopyOption")
        val standardCopyOption = Class.forName("java.nio.file.StandardCopyOption")
        val constants = standardCopyOption.enumConstants ?: return@runCatching false
        val replaceExisting = constants.firstOrNull { it.toString() == "REPLACE_EXISTING" }
            ?: return@runCatching false
        val options = ReflectArray.newInstance(copyOptionClass, 1)
        ReflectArray.set(options, 0, replaceExisting)
        moveMethod.invoke(
            null,
            pathMethod.invoke(temporary),
            pathMethod.invoke(target),
            options
        )
        true
    }.getOrDefault(false)
}
