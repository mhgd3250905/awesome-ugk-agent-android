package com.ugk.pi.android.testapp

import com.ugk.pi.android.ToolCall
import com.ugk.pi.android.ToolResult
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.json.*

internal data class DemoTeachingGuide(
    val title: String, val goal: String, val prerequisites: List<String>, val steps: List<String>,
    val corrections: List<String>, val completionChecks: List<String>, val uncertainties: List<String>,
    val intentAliases: List<String> = emptyList(), val targetApps: List<String> = emptyList(),
    val notApplicable: List<String> = emptyList(),
    val document: String = ""
) {
    fun readableText(): String = buildString {
        if (document.isNotBlank()) {
            append(document)
        } else {
            appendLine(title); appendLine(); appendLine("目标：$goal")
            fun section(title: String, items: List<String>) {
                if (items.isNotEmpty()) { appendLine(); appendLine(title); items.forEachIndexed { i, s -> appendLine("${i + 1}. $s") } }
            }
            section("准备条件", prerequisites); section("操作步骤", steps); section("纠正与注意事项", corrections)
            section("完成检查", completionChecks); section("待核实", uncertainties)
            section("适用表达", intentAliases); section("目标应用", targetApps); section("不适用情况", notApplicable)
        }
        appendLine(); append("这是经过整理的操作参考；复用时仍需观察当前页面，并按当前指令确认操作。")
    }
}

internal data class DemoTeachingAction(
    val id: String, val name: String, val input: JsonObject, val result: String? = null,
    val isError: Boolean? = null, val beforeImage: String? = null, val afterImage: String? = null,
    val gaps: List<String> = emptyList()
)
internal data class DemoTeachingSegment(
    val id: String, val instruction: String, val status: String = "running", val reply: String = "",
    val actions: List<DemoTeachingAction> = emptyList()
)
internal data class DemoTeachingRecord(
    val id: String, val title: String, val createdAt: Long, val updatedAt: Long,
    val status: String = "active", val segments: List<DemoTeachingSegment> = emptyList(),
    val guide: DemoTeachingGuide? = null,
    val guideRevision: Int = 0, val availability: String = "pending_validation",
    val compilationStatus: String = if (guide != null) "completed" else "not_started",
    val usageHistory: List<DemoTeachingUsage> = emptyList()
)
internal data class DemoTeachingUsage(val revision: Int, val outcome: String, val summary: String, val createdAt: Long)

/** Teaching is separate from legacy recordings and workflows; every update is a durable checkpoint. */
internal class DemoTeachingStore(private val root: File) {
    private var recovered = false
    private val json = Json { prettyPrint = true }

