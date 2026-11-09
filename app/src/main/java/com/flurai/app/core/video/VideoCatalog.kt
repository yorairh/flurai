package com.flurai.app.core.video

import java.net.URLEncoder

/**
 * A YouTube channel worth watching at some levels. We link to a YouTube search for the channel
 * instead of a channel id or video ids: those can go stale, and searches always resolve.
 */
data class VideoChannel(
    val name: String,
    val focus: String,
    /** JLPT numbers (5 = N5) this channel suits. */
    val levels: Set<Int>,
    val query: String,
)

/** The bundled, hand-picked list. No YouTube API key or network call is involved. */
val videoChannels = listOf(
    VideoChannel("Japanese Ammo with Misa", "Friendly grammar and everyday phrases, mostly in English.", setOf(5, 4, 3), "Japanese Ammo with Misa"),
    VideoChannel("Tokini Andy", "Genki textbook walkthroughs and practical beginner Japanese.", setOf(5, 4), "Tokini Andy Genki"),
    VideoChannel("Comprehensible Japanese", "Slow, picture-supported Japanese you can follow without translation.", setOf(5, 4, 3), "Comprehensible Japanese"),
    VideoChannel("Nihongo con Teppei", "Talk-style videos for beginners, spoken slowly and clearly.", setOf(5, 4, 3), "Nihongo con Teppei beginners"),
    VideoChannel("Miku Real Japanese", "Natural spoken Japanese with explanations and subtitles.", setOf(4, 3, 2), "Miku Real Japanese"),
    VideoChannel("Game Gengo", "Learn the language of video games, with grammar breakdowns.", setOf(4, 3, 2), "Game Gengo"),
    VideoChannel("Nihongo no Mori", "JLPT lessons taught entirely in Japanese, N5 to N1.", setOf(3, 2, 1), "日本語の森 JLPT"),
    VideoChannel("Japanese with Shun", "Podcast-style Japanese for upper-intermediate learners.", setOf(3, 2, 1), "Japanese with Shun"),
)

fun channelsFor(jlpt: Int): List<VideoChannel> = videoChannels.filter { jlpt in it.levels }

fun searchUrl(query: String): String = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")

/** Search for explanations of one grammar pattern, e.g. "たことがある Japanese grammar". */
fun grammarQuery(pattern: String): String = pattern.removePrefix("〜").substringBefore(" (") + " Japanese grammar"
