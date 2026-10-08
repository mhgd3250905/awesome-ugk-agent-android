package com.ugk.pi.android.testapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Both whole-collection preference stores learned the same rule in round 15: bytes the app
 * could not read must be copied aside before the next save replaces them, because `save`
 * writes the whole collection from what the read returned.
 *
 * That rule was applied to the *snapshot* as a whole. A collection that parses and yields
 * some records while dropping others was still read as "here is everything", and the next
 * ordinary save rewrote the collection without the records this build could not decode -
 * the same erasure, one level down. These cases hold both stores to one rule.
 */
class PartiallyUnreadableCollectionPreservedTest {

    /** Control, green on main: absent bytes are honestly empty. */
    @Test
    fun conversationsAbsentIsAnHonestEmpty() {
        assertNull(loadStoredSnapshot(null).unreadableRaw)
        assertNull(loadStoredSnapshot("").unreadableRaw)
    }

    /** Control, green on main: an empty array is how the store writes "nothing stored". */
    @Test
    fun conversationsEmptyArrayIsAnHonestEmpty() {
        val loaded = loadStoredSnapshot("[]")

        assertEquals(0, loaded.conversations.size)
        assertNull(
            "an array that says it holds nothing must not be archived as unreadable",
            loaded.unreadableRaw
        )
    }

    /** Control, green on main: every record unusable is already preserved. */
    @Test
    fun conversationsWithNoUsableRecordArePreserved() {
        assertNotNull(loadStoredSnapshot("[{},{},{}]")?.unreadableRaw)
    }

    @Test
    fun conversationsReaderPreservesTheBytesOfARecordItCouldNotDecode() {
        val stored = """[{"id":"keep","messages":[]},{}]"""

        val loaded = loadStoredSnapshot(stored)

        assertEquals(listOf("keep"), loaded.conversations.map { it.id })
        assertNotNull(
            "a record the app dropped is still the user's data: the next save replaces the whole " +
                "collection and would erase it",
            loaded.unreadableRaw
        )
    }

    @Test
    fun aWellFormedConversationCollectionNeedsNoArchive() {
        val stored = """[{"id":"a","messages":[]},{"id":"b","messages":[]}]"""

        val loaded = loadStoredSnapshot(stored)

        assertEquals(listOf("a", "b"), loaded.conversations.map { it.id })
        assertNull(loaded.unreadableRaw)
    }

    @Test
    fun providerReaderPreservesTheBytesOfAConfigItCouldNotDecode() {
        val stored = """
            {"configs":[
              {"id":"a","baseUrl":"https://example.test","apiKey":"k1","model":"m"},
              {"id":"","baseUrl":"https://example.test","apiKey":"k2","model":"m"}
            ]}
        """.trimIndent()

        val read = ApiProviderSettingsJson.read(stored)

        assertEquals(listOf("a"), read.state.configs.map { it.id })
        assertNotNull(
            "the second provider's apiKey and baseUrl survive only if the bytes are copied " +
                "aside before the next upsert rewrites the collection",
            read.unreadableRaw
        )
    }

    /** Control, green on main: the store's own way of writing "nobody configured a provider". */
    @Test
    fun anEmptyProviderCollectionIsAnHonestEmpty() {
        val read = ApiProviderSettingsJson.read("""{"configs":[]}""")

        assertEquals(0, read.state.configs.size)
        assertNull(read.unreadableRaw)
    }

    @Test
    fun aWellFormedProviderCollectionNeedsNoArchive() {
        val stored = """
            {"configs":[
              {"id":"a","baseUrl":"https://example.test","apiKey":"k1","model":"m"},
              {"id":"b","baseUrl":"https://example.test","apiKey":"k2","model":"m"}
            ]}
        """.trimIndent()

        val read = ApiProviderSettingsJson.read(stored)

        assertEquals(listOf("a", "b"), read.state.configs.map { it.id })
        assertNull(read.unreadableRaw)
    }
}
