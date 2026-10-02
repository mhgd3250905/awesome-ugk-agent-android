package com.ugk.pi.android

import java.io.File

/**
 * Whether this file's canonical location is [root] itself or lies inside it.
 *
 * The rule every model-authored relative path is checked against, in three
 * places: the app-private file workspace (`AppPrivateFileTool.resolvePath` and
 * `AppFileListTool`), skill embed resolution (`resolveInsideRoot`) and skill
 * deletion (`SkillRepository.validateDeleteTree`). `FileContainmentTest` pins
 * the boundary, including the sibling-with-a-shared-prefix case that a plain
 * `startsWith(root.path)` accepts.
 *
 * Five hand-written comparisons in four files are NOT covered by this
 * predicate: `LocalHttpServerManager.resolveWorkspaceDirectory` and two checks
 * in `PythonDistribution` - `:ugk-terminal-runtime-android` has no dependency
 * edge on this module, so it cannot reach it - plus `BashCommandTool.isInside`
 * and `DemoWorkflowRepository`. Each compares canonicalized operands and
 * appends `File.separator`, so they behave like this rule on Android; they are
 * a registered consolidation gap, not extra guards this file speaks for.
 *
 * Deliberately [File]-API only because the runtime supports API 24; the
 * comparison is case-insensitive on Windows, where the filesystem is too.
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
