package com.ugk.pi.android.testapp

import android.system.Os
import java.io.File
import java.io.IOException
import java.lang.reflect.Array as ReflectArray

/** Same-directory atomic moves without loading API 26 NIO APIs on Android 24/25. */
internal object DemoAtomicFileOps {
    fun move(source: File, target: File, replaceExisting: Boolean = false) {
        require(source.canonicalFile.parentFile == target.canonicalFile.parentFile) {
            "Atomic moves must stay in one directory"
        }
        if (System.getProperty("java.vm.name") == "Dalvik") {
            // Os.rename is available from API 21 and atomically replaces files on Android.
            Os.rename(source.absolutePath, target.absolutePath)
        } else if (!moveOnDesktop(source, target, replaceExisting)) {
            throw IOException("Failed to atomically move '${source.name}' to '${target.name}'.")
        }
    }

    /** NIO is used only by desktop JVM tests; Android always takes the Os.rename branch. */
    private fun moveOnDesktop(source: File, target: File, replaceExisting: Boolean): Boolean = runCatching {
        val filesClass = Class.forName("java.nio.file.Files")
        val pathMethod = File::class.java.getMethod("toPath")
        val moveMethod = filesClass.methods.first {
            it.name == "move" && it.parameterTypes.size == 3 && it.parameterTypes[2].isArray
        }
        val copyOptionClass = Class.forName("java.nio.file.CopyOption")
        val standardCopyOption = Class.forName("java.nio.file.StandardCopyOption")
        val constants = standardCopyOption.enumConstants.orEmpty().associateBy { it.toString() }
        val options = buildList {
            add(constants["ATOMIC_MOVE"] ?: return@runCatching false)
            if (replaceExisting) add(constants["REPLACE_EXISTING"] ?: return@runCatching false)
        }
        val optionArray = ReflectArray.newInstance(copyOptionClass, options.size)
        options.forEachIndexed { index, option -> ReflectArray.set(optionArray, index, option) }
        moveMethod.invoke(null, pathMethod.invoke(source), pathMethod.invoke(target), optionArray)
        true
    }.getOrDefault(false)
}
