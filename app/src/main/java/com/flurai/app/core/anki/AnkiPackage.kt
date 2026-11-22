package com.flurai.app.core.anki

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/*
 * Reads an Anki package (.apkg: some decks, .colpkg: a whole collection) into plain data. A package is
 * a zip holding the collection as an SQLite file:
 *   collection.anki21b  Anki 2.1.50 and later: zstd-compressed, schema 18 (note types, fields, templates
 *                       and decks in their own tables, configs as protobuf);
 *   collection.anki21   2.1 to 2.1.49: schema 11 (note types and decks as JSON in the col table);
 *   collection.anki2    older Anki, same schema 11. A new package also carries a dummy one of these
 *                       that only says "please update Anki", so the newest file always wins.
 * Media (pictures and sounds) are left out: FluRai cards are text and its own speech.
 * See https://github.com/ankitects/anki (rslib/src/storage, rslib/src/import_export/package).
 */

data class AnkiField(val ord: Int, val name: String)

/** A card template: [front] and [back] are its question and answer formats ("{{Expression}}"). */
data class AnkiTemplate(val ord: Int, val name: String, val front: String, val back: String)

data class AnkiNoteType(val id: Long, val name: String, val fields: List<AnkiField>, val templates: List<AnkiTemplate>, val cloze: Boolean)

/** A deck; [name] has its parents joined by "::" ("Japanese::Core 2k"). */
data class AnkiDeck(val id: Long, val name: String)

data class AnkiNote(val id: Long, val guid: String, val typeId: Long, val fields: List<String>)

/**
 * One card. [type]: 0 new, 1 learning, 2 review, 3 relearning. [queue]: -1 suspended, -2/-3 buried, else as type.
 * [due]: a new card's position, a review card's day (counted from the collection's creation), a learning
 * card's time in seconds. [data]: JSON with the FSRS memory state when the collection used FSRS
 * (`s` stability in days, `d` difficulty 1-10, `lrt` last review time in seconds).
 */
data class AnkiCard(
    val id: Long,
    val noteId: Long,
    val deckId: Long,
    val ord: Int,
    val type: Int,
    val queue: Int,
    val due: Long,
    val interval: Int,
    val reps: Int,
    val lapses: Int,
    val data: String,
)

/** One answer: [time] in ms, [ease] 1-4 (0 for a manual change), [type] 0 learn, 1 review, 2 relearn, 3 filtered, 4 manual, 5 rescheduled. */
data class AnkiReview(val cardId: Long, val time: Long, val ease: Int, val type: Int)

data class AnkiCollection(
    /** When the collection was made, in seconds: review cards' due days count from it. */
    val createdSecs: Long,
    val noteTypes: List<AnkiNoteType>,
    val decks: List<AnkiDeck>,
    val notes: List<AnkiNote>,
    val cards: List<AnkiCard>,
    val reviews: List<AnkiReview>,
)

class AnkiException(message: String, cause: Throwable? = null) : Exception(message, cause)

object AnkiPackageReader {
    private val CANDIDATES = listOf("collection.anki21b", "collection.anki21", "collection.anki2")

