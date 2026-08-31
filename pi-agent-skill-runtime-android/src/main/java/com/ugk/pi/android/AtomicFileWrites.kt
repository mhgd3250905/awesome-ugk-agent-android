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
    val temporary = File(parent, "${target.name}.tmp")
    try {
        performWrite(temporary, text)
        if (!replaceOnto(temporary, target)) {
            throw IOException("Failed to move temporary file onto '${target.name}'.")
        }
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

/** Default write step; internal so failure-injection tests can reuse it. */
internal fun writeTemporaryText(temporary: File, text: String) {
    temporary.writeText(text, Charsets.UTF_8)
}

/**
 * File.renameTo is atomic on Android's filesystem and keeps API 24
 * compatibility. The reflective JVM fallback replaces existing targets on
 * Windows, where renameTo cannot; copy+delete is a last-resort fallback that
 * is not atomic but still writes complete content.
 */
internal fun replaceOnto(temporary: File, target: File): Boolean {
    if (temporary.renameTo(target)) return true
    if (moveReflectively(temporary, target)) return true
    return runCatching {
        target.delete()
        temporary.copyTo(target)
    }.isSuccess
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
