package com.phoneuse.proactive

import com.phoneuse.i18n.Lang
import com.phoneuse.i18n.tr
import com.phoneuse.memory.MemoryKind
import com.phoneuse.phone.DigestBucket
import com.phoneuse.phone.DigestClassifier
import com.phoneuse.phone.StoredNotification
import com.phoneuse.routine.UpcomingEvent
import com.phoneuse.today.TodaySummary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * "알아서 챙기기": which nudges to show now. Pure; the Android side reads [NudgeSources], posts what [plan] returns
 * and logs it. Rules: nothing during the quiet hours (a nudge waits for them to end while it has not expired), at
 * most [NudgeSettings.maxPerDay] a day, a dedupe key only once, and a kind the user ignored three times in a row
 * rests for a week. Nudge text is our own wording plus parsed fields; button prompts never quote third-party text.
 */
object ProactiveEngine {
    const val CHECK_EVERY_MS = 15 * 60_000L
    const val IGNORES_TO_PAUSE = 3
    const val PAUSE_MS = 7 * DAY_MS
    const val REPLY_AFTER_MS = 3 * HOUR_MS
    const val LEAVE_LOOKAHEAD_MS = 2 * HOUR_MS
    const val LEAVE_NOTICE_MS = 10 * 60_000L
    const val LEAVE_BUFFER_MS = 5 * 60_000L
    const val UNUSUAL_FACTOR = 3
    const val ROUTINE_ASKS = 3
    const val ANNIVERSARY_DAYS_BEFORE = 3L
    val SPENDING_AT: LocalTime = LocalTime.of(21, 0)
    val ANNIVERSARY_BEFORE_AT: LocalTime = LocalTime.of(9, 0)

    /** Who wins when the day's cap is near: time-critical first. */
    private val PRIORITY = listOf(
        NudgeKind.LEAVE, NudgeKind.UNUSUAL_PAYMENT, NudgeKind.REPLY, NudgeKind.TODO,
        NudgeKind.ANNIVERSARY, NudgeKind.BRIEFING, NudgeKind.DELIVERY, NudgeKind.ROUTINE,
    )

    fun plan(now: ZonedDateTime, sources: NudgeSources, settings: NudgeSettings, sent: List<SentNudge>): List<Nudge> {
        if (settings.isQuiet(now.toLocalTime())) return emptyList()
        val nowMs = now.toInstant().toEpochMilli()
        val room = settings.maxPerDay - sentOn(now.toLocalDate(), now.zone, sent)
        if (room <= 0) return emptyList()
        val seen = sent.mapTo(HashSet()) { it.dedupeKey }
        val paused = pausedKinds(sent, nowMs)
        return candidates(now, sources, settings)
            .filter { it.kind in settings.enabled && it.kind !in paused && it.dedupeKey !in seen && it.dueAt <= nowMs && nowMs < it.expiresAt }
            .distinctBy { it.dedupeKey }
            .sortedWith(compareBy<Nudge>({ PRIORITY.indexOf(it.kind) }, { it.dueAt }))
            .take(room)
    }

    /** Kinds whose time matters to the minute: their check alarm is exact (RoutineScheduler.setProactive). */
    val EXACT_KINDS = setOf(NudgeKind.LEAVE, NudgeKind.BRIEFING)

    /** How far ahead an [EXACT_KINDS] nudge takes over the alarm, so a late inexact poll cannot skip past it. */
    const val EXACT_WINDOW_MS = 2 * CHECK_EVERY_MS

    /** The next check: [at] in epoch ms; [exact] when it is an [EXACT_KINDS] nudge's due time. */
    data class NextCheck(val at: Long, val exact: Boolean)

    /** The first retry of a missed exact due time; each further failure doubles it. */
    const val RETRY_EXACT_MS = 60_000L

    /**
     * The alarm after a check that failed or ran out of time, [failures] being the failed checks before this one: the
     * [last] computed exact due time while it is still ahead (a leave alarm stays exact); once it has passed, exact
     * retries backing off 1, 2, 4, 8 minutes, then the inexact poll (never a retry every minute for ever).
     */
    fun fallbackCheck(last: NextCheck?, nowMs: Long, failures: Int = 0): NextCheck {
        val poll = NextCheck(nowMs + CHECK_EVERY_MS, exact = false)
        if (last == null) return poll
        val backoff = RETRY_EXACT_MS shl failures.coerceIn(0, 10)
        return when {
            last.exact && last.at > nowMs -> last
            last.exact && backoff < CHECK_EVERY_MS -> NextCheck(nowMs + backoff, exact = true)
            last.exact -> poll
            last.at in (nowMs + 1) until poll.at -> last
            else -> poll
        }
    }

