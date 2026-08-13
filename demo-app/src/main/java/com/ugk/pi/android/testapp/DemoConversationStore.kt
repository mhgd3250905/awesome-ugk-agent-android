package com.ugk.pi.android.testapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DemoStoredMessage(
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

enum class DemoMessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class DemoConversation(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<DemoStoredMessage> = mutableListOf()
)

/**
 * Small, local-only conversation store for the demo app.
 *
 * The store deliberately keeps a bounded transcript and treats malformed
 * preferences as recoverable data, not as a reason to crash the host app.
 */
class DemoConversationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(): List<DemoConversation> = readAll()
        .sortedWith(compareByDescending<DemoConversation> { it.updatedAt }.thenBy { it.createdAt })

    fun loadAll(): List<DemoConversation> = list()

    fun get(id: String?): DemoConversation? {
        if (id.isNullOrBlank()) return null
        return readAll().firstOrNull { it.id == id }
    }

    fun activeId(): String? = prefs.getString(KEY_ACTIVE_ID, null)

    fun activeConversationId(): String? = activeId()

    fun setActive(id: String) {
        if (get(id) != null) prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun selectActiveConversation(id: String): DemoConversation? = get(id)?.also { setActive(id) }

    fun ensureActive(): DemoConversation {
        val active = get(activeId())
        if (active != null) return active
        return list().firstOrNull() ?: create()
    }

    fun getActiveConversation(): DemoConversation = ensureActive()

    fun create(title: String = DEFAULT_TITLE): DemoConversation {
        val now = System.currentTimeMillis()
        val conversation = DemoConversation(
            id = UUID.randomUUID().toString(),
            title = normalizeTitle(title),
            createdAt = now,
            updatedAt = now
        )
        val all = (readAll() + conversation).takeLast(MAX_CONVERSATIONS)
        writeAll(all)
        setActive(conversation.id)
        return conversation
    }

    fun save(conversation: DemoConversation) {
        val normalized = normalize(conversation)
        val all = readAll().filterNot { it.id == normalized.id } + normalized
        writeAll(all.sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS))
        setActive(normalized.id)
    }

    fun update(conversation: DemoConversation) = save(conversation)

    fun rename(id: String, title: String): DemoConversation? {
        val conversation = get(id) ?: return null
        conversation.title = normalizeTitle(title)
        conversation.updatedAt = System.currentTimeMillis()
        save(conversation)
        return conversation
    }

    fun delete(id: String): DemoConversation? {
        val remaining = readAll().filterNot { it.id == id }
        if (remaining.size == readAll().size) return null
        writeAll(remaining)
        if (activeId() == id) {
            val replacement = remaining.maxByOrNull { it.updatedAt }
            if (replacement == null) {
                val created = create()
                return created
            }
            setActive(replacement.id)
            return replacement
        }
        return remaining.maxByOrNull { it.updatedAt }
    }

    fun suggestedTitle(text: String): String {
        val compact = text.trim().replace(Regex("\\s+"), " ")
        return normalizeTitle(compact.ifBlank { DEFAULT_TITLE })
    }

    private fun readAll(): List<DemoConversation> {
        val raw = prefs.getString(KEY_CONVERSATIONS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val root = JSONArray(raw)
            buildList {
                for (index in 0 until root.length()) {
                    val item = root.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue
                    val messages = mutableListOf<DemoStoredMessage>()
                    val encodedMessages = item.optJSONArray("messages") ?: JSONArray()
                    for (messageIndex in 0 until encodedMessages.length()) {
                        val encoded = encodedMessages.optJSONObject(messageIndex) ?: continue
                        val role = encoded.optString("role").trim()
                        val content = encoded.optString("content")
                        if (role !in ALLOWED_ROLES || content.isBlank()) continue
                        messages += DemoStoredMessage(
                            role = role,
                            content = content.take(MAX_MESSAGE_CHARS),
                            createdAt = encoded.optLong("createdAt", System.currentTimeMillis())
                        )
                    }
                    add(
                        normalize(
                            DemoConversation(
                                id = id,
                                title = item.optString("title", DEFAULT_TITLE),
                                createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                                updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                                messages = messages
                            )
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeAll(conversations: List<DemoConversation>) {
        val root = JSONArray()
        conversations.take(MAX_CONVERSATIONS).forEach { conversation ->
            val item = JSONObject()
                .put("id", conversation.id)
                .put("title", normalizeTitle(conversation.title))
                .put("createdAt", conversation.createdAt)
                .put("updatedAt", conversation.updatedAt)
            val messages = JSONArray()
            conversation.messages.takeLast(MAX_MESSAGES).forEach { message ->
                messages.put(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", message.content.take(MAX_MESSAGE_CHARS))
                        .put("createdAt", message.createdAt)
                )
            }
            item.put("messages", messages)
            root.put(item)
        }
        prefs.edit().putString(KEY_CONVERSATIONS, root.toString()).apply()
    }

    private fun normalize(conversation: DemoConversation): DemoConversation {
        val messages = conversation.messages
            .filter { it.role in ALLOWED_ROLES && it.content.isNotBlank() }
            .takeLast(MAX_MESSAGES)
            .map { it.copy(content = it.content.take(MAX_MESSAGE_CHARS)) }
            .toMutableList()
        return conversation.copy(
            title = normalizeTitle(conversation.title),
            messages = messages
        )
    }

    private fun normalizeTitle(value: String): String = value.trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_TITLE_CHARS)
        .ifBlank { DEFAULT_TITLE }

    private companion object {
        const val PREFS_NAME = "demo_conversations"
        const val KEY_CONVERSATIONS = "conversations"
        const val KEY_ACTIVE_ID = "active_id"
        const val DEFAULT_TITLE = "新对话"
        const val MAX_CONVERSATIONS = 30
        const val MAX_MESSAGES = 100
        // Keep ordinary long answers intact across Activity recreation while
        // still protecting SharedPreferences from an accidental giant dump.
        const val MAX_MESSAGE_CHARS = 64_000
        const val MAX_TITLE_CHARS = 48
        val ALLOWED_ROLES = setOf("user", "assistant", "system")
    }
}