    @Synchronized fun list(): List<DemoTeachingRecord> {
        recover()
        return root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { read(it.name) }
            .sortedByDescending { it.createdAt }
    }
    @Synchronized fun read(id: String): DemoTeachingRecord? {
        recover()
        return runCatching {
            val file = File(directory(id), "record.json")
            check(file.length() in 1..MAX_JSON_BYTES)
            decode(json.parseToJsonElement(file.readText()).jsonObject).also { check(it.id == id) }
        }.getOrNull()
    }
    @Synchronized fun create(id: String, title: String) {
        recover(); check(list().size < 20) { "教学记录已达20份，请先整理已有记录" }
        root.mkdirs(); val dir = directory(id)
        check(!dir.exists() && dir.mkdir()) { "无法创建教学记录" }
        val now = System.currentTimeMillis()
        write(DemoTeachingRecord(id, title.take(120), now, now))
    }
    @Synchronized fun update(id: String, change: (DemoTeachingRecord) -> DemoTeachingRecord) {
        val previous = read(id) ?: error("教学记录无法读取，原文件已保留")
        val next = change(previous).copy(updatedAt = System.currentTimeMillis())
        // The count ceiling and the durable byte ceiling both bind, whichever is reached first:
        // long tool results cross 4 MB at about 350 actions, so a count-only guard reported an
        // unreachable capacity and the write failed as an internal error instead of a limit.
        if (next.id != id) error("教学记录标识与请求不一致")
        if (next.segments.size > 80 || next.segments.sumOf { it.actions.size } > 600) {
            throw DemoTeachingCapacityException(CAPACITY_MESSAGE)
        }
        val nextBytes = encode(next).toString().toByteArray(Charsets.UTF_8)
        // Only evidence growth is charged against the size limit. Status, availability, usage
        // history and the compiled guide all change the encoded size, and a record must always be
        // able to accept the writes that end it and that store its result. The reserve is sized for
        // the largest deliverable guide, so stopping evidence never costs the user the compilation
        // they already paid for.
        if (nextBytes.size > MAX_JSON_BYTES - GUIDE_RESERVE_BYTES && evidenceChars(next) > evidenceChars(previous)) {
            throw DemoTeachingCapacityException(CAPACITY_MESSAGE)
        }
        write(next, nextBytes)
    }
    @Synchronized fun saveGuide(id: String, guide: DemoTeachingGuide) = try { update(id) {
        check(it.status != "active") { "请先结束教学" }
        it.copy(guide = guide, guideRevision = it.guideRevision + 1,
            availability = "pending_validation", compilationStatus = "completed")
    } } catch (error: DemoTeachingCapacityException) {
        // The evidence, not the teaching, is what filled the record: saying "请结束后开始新教学" here
        // would send the user to end a teaching that has already ended, and pay for it again.
        throw DemoTeachingCapacityException("这份教学记录的证据已达容量上限，整理结果放不下；请新建一份教学")
    }
    @Synchronized fun resumeTeaching(id: String) = update(id) {
        check(it.status != "active" && it.compilationStatus != "compiling") { "记录正在使用" }
        check(it.segments.size < 80 && it.segments.sumOf { s -> s.actions.size } < 600) { "本记录已达容量上限，请开始新教学" }
        it.copy(status = "active", compilationStatus = "not_started", availability = "pending_validation",
            guideRevision = it.guideRevision + 1,
            segments = it.segments.map { s -> if (s.status == "running") s.copy(status = "interrupted") else s })
    }

    @Synchronized fun delete(id: String) {
        val record = read(id) ?: error("教学记录不存在或无法读取")
        check(record.status != "active" && record.compilationStatus != "compiling") { "请先停止教学或等待整理结束" }
        val dir = directory(id).canonicalFile
        check(dir.parentFile == root.canonicalFile) { "记录路径不正确" }
        val files = dir.listFiles().orEmpty()
        check(files.all { it.isFile && it.canonicalFile.parentFile == dir }) { "记录包含异常文件，未删除" }
        // Atomically remove the record from lookup before deleting its flat evidence directory.
        val removed = File(root, ".deleted-$id-${UUID.randomUUID()}")
        DemoAtomicFileOps.move(dir, removed)
        removed.listFiles().orEmpty().forEach { check(it.delete()) { "记录已移除，但部分文件清理失败" } }
        check(removed.delete()) { "记录已移除，但目录清理失败" }
    }
    @Synchronized fun setAvailability(id: String, availability: String) = update(id) {
        require(availability in setOf("pending_validation", "available", "needs_revision", "disabled"))
        require(it.guide != null && it.status != "active") { "请先结束并整理教学" }
        it.copy(availability = availability)
    }
    @Synchronized fun recordUsage(
        id: String,
        revision: Int,
        outcome: String,
        summary: String,
        verifiedAvailable: Boolean = false
    ) = update(id) {
        require(it.guide != null && revision == it.guideRevision) { "经验版本已变化，请重新检索" }
        require(outcome in setOf("success", "failure", "network_error", "cancelled", "needs_revision"))
        if (verifiedAvailable) {
            require(outcome == "success" && it.status != "active" && it.compilationStatus == "completed" &&
                it.availability in setOf("pending_validation", "available")) { "经验状态已变化，请重新检索" }
        }
        it.copy(availability = when {
            verifiedAvailable -> "available"
            outcome == "needs_revision" && it.availability != "disabled" -> "needs_revision"
            else -> it.availability
        },
            usageHistory = (it.usageHistory + DemoTeachingUsage(revision, outcome, summary.take(2000), System.currentTimeMillis())).takeLast(100))
    }
    /** Local lexical retrieval; pending references require the caller to verify current applicability. */
    @Synchronized fun searchGuides(query: String): List<DemoTeachingRecord> {
        val terms = searchTerms(query)
        if (terms.isEmpty()) return emptyList()
        return list().asSequence().filter {
            it.guide != null && it.status != "active" && it.compilationStatus == "completed" &&
                it.availability in setOf("pending_validation", "available")
        }.map { record ->
            val g = record.guide!!
            fun matches(text: String) = terms.intersect(searchTerms(text)).size
            val score = 4 * matches(g.title + " " + g.goal) +
                6 * matches((g.intentAliases + g.targetApps).joinToString(" ")) +
                matches(if (g.document.isNotBlank()) g.document else g.steps.joinToString(" "))
            record to score
        }.filter { it.second >= 4 }.sortedWith(compareByDescending<Pair<DemoTeachingRecord, Int>> { it.second }
            .thenByDescending { it.first.updatedAt }.thenBy { it.first.id }).take(5).map { it.first }.toList()
    }
    @Synchronized fun saveImage(id: String, bytes: ByteArray): String {
        require(bytes.size in 1..2 * 1024 * 1024)
        val dir = directory(id)
        check(dir.listFiles().orEmpty().count { it.extension == "jpg" } < 120) { "截图已达120张，后续保留文字证据" }
        val name = "image-${UUID.randomUUID()}.jpg"
        atomicWrite(File(dir, name), bytes)
        return name
    }
    fun imageFile(id: String, name: String): File? = runCatching {
        require(name.matches(Regex("image-[0-9a-f-]{36}\\.jpg")))
        File(directory(id), name).takeIf { it.isFile && it.length() in 1..2L * 1024 * 1024 }
    }.getOrNull()

