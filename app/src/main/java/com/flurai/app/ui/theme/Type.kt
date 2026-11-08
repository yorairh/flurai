package com.flurai.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.sp

/**
 * Kanji are shared between Chinese and Japanese (Han unification), and on a device whose system
 * language isn't Japanese, Android may draw Chinese glyph shapes. Tagging text with the Japanese
 * locale makes the system pick Japanese glyphs. Apply this to every piece of Japanese text.
 */
val JapaneseLocale = LocaleList("ja-JP")

fun TextStyle.japanese(): TextStyle = copy(localeList = JapaneseLocale)

val FluRaiTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    )
}
