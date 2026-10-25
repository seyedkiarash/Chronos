package com.chronos.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * Translation, done where text is drawn rather than at every literal.
 *
 * [Text] below shadows `androidx.compose.material3.Text` for this package — a
 * declaration in the same package wins over a star import — so every existing
 * `Text(...)` call goes through it unchanged, including text the engine
 * assembled at runtime.
 *
 * Lookup is by the English sentence. The source still says what it draws, and a
 * missing translation falls back to English rather than to a bare key. Three
 * passes, in order:
 *
 *  1. the sentence itself;
 *  2. the sentence with its numbers replaced by {0}, {1}, … so one entry covers
 *     "Day 1" and "Day 12";
 *  3. entries written as templates with `{}` where the user's own words go, so
 *     "English fluency: deadline is not set" matches "{}: deadline is not set"
 *     and the goal's name is carried through untranslated.
 *
 * Nothing the user typed is ever translated.
 */
enum class Lang(val code: String, val label: String, val rtl: Boolean) {
    English("en", "English", false),
    Persian("fa", "فارسی", true)
}

val LocalLang = compositionLocalOf { Lang.English }

/** The translation of [english] in the current language, or [english] itself. */
@Composable
@ReadOnlyComposable
fun tr(english: String): String = translate(LocalLang.current, english)

fun translate(lang: Lang, english: String): String {
    if (lang == Lang.English || english.isBlank()) return english
    val table = tableFor(lang) ?: return english

    table[english]?.let { return it }

    numberPattern(english)?.let { (pattern, numbers) ->
        table[pattern]?.let { translated ->
            var out = translated
            numbers.forEachIndexed { i, v -> out = out.replace("{$i}", v) }
            return out
        }
    }

    for ((regex, key) in templatesFor(lang)) {
        val m = regex.matchEntire(english) ?: continue
        var out = table[key] ?: continue
        m.groupValues.drop(1).forEach { captured ->
            out = out.replaceFirst("{}", captured)
        }
        return out
    }
    return english
}

private fun tableFor(lang: Lang): Map<String, String>? =
    if (lang == Lang.Persian) Fa else null

private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")

/** The sentence with its numbers lifted out, or null when it has none. */
private fun numberPattern(s: String): Pair<String, List<String>>? {
    val numbers = mutableListOf<String>()
    val pattern = NUMBER.replace(s) { m ->
        numbers.add(m.value)
        "{${numbers.size - 1}}"
    }
    return if (numbers.isEmpty()) null else pattern to numbers
}

/** Template entries compiled once: "{}: deadline is not set" becomes a regex. */
private val templateCache = HashMap<Lang, List<Pair<Regex, String>>>()

private fun templatesFor(lang: Lang): List<Pair<Regex, String>> = synchronized(templateCache) {
    templateCache.getOrPut(lang) {
        val table = tableFor(lang) ?: return@getOrPut emptyList()
        table.keys
            .filter { it.contains("{}") }
            // Longest first, so a specific template is preferred over a loose one.
            .sortedByDescending { it.length }
            .map { key ->
                val body = key.split("{}").joinToString("(.+?)") { Regex.escape(it) }
                Regex(body) to key
            }
    }
}

private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

/** Persian text reads with Persian digits; English keeps ASCII ones. */
fun localiseDigits(lang: Lang, s: String): String {
    if (!lang.rtl) return s
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c)
    return sb.toString()
}

/** Weekday names; the engine holds only the English abbreviations. */
fun weekday(lang: Lang, dow: Int): String =
    if (lang == Lang.Persian) PERSIAN_DOW.getOrElse(dow) { "" }
    else com.chronos.engine.Slots.DOW.getOrElse(dow) { "" }

private val PERSIAN_DOW =
    arrayOf("شنبه", "یک‌شنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه")

private val PERSIAN_MONTHS = arrayOf(
    "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
    "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")

private val PERSIAN_GREGORIAN_MONTHS = arrayOf(
    "ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن",
    "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر")

/**
 * Both calendars, the way each language writes them. [com.chronos.engine.Jalali]
 * transliterates the Persian month names for an English reader; in Persian they
 * are written in Persian, which is the whole point of showing both.
 */
fun formatDate(lang: Lang, date: java.time.LocalDate): String {
    if (lang != Lang.Persian) return com.chronos.engine.Jalali.both(date)
    val j = com.chronos.engine.Jalali.of(date)
    val dow = weekday(lang, com.chronos.engine.Slots.dow(date))
    return "$dow ${j.day()} ${PERSIAN_MONTHS[j.month() - 1]} ${j.year()}" +
        " / ${date.dayOfMonth} ${PERSIAN_GREGORIAN_MONTHS[date.monthValue - 1]} ${date.year}"
}

/** The date of a day, in the language the interface is in. */
@Composable
@ReadOnlyComposable
fun dateText(date: java.time.LocalDate): String = formatDate(LocalLang.current, date)

/**
 * Material's Text with the string translated first. Same signature, so nothing
 * at the call sites changes.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current
) {
    val lang = LocalLang.current
    androidx.compose.material3.Text(
        text = localiseDigits(lang, translate(lang, text)),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        style = style
    )
}
