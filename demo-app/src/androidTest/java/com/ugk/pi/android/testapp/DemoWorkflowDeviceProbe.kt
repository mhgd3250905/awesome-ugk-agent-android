package com.ugk.pi.android.testapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URI
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit emulator acceptance entry point, excluded from ordinary connected tests.
 *
 * -e workflowProbe true -e action compile|run -e draftId <recorded Settings draft>
 * Optional: -e goal <goal> -e completion <criteria> -e trial true|false. API material is read only from the
 * target application's files/workflow-probe-api.properties and is never reported.
 * Does not obtain UiAutomation: that would interfere with the real accessibility service.
 */
@RunWith(AndroidJUnit4::class)
class DemoWorkflowDeviceProbe {
    @Test
    fun explicitDeviceAcceptance() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit workflowProbe argument is required", arguments.containsKey("workflowProbe"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = arguments.getString("action").orEmpty()
        val startedAt = SystemClock.elapsedRealtime()
        var draftId = arguments.getString("draftId")?.trim()?.takeIf { it.isNotBlank() }
        var failure: String? = null
        var originalPrefs: Map<String, Any?>? = null
        var temporarySecret: String? = null
        var ownWorkflowStarted = false
        var ownRecordingStarted = false
        var preferencesRestored = true
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        runBlocking {
            val process = withContext(Dispatchers.Main) { DemoProcessScope.get(context) }
            val controller = withContext(Dispatchers.Main) { process.workflowController }
            try {
                requireProbe(action in ACTIONS, "invalid_action: use open, record_start, record_finish, compile or run")
                when (action) {
                    "open" -> {
                        withContext(Dispatchers.Main) {
                            context.startActivity(Intent(context, DemoOperationLearningActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .apply { draftId?.let { putExtra(DemoOperationLearningActivity.EXTRA_DRAFT_ID, it) } })
                        }
                        delay(750)
                    }
                    "record_start" -> {
                        val title = arguments.getString("goal")?.trim()?.takeIf { it.isNotEmpty() } ?: "SettingsWorkflowProbe"
                        requireProbe(title.length <= 120, "record_title_too_long: maximum 120 characters")
                        awaitAccessibility()
                        val result = withContext(Dispatchers.Main) { process.operationRecorder.start(title) }
                        requireProbe(result.isSuccess, "record_start_rejected: check accessibility, overlay, lock and busy state")
                        ownRecordingStarted = true
                        draftId = withContext(Dispatchers.Main) { process.operationRecorder.snapshot().draftId }
                        // Keep the owning process alive while external ADB inputs exercise
                        // the real service. End with the product's floating stop control.
                        withTimeout(ACTION_TIMEOUT) {
                            while (withContext(Dispatchers.Main) { process.operationRecorder.snapshot().phase } != DemoOperationPhase.IDLE) delay(150)
                        }
                    }
                    "record_finish" -> {
                        val before = withContext(Dispatchers.Main) { process.operationRecorder.snapshot() }
                        requireProbe(before.phase != DemoOperationPhase.IDLE, "no_active_recording: finish the recording through its owning process")
                        draftId = before.draftId
                        withContext(Dispatchers.Main) { process.operationRecorder.finish() }
                        withTimeout(ACTION_TIMEOUT) {
                            while (withContext(Dispatchers.Main) { process.operationRecorder.snapshot().phase } != DemoOperationPhase.IDLE) delay(100)
                        }
                        val saved = withContext(Dispatchers.IO) { draftId?.let(process.operationRecorder::readDraft) }
                        requireProbe(saved?.endedAt != null, "record_finish_not_saved")
                    }
                    "compile", "run" -> {
                        val id = draftId ?: throw ProbeFailure("draft_id_required")
                        requireProbe(withContext(Dispatchers.Main) { controller.snapshot().phase } == DemoWorkflowPhase.IDLE,
                            "workflow_already_busy: probe will not stop unrelated work")
                        val draft = withContext(Dispatchers.IO) { process.operationRecorder.readDraft(id) }
                            ?: throw ProbeFailure("draft_not_found")
                        requireProbe(draft.endedAt != null && draft.events.isNotEmpty(), "draft_must_be_finished_and_contain_events")
                        requireProbe(draft.events.all { it.packageName == SETTINGS_PACKAGE } &&
                            draft.frames.all { it.packageName == SETTINGS_PACKAGE }, "settings_only: other application recordings are refused")
                        val oldPlan = controller.load(id)
                        val trial = when (arguments.getString("trial")?.lowercase() ?: "true") {
                            "true" -> true
                            "false" -> false
                            else -> throw ProbeFailure("invalid_trial: use true or false")
                        }
                        if (action == "run") {
                            requireProbe(oldPlan != null, "compiled_plan_required")
                            requireSettingsPlan(oldPlan!!)
                            awaitAccessibility()
                        }
                        val config = readPrivateConfig(context)
                        temporarySecret = config.apiKey
                        // Snapshot before Store.load can seed debug defaults. Copy StringSets by value.
                        originalPrefs = snapshotPreferences(prefs)
                        preferencesRestored = false
                        withContext(Dispatchers.Main) { ApiProviderSettingsStore(context).upsertAndActivate(config) }
                        val oldRunIds = if (action == "run") controller.records(id).map { it.id }.toSet() else emptySet()
                        val goal = arguments.getString("goal")?.trim()?.takeIf { it.isNotEmpty() } ?: draft.title
                        val completion = arguments.getString("completion")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: "The battery usage page visibly contains the battery level chart and the screen time section. Verify these with a visual checkpoint."
                        withTimeout(ACTION_TIMEOUT) {
                            val result = withContext(Dispatchers.Main) {
                                if (action == "compile") controller.compile(id, goal, completion)
                                else controller.start(oldPlan!!, trial)
                            }
                            requireProbe(result.isSuccess, "${action}_start_rejected: check API configuration, draft and device readiness")
                            ownWorkflowStarted = true
                            awaitIdle(controller)
                            if (action == "compile") {
                                val compiled = controller.load(id)
                                requireProbe(compiled != null && compiled.version > (oldPlan?.version ?: -1),
                                    "compile_failed: inspect the sanitized state message in workflow-probe-result.json")
                                requireSettingsPlan(compiled!!)
                            } else {
                                val newRecords = controller.records(id).filter { it.id !in oldRunIds }
                                requireProbe(newRecords.size == 1, "run_record_missing_or_ambiguous")
                                val record = newRecords.single()
                                requireProbe(record.version == oldPlan!!.version && record.planDigest == oldPlan.digest() && record.isTrial == trial,
                                    "run_record_does_not_match_confirmed_version")
                                requireProbe(record.status == "succeeded", "run_${record.status}: inspect workflow-probe-result.json")
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                // No original network message or cause escapes instrumentation output.
                failure = if (error is ProbeFailure) error.safeMessage else "${action}_failed_${error.javaClass.simpleName}"
            } finally {
                withContext(NonCancellable) {
                    if (ownRecordingStarted) {
                        withContext(Dispatchers.Main) {
                            if (process.operationRecorder.snapshot().phase != DemoOperationPhase.IDLE) process.operationRecorder.finish()
                        }
                        withTimeout(CLEANUP_TIMEOUT) {
                            while (withContext(Dispatchers.Main) { process.operationRecorder.snapshot().phase } != DemoOperationPhase.IDLE) delay(100)
                        }
                    }
                    if (ownWorkflowStarted) {
                        try {
                            withContext(Dispatchers.Main) { if (controller.isBusy()) controller.stop("设备验收已停止") }
                            withTimeout(CLEANUP_TIMEOUT) { awaitIdle(controller) }
                        } catch (_: Throwable) {
                            failure = "cleanup_failed: workflow did not become idle after stop"
                        }
                    }
                    originalPrefs?.let { original ->
                        preferencesRestored = restorePreferences(prefs, original)
                        if (!preferencesRestored) failure = "cleanup_failed: API preferences were not restored"
                    }
                    try {
                        writeReceipt(context, process, action, draftId, startedAt, failure, preferencesRestored, temporarySecret)
                    } catch (_: Throwable) {
                        failure = "receipt_write_failed: check private application storage"
                    }
                }
            }
        }
        failure?.let { throw AssertionError(it) }
    }

    private suspend fun awaitIdle(controller: DemoWorkflowController) {
        while (withContext(Dispatchers.Main) { controller.snapshot().phase } != DemoWorkflowPhase.IDLE) delay(150)
    }

    private suspend fun awaitAccessibility() = withTimeout(30_000) {
        while (withContext(Dispatchers.Main) { AgentAccessibilityService.instance == null }) delay(150)
    }

    private fun requireSettingsPlan(plan: DemoWorkflowPlan) {
        requireProbe(plan.steps.isNotEmpty() && plan.steps.all {
            it.packageName == SETTINGS_PACKAGE && it.postcondition.packageName == SETTINGS_PACKAGE
        }, "settings_only: compiled operation contains an unapproved target application")
    }

    private fun readPrivateConfig(context: Context): ApiProviderConfig {
        val file = File(context.filesDir, "workflow-probe-api.properties")
        requireProbe(file.canonicalFile.parentFile == context.filesDir.canonicalFile && file.isFile && file.length() in 1L..65_536L,
            "private_api_config_missing_or_invalid")
        val values = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
        fun value(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            values.getProperty(key)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        val apiKey = value("api_key", "apiKey")
        val model = value("model")
        val base = value("base_url", "baseUrl").trimEnd('/')
        val endpoint = value("endpoint")
        val address = when {
            endpoint.startsWith("https://") || endpoint.startsWith("http://") -> endpoint
            endpoint.isNotBlank() && base.isNotBlank() -> "$base/${endpoint.trimStart('/')}"
            base.endsWith("/chat/completions", ignoreCase = true) -> base
            Regex("/v[0-9]+$", RegexOption.IGNORE_CASE).containsMatchIn(base) -> "$base/chat/completions"
            else -> base
        }
        val uri = runCatching { URI(address) }.getOrNull()
        requireProbe(apiKey.isNotBlank() && model.isNotBlank(), "private_api_config_requires_api_key_and_model")
        requireProbe(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null,
            "private_api_config_requires_valid_base_url_or_endpoint")
        return ApiProviderConfig(id = "workflow-probe-${UUID.randomUUID()}", baseUrl = address, apiKey = apiKey,
            model = model, name = "Workflow device acceptance", protocol = ProviderProtocol.AUTO)
    }

    private fun snapshotPreferences(prefs: SharedPreferences): Map<String, Any?> = prefs.all.mapValues { (_, value) ->
        if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
    }

    private fun restorePreferences(prefs: SharedPreferences, values: Map<String, Any?>): Boolean = runCatching {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> editor.remove(key)
                else -> throw ProbeFailure("unsupported_preference_type")
            }
        }
        editor.commit() && snapshotPreferences(prefs) == values
    }.getOrDefault(false)

    private suspend fun writeReceipt(context: Context, process: DemoProcessScope, action: String, draftId: String?,
                                     startedAt: Long, failure: String?, preferencesRestored: Boolean, secret: String?) {
        val state = withContext(Dispatchers.Main) { process.workflowController.snapshot() }
        val recording = withContext(Dispatchers.Main) { process.operationRecorder.snapshot() }
        val plan = draftId?.let { runCatching { process.workflowController.load(it) }.getOrNull() }
        val records = draftId?.let { runCatching { process.workflowController.records(it) }.getOrNull() }.orEmpty()
        fun safe(value: String): String = if (secret.isNullOrBlank()) value else value.replace(secret, "[redacted]")
        val receipt = buildJsonObject {
            put("action", action); put("ok", failure == null)
            draftId?.let { put("draftId", it) }
            failure?.let { put("error", safe(it)) }
            put("elapsedMillis", SystemClock.elapsedRealtime() - startedAt)
            put("preferencesRestored", preferencesRestored)
            put("state", buildJsonObject {
                put("phase", state.phase.name); put("message", safe(state.message)); put("revision", state.revision)
                put("completedSteps", state.completedSteps); put("totalSteps", state.totalSteps)
                put("modelCalls", state.modelCalls); put("imagesSent", state.imagesSent)
            })
            put("recording", buildJsonObject {
                put("phase", recording.phase.name); recording.draftId?.let { put("draftId", it) }
                put("events", recording.eventCount); put("frames", recording.frameCount)
                put("elapsedMillis", recording.elapsedMillis); recording.message?.let { put("message", safe(it)) }
            })
            plan?.let { current -> put("plan", buildJsonObject {
                put("version", current.version); put("title", safe(current.title)); put("goal", safe(current.goal))
                put("completionCriteria", safe(current.completionCriteria))
                put("modelCalls", current.modelCalls); put("imagesSent", current.imagesSent)
                put("steps", buildJsonArray { current.steps.forEach { step -> add(buildJsonObject {
                    put("id", step.id); put("title", safe(step.title)); put("action", step.action)
                    put("packageName", step.packageName); put("postconditionPackage", step.postcondition.packageName)
                    put("completion", safe(DemoWorkflowUi.conditionText(step.postcondition)))
                    put("sourceEventIds", JsonArray(step.sourceEventIds.map(::JsonPrimitive)))
                }) } })
            }) }
            put("records", buildJsonArray { records.forEach { record -> add(buildJsonObject {
                put("id", record.id); put("version", record.version); put("trial", record.isTrial); put("status", record.status)
                put("completedSteps", record.completedSteps); put("totalSteps", record.totalSteps)
                put("modelCalls", record.modelCalls); put("imagesSent", record.imagesSent); put("message", safe(record.message))
                put("startedAt", record.startedAt)
                record.endedAt?.let { put("endedAt", it); put("elapsedMillis", (it - record.startedAt).coerceAtLeast(0)) }
            }) } })
        }
        withContext(Dispatchers.IO) { File(context.filesDir, "workflow-probe-result.json").writeText(receipt.toString(), Charsets.UTF_8) }
    }

    private fun requireProbe(condition: Boolean, message: String) { if (!condition) throw ProbeFailure(message) }
    private class ProbeFailure(val safeMessage: String) : RuntimeException(safeMessage)

    companion object {
        private const val PREFERENCES = "api_provider_settings"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val ACTION_TIMEOUT = 330_000L
        private const val CLEANUP_TIMEOUT = 30_000L
        private val ACTIONS = setOf("open", "record_start", "record_finish", "compile", "run")
    }
}
