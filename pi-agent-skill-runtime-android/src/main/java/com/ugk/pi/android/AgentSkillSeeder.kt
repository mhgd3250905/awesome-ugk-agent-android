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
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.delete()
        if (target.exists()) return 0
        return try {
            source.open(assetPath).use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Rename so a partial copy can never occupy the target path:
            // existing targets are never re-seeded, so a truncated target
            // would survive every later run.
            if (!temporary.renameTo(target)) {
                throw java.io.IOException("Failed to move seeded file onto '${target.name}'.")
            }
            1
        } catch (error: java.io.IOException) {
            temporary.delete()
            0
        }
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