    /**
     * When to look again: in [CHECK_EVERY_MS] at the latest, earlier when a nudge falls due. A leave time or the
     * briefing within [EXACT_WINDOW_MS] gets an exact alarm at its due time. Kinds that are off or resting, and due
     * times inside the quiet hours (they wait for the hours to end anyway), do not wake the phone.
     */
    fun nextCheck(now: ZonedDateTime, sources: NudgeSources, settings: NudgeSettings, sent: List<SentNudge>): NextCheck {
        val nowMs = now.toInstant().toEpochMilli()
        val seen = sent.mapTo(HashSet()) { it.dedupeKey }
        val paused = pausedKinds(sent, nowMs)
        val upcoming = candidates(now, sources, settings).filter {
            it.kind in settings.enabled && it.kind !in paused && it.dedupeKey !in seen && it.dueAt > nowMs && it.dueAt < it.expiresAt &&
                !settings.isQuiet(local(it.dueAt, now.zone).toLocalTime())
        }
        val soonest = upcoming.minOfOrNull { it.dueAt } ?: Long.MAX_VALUE
        // An exact alarm, once one is needed, also covers whatever falls due before it.
        val exact = upcoming.filter { it.kind in EXACT_KINDS && it.dueAt - nowMs <= EXACT_WINDOW_MS }.minOfOrNull { it.dueAt }
        if (exact != null) return NextCheck(minOf(exact, soonest), true)
        return NextCheck(minOf(nowMs + CHECK_EVERY_MS, soonest), false)
    }

    /** Events the leave nudge needs a travel time for: with a place, timed, starting within two hours. */
    fun needsTravel(nowMs: Long, events: List<UpcomingEvent>): List<UpcomingEvent> =
        events.filter { !it.allDay && !it.location.isNullOrBlank() && it.startsAt > nowMs && it.startsAt - nowMs <= LEAVE_LOOKAHEAD_MS }

    fun sentOn(day: LocalDate, zone: ZoneId, sent: List<SentNudge>): Int =
        sent.count { Instant.ofEpochMilli(it.sentAt).atZone(zone).toLocalDate() == day }

    /**
     * Kinds resting now: three ignored in a row (opened ones reset the count) pause the kind for [PAUSE_MS] from
     * the third; the count starts over after a pause.
     */
    fun pausedKinds(sent: List<SentNudge>, nowMs: Long): Set<NudgeKind> {
        val out = HashSet<NudgeKind>()
        for ((kind, list) in sent.groupBy { it.kind }) {
            var streak = 0
            var until = Long.MIN_VALUE
            for (s in list.sortedBy { it.sentAt }) {
                when (s.outcome) {
                    NudgeOutcome.OPENED -> streak = 0
                    NudgeOutcome.IGNORED -> {
                        streak++
                        if (streak >= IGNORES_TO_PAUSE) {
                            until = (s.resolvedAt ?: s.sentAt) + PAUSE_MS
                            streak = 0
                        }
                    }
                    NudgeOutcome.PENDING -> Unit
                }
            }
            if (until > nowMs) out += kind
        }
        return out
    }

    /** Every nudge the sources call for today, due or not, before the settings, the log and the cap. */
    fun candidates(now: ZonedDateTime, sources: NudgeSources, settings: NudgeSettings): List<Nudge> = buildList {
        addAll(briefing(now, sources, settings))
        addAll(leave(now, sources))
        addAll(reply(now, sources))
        addAll(anniversary(now, sources, settings))
        addAll(delivery(now, sources))
        addAll(unusualPayment(now, sources))
        addAll(todo(now, sources, settings))
        addAll(routine(now, sources))
    }

    // ---- the kinds -------------------------------------------------------------------------------

