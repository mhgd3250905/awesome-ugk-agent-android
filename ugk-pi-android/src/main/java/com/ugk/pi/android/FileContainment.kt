package com.ugk.pi.android

import java.io.File

/**
 * Whether this file's canonical location is [root] itself or lies inside it.
 *
 * Shared by every SDK path-safety check that must keep a model-authored
 * relative path inside its owning root (the app-private file workspace, the
 * skill repository, and skill embed resolution). Deliberately [File]-API
 * only because the runtime supports API 24; the comparison is
 * case-insensitive on Windows, where the filesystem is too.
 */
fun File.isInsideRoot(root: File): Boolean {
    val candidatePath = canonicalFile.path
    val rootPath = root.canonicalFile.path
    if (candidatePath == rootPath) return true

    return candidatePath.startsWith(
        rootPath.withTrailingFileSeparator(),
        ignoreCase = File.separatorChar == '\\'
    )
}

private fun String.withTrailingFileSeparator(): String {
    return if (endsWith(File.separatorChar)) this else "$this${File.separatorChar}"
}
