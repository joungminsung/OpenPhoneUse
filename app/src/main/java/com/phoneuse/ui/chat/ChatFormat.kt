package com.phoneuse.ui.chat

import com.phoneuse.i18n.tr
import com.phoneuse.chat.Conversation
import com.phoneuse.i18n.Lang
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val WEEKDAYS_KO = arrayOf("월", "화", "수", "목", "금", "토", "일")
private val WEEKDAYS_EN = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WEEKDAYS get() = if (Lang.korean) WEEKDAYS_KO else WEEKDAYS_EN

private val HOME_DATE_EN = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.ENGLISH)
private val MONTH_DAY_EN = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

/** "gpt-6.1-terra" → "GPT-6.1 Terra"; ids that are not `gpt-<version>-…` are shown as they are. */
fun modelDisplayName(id: String): String {
    val parts = id.trim().split('-').filter { it.isNotEmpty() }
    // "claude-opus-5-5" → "Claude Opus 5.5" (version numbers follow the family name).
    if (parts.size >= 3 && parts[0].equals("claude", ignoreCase = true) && parts.drop(2).all { p -> p.all(Char::isDigit) }) {
        return "Claude " + parts[1].replaceFirstChar { it.uppercase() } + " " + parts.drop(2).joinToString(".")
    }
    if (parts.size < 2 || !parts[0].equals("gpt", ignoreCase = true)) return id.trim()
    val words = parts.drop(2).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    return "GPT-${parts[1]}" + if (words.isEmpty()) "" else " $words"
}

/** The short name in the top-bar chip: "gpt-6.1-terra" → "Terra". */
fun modelChipName(id: String): String {
    val display = modelDisplayName(id)
    if (display.startsWith("Claude ")) return display.removePrefix("Claude ")
    if (!display.startsWith("GPT-")) return display
    val space = display.indexOf(' ')
    return if (space < 0) display else display.substring(space + 1)
}

private fun zoned(millis: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)

private fun daysBetween(then: Long, now: Long, zone: ZoneId): Long =
    ChronoUnit.DAYS.between(zoned(then, zone).toLocalDate(), zoned(now, zone).toLocalDate())

/** Home header line, e.g. "11.01 일 · 저녁 7:40". */
fun homeDateLine(now: Long, zone: ZoneId): String {
    val t = zoned(now, zone)
    if (!Lang.korean) return HOME_DATE_EN.format(t)
    val period = when (t.hour) {
        in 0..5 -> "새벽"
        in 6..11 -> "오전"
        in 12..17 -> "오후"
        else -> "저녁"
    }
    val h12 = (t.hour % 12).let { if (it == 0) 12 else it }
    return "%02d.%02d %s · %s %d:%02d".format(
        t.monthValue, t.dayOfMonth, WEEKDAYS[t.dayOfWeek.value - 1], period, h12, t.minute,
    )
}

/** Drawer sections in order: 오늘, 어제, 지난 7일, 이전; empty sections are left out. Keeps the input order inside each. */
fun groupConversations(list: List<Conversation>, now: Long, zone: ZoneId): List<Pair<String, List<Conversation>>> {
    val labels = listOf(tr("Today", "오늘"), tr("Yesterday", "어제"), tr("Previous 7 days", "지난 7일"), tr("Older", "이전"))
    val buckets = List(labels.size) { mutableListOf<Conversation>() }
    for (c in list) {
        val days = daysBetween(c.updatedAt, now, zone)
        val index = when {
            days <= 0 -> 0
            days == 1L -> 1
            days < 7 -> 2
            else -> 3
        }
        buckets[index] += c
    }
    return labels.indices.filter { buckets[it].isNotEmpty() }.map { labels[it] to buckets[it].toList() }
}

/** Drawer row time: "19:20" today, a weekday within the last 7 days, otherwise "10.25". */
fun conversationTime(updatedAt: Long, now: Long, zone: ZoneId): String {
    val t = zoned(updatedAt, zone)
    val days = daysBetween(updatedAt, now, zone)
    return when {
        days <= 0 -> "%02d:%02d".format(t.hour, t.minute)
        days < 7 -> WEEKDAYS[t.dayOfWeek.value - 1]
        else -> monthDay(t.toLocalDate())
    }
}

/** "이어서" row time: "19:20" today, "어제", "6일 전", otherwise "10.25". */
fun relativeDay(updatedAt: Long, now: Long, zone: ZoneId): String {
    val t = zoned(updatedAt, zone)
    val days = daysBetween(updatedAt, now, zone)
    return when {
        days <= 0 -> "%02d:%02d".format(t.hour, t.minute)
        days == 1L -> tr("Yesterday", "어제")
        days < 7 -> tr("${days}d ago", "${days}일 전")
        else -> monthDay(t.toLocalDate())
    }
}

private fun monthDay(d: LocalDate) = if (Lang.korean) "${d.monthValue}.${d.dayOfMonth}" else MONTH_DAY_EN.format(d)
