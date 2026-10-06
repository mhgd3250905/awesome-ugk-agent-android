package com.ugk.pi.android.testapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * An unreadable durable snapshot must never look like "the user has nothing".
 *
 * Both demo-app stores that keep a whole collection in one value decode a parse
 * failure into an empty collection, and every save is a read-modify-write of the
 * whole collection. So one torn SharedPreferences write plus one ordinary tap
 * replaced the user's conversations, or every API provider and key, with the
 * single record being saved. `DemoConversationStore`'s own comment promises the
 * opposite: "treats malformed preferences as recoverable data".
 *
 * The recovery directory and file naming are asserted as literals rather than
 * through new symbols so every case here also runs against the pre-fix tree.
 */
class DemoUnreadableSnapshotTest {

    @JvmField
    @Rule
    val tempFolder = TemporaryFolder()

        /** The store keeps its whole collection under this private prefs key. */
    private val CONVERSATIONS_KEY = "conversations"
    private val PROVIDERS_KEY = "state"

    private val recoveryDir get() = File(tempFolder.root, "snapshot-recovery")

    @Test
    fun aTornConversationArrayStillReturnsEveryCompleteRecord() {
        val complete = encodeStoredConversations(
            listOf(
                conversation("c1", "第一份", "写在前面"),
                conversation("c2", "第二份", "写在中间")
            )
        )
        // A torn write keeps the complete records and stops inside the last one:
        // drop the array's `]`, then start a third record that never closes.
        val torn = complete.removeSuffix("]") +
            ",{\"id\":\"c3\",\"messages\":[{\"role\":\"user\",\"content\":\"被截断的尾巴\""
        assertTrue(
            "the fixture must stay unparseable as it stands",
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(torn) }.isFailure
        )

        val decoded = decodeStoredConversations(torn)

