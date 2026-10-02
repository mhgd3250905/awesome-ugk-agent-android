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
 * and `DemoWorkflowRepository`. `LocalHttpServerManager` and `BashCommandTool`
 * gate a model-authored path and spell out `candidate == root` plus a
 * `File.separator`-suffixed prefix, which is exactly this rule.
 * `PythonDistribution`'s two compare archive/manifest paths and
 * `DemoWorkflowRepository` compares a workflow file path, and all three accept
 * only a strict prefix - they reject the root itself, so they are stricter than
 * this predicate rather than equivalent to it. All five are a registered
 * consolidation gap: this file does not speak for them.
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
