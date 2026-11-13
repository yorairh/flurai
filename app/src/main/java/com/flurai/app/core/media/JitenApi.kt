package com.flurai.app.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/*
 * A small client for the public jiten.moe API (https://api.jiten.moe, no key needed for reads).
 * jiten.moe asks apps not to mirror or relay its data, so the phone calls it directly and keeps
 * only a short in-memory cache (see MediaRepository). Field names follow its OpenAPI document.
 */

/** jiten.moe's media types, with the numbers its API uses. */
enum class MediaType(val id: Int, val label: String) {
    Anime(1, "Anime"),
    Drama(2, "Drama"),
    Movie(3, "Movies"),
    Manga(9, "Manga"),
    Novel(4, "Novels"),
    VideoGame(6, "Games"),
    ;

    companion object {
        fun of(id: Int): MediaType? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class DeckLink(val linkType: Int = 0, val url: String = "")

@Serializable
data class Deck(
    val deckId: Int,
    val coverName: String? = null,
    val mediaType: Int = 0,
    val originalTitle: String = "",
    val romajiTitle: String? = null,
    val englishTitle: String? = null,
    val description: String? = null,
    val characterCount: Int = 0,
    val wordCount: Int = 0,
    val uniqueWordCount: Int = 0,
    /** jiten.moe's 0 to 5 difficulty band. */
    val difficulty: Int = 0,
    val difficultyRaw: Double = 0.0,
    val links: List<DeckLink> = emptyList(),
) {
    /** The English title when there is one, else romaji, else the original. */
    val title: String get() = englishTitle?.takeIf { it.isNotBlank() } ?: romajiTitle?.takeIf { it.isNotBlank() } ?: originalTitle
}

@Serializable
data class DeckPage(val data: List<Deck> = emptyList(), val totalItems: Int = 0, val pageSize: Int = 50, val currentOffset: Int = 0)

@Serializable
data class WordForm(val text: String = "")

@Serializable
data class DeckWord(val wordId: Int, val mainReading: WordForm = WordForm(), val occurrences: Int = 0)

@Serializable
data class DeckVocabulary(val words: List<DeckWord> = emptyList())

@Serializable
data class VocabularyPage(val data: DeckVocabulary = DeckVocabulary(), val totalItems: Int = 0)

class JitenException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Singleton
class JitenApi @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** One page (50) of decks of a type whose difficulty lies in [difficultyMin]..[difficultyMax], most popular first. */
    suspend fun decks(
        type: MediaType,
        difficultyMin: Double,
        difficultyMax: Double,
        offset: Int,
        titleFilter: String = "",
    ): DeckPage = get(
        "/api/media-deck/get-media-decks",
        "mediaType" to type.id, "difficultyMin" to difficultyMin, "difficultyMax" to difficultyMax,
        "sortBy" to "popularity", "sortOrder" to 1, "offset" to offset, "titleFilter" to titleFilter,
    ).let { json.decodeFromString(DeckPage.serializer(), it) }

    /** The [limit] (at most 200) words that occur most in a deck. */
    suspend fun topVocabulary(deckId: Int, limit: Int = 200): List<DeckWord> = get(
        "/api/media-deck/$deckId/vocabulary",
        "sortBy" to "deckFreq", "sortOrder" to 1, "offset" to 0, "limit" to limit,
    ).let { json.decodeFromString(VocabularyPage.serializer(), it).data.words }

    /** One page of a deck's words, most frequent first, starting at [offset]. */
    suspend fun vocabularyPage(deckId: Int, offset: Int, limit: Int = VOCABULARY_PAGE): VocabularyPage = get(
        "/api/media-deck/$deckId/vocabulary",
        "sortBy" to "deckFreq", "sortOrder" to 1, "offset" to offset, "limit" to limit,
    ).let { json.decodeFromString(VocabularyPage.serializer(), it) }

    private suspend fun get(path: String, vararg params: Pair<String, Any>): String = withContext(Dispatchers.IO) {
        val query = params.filter { it.second.toString().isNotEmpty() }
            .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second.toString(), "UTF-8")}" }
        val connection = URL("$BASE$path?$query").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "FluRai (personal Japanese study app)")
            when (val code = connection.responseCode) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                429 -> throw JitenException("jiten.moe is busy right now. Try again in a minute.")
                else -> throw JitenException("jiten.moe answered with an error ($code).")
            }
        } catch (e: JitenException) {
            throw e
        } catch (e: Exception) {
            throw JitenException("Could not reach jiten.moe. Check your connection.", e)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** The most words jiten.moe gives in one answer. */
        const val VOCABULARY_PAGE = 200
        private const val BASE = "https://api.jiten.moe"
    }
}
