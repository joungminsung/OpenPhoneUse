package com.phoneuse.ui.settings

import com.phoneuse.i18n.tr
import com.phoneuse.app.AssistAction
import com.phoneuse.app.ControlSpeed
import com.phoneuse.app.FontSize
import com.phoneuse.app.QuietHours
import com.phoneuse.app.ScreenContent
import com.phoneuse.app.UnfoldedLayout
import com.phoneuse.app.VoiceLanguage

/** The settings that open a choice dialog. */
enum class Choice { RETENTION, ASSIST, SPEED, SUMMARY, QUIET, FONT, UNFOLDED, VOICE, SCREEN }

val RETENTION_LABELS get() = listOf(30 to tr("30 days", "30일"), 90 to tr("90 days", "90일"), 365 to tr("1 year", "1년"), 0 to tr("Forever", "무기한"))
val ASSIST_LABELS get() = listOf(AssistAction.SUGGEST to tr("Ideas for this screen", "이 화면으로 할 일"), AssistAction.TYPE to tr("Talk right away", "바로 말하기"))
val SPEED_LABELS get() = listOf(ControlSpeed.FAST to tr("Fast", "빠르게"), ControlSpeed.NORMAL to tr("Normal", "보통"), ControlSpeed.SLOW to tr("Slow", "천천히"))
val FONT_LABELS get() = listOf(FontSize.DEFAULT to tr("Default", "기본"), FontSize.LARGE to tr("Large", "크게"), FontSize.LARGEST to tr("Largest", "아주 크게"))
val UNFOLDED_LABELS get() = listOf(UnfoldedLayout.SIDE_BY_SIDE to tr("Chat + result side by side", "대화 + 결과 나란히"), UnfoldedLayout.CHAT_ONLY to tr("Chat only", "대화만"))
val VOICE_LABELS get() = listOf(VoiceLanguage.KOREAN to "한국어", VoiceLanguage.ENGLISH to "English", VoiceLanguage.SYSTEM to tr("Device language", "기기 언어"))
val SCREEN_LABELS get() = listOf(ScreenContent.ALL to tr("Everything", "전부"), ScreenContent.TEXT_ONLY to tr("Text only", "글자만"))

/** "오늘 요약 받기" times (minutes of the day); -1 = off. */
val SUMMARY_OPTIONS = listOf(-1, 7 * 60, 8 * 60, 12 * 60, 18 * 60, 20 * 60, 21 * 60, 22 * 60)

/** "방해하지 않는 시간" spans; the first is off. */
val QUIET_OPTIONS = listOf(
    QuietHours(on = false),
    QuietHours(true, 22 * 60, 7 * 60),
    QuietHours(true, 23 * 60, 7 * 60),
    QuietHours(true, 23 * 60, 6 * 60),
    QuietHours(true, 0, 8 * 60),
)

fun <T> label(options: List<Pair<T, String>>, value: T): String = options.firstOrNull { it.first == value }?.second.orEmpty()

private fun hhmm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

fun summaryLabel(minute: Int): String = if (minute < 0) tr("Off", "끄기") else hhmm(minute)

/** "23:00–07:00", or "끄기". */
fun quietLabel(q: QuietHours): String = if (!q.on) tr("Off", "끄기") else "${hhmm(q.startMin)}–${hhmm(q.endMin)}"
