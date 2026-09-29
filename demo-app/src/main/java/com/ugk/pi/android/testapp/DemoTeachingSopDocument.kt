package com.ugk.pi.android.testapp

/** The document is authoritative. Headings only enrich local search and the optional preview. */
internal object DemoTeachingSopDocument {
    fun guide(recordTitle: String, document: String, reviewNotes: String): DemoTeachingGuide {
        val body = document.trim()
        require(body.isNotBlank()) { "整理 Agent 尚未交付操作指南" }
        val sections = linkedMapOf<String, MutableList<String>>()
        var current = ""
        var title = recordTitle
        body.lineSequence().forEach { line ->
            val heading = HEADING.matchEntire(line.trim())
            if (heading != null) {
                val name = heading.groupValues[2].trim().trim('*', ' ')
                if (heading.groupValues[1].length == 1 && sections.isEmpty()) title = name.take(120)
                current = name
                sections.getOrPut(current) { mutableListOf() }
            } else if (current.isNotEmpty()) sections.getValue(current).add(line)
        }
        fun section(vararg names: String): String = sections.entries.firstOrNull { (heading, _) ->
            names.any { heading == it || heading.endsWith(it) }
        }?.value?.joinToString("\n")?.trim().orEmpty()
        fun items(vararg names: String): List<String> = section(*names).lineSequence()
            .map { it.trim().replaceFirst(LIST_PREFIX, "") }.filter { it.isNotBlank() }
            .take(30).map { it.take(1200) }.toList()
        val reviewedDocument = if (reviewNotes.isBlank()) body else
            "$body\n\n## 整理核对\n\n${reviewNotes.trim()}\n\n这是 Agent 对记录与指南的核对，实际操作时仍需确认当前页面和完成结果。"
        return DemoTeachingGuide(
            title = title.ifBlank { recordTitle },
            goal = section("目标", "操作目标").take(1000).ifBlank { "请结合指南正文核对当前任务与适用范围。" },
            prerequisites = items("准备条件", "前置条件"),
            steps = emptyList(),
            corrections = items("纠正与注意事项", "注意事项"),
            completionChecks = items("完成检查", "完成条件"),
            uncertainties = items("待核实", "证据缺口"),
            intentAliases = items("适用表达"), targetApps = items("目标应用"),
            notApplicable = items("不适用情况", "不适用范围"),
            document = reviewedDocument
        )
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
    private val LIST_PREFIX = Regex("^(?:[-*+]\\s+|[0-9]+[.)、]\\s*)")
}
