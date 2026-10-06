package com.ugk.pi.android.testapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The value domain of the two rules introduced for the unreadable-snapshot family.
 *
 * The rule is not "the text does not parse" but "a present, non-blank value yielded
 * nothing usable and does not itself say it is empty". Those are different sets:
 * `[{"id":""}]` and `{"configs":"oops"}` parse fine and still destroy everything on
 * the next save, while `[]` and `{"configs":[]}` are honest empties that must not
 * raise a recovery file - if they did, the recovery directory would stop meaning
 * anything.
 *
 * The salvage rows pin the loop's progress guarantee: the most likely torn offset is
 * a write that stops right after a closing brace, and a boundary search that includes
 * the character it just rejected re-tests the same candidate forever.
 */
class DemoUnreadableSnapshotValueDomainTest {

    @JvmField
    @Rule
    val tempFolder = TemporaryFolder()

    private val recoveryDir get() = File(tempFolder.root, "snapshot-recovery")

    @Test
    fun aTailThatStopsRightAfterAClosingBraceStillRecoversTheEarlierRecords() {
        val torn = "[{\"id\":\"c1\",\"messages\":[{\"role\":\"user\",\"content\":\"keep\"}]}," +
            "{\"id\":\"c2\",\"messages\":[{\"role\":\"user\",\"content\":\"half\"}"
        assertEquals("the fixture must end on a closing brace", '}', torn.last())

        val loaded = loadStoredSnapshot(torn)

        assertEquals(
            "the complete record must come back into the app, got: ${loaded.conversations.map { it.id }}," +
                " unreadable=${loaded.unreadableRaw != null}",
            listOf("c1"),
            loaded.conversations.map { it.id }
        )
        assertNull("a salvaged snapshot must not also be archived", loaded.unreadableRaw)
    }

    @Test
    fun recordsThatCannotBeDecodedAreArchivedEvenThoughTheArrayParsed() {
        // Parses as a one-element array; the element is rejected, so the app reads
        // "no conversations" and the next save would erase the value it just misread.
        val stored = "[{\"id\":\"\",\"messages\":[]},{\"messages\":[{\"role\":\"user\"}]}]"

        val store = DemoConversationStore(contextWith(stored, CONVERSATIONS_KEY))
        assertEquals(emptyList<String>(), store.list().map { it.id })

        assertNotNull(
            "a value that yields nothing usable must be archived even when it is valid JSON",
            recoveryDir.listFiles()?.firstOrNull { it.name.contains("conversations") }?.readText()
        )
    }

    @Test
    fun anHonestlyEmptyConversationArrayArchivesNothing() {
        val store = DemoConversationStore(contextWith("[]", CONVERSATIONS_KEY))

        assertEquals(emptyList<String>(), store.list().map { it.id })
        assertEquals("an explicit empty array is not corruption", 0, recoveryDir.listFiles()?.size ?: 0)
    }

    @Test
    fun aHealthySnapshotReadPerformsNoFileIo() {
        val stored = encodeStoredConversations(
            listOf(
                DemoConversation("c1", "t", 1L, 1L, mutableListOf(DemoStoredMessage("user", "x"))),
                DemoConversation("c2", "t", 1L, 1L, mutableListOf(DemoStoredMessage("user", "y")))
            )
        )

        val store = DemoConversationStore(contextWith(stored, CONVERSATIONS_KEY))

        assertEquals(listOf("c1", "c2"), store.list().map { it.id }.sorted())
        assertEquals("a readable store must touch no recovery file", 0, recoveryDir.listFiles()?.size ?: 0)
    }

    @Test
    fun aProviderConfigsValueThatIsNotAnArrayIsArchived() {
        // Valid JSON, then `jsonArray` refuses a string: this is the "no object could
        // be read at all" arm, and the archive is what keeps the old keys alive.
        val store = ApiProviderSettingsStore(
            contextWith("{\"configs\":\"oops\",\"activeId\":\"p1\"}", PROVIDERS_KEY)
        )

        assertEquals(emptyList<String>(), store.load().configs.map { it.id })

        val archived = recoveryDir.listFiles()?.firstOrNull { it.name.contains("providers") }?.readText()
        assertNotNull("a provider value that yields nothing usable must be kept", archived)
        assertEquals("the archive must hold the original bytes", "{\"configs\":\"oops\",\"activeId\":\"p1\"}", archived)
    }

    @Test
    fun providerRecordsThatCannotBeDecodedAreArchivedEvenThoughEverythingParsed() {
        // The arm the row above cannot reach: the value parses, `configs` is a real
        // array, and every record inside it is unusable. `load()` answers "nobody
        // configured a provider" without throwing, so only the
        // "yields nothing usable and does not say it is empty" rule keeps the bytes.
        val store = ApiProviderSettingsStore(
            contextWith("{\"configs\":[{\"baseUrl\":\"https://old\",\"apiKey\":\"sk-keepme\"}]}", PROVIDERS_KEY)
        )

        assertEquals(emptyList<String>(), store.load().configs.map { it.id })

        val archived = recoveryDir.listFiles()?.firstOrNull { it.name.contains("providers") }?.readText()
        assertNotNull(
            "a provider value that parses but yields no usable record must still be kept",
            archived
        )
        assertTrue(
            "the archived copy must be the whole original value, got: $archived",
            archived.orEmpty().contains("sk-keepme")
        )
    }

    @Test
    fun anHonestlyEmptyProviderStateArchivesNothing() {
        val store = ApiProviderSettingsStore(contextWith("{\"configs\":[]}", PROVIDERS_KEY))

        assertEquals(emptyList<String>(), store.load().configs.map { it.id })
        assertEquals("an explicit empty config list is not corruption", 0, recoveryDir.listFiles()?.size ?: 0)
    }

    private fun contextWith(stored: String?, key: String): Context {
        val preferences = InMemoryPreferences(key, key to stored)
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences.handle

            override fun getFilesDir(): File = tempFolder.root
        }
    }

    private class InMemoryPreferences(private val primaryKey: String, seed: Pair<String, String?>) {
        private val values = mutableMapOf<String, String>()
        private val pending = mutableMapOf<String, String?>()

        init {
            seed.second?.let { values[seed.first] = it }
        }

        val handle: SharedPreferences by lazy {
            lateinit var created: SharedPreferences
            created = Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java, SharedPreferences.Editor::class.java)
            ) { _, method, args -> dispatch(method.name, args, created) } as SharedPreferences
            created
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
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                pending.clear()
                true
            }
            "getInt", "getLong", "getFloat", "getBoolean" -> 0
            "registerOnSharedPreferenceChangeListener",
            "unregisterOnSharedPreferenceChangeListener" -> Unit
            "toString" -> "InMemoryPreferences"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            else -> null
        }
    }

    private companion object {
        const val CONVERSATIONS_KEY = "conversations"
        const val PROVIDERS_KEY = "state"
    }
}