    /** Complete text checkpoints; reusing a note or draft never substitutes for final Agent review. */
    @Synchronized fun readCompilationSummary(id: String, key: String): JsonObject? = runCatching {
        require(key.matches(Regex("[a-f0-9]{64}")))
        val file = File(directory(id), "compilation-summaries.json")
        if (!file.isFile || file.length() !in 1..MAX_COMPILATION_CACHE_BYTES) return@runCatching null
        Json.parseToJsonElement(file.readText()).jsonObject[key]?.jsonObject
    }.getOrNull()

    @Synchronized fun saveCompilationSummary(id: String, key: String, summary: JsonObject) {
        require(key.matches(Regex("[a-f0-9]{64}")))
        val file = File(directory(id), "compilation-summaries.json")
        val previous = runCatching {
            if (file.isFile && file.length() in 1..MAX_COMPILATION_CACHE_BYTES)
                Json.parseToJsonElement(file.readText()).jsonObject else JsonObject(emptyMap())
        }.getOrDefault(JsonObject(emptyMap()))
        val retained = LinkedHashMap(previous).apply { remove(key); put(key, summary) }
        var bytes = JsonObject(retained).toString().toByteArray(Charsets.UTF_8)
        while (bytes.size > MAX_COMPILATION_CACHE_BYTES && retained.size > 1) {
            retained.remove(retained.keys.first())
            bytes = JsonObject(retained).toString().toByteArray(Charsets.UTF_8)
        }
        check(bytes.size <= MAX_COMPILATION_CACHE_BYTES)
        atomicWrite(file, bytes)
    }

