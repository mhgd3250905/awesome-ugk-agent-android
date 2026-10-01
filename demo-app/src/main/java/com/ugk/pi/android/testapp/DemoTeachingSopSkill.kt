package com.ugk.pi.android.testapp

import android.content.Context
import com.ugk.pi.android.AndroidSkill
import com.ugk.pi.android.AndroidSkillPromptBuilder
import com.ugk.pi.agent.skill.runtime.SkillManifestParseResult
import com.ugk.pi.agent.skill.runtime.SkillManifestParser
import kotlinx.coroutines.CancellationException

/** One bundled skill snapshot shared by every phase of a teaching compilation. */
internal class DemoTeachingSopSkill private constructor(
    private val common: AndroidSkill,
    private val references: Map<String, String>
) {
    enum class Stage(val label: String, val reference: String) {
        STEP_NOTES("提炼当前批次的步骤笔记", "step-notes.md"),
        MERGE_NOTES("合并已有步骤笔记", "step-notes.md"),
        WRITE_SOP("编写完整 SOP 草稿", "write-sop.md"),
        REVIEW_SOP("审核修订并交付 SOP", "review-sop.md")
    }

    fun forStage(stage: Stage): AndroidSkill = common.copy(
        instructions = common.instructions + "\n\n## 当前阶段：${stage.label}\n\n" +
            references.getValue(stage.reference)
    )

    fun prompt(stage: Stage): String = AndroidSkillPromptBuilder().build(
        listOf(forStage(stage)), availableToolNames = emptySet()
    )

    companion object {
        private const val SKILL_ID = "teaching-sop-author"
        private const val ASSET_ROOT = "teaching-skills/$SKILL_ID"

        fun load(context: Context): DemoTeachingSopSkill = load { path ->
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        /** The host supplies an asset reader; JVM callers can read the same source assets. */
        fun load(readAsset: (String) -> String): DemoTeachingSopSkill = try {
            val parsed = SkillManifestParser.parse(readAsset("$ASSET_ROOT/SKILL.md"))
            check(parsed is SkillManifestParseResult.Valid && parsed.manifest.name == SKILL_ID)
            val references = Stage.entries.map { it.reference }.distinct().associateWith { reference ->
                readAsset("$ASSET_ROOT/references/$reference").trim().also { check(it.isNotEmpty()) }
            }
            DemoTeachingSopSkill(
                AndroidSkill(parsed.manifest.name, parsed.manifest.description, parsed.body.trim()),
                references
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw DemoTeachingCompileException(
                "SOP_SKILL_UNAVAILABLE", "教学整理方法暂时无法加载，请更新或重新打开 App 后重试。原始记录已保留。"
            )
        }
    }
}
