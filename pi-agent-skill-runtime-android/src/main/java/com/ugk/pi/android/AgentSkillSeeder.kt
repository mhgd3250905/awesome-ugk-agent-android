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
        if (target.exists()) return 0
        target.parentFile?.mkdirs()
        return try {
            source.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            1
        } catch (error: java.io.IOException) {
            // A partially written target would be re-seeded next time; delete it
            // so the next attempt starts clean instead of keeping a truncated file.
            target.delete()
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