    private fun briefing(now: ZonedDateTime, s: NudgeSources, settings: NudgeSettings): List<Nudge> {
        val day = now.toLocalDate()
        val at = ms(day.atTime(settings.briefingAt), now.zone)
        val nowMs = ms(now)
        val todayEvents = s.events.filter { !it.allDay && local(it.startsAt, now.zone).toLocalDate() == day && it.startsAt >= nowMs }
            .sortedBy { it.startsAt }
        val parcels = s.parcels.count { (it.status == "배송 출발" || it.status == "도착") && nowMs - it.at < 18 * HOUR_MS }
        val overnightFrom = ms(day.minusDays(1).atTime(settings.quietStart), now.zone)
        val replies = s.notifications.count { it.postedAt >= overnightFrom && needsReply(it) }
        val body = BriefingText.body(todayEvents, parcels, replies, now.zone)
        val actions = buildList {
            add(NudgeAction.prompt(tr("Today at a glance", "오늘 한눈에"), BriefingText.TODAY_PROMPT))
            if (replies > 0) add(NudgeAction.prompt(tr("See replies", "답장 보기"), BriefingText.REPLY_PROMPT))
        }
        return listOf(Nudge(NudgeKind.BRIEFING, BriefingText.TITLE, body, actions, at, at + 3 * HOUR_MS + 30 * 60_000L, "briefing:$day"))
    }

    private fun leave(now: ZonedDateTime, s: NudgeSources): List<Nudge> = s.events.mapNotNull { e ->
        if (e.allDay || e.location.isNullOrBlank()) return@mapNotNull null
        val est = s.travel[NudgeSources.leaveKey(e)] ?: return@mapNotNull null
        val travelMs = est.minutes * 60_000L
        val depart = e.startsAt - travelMs - LEAVE_BUFFER_MS
        val place = e.location.trim().take(30)
        Nudge(
            NudgeKind.LEAVE,
            tr(
                "Leave at ${hm(depart, now.zone)} to reach ${e.title.trim().take(20)} by ${hm(depart + travelMs, now.zone)}",
                "${hm(depart, now.zone)}에 나가면 ${e.title.trim().take(20)} ${hm(depart + travelMs, now.zone)} 도착",
            ),
            tr("About ${est.minutes} min to $place · estimated from the distance", "${place}까지 약 ${est.minutes}분 · 거리로 어림한 시간이에요"),
            listOf(
                NudgeAction(tr("Map app", "지도 앱"), lat = est.lat, lng = est.lng, place = place),
                NudgeAction.prompt(
                    tr("Send \"10 min late\"", "10분 늦는다고 보내기"),
                    tr("Write a message saying I'll be 10 minutes late to the ${hm(e.startsAt, now.zone)} event", "${hm(e.startsAt, now.zone)} 일정에 10분 늦는다고 알릴 메시지 써줘"),
                ),
            ),
            dueAt = depart - LEAVE_NOTICE_MS,
            expiresAt = e.startsAt,
            dedupeKey = "leave:${NudgeSources.leaveKey(e)}",
        )
    }

    private fun reply(now: ZonedDateTime, s: NudgeSources): List<Nudge> {
        val nowMs = ms(now)
        return s.notifications.filter { needsReply(it) && nowMs - it.postedAt < DAY_MS }.map { n ->
            val who = n.title.trim().ifBlank { n.appLabel }.take(20)
            val at = hm(n.postedAt, now.zone)
            Nudge(
                NudgeKind.REPLY,
                tr("You haven't replied to $who yet", "${who}님에게 아직 답장 안 했어요"),
                tr("${n.appLabel.take(20)} · message from $at", "${n.appLabel.take(20)} · ${at}에 온 메시지"),
                listOf(
                    NudgeAction.prompt(
                        tr("Write reply", "답장 쓰기"),
                        tr("Draft a reply to the 1:1 message that arrived in ${n.appLabel.take(20)} at $at", "${n.appLabel.take(20)}에서 ${at}에 온 1:1 메시지에 보낼 답장 초안 써줘"),
                    ),
                ),
                dueAt = n.postedAt + REPLY_AFTER_MS,
                expiresAt = n.postedAt + DAY_MS,
                dedupeKey = "reply:${n.key}:${local(n.postedAt, now.zone).toLocalDate()}",
            )
        }
    }

