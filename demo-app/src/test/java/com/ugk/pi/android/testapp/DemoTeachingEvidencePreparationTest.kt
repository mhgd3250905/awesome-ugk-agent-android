package com.ugk.pi.android.testapp

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DemoTeachingEvidencePreparationTest {
    @Test fun longInstructionsRepliesAndFailureEvidenceKeepTheirMiddleAndEnd() {
        val instruction = "前文".repeat(6000) + "纠正：只看秒表，不要启动" + "后文".repeat(6000)
        val reply = "反馈".repeat(4000) + "中途仍没有看到秒表" + "结束".repeat(4000)
        val failure = "失败详情".repeat(3000) + "实际原因：目标按钮不可用"
        val gap = "证据说明".repeat(1000) + "最后没有验证成功"
        val action = action("screen_visual_gesture", failure, isError = true).copy(gaps = listOf(gap))
        val source = record(listOf(action), instruction, reply)
        val originalEncoded = DemoTeachingStore.encode(source).toString()

        val evidence = DemoTeachingEvidencePreparation.prepare(source)
        val header = evidence.parts[0].json()
        val preparedAction = evidence.parts[1].json()
        assertEquals(instruction, header.getValue("instruction").jsonPrimitive.content)
        assertEquals(reply, header.getValue("reply").jsonPrimitive.content)
        assertEquals(failure, preparedAction.getValue("result").jsonPrimitive.content)
        assertEquals(gap, preparedAction.getValue("gaps").jsonArray.single().jsonPrimitive.content)
        assertTrue(preparedAction.getValue("isError").jsonPrimitive.boolean)
        assertTrue(preparedAction.getValue("possibleIncompleteResult").jsonPrimitive.boolean)
        assertEquals(originalEncoded, DemoTeachingStore.encode(source).toString())
    }

    @Test fun unknownAndMalformedResultsAreKeptAsExactText() {
        val unknown = """ { "success": true, "gestureId": "do-not-strip", "payload": [1,2,3], "text": "中部含完成证据" } """
        val truncatedTree = "{\"snapshotId\":\"s\",\"package\":\"clock\",\"elements\":[{\"text\":\"成功" + "x".repeat(12_000)
        val evidence = DemoTeachingEvidencePreparation.prepare(record(listOf(
            action("custom_inspection", unknown),
            action("screen_read_ui_tree", truncatedTree)
        )))
        assertEquals(unknown, evidence.parts[1].json().getValue("result").jsonPrimitive.content)
        assertEquals(truncatedTree, evidence.parts[2].json().getValue("result").jsonPrimitive.content)
        assertTrue(evidence.parts[2].json().getValue("possibleIncompleteResult").jsonPrimitive.boolean)
        assertFalse(evidence.parts[2].json().containsKey("resultRef"))
    }

    @Test fun treeCompactionPreservesAllLabelsStatesHierarchyAndUnknownFields() {
        val elements = JsonArray((0 until 100).map { index -> buildJsonObject {
            put("nodeId", "0.1.$index")
            put("package", if (index == 99) "other.window" else "clock")
            put("windowIndex", if (index == 99) 1 else 0)
            put("type", "android.widget.CheckBox")
            put("text", if (index == 50) "更新已完成" else "选项$index")
            put("contentDesc", "说明$index")
            put("hint", "输入提示$index")
            put("checked", index % 2 == 0)
            put("enabled", index != 98)
            put("bounds", JsonArray(listOf(1, 2, 3, 4).map(::JsonPrimitive)))
            put("actions", JsonArray(listOf(buildJsonObject { put("id", 16); put("name", "click") })))
            putJsonObject("futureField") { put("value", "保留$index") }
        } })
        val raw = buildJsonObject {
            put("snapshotId", "latest-snapshot")
            put("package", "clock")
            put("truncated", true)
            put("elements", elements)
            putJsonObject("futureTopField") { put("unverified", true) }
        }
        val source = record(listOf(action("screen_read_ui_tree", raw.toString())))
        val evidence = DemoTeachingEvidencePreparation.prepare(source)
        val preparedAction = evidence.parts[1].json()
        val result = preparedAction.getValue("result").jsonObject
        val restored = result.getValue("elements").jsonArray.map { element ->
            val node = element.jsonObject
            if ("package" in node) node else JsonObject(node + ("package" to preparedAction.getValue("elementPackageDefault")))
        }
        assertTrue(evidence.denoised)
        assertEquals(elements, JsonArray(restored))
        assertEquals(raw.getValue("futureTopField"), result.getValue("futureTopField"))
        assertEquals(raw.getValue("truncated"), result.getValue("truncated"))
        assertEquals(raw.toString(), source.segments.single().actions.single().result)
    }

    @Test fun consecutiveIdenticalObservationsKeepTheirSlotsAndSelfContainedResults() {
        val raw = tree("停止", checked = false)
        val read = action("screen_read_ui_tree", raw)
        val mutation = action("screen_press_key", """{"success":true,"code":"OK","action":"enter"}""")
        val source = record(listOf(read, read.copy(id = "read-two"), mutation, read.copy(id = "read-three")))
            .let { it.copy(segments = it.segments + DemoTeachingSegment("second", "再确认一次", actions = listOf(read))) }
        val parts = DemoTeachingEvidencePreparation.prepare(source).parts
        assertEquals(listOf(null, 1, 2, 3, 4, null, 1), parts.map { it.actionNumber })
        assertTrue(parts.filter { it.actionNumber != null }.all { it.json().containsKey("result") })
        assertEquals(parts[1].json().getValue("result"), parts[2].json().getValue("result"))
        assertEquals(parts[1].json().getValue("result"), parts[4].json().getValue("result"))
        assertEquals(parts[1].json().getValue("result"), parts[6].json().getValue("result"))
        assertEquals("screen_press_key", parts[3].json().getValue("name").jsonPrimitive.content)
    }

    @Test fun changesInReadingsAndToggleStateAreNotDeduplicated() {
        val parts = DemoTeachingEvidencePreparation.prepare(record(listOf(
            action("screen_read_ui_tree", tree("00:01", checked = false)),
            action("screen_read_ui_tree", tree("00:02", checked = false)),
            action("screen_read_ui_tree", tree("00:02", checked = true))
        ))).parts.drop(1)
        assertTrue(parts.none { "resultRef" in it.json() })
        assertEquals(listOf("00:01", "00:02", "00:02"), parts.map {
            it.json().getValue("result").jsonObject.getValue("elements").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        })
        assertEquals(listOf(false, false, true), parts.map {
            it.json().getValue("result").jsonObject.getValue("elements").jsonArray.single().jsonObject.getValue("checked").jsonPrimitive.boolean
        })
    }

    @Test fun successfulDispatchDoesNotLoseUnverifiedOutcomeOrImageReferences() {
        val raw = """{"success":true,"code":"OK","gestureId":"runtime-id","action":"tap","dispatched":"true","effectVerified":"false","screenChange":"unchanged","message":"手势已派发，不能证明任务成功","targetDescription":"保存","futureEvidence":{"reason":"keep"}}"""
        val source = record(listOf(action("screen_visual_gesture", raw).copy(
            beforeImage = "before.jpg", afterImage = "after.jpg", gaps = listOf("操作后截图不完整")
        )))
        val part = DemoTeachingEvidencePreparation.prepare(source).parts[1]
        val result = part.json().getValue("result").jsonObject
        assertFalse("gestureId" in result)
        assertEquals(Json.parseToJsonElement(raw).jsonObject.filterKeys { it != "gestureId" }, result)
        assertEquals(listOf("before.jpg", "after.jpg"), part.imageNames)
        assertEquals("before.jpg", part.json().getValue("beforeImage").jsonPrimitive.content)
        assertEquals("after.jpg", part.json().getValue("afterImage").jsonPrimitive.content)
        assertEquals("操作后截图不完整", part.json().getValue("gaps").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun unknownToolsAndFailuresBetweenObservationsStayVerbatimAndOrdered() {
        val read = action("screen_read_ui_tree", tree("已保存", checked = true))
        val failure = """{ "success": false, "code": "STALE_SNAPSHOT", "message": "页面已变化", "recovery": "重新读取", "futureField": 12 }"""
        val parts = DemoTeachingEvidencePreparation.prepare(record(listOf(
            read,
            action("custom_tool", "完成"),
            read,
            action("screen_perform_action", failure, isError = true)
        ))).parts
        assertFalse("resultRef" in parts[3].json())
        assertEquals("完成", parts[2].json().getValue("result").jsonPrimitive.content)
        assertEquals(failure, parts[4].json().getValue("result").jsonPrimitive.content)
    }

    @Test fun unknownTreeNodesAndLongInputArraysArePreservedWithoutInventingDefaults() {
        val raw = """{"snapshotId":"s","package":"clock","truncated":false,"elements":[{"package":"clock","text":"已完成"},{"futureNode":"originally has no package"},null]}"""
        val input = buildJsonObject {
            put("items", JsonArray((0 until 150).map { JsonPrimitive("输入$it") }))
            put("text", "文字".repeat(3000) + "中部纠正" + "尾文".repeat(3000))
        }
        val source = record(listOf(action("screen_read_ui_tree", raw).copy(input = input)))
        val prepared = DemoTeachingEvidencePreparation.prepare(source).parts[1].json()
        assertEquals(input, prepared.getValue("input"))
        assertEquals(Json.parseToJsonElement(raw), prepared.getValue("result"))
        assertFalse("elementPackageDefault" in prepared)
    }

    private fun tree(text: String, checked: Boolean): String = buildJsonObject {
        put("snapshotId", "same-snapshot")
        put("package", "clock")
        put("truncated", false)
        putJsonArray("elements") { add(buildJsonObject {
            put("nodeId", "0.1")
            put("package", "clock")
            put("text", text)
            put("checked", checked)
        }) }
    }.toString()

    private fun action(name: String, result: String, isError: Boolean = false) = DemoTeachingAction(
        id = "saved-action-id", name = name, input = buildJsonObject { put("selector", "原始输入") }, result = result, isError = isError
    )

    private fun record(actions: List<DemoTeachingAction>, instruction: String = "打开秒表", reply: String = "已显示") = DemoTeachingRecord(
        id = "saved-record-id", title = "查看秒表", createdAt = 10, updatedAt = 20, status = "finished",
        segments = listOf(DemoTeachingSegment("saved-segment-id", instruction, "completed", reply, actions))
    )

    private fun TeachingEvidencePart.json(): JsonObject = Json.parseToJsonElement(content).jsonObject
}