    /** Bounded, app-private metadata only: never persist prompts, responses, reasoning or credentials here. */
    @Synchronized fun appendCompilationDiagnostic(id: String, metadata: JsonObject) {
        val file = File(directory(id), "compilation-diagnostics.jsonl")
        val previous = if (file.isFile && file.length() <= 96_000) file.readLines().takeLast(79) else emptyList()
        val line = JsonObject(metadata + ("timestamp" to JsonPrimitive(System.currentTimeMillis()))).toString()
        require(line.length <= 2000)
        atomicWrite(file, (previous + line).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
    }

    private fun recover() {
        if (recovered) return
        recovered = true
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val old = read(dir.name) ?: return@forEach
            if (old.compilationStatus == "compiling") runCatching { write(old.copy(compilationStatus = "failed")) }
            if (old.status == "active") runCatching { write(old.copy(status = "interrupted", segments = old.segments.map {
                if (it.status == "running") it.copy(status = "interrupted") else it
            })) }
        }
    }
    private fun directory(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))); return File(root, id)
    }
    private fun write(record: DemoTeachingRecord, bytes: ByteArray = encode(record).toString().toByteArray(Charsets.UTF_8)) {
        if (bytes.size > MAX_JSON_BYTES) throw DemoTeachingCapacityException("教学记录过大，原文件已保留")
        atomicWrite(File(directory(record.id), "record.json"), bytes)
    }
    /**
     * Characters of teaching evidence only, so the size guard can tell "the user taught more" apart
     * from "the record gained a status word or a guide". Compared only against the same measure of
     * the previous record, so encoding differences cannot bias the direction.
     */
    private fun evidenceChars(record: DemoTeachingRecord): Int = record.segments.sumOf { segment ->
        segment.instruction.length + segment.reply.length + segment.actions.sumOf { action ->
            action.name.length + action.input.toString().length + (action.result?.length ?: 0) +
                action.gaps.sumOf { it.length } + (action.beforeImage?.length ?: 0) + (action.afterImage?.length ?: 0)
        }
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            DemoAtomicFileOps.move(temporary, file, replaceExisting = true)
        } finally {
            temporary.delete()
        }
    }
    companion object {
        private const val MAX_JSON_BYTES = 4L * 1024 * 1024
        private const val MAX_COMPILATION_CACHE_BYTES = 512 * 1024
        private const val CAPACITY_MESSAGE = "本次教学记录已达上限，请结束后开始新教学"
        // Room kept for the compiled result: the delivered document is bounded at 120,000 characters
        // and review notes at 12,000 (DemoTeachingSopAgent), which is under 0.5 MB even in UTF-8.
        private const val GUIDE_RESERVE_BYTES = 512 * 1024
        private fun searchTerms(text: String): Set<String> = buildSet {
            Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*|[\\p{IsHan}]+").findAll(text.lowercase(java.util.Locale.ROOT)).forEach {
                val word = it.value
                if (word.first() in 'a'..'z' || word.first().isDigit()) add(word)
                else if (word.length >= 2) addAll(word.windowed(2))
            }
        }
        fun guideJson(g: DemoTeachingGuide): JsonObject = buildJsonObject {
            put("title", g.title); put("goal", g.goal)
            put("prerequisites", strings(g.prerequisites)); put("steps", strings(g.steps))
            put("corrections", strings(g.corrections)); put("completionChecks", strings(g.completionChecks))
            put("uncertainties", strings(g.uncertainties))
            put("intentAliases", strings(g.intentAliases)); put("targetApps", strings(g.targetApps))
            put("notApplicable", strings(g.notApplicable))
            if (g.document.isNotEmpty()) put("document", g.document)
        }
        fun decodeGuide(j: JsonObject): DemoTeachingGuide = DemoTeachingGuide(j.text("title"), j.text("goal"),
            j.list("prerequisites"), j.list("steps"), j.list("corrections"), j.list("completionChecks"), j.list("uncertainties"),
            j.optionalList("intentAliases"), j.optionalList("targetApps"), j.optionalList("notApplicable"),
            document = j["document"]?.jsonPrimitive?.also { require(it.isString) }?.content.orEmpty())
        fun encode(r: DemoTeachingRecord): JsonObject = buildJsonObject {
            put("schemaVersion", 1); put("id", r.id); put("title", r.title); put("createdAt", r.createdAt)
            put("updatedAt", r.updatedAt); put("status", r.status)
            put("guideRevision", r.guideRevision); put("availability", r.availability); put("compilationStatus", r.compilationStatus)
            put("usageHistory", JsonArray(r.usageHistory.map { usage -> buildJsonObject {
                put("revision", usage.revision); put("outcome", usage.outcome); put("summary", usage.summary); put("createdAt", usage.createdAt)
            } }))
            r.guide?.let { put("guide", guideJson(it)) }
            put("segments", JsonArray(r.segments.map { s -> buildJsonObject {
                put("id", s.id); put("instruction", s.instruction); put("status", s.status); put("reply", s.reply)
                put("actions", JsonArray(s.actions.map { a -> buildJsonObject {
                    put("id", a.id); put("name", a.name); put("input", a.input)
                    a.result?.let { put("result", it) }; a.isError?.let { put("isError", it) }
                    a.beforeImage?.let { put("beforeImage", it) }; a.afterImage?.let { put("afterImage", it) }
                    put("gaps", strings(a.gaps))
                } }))
            } }))
        }
        private fun decode(j: JsonObject): DemoTeachingRecord {
            require(j["schemaVersion"]?.jsonPrimitive?.int == 1)
            return DemoTeachingRecord(j.text("id"), j.text("title"), j.getValue("createdAt").jsonPrimitive.long,
                j.getValue("updatedAt").jsonPrimitive.long, j.text("status"), j.getValue("segments").jsonArray.map { item ->
                    val s = item.jsonObject
                    DemoTeachingSegment(s.text("id"), s.text("instruction"), s.text("status"), s.text("reply"),
                        s.getValue("actions").jsonArray.map { entry -> val a = entry.jsonObject
                            DemoTeachingAction(a.text("id"), a.text("name"), a.getValue("input").jsonObject,
                                a["result"]?.jsonPrimitive?.content, a["isError"]?.jsonPrimitive?.boolean,
                                a["beforeImage"]?.jsonPrimitive?.content, a["afterImage"]?.jsonPrimitive?.content, a.list("gaps"))
                        })
                }, j["guide"]?.jsonObject?.let(::decodeGuide),
                j["guideRevision"]?.jsonPrimitive?.int ?: 0,
                j["availability"]?.jsonPrimitive?.content ?: "pending_validation",
                j["compilationStatus"]?.jsonPrimitive?.content ?: if (j["guide"] != null) "completed" else "not_started",
                j["usageHistory"]?.jsonArray?.map { entry -> val u = entry.jsonObject
                    DemoTeachingUsage(u.getValue("revision").jsonPrimitive.int, u.text("outcome"), u.text("summary"), u.getValue("createdAt").jsonPrimitive.long)
                }.orEmpty())
        }
        private fun strings(items: List<String>) = JsonArray(items.map(::JsonPrimitive))
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        private fun JsonObject.list(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
        private fun JsonObject.optionalList(key: String) = get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    }
}