    private fun anniversary(now: ZonedDateTime, s: NudgeSources, settings: NudgeSettings): List<Nudge> {
        val today = now.toLocalDate()
        return MemoryDates.usable(s.memories).filter { it.kind == MemoryKind.PERSON }.flatMap { m ->
            val md = MemoryDates.monthDay(m) ?: return@flatMap emptyList()
            val day = MemoryDates.nextOn(md, today)
            val text = m.text.trim().take(MEMORY_CHARS)
            val dayText = dayText(day)
            buildList {
                val before = day.minusDays(ANNIVERSARY_DAYS_BEFORE)
                if (before == today) {
                    val at = ms(today.atTime(ANNIVERSARY_BEFORE_AT), now.zone)
                    add(
                        Nudge(
                            NudgeKind.ANNIVERSARY, tr("3 days to go · $dayText", "기념일이 3일 남았어요 · $dayText"), text,
                            listOf(NudgeAction.prompt(tr("Pick a gift", "선물 고르기"), tr("$text — help me pick a gift", "$text — 선물 고르는 것 도와줘")), NudgeAction.later()),
                            at, endOf(today, now.zone), "anniversary:${m.id}:$day:before",
                        ),
                    )
                }
                if (day == today) {
                    val at = ms(today.atTime(settings.briefingAt), now.zone)
                    add(
                        Nudge(
                            NudgeKind.ANNIVERSARY, tr("It's today · $dayText", "오늘이에요 · $dayText"), text,
                            listOf(NudgeAction.prompt(tr("Draft greeting", "축하 문자 초안"), tr("$text — it's today. Draft a greeting message", "$text — 오늘이야. 축하 문자 초안 써줘")), NudgeAction.later()),
                            at, endOf(today, now.zone), "anniversary:${m.id}:$day:day",
                        ),
                    )
                }
            }
        }
    }

