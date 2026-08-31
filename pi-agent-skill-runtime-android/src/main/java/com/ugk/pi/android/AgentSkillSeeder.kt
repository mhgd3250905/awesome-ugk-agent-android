package com.ugk.pi.android

import android.content.Context
import java.io.File
import java.io.InputStream

/** Source of packaged skill assets, so seeding can be tested off-device. */
interface SkillAssetSource {
    fun list(path: String): List<String>
    fun open(path: String): InputStream
}

/**
 * Seeds packaged skill files from the module assets (`agent-skills/`) into the
 * app-private `filesDir/agent-skills/` directory. Existing targets are never
 * overwritten, so re-seeding is idempotent and user-modified skills survive.
 */
object AgentSkillSeeder {

    const val ASSET_ROOT = "agent-skills"

    fun seed(context: Context): Int {
        val appContext = context.applicationContext
        val targetRoot = File(appContext.filesDir, ASSET_ROOT)
        return seed(AndroidSkillAssetSource(appContext), targetRoot)
    }

    fun seed(source: SkillAssetSource, targetRoot: File): Int {
        return copyAssetTree(source, ASSET_ROOT, targetRoot)
    }

    private fun copyAssetTree(source: SkillAssetSource, assetPath: String, target: File): Int {
        val children = source.list(assetPath)
        if (children.isEmpty()) {
            return copyAssetFile(source, assetPath, target)
        }
        var seeded = 0
        target.mkdirs()
        children.sorted().forEach { child ->
            seeded += copyAssetTree(source, "$assetPath/$child", File(target, child))
        }
        return seeded
    }

    private fun copyAssetFile(source: SkillAssetSource, assetPath: String, target: File): Int {
        val parent = target.parentFile ?: return 0
        parent.mkdirs()
        // Sweep the fixed-name residue an older version could have left
        // behind. Current seeds stage into unique names, so this path is
        // never a file a concurrent seed is still writing.
        File(parent, "${target.name}.tmp").delete()
        if (target.exists()) return 0
        // Two Agent runs can seed concurrently (a foreground chat plus a
        // scheduled task), so every seed stages into its own uniquely named
        // file: a shared "<name>.tmp" let one seed truncate or delete bytes
        // another seed was about to rename onto the target, and because the
        // target is never re-seeded the corruption would be permanent.
        val temporary = try {
            File.createTempFile(temporaryPrefixFor(target), ".tmp", parent)
        } catch (error: java.io.IOException) {
            return 0
        }
        return try {
            source.open(assetPath).use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Move the finished copy into place so a partial copy can never
            // occupy the target path: existing targets are never re-seeded,
            // so a truncated target would survive every later run. Re-check
            // first though — a concurrent seed may have landed a complete
            // file while we were copying, and existing targets are never
            // overwritten. Either way the winner's staged file holds the
            // full asset bytes, so the target content is always complete.
            if (target.exists()) {
                temporary.delete()
                0
            } else if (!temporary.renameTo(target)) {
                throw java.io.IOException("Failed to move seeded file onto '${target.name}'.")
            } else {
                1
            }
        } catch (error: java.io.IOException) {
            temporary.delete()
            0
        }
    }

    /** createTempFile rejects prefixes shorter than three characters. */
    private fun temporaryPrefixFor(target: File): String {
        val prefix = "${target.name}."
        return if (prefix.length < 3) prefix.padEnd(3, '_') else prefix
    }
}

private class AndroidSkillAssetSource(private val context: Context) : SkillAssetSource {
    override fun list(path: String): List<String> {
        return context.assets.list(path).orEmpty().toList()
    }

    override fun open(path: String): InputStream {
        return context.assets.open(path)
    }
}