    /**
     * Reads the package [input] (a zip). The collection is unpacked into [workDir] (deleted again) and
     * opened with [driver] (Android's SQLite in the app, a bundled SQLite in tests); [unzstd] decompresses
     * a 2.1.50+ collection (zstd-jni in the app, aircompressor in tests). Any failure is an [AnkiException].
     */
    fun read(input: () -> InputStream, workDir: File, driver: SQLiteDriver, unzstd: (InputStream) -> InputStream): AnkiCollection {
        workDir.mkdirs()
        val db = File(workDir, "collection.db")
        try {
            val name = unpack(input, db, unzstd)
            return driver.open(db.path).use { readCollection(it) }.also {
                if (name == "collection.anki2" && it.notes.size == 1 && it.notes[0].fields.any { f -> "update" in f.lowercase() && "anki" in f.lowercase() }) {
                    throw AnkiException("This package was made by a newer Anki in a form FluRai could not open. Export it again with \"Support older Anki versions\" ticked.")
                }
            }
        } catch (e: AnkiException) {
            throw e
        } catch (e: Throwable) { // also errors from the native decompressor or SQLite
            throw AnkiException("Could not read this file as an Anki deck.", e)
        } finally {
            workDir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Copies the newest collection of the package to [target]; returns which one it was. */
    private fun unpack(input: () -> InputStream, target: File, unzstd: (InputStream) -> InputStream): String {
        // Find the newest collection first (the zip can only be read front to back), then extract it.
        val names = input().use { raw -> ZipInputStream(raw).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toSet() } }
        val name = CANDIDATES.firstOrNull { it in names }
            ?: throw AnkiException("This is not an Anki deck (.apkg) or collection (.colpkg).")
        input().use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name != name) continue
                    target.outputStream().use { out -> (if (name.endsWith("b")) unzstd(zip) else zip).copyTo(out) }
                    return name
                }
            }
        }
        throw AnkiException("This Anki file is damaged.")
    }

    internal fun readCollection(db: SQLiteConnection): AnkiCollection {
        val created = db.single("SELECT crt FROM col") { it.getLong(0) } ?: 0L
        val newSchema = db.single("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'notetypes'") { it.getLong(0) } == 1L
        val (types, decks) = if (newSchema) noteTypes18(db) to decks18(db) else schema11(db)
        val notes = db.all("SELECT id, guid, mid, flds FROM notes") {
            AnkiNote(it.getLong(0), it.getText(1), it.getLong(2), it.getText(3).split('\u001f'))
        }
        val cards = db.all("SELECT id, nid, did, ord, type, queue, due, ivl, reps, lapses, data FROM cards") {
            AnkiCard(
                id = it.getLong(0), noteId = it.getLong(1), deckId = it.getLong(2), ord = it.getInt(3), type = it.getInt(4),
                queue = it.getInt(5), due = it.getLong(6), interval = it.getInt(7), reps = it.getInt(8), lapses = it.getInt(9),
                data = if (it.isNull(10)) "" else it.getText(10),
            )
        }
        val reviews = db.all("SELECT cid, id, ease, type FROM revlog ORDER BY id") { AnkiReview(it.getLong(0), it.getLong(1), it.getInt(2), it.getInt(3)) }
        return AnkiCollection(created, types, decks, notes, cards, reviews)
    }

    // -- schema 18 (Anki 2.1.50+) ----------------------------------------------------------------

    private fun noteTypes18(db: SQLiteConnection): List<AnkiNoteType> {
        val fields = db.all("SELECT ntid, ord, name FROM fields ORDER BY ntid, ord") { Triple(it.getLong(0), it.getInt(1), it.getText(2)) }
            .groupBy({ it.first }, { AnkiField(it.second, it.third) })
        val templates = db.all("SELECT ntid, ord, name, config FROM templates ORDER BY ntid, ord") {
            val config = Proto.fields(it.getBlob(3))
            it.getLong(0) to AnkiTemplate(it.getInt(1), it.getText(2), config.string(1), config.string(2))
        }.groupBy({ it.first }, { it.second })
        return db.all("SELECT id, name, config FROM notetypes") {
            val id = it.getLong(0)
            val cloze = Proto.fields(it.getBlob(2)).varint(1) == 1L // NotetypeConfig.kind: 0 normal, 1 cloze
            AnkiNoteType(id, it.getText(1), fields[id].orEmpty(), templates[id].orEmpty(), cloze)
        }
    }

    /** Schema 18 keeps a deck's path with \u001f between the parts. */
    private fun decks18(db: SQLiteConnection) = db.all("SELECT id, name FROM decks") { AnkiDeck(it.getLong(0), it.getText(1).replace("\u001f", "::")) }

    // -- schema 11 -------------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    private fun schema11(db: SQLiteConnection): Pair<List<AnkiNoteType>, List<AnkiDeck>> {
        val (models, decks) = db.single("SELECT models, decks FROM col") { it.getText(0) to it.getText(1) }
            ?: throw AnkiException("This Anki file has no collection in it.")
        val types = json.parseToJsonElement(models).jsonObject.values.map { m ->
            val o = m.jsonObject
            AnkiNoteType(
                id = o.long("id"),
                name = o.text("name"),
                fields = (o["flds"] as? JsonArray).orEmpty().map { f -> AnkiField(f.jsonObject.int("ord"), f.jsonObject.text("name")) },
                templates = (o["tmpls"] as? JsonArray).orEmpty().map { t ->
                    val tj = t.jsonObject
                    AnkiTemplate(tj.int("ord"), tj.text("name"), tj.text("qfmt"), tj.text("afmt"))
                },
                cloze = o.int("type") == 1,
            )
        }
        val deckList = json.parseToJsonElement(decks).jsonObject.values.map { d -> AnkiDeck(d.jsonObject.long("id"), d.jsonObject.text("name")) }
        return types to deckList
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.long ?: 0L
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.int ?: 0

    // -- SQLite helpers --------------------------------------------------------------------------

    private fun <T> SQLiteConnection.all(sql: String, row: (SQLiteStatement) -> T): List<T> =
        prepare(sql).use { s -> buildList { while (s.step()) add(row(s)) } }

    private fun <T> SQLiteConnection.single(sql: String, row: (SQLiteStatement) -> T): T? =
        prepare(sql).use { s -> if (s.step()) row(s) else null }
}

/** Just enough protobuf to read the few config fields FluRai needs (strings and numbers by field number). */
internal object Proto {
    class Fields(private val values: Map<Int, Any>) {
        fun string(field: Int): String = (values[field] as? ByteArray)?.toString(Charsets.UTF_8).orEmpty()
        fun varint(field: Int): Long = values[field] as? Long ?: 0L
    }

    /** The last value of each field of a message (repeated fields are not needed here). */
    fun fields(bytes: ByteArray): Fields {
        val values = HashMap<Int, Any>()
        var i = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (i < bytes.size) {
                val b = bytes[i++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) break
                shift += 7
            }
            return result
        }
        while (i < bytes.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> values[field] = varint()
                1 -> i += 8
                2 -> {
                    val length = varint().toInt()
                    if (length < 0 || i + length > bytes.size) break
                    values[field] = bytes.copyOfRange(i, i + length)
                    i += length
                }
                5 -> i += 4
                else -> break // groups are not used by Anki
            }
        }
        return Fields(values)
    }
}