    private fun delivery(now: ZonedDateTime, s: NudgeSources): List<Nudge> = buildList {
        val nowMs = ms(now)
        for (p in s.parcels) {
            if (p.status != "배송 출발" && p.status != "도착") continue
            if (nowMs - p.at > 12 * HOUR_MS) continue
            val arrived = p.status == "도착"
            add(
                Nudge(
                    NudgeKind.DELIVERY,
                    if (arrived) tr("Your parcel has arrived", "택배가 도착했어요") else tr("Your parcel arrives today", "택배가 오늘 와요"),
                    "${p.carrier} · ${TodaySummary.stageLabel(p.status)}",
                    if (arrived) emptyList() else listOf(NudgeAction.prompt(tr("Where is it?", "어디쯤 왔어?"), PARCEL_PROMPT)),
                    p.at, p.at + 12 * HOUR_MS, "parcel:${p.tracking ?: p.key}:${p.status}",
                ),
            )
        }
        val day = now.toLocalDate()
        val today = s.payments.filter { local(it.at, now.zone).toLocalDate() == day }
        if (today.isNotEmpty()) {
            val at = ms(day.atTime(SPENDING_AT), now.zone)
            val top = today.groupBy { it.merchant }.mapValues { (_, v) -> v.sumOf { it.amount } }.entries
                .sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key.take(16)} ${won(it.value)}" }
            add(
                Nudge(
                    NudgeKind.DELIVERY,
                    tr("${today.size} ${if (today.size == 1) "payment" else "payments"} today · ${won(today.sumOf { it.amount })}", "오늘 결제 ${today.size}건 · ${won(today.sumOf { it.amount })}"),
                    top,
                    listOf(NudgeAction.prompt(tr("See details", "내역 보기"), tr("Summarize today's payments", "오늘 결제 내역 정리해줘"))),
                    at, endOf(day, now.zone), "spending:$day",
                ),
            )
        }
    }

    private fun unusualPayment(now: ZonedDateTime, s: NudgeSources): List<Nudge> {
        val nowMs = ms(now)
        return s.payments.filter { nowMs - it.at in 0 until 12 * HOUR_MS }.mapNotNull { p ->
            val usual = s.payments.filter { it.merchant == p.merchant && it.at < p.at && p.at - it.at <= 30 * DAY_MS }
            if (usual.isEmpty()) return@mapNotNull null
            val average = usual.sumOf { it.amount } / usual.size
            if (p.amount <= UNUSUAL_FACTOR * average) return@mapNotNull null
            Nudge(
                NudgeKind.UNUSUAL_PAYMENT, tr("Larger payment than usual", "평소보다 큰 결제예요"),
                tr("${p.merchant.take(16)} ${won(p.amount)} · usually ${won(average)}", "${p.merchant.take(16)} ${won(p.amount)} · 평소 ${won(average)}"),
                listOf(NudgeAction.prompt(tr("See payments", "결제 내역 보기"), tr("Check my recent payments", "최근 결제 내역 확인해줘"))),
                p.at, p.at + 12 * HOUR_MS, "payment:${p.merchant}:${p.at}:${p.amount}",
            )
        }
    }

    private fun todo(now: ZonedDateTime, s: NudgeSources, settings: NudgeSettings): List<Nudge> {
        val today = now.toLocalDate()
        return MemoryDates.usable(s.memories).filter { it.kind == MemoryKind.TODO }.mapNotNull { m ->
            if (MemoryDates.due(m, now.zone) != today) return@mapNotNull null
            val text = m.text.trim().take(MEMORY_CHARS)
            Nudge(
                NudgeKind.TODO, tr("Something is due today", "오늘까지 할 일이 있어요"), text,
                listOf(NudgeAction.prompt(tr("Do it together", "같이 하기"), tr("$text — it's due today. Help me", "$text — 오늘까지야. 도와줘")), NudgeAction.later()),
                ms(today.atTime(settings.briefingAt), now.zone), endOf(today, now.zone), "todo:${m.id}:$today",
            )
        }
    }

    private fun routine(now: ZonedDateTime, s: NudgeSources): List<Nudge> {
        val nowMs = ms(now)
        val recent = s.asks.filter { nowMs - it.at in 0 until 7 * DAY_MS && AskKinds.normalize(it.text).isNotEmpty() }
        return recent.groupBy { AskKinds.normalize(it.text) to AskKinds.band(local(it.at, now.zone).hour) }
            .filter { it.value.size >= ROUTINE_ASKS }
            .map { (key, asks) ->
                val latest = asks.maxBy { it.at }
                val sentence = AskKinds.firstSentence(latest.text).take(40)
                val hours = asks.map { local(it.at, now.zone).hour }.sorted()
                val hour = hours[hours.size / 2]
                Nudge(
                    NudgeKind.ROUTINE, tr("A request you make often", "자주 하는 부탁이에요"),
                    tr("“$sentence” · ${asks.size} times this week, ${bandEn(key.second)}", "“$sentence” · 이번 주 ${asks.size}번, ${key.second}에"),
                    listOf(
                        NudgeAction.prompt(
                            tr("Make it a routine", "루틴으로 만들기"),
                            tr("${BriefingText.ROUTINE_PROMPT_EN} at ${"%02d".format(hour)}:00: $sentence", "루틴 만들어줘: 매일 ${hour}시에 $sentence"),
                        ),
                    ),
                    latest.at, latest.at + 7 * DAY_MS, "routine:${key.first}:${key.second}",
                )
            }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** A 1:1 message still in the shade: one that was read, answered or swiped away (removedAt) needs no nudge. */
    private fun needsReply(n: StoredNotification) = n.removedAt == null && DigestClassifier.classify(n) == DigestBucket.REPLY

    const val PARCEL_PROMPT_KO = "오늘 오는 택배 어디쯤 왔어?"
    const val PARCEL_PROMPT_EN = "Where are the parcels arriving today?"
    val PARCEL_PROMPT: String get() = tr(PARCEL_PROMPT_EN, PARCEL_PROMPT_KO)
    private const val MEMORY_CHARS = 60

    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    private fun ms(t: java.time.LocalDateTime, zone: ZoneId) = t.atZone(zone).toInstant().toEpochMilli()

    private fun endOf(day: LocalDate, zone: ZoneId) = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun local(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone)

    fun hm(at: Long, zone: ZoneId): String = local(at, zone).let { "%02d:%02d".format(it.hour, it.minute) }

    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)

    /** "11월 5일 목요일", or "Thu, Nov 5". */
    private fun dayText(day: LocalDate): String =
        if (Lang.korean) "${day.monthValue}월 ${day.dayOfMonth}일 ${weekday(day)}"
        else day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH))

    /** AskKinds.band (kept Korean in the data) as "in the morning". */
    private fun bandEn(band: String): String = when (band) {
        "아침" -> "in the morning"
        "낮" -> "in the afternoon"
        "저녁" -> "in the evening"
        else -> "at night"
    }

    /** "₩48,300" */
    fun won(amount: Long): String = "₩" + "%,d".format(Locale.US, amount)
}