/** The durable record has no room for more evidence; callers must still be able to end the teaching. */
internal class DemoTeachingCapacityException(message: String) : IllegalStateException(message)

/** Never persist transient model text, image base64, clipboard values, or terminal output. */
internal object DemoTeachingEvidence {
    /** Lower-cased parameter-name fragments whose value must never reach the transcript. */
    private val REDACTION_MARKERS = listOf("password", "token", "secret", "apikey", "api_key")

    fun input(call: ToolCall): JsonObject {
        if (call.name.startsWith("terminal_") || call.name.startsWith("clipboard_")) return buildJsonObject { put("redacted", true) }
        fun scrub(value: JsonElement, key: String = ""): JsonElement = when {
            // The list is compared against one normalised key. What actually leaked was the
            // underscored spelling: the old inline checks knew "apikey" only, so a parameter
            // named api_key matched nothing at all. Locale.ROOT is pinned because a security
            // boundary must not depend on a fold that is locale-sensitive in principle - on
            // this toolchain the no-argument lowercase() was measured to fold like ROOT already
            // (build/review-evidence/r16-lowercase-locale-probe2.log), so no Turkish device was
            // ever the cause, and that claim is recorded as falsified.
            REDACTION_MARKERS.any { marker -> key.lowercase(java.util.Locale.ROOT).contains(marker) } ||
                key == "text" && call.name == "screen_perform_action" -> JsonPrimitive("[已省略输入值]")
            value is JsonObject -> JsonObject(value.mapValues { (k, v) -> scrub(v, k) })
            value is JsonArray -> JsonArray(value.take(100).map { scrub(it) })
            value is JsonPrimitive && value.isString -> JsonPrimitive(value.content.take(2000))
            else -> value
        }
        return scrub(call.input).jsonObject
    }
    fun result(result: ToolResult): String = if (result.name.startsWith("terminal_") || result.name.startsWith("clipboard_"))
        "输出未记录；工具错误=${result.isError}" else result.content.take(12_000)
}
