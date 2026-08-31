package com.ugk.pi.android.testapp

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.UUID

data class DemoStoredMessage(
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val imagePaths: List<String> = emptyList()
) {
    val imagePath: String?
        get() = imagePaths.firstOrNull()

    constructor(
        role: String,
        content: String,
        createdAt: Long = System.currentTimeMillis(),
        imagePath: String?
    ) : this(
        role = role,
        content = content,
        createdAt = createdAt,
        imagePaths = if (imagePath.isNullOrBlank()) emptyList() else listOf(imagePath)
    )
}

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

internal fun keepNewestDemoConversations(
    conversations: List<DemoConversation>,
    limit: Int
): List<DemoConversation> {
    require(limit > 0) { "limit must be greater than zero" }
    return conversations.sortedWith(
        compareByDescending<DemoConversation> { it.updatedAt }.thenBy { it.createdAt }
    ).take(limit)
}

// Bounded-transcript limits shared by the store's save/append paths. Kept at
// module level (like keepNewestDemoConversations above) so the pure merge
// helpers are unit-testable against the exact production semantics.
internal const val MAX_CONVERSATIONS = 30
internal const val MAX_MESSAGES = 100
internal const val MAX_STORED_IMAGES_PER_MESSAGE = 4
// Keep ordinary long answers intact across Activity recreation while still
// protecting SharedPreferences from an accidental giant dump.
internal const val MAX_MESSAGE_CHARS = 64_000
internal const val MAX_TITLE_CHARS = 48
internal const val DEFAULT_TITLE = "新对话"
internal val ALLOWED_ROLES = setOf("user", "assistant", "system")

internal fun normalizeStoredTitle(value: String): String = value.trim()
    .replace(Regex("\\s+"), " ")
    .take(MAX_TITLE_CHARS)
    .ifBlank { DEFAULT_TITLE }

internal fun normalizeStoredMessage(message: DemoStoredMessage): DemoStoredMessage {
    val cleanPaths = message.imagePaths
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(MAX_STORED_IMAGES_PER_MESSAGE)
    return message.copy(
        role = message.role.trim(),
        content = message.content.take(MAX_MESSAGE_CHARS),
        imagePaths = cleanPaths
    )
}

internal fun normalizeStoredConversation(conversation: DemoConversation): DemoConversation {
    val messages = conversation.messages
        .filter { it.role in ALLOWED_ROLES && (it.content.isNotBlank() || it.imagePaths.any { path -> path.isNotBlank() }) }
        .takeLast(MAX_MESSAGES)
        .map { normalizeStoredMessage(it) }
        .toMutableList()
    return conversation.copy(
        title = normalizeStoredTitle(conversation.title),
        messages = messages
    )
}

/**
 * Pure append-merge shared by DemoConversationStore.appendMessages.
 *
 * Append semantics must not be built on save()'s whole-conversation
 * replacement: a foreground Activity holding a stale in-memory snapshot
 * would otherwise erase messages a background scheduled run appended in the
 * meantime. Normalization mirrors the save() path, so an append is truncated
 * to MAX_MESSAGES exactly like a save would be.
 */
internal fun appendStoredMessages(
    existing: DemoConversation,
    incoming: List<DemoStoredMessage>,
    now: Long,
    titleUpdate: String? = null
): DemoConversation = normalizeStoredConversation(
    existing.copy(
        title = titleUpdate?.let(::normalizeStoredTitle) ?: existing.title,
        updatedAt = now,
        messages = (existing.messages + incoming).toMutableList()
    )
)

/**
 * Small, local-only conversation store for the demo app.
 *
 * The store deliberately keeps a bounded transcript and treats malformed
 * preferences as recoverable data, not as a reason to crash the host app.
 */
class DemoConversationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheLock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "demo-conversation-store").apply { isDaemon = true }
    }
    private val writeLock = Any()
    private var pendingWrite: List<DemoConversation>? = null
    private var writeDrainScheduled = false
    private var cachedConversations: List<DemoConversation>? = null

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
            title = normalizeStoredTitle(title),
            createdAt = now,
            updatedAt = now
        )
        // Keep the same newest-first ordering used by save(); otherwise the
        // 31st conversation can evict a recent conversation via takeLast().
        val all = keepNewestDemoConversations(readAll() + conversation, MAX_CONVERSATIONS)
        writeAll(all)
        setActive(conversation.id)
        return conversation
    }

    @Synchronized
    fun save(conversation: DemoConversation) {
        val normalized = normalizeStoredConversation(conversation)
        // Keep the caller's in-memory object bounded as well as the JSON
        // representation. Otherwise a long-lived Activity can keep every
        // tool/result message even though persistence is capped.
        conversation.title = normalized.title
        conversation.messages.clear()
        conversation.messages.addAll(normalized.messages)
        val all = readAll().filterNot { it.id == normalized.id } + normalized
        writeAll(keepNewestDemoConversations(all, MAX_CONVERSATIONS))
        setActive(normalized.id)
    }

    /**
     * Atomically appends messages to a stored conversation instead of
     * replacing it wholesale like save(). A foreground Activity can hold a
     * stale snapshot while a background scheduled run appends its result;
     * only an append-merge keeps both turns alive. Returns the updated
     * conversation, or null when the conversation no longer exists — a
     * background append must not resurrect a conversation the user deleted
     * while the run was in flight.
     */
    @Synchronized
    fun appendMessages(
        conversationId: String,
        messages: List<DemoStoredMessage>,
        titleUpdate: String? = null
    ): DemoConversation? {
        val current = readAll()
        val existing = current.firstOrNull { it.id == conversationId } ?: return null
        val updated = appendStoredMessages(
            existing = existing,
            incoming = messages,
            now = System.currentTimeMillis(),
            titleUpdate = titleUpdate
        )
        val all = current.filterNot { it.id == updated.id } + updated
        writeAll(keepNewestDemoConversations(all, MAX_CONVERSATIONS))
        setActive(updated.id)
        return updated
    }

    /**
     * Flushed append for background executors. Mirrors saveAndFlush: waits
     * behind the same single writer and commits synchronously to disk so a
     * process kill cannot discard a scheduled result merely because the write
     * was still queued behind an asynchronous apply().
     */
    @Synchronized
    fun appendMessagesAndFlush(
        conversationId: String,
        messages: List<DemoStoredMessage>,
        titleUpdate: String? = null
    ): DemoConversation? {
        val updated = appendMessages(conversationId, messages, titleUpdate) ?: return null
        try {
            writeExecutor.submit { drainPendingWrites(syncToDisk = true) }.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
        return updated
    }

    /**
     * Persists a background-run result before its JobService is allowed to
     * finish. The ordinary UI path remains asynchronous; this method waits
     * behind the same single writer and commits synchronously to disk so a
     * process kill cannot discard the last scheduled result merely because
     * the write was queued behind an asynchronous apply().
     */
    @Synchronized
    fun saveAndFlush(conversation: DemoConversation) {
        save(conversation)
        try {
            writeExecutor.submit { drainPendingWrites(syncToDisk = true) }.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    fun update(conversation: DemoConversation) = save(conversation)

    fun rename(id: String, title: String): DemoConversation? {
        val conversation = get(id) ?: return null
        conversation.title = normalizeStoredTitle(title)
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
        return normalizeStoredTitle(compact.ifBlank { DEFAULT_TITLE })
    }

    private fun readAll(): List<DemoConversation> {
        synchronized(cacheLock) {
            val cached = cachedConversations
            if (cached != null) return cached.map(::copyConversation)
            val loaded = readAllFromPreferences()
            cachedConversations = loaded.map(::copyConversation)
            return loaded.map(::copyConversation)
        }
    }

    private fun readAllFromPreferences(): List<DemoConversation> =
        decodeStoredConversations(prefs.getString(KEY_CONVERSATIONS, null).orEmpty())

    private fun writeAll(conversations: List<DemoConversation>) {
        val snapshot = conversations
            .take(MAX_CONVERSATIONS)
            .map(::copyConversation)
        synchronized(cacheLock) {
            cachedConversations = snapshot
        }
        synchronized(writeLock) {
            // Keep only the newest snapshot. UI events can save several times
            // in one frame while a previous JSON write is still pending.
            pendingWrite = snapshot
            if (writeDrainScheduled) return
            writeDrainScheduled = true
        }
        writeExecutor.execute {
            drainPendingWrites()
        }
    }

    private fun drainPendingWrites(syncToDisk: Boolean = false) {
        while (true) {
            val snapshot = synchronized(writeLock) {
                val next = pendingWrite
                pendingWrite = null
                if (next == null) {
                    writeDrainScheduled = false
                }
                next
            } ?: break

            try {
                persistSnapshot(snapshot, syncToDisk)
            } catch (error: Exception) {
                // persistSnapshot consumed this snapshot above; if the failure
                // left writeDrainScheduled == true, every later writeAll()
                // would see the flag set and skip scheduling, so the snapshot
                // would never reach disk until some future flush. Put the
                // failed snapshot back (unless a newer snapshot already
                // arrived while we were persisting — keep the newer one) and
                // clear the flag inside the lock, so any subsequent writeAll()
                // reschedules the drain and retries it. The exception is then
                // rethrown so flush callers still observe the failure.
                synchronized(writeLock) {
                    if (pendingWrite == null) {
                        pendingWrite = snapshot
                    }
                    writeDrainScheduled = false
                }
                throw error
            }
        }
        if (!syncToDisk) return
        // A plain async drain queued ahead of this task may already have
        // consumed the pending write with apply(), which only updates the
        // in-memory layer. The newest accepted snapshot still lives in the
        // cache, so commit it as well: a flush must leave disk in sync with
        // memory even when its own write was drained by an earlier task.
        val latest = synchronized(cacheLock) { cachedConversations } ?: return
        persistSnapshot(latest, syncToDisk = true)
    }

    private fun persistSnapshot(snapshot: List<DemoConversation>, syncToDisk: Boolean) {
        val encoded = encode(snapshot)
        if (!syncToDisk) {
            // Ordinary UI writes stay asynchronous so the main thread never
            // blocks on disk I/O.
            prefs.edit().putString(KEY_CONVERSATIONS, encoded).apply()
            return
        }
        // apply() only updates the in-memory layer and defers the disk write;
        // a process kill right after a flush returns could still discard it.
        // commit() blocks until the bytes reach disk. It runs on the store's
        // single writer thread only — flush callers are background executors
        // that wait via Future.get(), so the main thread never performs this
        // write.
        if (!prefs.edit().putString(KEY_CONVERSATIONS, encoded).commit()) {
            throw IllegalStateException(
                "Committing demo conversations to SharedPreferences failed; " +
                    "the flushed write is not durable on disk."
            )
        }
    }

    private fun encode(conversations: List<DemoConversation>): String =
        encodeStoredConversations(conversations)

    private fun copyConversation(conversation: DemoConversation): DemoConversation =
        conversation.copy(messages = conversation.messages.map { it.copy() }.toMutableList())

    private companion object {
        const val PREFS_NAME = "demo_conversations"
        const val KEY_CONVERSATIONS = "conversations"
        const val KEY_ACTIVE_ID = "active_id"
    }
}

internal fun encodeStoredConversations(conversations: List<DemoConversation>): String {
    val array = buildJsonArray {
        conversations.forEach { conversation ->
            add(
                buildJsonObject {
                    put("id", conversation.id)
                    put("title", normalizeStoredTitle(conversation.title))
                    put("createdAt", conversation.createdAt)
                    put("updatedAt", conversation.updatedAt)
                    putJsonArray("messages") {
                        conversation.messages.takeLast(MAX_MESSAGES).forEach { message ->
                            add(
                                buildJsonObject {
                                    put("role", message.role)
                                    put("content", message.content.take(MAX_MESSAGE_CHARS))
                                    put("createdAt", message.createdAt)
                                    val cleanPaths = message.imagePaths
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                        .take(MAX_STORED_IMAGES_PER_MESSAGE)
                                    if (cleanPaths.isNotEmpty()) {
                                        putJsonArray("imagePaths") {
                                            cleanPaths.forEach { add(JsonPrimitive(it)) }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            )
        }
    }
    return array.toString()
}

internal fun decodeStoredConversations(raw: String): List<DemoConversation> {
    if (raw.isBlank()) return emptyList()
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
        ?: return emptyList()

    return buildList {
        // A malformed record is isolated to that record. One bad element must
        // not discard otherwise valid conversations in the same preference.
        for (element in root) {
            val conversation = runCatching { decodeStoredConversation(element) }.getOrNull()
            if (conversation != null) add(conversation)
        }
    }
}

private fun JsonElement?.storedStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.contentOrNull
}

private fun JsonElement?.storedLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.toLongOrNull()
}

private fun decodeStoredConversation(element: JsonElement): DemoConversation? {
    val item = element as? JsonObject ?: return null
    // id is the only required conversation field; wrong JSON types are not
    // coerced into an id because that could merge unrelated records.
    val id = item["id"].storedStringOrNull()?.trim().orEmpty()
    if (id.isBlank()) return null

    val now = System.currentTimeMillis()
    val messages = mutableListOf<DemoStoredMessage>()
    val encodedMessages = item["messages"] as? JsonArray
    if (encodedMessages != null) {
        for (messageElement in encodedMessages) {
            val message = runCatching { decodeStoredMessage(messageElement) }.getOrNull()
            if (message != null) messages += message
        }
    }

    return normalizeStoredConversation(
        DemoConversation(
            id = id,
            title = item["title"].storedStringOrNull() ?: DEFAULT_TITLE,
            createdAt = item["createdAt"].storedLongOrNull() ?: now,
            updatedAt = item["updatedAt"].storedLongOrNull() ?: now,
            messages = messages
        )
    )
}

private fun decodeStoredMessage(element: JsonElement): DemoStoredMessage? {
    val encoded = element as? JsonObject ?: return null
    val role = encoded["role"].storedStringOrNull()?.trim().orEmpty()
    if (role !in ALLOWED_ROLES) return null

    val content = encoded["content"].storedStringOrNull().orEmpty()
    val imagePaths = decodeStoredImagePaths(encoded)
    if (content.isBlank() && imagePaths.isEmpty()) return null

    return DemoStoredMessage(
        role = role,
        content = content.take(MAX_MESSAGE_CHARS),
        createdAt = encoded["createdAt"].storedLongOrNull() ?: System.currentTimeMillis(),
        imagePaths = imagePaths
    )
}

private fun decodeStoredImagePaths(message: JsonObject): List<String> {
    // Salvage valid string entries from a valid array. If the field is absent,
    // the wrong JSON type, or contains no usable path, use the legacy field.
    val paths = (message["imagePaths"] as? JsonArray)
        ?.mapNotNull { element ->
            element.storedStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }
        ?.take(MAX_STORED_IMAGES_PER_MESSAGE)
        .orEmpty()
    if (paths.isNotEmpty()) return paths

    val legacyPath = message["imagePath"].storedStringOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return if (legacyPath == null) emptyList() else listOf(legacyPath)
}
