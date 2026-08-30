package com.ugk.pi.android

import java.io.File
import java.io.IOException

/**
 * Resolves a relative skill embed path only when its canonical target remains
 * inside the canonical root directory. This intentionally uses [File] APIs
 * only because the runtime supports API 24.
 */
internal fun resolveInsideRoot(root: File, path: String): File? {
    if (File(path).isAbsolute || path.startsWith("/") || path.contains('\\')) return null
    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.any { it == "." || it == ".." }) return null

    return try {
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, segments.joinToString(File.separator)).canonicalFile
        if (file.isInsideCanonical(canonicalRoot)) file else null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private fun File.isInsideCanonical(root: File): Boolean {
    val candidatePath = path
    val rootPath = root.path
    if (candidatePath == rootPath) return true

    val rootPrefix = if (rootPath.endsWith(File.separatorChar)) {
        rootPath
    } else {
        "$rootPath${File.separatorChar}"
    }
    return candidatePath.startsWith(
        rootPrefix,
        ignoreCase = File.separatorChar == '\\'
    )
}
