package com.ugk.pi.android.testapp

import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plan that carries `"error": null` is a plan, not missing evidence.
 *
 * The workflow compiler gate checked the presence of the key, so the model answer
 * `{"error":null,...}` - which is what a gateway that serializes every field of its
 * response struct produces - was refused with "演示缺少完成目标所需的证据", a
 * message that sends the user back to re-record the demo when the real problem was
 * the shape of the check. The second case is the control: an error the model
 * actually states still refuses, so this is not a blanket放行.
 */
class DemoWorkflowCompilerErrorFieldTest {

    private val id = UUID.randomUUID().toString()
    private val node = DemoOperationNode("0", "com.android.settings:id/title", "TextView", "设置", null, emptyList(), true, false, false)
    private val frame = DemoOperationFrame("f1", 5, "f.jpg", "com.android.settings", 10, 10, 1, listOf(node))
    private val event = DemoOperationEvent(1, 5, 1, "com.android.settings", "TextView", node.viewId, "设置", emptyList(), "f1", "f1")
    private val draft = DemoOperationDraft(id, "设置", 1, 6, "saved", listOf(event), listOf(frame))

    @Test
    fun aNullErrorFieldInModelOutputDoesNotRefuseThePlanAsMissingEvidence() = runBlocking {
        val failure = compileReturning("""{"error":null}""")
        assertFalse(
            "an absent error must not be read as a report, got: $failure",
            failure.contains("缺少完成目标所需的证据")
        )
        assertTrue("the run still had to refuse something: $failure", failure.isNotEmpty())
    }

    @Test
    fun anErrorTheModelActuallyStatesStillRefusesThePlan() = runBlocking {
        val failure = compileReturning("""{"error":"缺少完成画面"}""")
        assertTrue("expected the evidence refusal, got: $failure", failure.contains("缺少完成目标所需的证据"))
    }

    private suspend fun compileReturning(modelAnswer: String): String {
        val compiler = DemoWorkflowCompiler(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse = ModelResponse(modelAnswer)
        })
        val failure = runCatching { compiler.compile(draft, "设置") { byteArrayOf(1) } }.exceptionOrNull()
        return failure?.message.orEmpty()
    }
}
