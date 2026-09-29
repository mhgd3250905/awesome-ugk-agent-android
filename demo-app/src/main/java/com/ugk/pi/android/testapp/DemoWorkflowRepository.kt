package com.ugk.pi.android.testapp

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.json.*

/** Independent storage: source recordings are never opened or changed here. */
internal class DemoWorkflowRepository(private val root: File) {
    private val unavailableDrafts = mutableSetOf<String>()
    @Synchronized fun readIntent(draftId: String): DemoWorkflowIntent? {
        val file = intentFile(draftId)
        if (!file.exists()) return null
        return DemoWorkflowJson.decodeIntent(readObject(file)).also { check(it.draftId == draftId) { "操作说明身份不匹配" } }
    }
    @Synchronized fun saveIntent(intent: DemoWorkflowIntent) {
        DemoWorkflowJson.validateIntent(intent)
        val dir = ensureDirectory(intent.draftId)
        val file = intentFile(intent.draftId)
        if (file.exists()) readIntent(intent.draftId) // Never overwrite damaged or foreign data.
        atomicWrite(File(dir, file.name), DemoWorkflowJson.intent(intent), true)
    }
    @Synchronized fun read(draftId: String): DemoWorkflowPlan? {
        val dir = directory(draftId)
        val versions = dir.listFiles().orEmpty().filter { it.name.matches(Regex("v[1-9][0-9]*\\.json")) }
        return versions.maxByOrNull { it.name.drop(1).removeSuffix(".json").toInt() }?.let {
            DemoWorkflowJson.decodePlan(readObject(it)).also { plan ->
                check(plan.draftId == draftId && it.name == "v${plan.version}.json") { "操作版本身份不匹配" }
            }
        }
    }
    @Synchronized fun saveNewVersion(plan: DemoWorkflowPlan): DemoWorkflowPlan {
        DemoWorkflowJson.validate(plan)
        val dir = ensureDirectory(plan.draftId)
        val files = dir.listFiles().orEmpty().filter { it.name.matches(Regex("v[1-9][0-9]*\\.json")) }
        check(files.size < 50) { "此操作已达50个版本上限" }
        // Validate every existing version before extending a potentially corrupt history.
        files.forEach { file ->
            val existing = DemoWorkflowJson.decodePlan(readObject(file))
            check(existing.draftId == plan.draftId && file.name == "v${existing.version}.json") { "历史版本身份不匹配" }
        }
        val saved = plan.copy(version = (read(plan.draftId)?.version ?: 0) + 1)
        atomicWrite(File(dir, "v${saved.version}.json"), DemoWorkflowJson.plan(saved), false)
        return saved
    }
    @Synchronized fun records(draftId: String): List<DemoWorkflowRunRecord> = File(directory(draftId), "runs").listFiles().orEmpty()
        .filter { it.name.endsWith(".json") }.map { file ->
            DemoWorkflowJson.decodeRun(readObject(file)).also { check(it.draftId == draftId && file.name == "${it.id}.json") }
        }.sortedByDescending { it.startedAt }
    @Synchronized fun saveRun(record: DemoWorkflowRunRecord) {
        uuid(record.id)
        val version = File(directory(record.draftId), "v${record.version}.json")
        check(version.exists()) { "运行引用的版本不存在" }
        val plan = DemoWorkflowJson.decodePlan(readObject(version))
        check(record.planDigest == plan.digest()) { "运行与版本摘要不匹配" }
        require(record.totalSteps == plan.steps.size && record.completedSteps in 0..record.totalSteps)
        require(record.modelCalls in 0..100 && record.imagesSent in 0..100 && record.message.length <= 2000)
        require(record.status in setOf("started", "preparing", "running", "judging", "succeeded", "completed", "failed", "interrupted", "cancelled", "awaiting_user"))
        val dir = File(directory(record.draftId), "runs")
        check(dir.exists() || dir.mkdir())
        val file = File(dir, "${record.id}.json")
        if (file.exists()) {
            val old = DemoWorkflowJson.decodeRun(readObject(file))
            check(old.draftId == record.draftId && old.version == record.version && old.planDigest == record.planDigest && old.startedAt == record.startedAt && old.isTrial == record.isTrial)
            check(old.endedAt == null || old == record) { "已结束运行不可覆盖" }
        } else check(dir.listFiles().orEmpty().count { it.name.endsWith(".json") } < 200) { "此操作已达200条运行记录上限" }
        atomicWrite(file, DemoWorkflowJson.run(record), true)
    }
    @Synchronized fun recoverInterrupted() {
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            try {
                records(dir.name).filter { it.endedAt == null }.forEach {
                    saveRun(it.copy(status = "interrupted", endedAt = System.currentTimeMillis(), message = "进程退出，操作结果可能未确认；不会自动重放"))
                }
            } catch (_: Exception) {
                // A corrupt recording must not poison every healthy operation. Keep all
                // bytes, and fail closed for this draft (including trial receipt reads).
                unavailableDrafts += dir.name
            }
        }
    }
    /** Explicit user deletion only. Fully inspect ownership before removing any file. */
    @Synchronized fun delete(draftId: String) {
        val dir = directory(draftId)
        check(root.absoluteFile == root.canonicalFile) { "操作仓库路径包含链接，保留文件" }
        fun owned(file: File) {
            check(file.absoluteFile == file.canonicalFile && file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                "操作路径包含链接或越界，保留文件"
            }
        }
        owned(dir)
        if (!dir.exists()) return
        check(dir.isDirectory) { "操作目录异常，保留文件" }
        val children = dir.listFiles() ?: error("无法读取操作目录，保留文件")
        val plans = mutableMapOf<Int, DemoWorkflowPlan>()
        val files = mutableListOf<File>()
        var runsDirectory: File? = null
        children.forEach { file ->
            owned(file)
            when {
                file.name.matches(Regex("v[1-9][0-9]*\\.json")) && file.isFile -> {
                    val plan = DemoWorkflowJson.decodePlan(readObject(file))
                    check(plan.draftId == draftId && file.name == "v${plan.version}.json") { "版本身份异常，保留文件" }
                    plans[plan.version] = plan
                    files += file
                }
                file.name == "runs" && file.isDirectory -> runsDirectory = file
                file.name == "intent.json" && file.isFile -> {
                    check(DemoWorkflowJson.decodeIntent(readObject(file)).draftId == draftId) { "操作说明身份异常，保留文件" }
                    files += file
                }
                else -> error("操作目录含未知文件，保留文件")
            }
        }
        runsDirectory?.let { runs ->
            (runs.listFiles() ?: error("无法读取运行目录，保留文件")).forEach { file ->
                owned(file)
                check(file.isFile && file.name.endsWith(".json")) { "运行目录含未知文件，保留文件" }
                val record = DemoWorkflowJson.decodeRun(readObject(file))
                uuid(record.id)
                check(record.draftId == draftId && file.name == "${record.id}.json") { "运行身份异常，保留文件" }
                check(record.endedAt != null) { "操作仍有未结束运行，请先停止并恢复记录" }
                val plan = plans[record.version] ?: error("运行引用版本缺失，保留文件")
                check(record.planDigest == plan.digest()) { "运行摘要异常，保留文件" }
                files += file
            }
        }
        // No recursive traversal or cleanup of unknown data. A failed I/O is reported.
        files.forEach { owned(it); check(it.delete()) { "部分操作文件删除失败，请重试" } }
        runsDirectory?.let { owned(it); check(it.delete()) { "运行目录删除失败" } }
        check(dir.delete()) { "操作目录删除失败" }
    }
    private fun directory(id: String): File {
        uuid(id)
        check(id !in unavailableDrafts) { "此操作的运行记录损坏或恢复失败，原文件已保留；其他操作仍可使用" }
        return File(root, id).also { check(it.canonicalFile == File(root.canonicalFile, id)) { "非法操作路径" } }
    }
    private fun ensureDirectory(id: String): File = directory(id).also { dir ->
        if (!dir.exists()) {
            check(root.listFiles().orEmpty().count { it.isDirectory } < 20) { "已学操作已达20份上限" }
            check(dir.mkdirs()) { "无法创建操作目录" }
        }
        check(dir.isDirectory) { "操作目录异常" }
    }
    private fun intentFile(id: String): File {
        val dir = directory(id)
        return File(dir, "intent.json").also { check(it.canonicalFile == File(dir.canonicalFile, "intent.json")) { "非法操作说明路径" } }
    }
    private fun uuid(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "非法操作标识" } }
    private fun readObject(file: File): JsonObject {
        check(file.length() in 1..MAX_BYTES.toLong()) { "操作文件为空或超过大小限制；已保留原文件" }
        return Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    }
    private fun atomicWrite(file: File, value: JsonObject, replace: Boolean) {
        check(replace || !file.exists()) { "操作版本不可覆盖" }
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        val temp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            DemoAtomicFileOps.move(temp, file, replaceExisting = true)
        } finally { temp.delete() }
    }
    companion object { const val MAX_BYTES = 256 * 1024 }
}

