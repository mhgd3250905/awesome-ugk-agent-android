package com.ugk.pi.android.testapp

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.*

/** Private append/checkpoint storage. Corrupt records are preserved and never overwritten. */
internal class DemoOperationDraftStore(private val root: File) {
    private val json = Json { prettyPrint = true }
    private var recovered = false

    @Synchronized fun create(draft: DemoOperationDraft) {
        recover()
        root.mkdirs()
        check((root.listFiles()?.count { it.isDirectory } ?: 0) < DemoOperationLimits.MAX_DRAFTS) {
            "草稿已达20份，请先查看已有草稿；不会自动删除素材"
        }
        val dir = directory(draft.id)
        check(!dir.exists() && dir.mkdir()) { "无法创建草稿目录" }
        write(draft)
        atomicWrite(File(dir, "active"), draft.id.toByteArray())
    }

    @Synchronized fun write(draft: DemoOperationDraft) {
        val target = File(directory(draft.id), "draft.json")
        if (target.exists()) check(read(draft.id) != null) { "草稿已损坏，保留原文件并停止写入" }
        val bytes = encode(draft).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= DemoOperationLimits.MAX_JSON_BYTES) { "草稿结构已达8MB上限" }
        atomicWrite(target, bytes)
        if (draft.endedAt != null) File(target.parentFile, "active").delete()
    }

    @Synchronized fun recover() {
        if (recovered) return
        recovered = true
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val draft = read(dir.name) ?: return@forEach
            val recoveredDraft = if (draft.endedAt == null) {
                val interrupted = draft.copy(endedAt = System.currentTimeMillis(), status = "interrupted",
                    gaps = (draft.gaps + "进程退出，未确认的后续事件可能丢失；不会自动恢复录制").takeLast(100))
                if (runCatching { write(interrupted) }.isFailure) return@forEach
                interrupted
            } else {
                // A process may die after the final JSON commit but before marker deletion.
                File(dir, "active").delete()
                draft
            }
            cleanUnreferencedFrames(dir, recoveredDraft)
        }
    }

    private fun cleanUnreferencedFrames(dir: File, draft: DemoOperationDraft) {
        val referenced = draft.frames.map { it.fileName }.toSet()
        val ownFrameName = Regex("frame-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg(?:\\.tmp)?")
        dir.listFiles()?.filter { it.isFile && it.name !in referenced && ownFrameName.matches(it.name) }
            ?.forEach { it.delete() }
    }

    @Synchronized fun list(): List<DemoOperationDraft> {
        recover()
        return root.listFiles().orEmpty().filter { it.isDirectory }
            .mapNotNull { read(it.name) }.sortedByDescending { it.startedAt }
    }

    @Synchronized fun delete(id: String): Result<Unit> = runCatching {
        val dir = directory(id)
        check(!File(dir, "active").exists()) { "活动或未恢复草稿不能删除" }
        check(dir.exists()) { "草稿不存在" }
        check(dir.deleteRecursively()) { "部分素材删除失败" }
    }

    @Synchronized fun read(id: String): DemoOperationDraft? {
        recover()
        return runCatching {
        val file = File(directory(id), "draft.json")
        check(file.length() <= DemoOperationLimits.MAX_JSON_BYTES)
        decode(json.parseToJsonElement(file.readText()).jsonObject)
        }.getOrNull()
    }

    @Synchronized fun saveFrame(id: String, name: String, bytes: ByteArray): File {
        val file = frameFile(id, name) ?: error("非法素材路径")
        check(!file.exists()) { "素材已存在" }
        atomicWrite(file, bytes)
        return file
    }

    fun frameFile(id: String, name: String): File? = runCatching {
        require(name.matches(Regex("frame-[0-9a-f-]+\\.jpg")))
        File(directory(id), name)
    }.getOrNull()

    private fun directory(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return File(root, id)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        java.nio.file.Files.move(temp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun encode(d: DemoOperationDraft) = buildJsonObject {
        put("schemaVersion", 2); put("id", d.id); put("title", d.title); put("startedAt", d.startedAt)
        d.endedAt?.let { put("endedAt", it) }; put("status", d.status)
        put("guided", d.guided)
        put("gaps", JsonArray(d.gaps.map(::JsonPrimitive)))
        put("steps", JsonArray(d.steps.map { step -> buildJsonObject {
            put("id", step.id); put("eventIds", JsonArray(step.eventIds.map(::JsonPrimitive)))
            step.preFrameId?.let { put("preFrameId", it) }; step.postFrameId?.let { put("postFrameId", it) }
            put("localSummary", step.localSummary); step.aiSummary?.let { put("aiSummary", it) }
            put("userCorrection", step.userCorrection); put("confirmed", step.confirmed); put("preparation", step.preparation); put("discarded", step.discarded)
        } }))
        put("events", JsonArray(d.events.map { e -> buildJsonObject {
            put("id", e.id); put("at", e.at); put("type", e.type); put("packageName", e.packageName)
            e.className?.let { put("className", it) }; e.viewId?.let { put("viewId", it) }
            e.label?.let { put("label", it) }; put("bounds", JsonArray(e.bounds.map(::JsonPrimitive)))
            e.preFrameId?.let { put("preFrameId", it) }; e.postFrameId?.let { put("postFrameId", it) }
            e.scrollDeltaX?.let { put("scrollDeltaX", it) }; e.scrollDeltaY?.let { put("scrollDeltaY", it) }
            e.scrollX?.let { put("scrollX", it) }; e.scrollY?.let { put("scrollY", it) }
            e.fromIndex?.let { put("fromIndex", it) }; e.toIndex?.let { put("toIndex", it) }
        } }))
        put("frames", JsonArray(d.frames.map { f -> buildJsonObject {
            put("id", f.id); put("at", f.at); put("fileName", f.fileName); put("packageName", f.packageName)
            put("width", f.width); put("height", f.height); put("bytes", f.bytes)
            put("treeTruncated", f.treeTruncated)
            put("nodes", JsonArray(f.nodes.map { n -> buildJsonObject {
                put("path", n.path); n.viewId?.let { put("viewId", it) }; n.className?.let { put("className", it) }
                n.text?.let { put("text", it) }; n.description?.let { put("description", it) }
                put("bounds", JsonArray(n.bounds.map(::JsonPrimitive)))
                put("clickable", n.clickable); put("scrollable", n.scrollable); put("checked", n.checked)
                n.checkable?.let { put("checkable", it) }
            } }))
        } }))
    }

    private fun decode(o: JsonObject): DemoOperationDraft {
        fun JsonObject.s(k: String) = get(k)?.jsonPrimitive?.contentOrNull
        fun JsonObject.n(k: String) = getValue(k).jsonPrimitive.long
        require(o.n("schemaVersion") in 1L..2L)
        return DemoOperationDraft(o.s("id")!!, o.s("title")!!, o.n("startedAt"),
            o["endedAt"]?.jsonPrimitive?.long, o.s("status")!!,
            o.getValue("events").jsonArray.map { item -> item.jsonObject.let { e ->
                DemoOperationEvent(e.n("id").toInt(), e.n("at"), e.n("type").toInt(), e.s("packageName")!!,
                    e.s("className"), e.s("viewId"), e.s("label"), e.getValue("bounds").jsonArray.map { it.jsonPrimitive.int },
                    e.s("preFrameId"), e.s("postFrameId"),
                    e["scrollDeltaX"]?.jsonPrimitive?.intOrNull, e["scrollDeltaY"]?.jsonPrimitive?.intOrNull,
                    e["scrollX"]?.jsonPrimitive?.intOrNull, e["scrollY"]?.jsonPrimitive?.intOrNull,
                    e["fromIndex"]?.jsonPrimitive?.intOrNull, e["toIndex"]?.jsonPrimitive?.intOrNull)
            } },
            o.getValue("frames").jsonArray.map { item -> item.jsonObject.let { f ->
                DemoOperationFrame(f.s("id")!!, f.n("at"), f.s("fileName")!!, f.s("packageName")!!,
                    f.n("width").toInt(), f.n("height").toInt(), f.n("bytes").toInt(),
                    f["nodes"]?.jsonArray?.map { node -> node.jsonObject.let { n ->
                        DemoOperationNode(n.s("path")!!, n.s("viewId"), n.s("className"), n.s("text"), n.s("description"),
                            n.getValue("bounds").jsonArray.map { it.jsonPrimitive.int },
                            n.getValue("clickable").jsonPrimitive.boolean, n.getValue("scrollable").jsonPrimitive.boolean,
                            n.getValue("checked").jsonPrimitive.boolean, n["checkable"]?.jsonPrimitive?.booleanOrNull)
                    } }.orEmpty(), f["treeTruncated"]?.jsonPrimitive?.booleanOrNull ?: false)
            } }, o.getValue("gaps").jsonArray.map { it.jsonPrimitive.content },
            o["steps"]?.jsonArray?.map { item -> item.jsonObject.let { step ->
                DemoOperationStep(step.n("id").toInt(), step["eventIds"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty(),
                    step.s("preFrameId"), step.s("postFrameId"), step.s("localSummary").orEmpty(), step.s("aiSummary"),
                    step.s("userCorrection").orEmpty(), step["confirmed"]?.jsonPrimitive?.booleanOrNull ?: false,
                    step["preparation"]?.jsonPrimitive?.booleanOrNull ?: false,
                    step["discarded"]?.jsonPrimitive?.booleanOrNull ?: false)
            } }.orEmpty(), o["guided"]?.jsonPrimitive?.booleanOrNull ?: false)
    }
}
