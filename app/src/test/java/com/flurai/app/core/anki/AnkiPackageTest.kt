package com.flurai.app.core.anki

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reads real Anki exports (made by the official Anki library, see src/test/resources/anki/make_fixtures.py):
 * the 2.1.50+ format (zstd, schema 18) and the "support older Anki versions" format (schema 11).
 */
class AnkiPackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String) = File("src/test/resources/anki/$name")

    private fun read(name: String) = AnkiPackageReader.read({ fixture(name).inputStream() }, tmp.newFolder(), BundledSQLiteDriver(), ::unzstd)

    @Test
    fun readsTheNewFormat() = checkJapaneseDeck(read("japanese.apkg"))

    @Test
    fun readsTheLegacyFormat() = checkJapaneseDeck(read("japanese-legacy.apkg"))

    private fun checkJapaneseDeck(c: AnkiCollection) {
        val type = c.noteTypes.single { it.name == "Japanese (recognition & production)" }
        assertEquals(listOf("Expression", "Reading", "Meaning", "Audio"), type.fields.sortedBy { it.ord }.map { it.name })
        assertEquals(listOf("Recognition" to "{{Expression}}", "Production" to "{{Meaning}}"), type.templates.sortedBy { it.ord }.map { it.name to it.front })
        assertFalse(type.cloze)

        assertTrue(c.decks.any { it.name == "Japanese::Food" })
        assertEquals(8, c.notes.size)
        assertEquals(16, c.cards.size)
        val eat = c.notes.single { it.fields[0] == "食べる" }
        assertEquals("食べる[たべる]", eat.fields[1])

        // 食べる's recognition card was answered Easy with FSRS on: a review card with a memory state.
        val eatCard = c.cards.single { it.noteId == eat.id && it.ord == 0 }
        assertEquals(2, eatCard.type)
        assertTrue("FSRS state in ${eatCard.data}", "\"s\"" in eatCard.data && "\"d\"" in eatCard.data)
        assertTrue(c.reviews.any { it.cardId == eatCard.id && it.ease == 4 })
        // Sushi's cards are suspended.
        val sushi = c.notes.single { it.fields[0] == "寿司" }
        assertTrue(c.cards.filter { it.noteId == sushi.id }.all { it.queue == -1 })
        assertTrue(c.createdSecs > 1_600_000_000)
    }

    @Test
    fun aFileThatIsNotAnAnkiPackageSaysSo() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("photo.jpg")); it.write(1) }
        }.toByteArray()
        val e = runCatching { AnkiPackageReader.read({ ByteArrayInputStream(zip) }, tmp.newFolder(), BundledSQLiteDriver(), ::unzstd) }.exceptionOrNull()
        assertTrue(e is AnkiException && "not an Anki deck" in e.message!!)
        val notZip = runCatching { AnkiPackageReader.read({ ByteArrayInputStream("hello".toByteArray()) }, tmp.newFolder(), BundledSQLiteDriver(), ::unzstd) }.exceptionOrNull()
        assertTrue(notZip is AnkiException)
    }

    @Test
    fun protobufFieldsAreRead() {
        // field 1 = "ab" (length-delimited), field 2 = 150 (varint), then a fixed64 to skip.
        val bytes = byteArrayOf(0x0a, 2, 'a'.code.toByte(), 'b'.code.toByte(), 0x10, 0x96.toByte(), 0x01, 0x19, 1, 2, 3, 4, 5, 6, 7, 8)
        val f = Proto.fields(bytes)
        assertEquals("ab", f.string(1))
        assertEquals(150L, f.varint(2))
        assertEquals("", f.string(9))
    }
}