        assertEquals(
            "the two complete records must survive a torn tail, got ids: ${decoded.map { it.id }}",
            listOf("c1", "c2"),
            decoded.map { it.id }.sorted()
        )
    }

    @Test
    fun anUnreadableConversationSnapshotIsArchivedBeforeAWriteCanReplaceIt() {
        val unreadable = "[{\"id\":\"c1\",\"messages\":[{\"role\":\"user\",\"content\":\"非常重要\"}},{\"id\":\"c2\""
        val store = DemoConversationStore(contextWith(unreadable, CONVERSATIONS_KEY))

        // Any ordinary action: the store now believes nothing was ever saved.
        store.create("新的对话")
        // Force the queued snapshot onto disk synchronously, the way the platform's
        // own writer would, so the assertion below is about the archive and not about
        // a daemon thread that may not have run yet.
        store.saveAndFlush(store.list().first())

        val archived = archivedContents()
        assertNotNull(
            "the unreadable bytes must be kept somewhere durable before being replaced",
            archived
        )
        assertTrue(
            "the archive must hold the user's own text, got: $archived",
            archived.orEmpty().contains("非常重要")
        )
    }

    @Test
    fun theSameUnreadableSnapshotIsArchivedOnlyOnce() {
        val unreadable = "[{\"id\":\"c1\",\"messages\":[ truncated"
        // Two stores over the same prefs and files dir are two launches of the app.
        DemoConversationStore(contextWith(unreadable, CONVERSATIONS_KEY)).list()
        DemoConversationStore(contextWith(unreadable, CONVERSATIONS_KEY)).list()

        assertEquals(
            "repeated launches must not pile up copies of the same bytes",
            1,
            recoveryDir.listFiles()?.count { it.name.contains("conversations") } ?: 0
        )
    }

    @Test
    fun aFirstLaunchWithNothingStoredArchivesNothing() {
        // Control: an absent value is not corruption, and noise here would make the
        // recovery directory mean nothing.
        val store = DemoConversationStore(contextWith(null, CONVERSATIONS_KEY))

        assertEquals(emptyList<String>(), store.list().map { it.id })

        assertEquals("a blank first launch must archive nothing", 0, recoveryDir.listFiles()?.size ?: 0)
    }

    @Test
    fun anUnreadableProviderBlobIsArchivedAndStillSavesTheNewConfig() {
        val unreadable = "{\"configs\":[{\"id\":\"p1\",\"baseUrl\":\"https://old.example\",\"apiKey\":\"sk-secret-123\"}"
        val store = ApiProviderSettingsStore(contextWith(unreadable, PROVIDERS_KEY))
        val prefs = storePreferences()

        store.upsertAndActivate(
            ApiProviderConfig(id = "p2", baseUrl = "https://new.example", apiKey = "sk-new", model = "m")
        )

        val archived = archivedContents()
        assertNotNull(
            "the previous provider blob must be kept before it is replaced",
            archived
        )
        assertTrue(
            "the archived copy must still contain the old API key, got: $archived",
            archived.orEmpty().contains("sk-secret-123")
        )
        // And the app must stay usable: refusing to save would be the worse failure.
        assertTrue(prefs.saved.orEmpty().contains("sk-new"))
    }

    @Test
    fun aReadableProviderBlobArchivesNothing() {
        // Control for the same rule: healthy data must not raise a recovery file, or
        // the recovery directory stops meaning anything. A never-saved store cannot
        // be driven here because `load()` falls through to `loadDebugDefaults()`,
        // which reads Android resources and is not mocked on this host.
        val stored = ApiProviderSettingsJson.encode(
            ApiProviderSettingsState(
                activeId = "p1",
                configs = listOf(ApiProviderConfig(id = "p1", baseUrl = "https://a", apiKey = "k", model = "m"))
            )
        )

        val loaded = ApiProviderSettingsStore(contextWith(stored, PROVIDERS_KEY)).load()

        assertEquals(listOf("p1"), loaded.configs.map { it.id })
        assertEquals("a readable blob must archive nothing", 0, recoveryDir.listFiles()?.size ?: 0)
    }

    private fun conversation(id: String, title: String, content: String): DemoConversation =
        DemoConversation(
            id = id,
            title = title,
            createdAt = 1L,
            updatedAt = 1L,
            messages = mutableListOf(DemoStoredMessage("user", content))
        )

    private lateinit var capturedPreferences: InMemoryPreferences

    private fun contextWith(stored: String?, key: String): Context {
        capturedPreferences = InMemoryPreferences(key, key to stored)
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                capturedPreferences.handle

            override fun getFilesDir(): File = tempFolder.root
        }
    }

    private fun storePreferences(): InMemoryPreferences = capturedPreferences

    private fun archivedContents(): String? =
        recoveryDir.listFiles()?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.joinToString("\n") { it.readText() }
            ?.takeIf { it.isNotEmpty() }

    /**
     * A `SharedPreferences` backed by one map, so a whole-collection save is the
     * same replace-the-value write the platform performs. One proxy implements both
     * `SharedPreferences` and its `Editor`, which is what the platform's own editor
     * chaining relies on.
     */
    private class InMemoryPreferences(
        private val primaryKey: String,
        seed: Pair<String, String?>
    ) {
        private val values = mutableMapOf<String, String>()
        private val pending = mutableMapOf<String, String?>()
        var saved: String? = null
            private set

        init {
            seed.second?.let { values[seed.first] = it }
        }

        val handle: SharedPreferences by lazy { newProxy() }

        private fun newProxy(): SharedPreferences {
            lateinit var created: SharedPreferences
            created = Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java, SharedPreferences.Editor::class.java)
            ) { _, method, args -> dispatch(method.name, args, created) } as SharedPreferences
            return created
        }

        private fun dispatch(name: String, args: Array<Any?>?, self: SharedPreferences): Any? = when (name) {
            "getString" -> values[args?.get(0) as? String] ?: args?.get(1)
            "contains" -> values.containsKey(args?.get(0) as? String)
            "getAll", "all" -> HashMap(values)
            "edit" -> self
            "putString" -> {
                pending[args?.get(0) as String] = args?.get(1) as? String
                self
            }
            "remove" -> {
                pending[args?.get(0) as String] = null
                self
            }
            "clear" -> {
                values.keys.forEach { pending[it] = null }
                self
            }
            "commit", "apply" -> {
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                pending.clear()
                saved = values[primaryKey]
                true
            }
            else -> defaultValueFor(name)
        }

        private fun defaultValueFor(name: String): Any? = when (name) {
            "getInt", "getLong", "getFloat", "getBoolean" -> null
            "registerOnSharedPreferenceChangeListener",
            "unregisterOnSharedPreferenceChangeListener" -> Unit
            "toString" -> "InMemoryPreferences"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            else -> null
        }
    }
}