internal object DemoWorkflowJson {
    fun selector(s: DemoWorkflowSelector) = buildJsonObject {
        s.viewId?.let { put("viewId", it) }; s.text?.let { put("text", it) }; s.description?.let { put("description", it) }
        s.className?.let { put("className", it) }; s.checked?.let { put("checked", it) }
    }
    fun plan(p: DemoWorkflowPlan, usage: Boolean = true) = buildJsonObject {
        put("draftId", p.draftId); put("version", p.version); put("createdAt", p.createdAt); put("title", p.title); put("goal", p.goal)
        // Blank legacy criteria keep their original digest and existing run receipts.
        if (usage || p.completionCriteria.isNotEmpty()) put("completionCriteria", p.completionCriteria)
        put("steps", JsonArray(p.steps.map { s -> buildJsonObject {
            put("id", s.id); put("title", s.title); put("action", s.action); put("packageName", s.packageName)
            s.selector?.let { put("selector", selector(it)) }
            put("postcondition", buildJsonObject { put("packageName", s.postcondition.packageName); put("selectors", JsonArray(s.postcondition.selectors.map(::selector))); s.postcondition.visualQuestion?.let { put("visualQuestion", it) } })
            put("sourceEventIds", JsonArray(s.sourceEventIds.map(::JsonPrimitive)))
        } }))
        put("warnings", JsonArray(p.warnings.map(::JsonPrimitive)))
        if (usage) { put("modelCalls", p.modelCalls); put("imagesSent", p.imagesSent) }
    }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
    private fun JsonObject.optional(key: String) = get(key)?.let { it.jsonPrimitive.also { p -> require(p.isString) }.content }
    private fun keys(o: JsonObject, vararg allowed: String) { require(o.keys.all { it in allowed }) { "步骤包含不支持的字段" } }
    fun decodeSelector(o: JsonObject): DemoWorkflowSelector {
        keys(o, "viewId", "text", "description", "className", "checked")
        return DemoWorkflowSelector(o.optional("viewId"), o.optional("text"), o.optional("description"), o.optional("className"), o["checked"]?.jsonPrimitive?.boolean)
    }
    fun decodePlan(o: JsonObject): DemoWorkflowPlan {
        keys(o, "draftId", "version", "createdAt", "title", "goal", "steps", "warnings", "modelCalls", "imagesSent", "completionCriteria")
        return DemoWorkflowPlan(o.string("draftId"), o["version"]?.jsonPrimitive?.int ?: 0, o.getValue("createdAt").jsonPrimitive.long, o.string("title"), o.string("goal"), o.getValue("steps").jsonArray.map { element ->
            val s = element.jsonObject
            keys(s, "id", "title", "action", "packageName", "selector", "postcondition", "sourceEventIds")
            val c = s.getValue("postcondition").jsonObject
            keys(c, "packageName", "selectors", "visualQuestion")
            DemoWorkflowStep(s.string("id"), s.string("title"), s.string("action"), s.string("packageName"), s["selector"]?.jsonObject?.let(::decodeSelector), DemoWorkflowCondition(c.string("packageName"), c["selectors"]?.jsonArray?.map { decodeSelector(it.jsonObject) }.orEmpty(), c.optional("visualQuestion")), s.getValue("sourceEventIds").jsonArray.map { it.jsonPrimitive.int })
        }, o["warnings"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), o["modelCalls"]?.jsonPrimitive?.int ?: 0, o["imagesSent"]?.jsonPrimitive?.int ?: 0, o.optional("completionCriteria") ?: "").also(::validate)
    }
    fun validate(p: DemoWorkflowPlan) {
        require(runCatching { UUID.fromString(p.draftId).toString() == p.draftId }.getOrDefault(false))
        require(p.version in 0..50 && p.createdAt > 0 && p.modelCalls in 0..100 && p.imagesSent in 0..100)
        require(p.title.isNotBlank() && p.title.length <= 120 && p.goal.isNotBlank() && p.goal.length <= 1000)
        require(p.completionCriteria.length <= 1000)
        require(p.steps.size in 1..40 && p.steps.map { it.id }.distinct().size == p.steps.size)
        require(p.warnings.size <= 30 && p.warnings.all { it.length <= 500 })
        p.steps.forEach { s ->
            require(s.id.matches(Regex("[A-Za-z0-9_-]{1,40}")) && s.title.isNotBlank() && s.title.length <= 200)
            require(s.action in setOf("launch", "click", "long_click", "scroll_forward", "scroll_backward", "back", "check"))
            require(s.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) && s.postcondition.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
            require(s.sourceEventIds.isNotEmpty() && s.sourceEventIds.size <= 100)
            if (s.action in setOf("click", "long_click", "scroll_forward", "scroll_backward")) require(s.selector != null)
            require(s.postcondition.selectors.isNotEmpty() || !s.postcondition.visualQuestion.isNullOrBlank()) { "不能仅凭应用包名宣告步骤成功" }
            require(s.postcondition.selectors.size <= 8 && (s.postcondition.visualQuestion?.length ?: 0) <= 500)
            (listOfNotNull(s.selector) + s.postcondition.selectors).forEach { selector ->
                require(listOf(selector.viewId, selector.text, selector.description).any { !it.isNullOrBlank() }) { "需要明确的语义目标" }
                require(listOf(selector.viewId, selector.text, selector.description, selector.className).filterNotNull().all { it.isNotBlank() && it.length <= 300 })
            }
        }
    }
    fun validateIntent(i: DemoWorkflowIntent) {
        require(runCatching { UUID.fromString(i.draftId).toString() == i.draftId }.getOrDefault(false))
        require(i.goal.length <= 1000 && i.completionCriteria.length <= 1000 && i.updatedAt > 0)
    }
    fun intent(i: DemoWorkflowIntent) = buildJsonObject {
        put("draftId", i.draftId); put("goal", i.goal); put("completionCriteria", i.completionCriteria); put("updatedAt", i.updatedAt)
    }
    fun decodeIntent(o: JsonObject): DemoWorkflowIntent {
        keys(o, "draftId", "goal", "completionCriteria", "updatedAt")
        return DemoWorkflowIntent(o.string("draftId"), o.string("goal"), o.string("completionCriteria"), o.getValue("updatedAt").jsonPrimitive.long).also(::validateIntent)
    }
    fun run(r: DemoWorkflowRunRecord) = buildJsonObject {
        put("id", r.id); put("draftId", r.draftId); put("version", r.version); put("planDigest", r.planDigest); put("isTrial", r.isTrial); put("status", r.status); put("startedAt", r.startedAt)
        r.endedAt?.let { put("endedAt", it) }; put("completedSteps", r.completedSteps); put("totalSteps", r.totalSteps); put("modelCalls", r.modelCalls); put("imagesSent", r.imagesSent); put("message", r.message)
    }
    fun decodeRun(o: JsonObject) = DemoWorkflowRunRecord(o.string("id"), o.string("draftId"), o.getValue("version").jsonPrimitive.int, o.string("planDigest"), o.getValue("isTrial").jsonPrimitive.boolean, o.string("status"), o.getValue("startedAt").jsonPrimitive.long, o["endedAt"]?.jsonPrimitive?.long, o.getValue("completedSteps").jsonPrimitive.int, o.getValue("totalSteps").jsonPrimitive.int, o.getValue("modelCalls").jsonPrimitive.int, o.getValue("imagesSent").jsonPrimitive.int, o.string("message"))
}