/** The briefing's own text (the weather line is added later by the one model call, when it works). */
object BriefingText {
    val TITLE: String get() = tr("Good morning", "좋은 아침이에요")
    const val TODAY_PROMPT_KO = "오늘 날씨, 일정, 택배, 밤사이 알림 한눈에 정리해줘"
    const val TODAY_PROMPT_EN = "Give me today's weather, schedule, parcels and overnight notifications at a glance"
    const val REPLY_PROMPT_KO = "답장 필요한 알림 요약해줘"
    const val REPLY_PROMPT_EN = "Summarize the notifications that need a reply"
    const val ROUTINE_PROMPT_EN = "Make a routine: every day"
    val TODAY_PROMPT: String get() = tr(TODAY_PROMPT_EN, TODAY_PROMPT_KO)
    val REPLY_PROMPT: String get() = tr(REPLY_PROMPT_EN, REPLY_PROMPT_KO)

    /** "10:00 팀 회의, 15:00 치과. 택배 1개 오늘 도착. 밤사이 답장 필요한 메시지 2개." */
    fun body(events: List<UpcomingEvent>, parcels: Int, replies: Int, zone: ZoneId): String {
        val parts = ArrayList<String>()
        if (events.isEmpty()) {
            parts += tr("Nothing left on your schedule today.", "오늘 남은 일정은 없어요.")
        } else {
            val shown = events.take(2).joinToString(", ") { "${ProactiveEngine.hm(it.startsAt, zone)} ${it.title.trim().take(16)}" }
            parts += if (events.size > 2) tr("$shown and ${events.size - 2} more.", "$shown 외 ${events.size - 2}개.") else "$shown."
        }
        if (parcels > 0) parts += tr(if (parcels == 1) "1 parcel arriving today." else "$parcels parcels arriving today.", "택배 ${parcels}개 오늘 도착.")
        if (replies > 0) parts += tr(if (replies == 1) "1 message needs a reply from overnight." else "$replies messages need a reply from overnight.", "밤사이 답장 필요한 메시지 ${replies}개.")
        return parts.joinToString(" ")
    }

    /**
     * The one model call of the briefing: today's weather with the hosted web search, from the city and district
     * only. Nothing from notifications, messages or the calendar goes in, and the answer is only shown (cleaned by
     * [weatherLine]); it never enters a conversation, so the web text cannot steer anything.
     */
    val WEATHER_INSTRUCTIONS: String get() = if (Lang.korean) {
        "You write one short Korean line about today's weather for a phone notification: the sky, the low→high " +
            "temperature in °C and whether an umbrella is needed, e.g. \"맑음 4°→13°, 우산 필요 없어요\". " +
            "At most 40 characters. No links, no sources, no other text. Search the web for today's forecast."
    } else {
        "You write one short English line about today's weather for a phone notification: the sky, the low→high " +
            "temperature in °C and whether an umbrella is needed, e.g. \"Sunny 4°→13°, no umbrella needed\". " +
            "At most 40 characters. No links, no sources, no other text. Search the web for today's forecast."
    }

    fun weatherAsk(area: String, day: java.time.LocalDate) = "지역: $area\n날짜: $day"

    /** A coordinate rounded to 0.01° (about a kilometre) before the reverse lookup: only the district is needed. */
    fun coarse(degrees: Double): Double = Math.round(degrees * 100) / 100.0

    /** "역삼동, 강남구, 서울특별시, 06236, 대한민국" → "서울특별시 강남구": city and district only; null when none. */
    fun area(displayName: String?): String? {
        val parts = displayName.orEmpty().split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it.none(Char::isDigit) && it != "대한민국" && it != "South Korea" }
        val coarse = parts.filter { it.endsWith("구") || it.endsWith("군") || it.endsWith("시") || it.endsWith("도") }
        return coarse.take(2).reversed().joinToString(" ").takeIf { it.isNotBlank() }
    }

    private val LINK = Regex("""\(?\[[^\]]*]\([^)]*\)\)?|https?://\S+""")
    private val MARKUP = Regex("""[*#`_>]""")

    /** The model's answer as a notification title: first line, no links or markup, at most 60 characters. */
    fun weatherLine(raw: String?): String? = raw.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?.replace(LINK, "")?.replace(MARKUP, "")?.replace(Regex("\\s+"), " ")?.trim()?.take(60)?.takeIf { it.isNotEmpty() }
}

private const val HOUR_MS = 60 * 60_000L
private const val DAY_MS = 24 * HOUR_MS
