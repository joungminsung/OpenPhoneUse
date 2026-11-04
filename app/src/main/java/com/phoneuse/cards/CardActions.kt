package com.phoneuse.cards

import com.phoneuse.i18n.tr

/**
 * What a card button does. The app runs [Dial], [Navigate], [OpenUrl], [CopyText] and [RequestPermission] itself; anything that
 * needs the agent (saving an event, sending, making an album, a follow-up) is a [Prompt] sent as a user
 * message to the same conversation, so it goes through the usual confirmation gates.
 *
 * Prompt text never quotes card content that may come from third parties (notifications, messages, web
 * pages, place names): items are named by their position, which the conversation history lists
 * (CardJson.summary). Prompts are also sent as card-originated, so they never authorize a send by themselves.
 */
sealed interface CardAction {
    data class Dial(val phone: String) : CardAction
    data class Navigate(val lat: Double, val lng: Double, val name: String) : CardAction
    data class OpenUrl(val url: String) : CardAction
    data class CopyText(val text: String) : CardAction
    data class Prompt(val text: String) : CardAction

    /** Opens the share sheet with these photos (MediaStore URIs, checked again when run). */
    data class SharePhotos(val uris: List<String>) : CardAction

    /**
     * Asks for runtime [permissions] from the Activity (the [권한 허용] card); [label] names them in Korean.
     * [settings] is PermissionCard.settings: the page opened when they cannot be asked for.
     */
    data class RequestPermission(val permissions: List<String>, val label: String, val settings: String? = null) : CardAction
}

/** The buttons of each card, kept out of the UI so the wording and targets are testable. */
object CardActions {
    private const val PREPARE_MINUTES = 7

    fun forPlace(place: Place): List<CardAction> = buildList {
        place.phone?.takeIf { it.isNotBlank() }?.let { add(CardAction.Dial(it)) }
        add(CardAction.Navigate(place.lat, place.lng, place.name))
    }

    /** Opens the destination in a map app; without coordinates, a web map search for its name. */
    fun openRoute(card: RouteCard): CardAction {
        val d = card.dest
        return if (d.lat != null && d.lng != null) {
            CardAction.Navigate(d.lat, d.lng, d.name)
        } else {
            CardAction.OpenUrl("https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(d.name, "UTF-8"))
        }
    }

    /** HH:mm a few minutes before [departAt], wrapping past midnight; a malformed time (old stored card) comes back unchanged. */
    fun prepareAt(departAt: String): String {
        if (!isClock(departAt)) return departAt.trim()
        val (h, m) = departAt.trim().split(":").map { it.toInt() }
        val total = Math.floorMod(h * 60 + m - PREPARE_MINUTES, 24 * 60)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    fun routeReminder(card: RouteCard) = CardAction.Prompt(tr("Remind me to get ready to leave at ${prepareAt(card.departAt)}", "${prepareAt(card.departAt)}에 나갈 준비 알림 맞춰줘"))

    fun shareArrival(card: RouteCard): CardAction.Prompt {
        val at = card.arriveAt.takeIf { isClock(it) }?.let { "($it)" }.orEmpty()
        return CardAction.Prompt(tr("Write a text telling them my arrival time for this route$at", "이 경로의 도착 시간${at}을 알릴 문자 써줘"))
    }

    fun saveEvent() = CardAction.Prompt(tr(SAVE_EVENT_EN, SAVE_EVENT_KO))

    /** The save button's prompt in either language, for the confirm gate. */
    val saveEventTexts: Set<String> get() = setOf(SAVE_EVENT_EN, SAVE_EVENT_KO)

    private const val SAVE_EVENT_EN = "Save this event as is"
    private const val SAVE_EVENT_KO = "이 일정 그대로 저장해줘"

    fun editEvent() = CardAction.Prompt(tr("I want to change this event", "이 일정 고칠래"))

    fun findFreeTime() = CardAction.Prompt(tr("Find a free time that does not overlap this event", "이 일정과 겹치지 않는 비는 시간 찾아줘"))

    fun notifyConflict(index: Int) = CardAction.Prompt(tr("Write a message saying I can't attend conflicting event ${index + 1}", "겹치는 일정 ${index + 1}번에 불참한다고 알릴 메시지 써줘"))

    /** Photos are named by their 1-based number in the card (the history lists them the same way). */
    fun makeAlbum(card: PhotosCard, selected: Set<String>): CardAction.Prompt {
        val n = numbers(card, selected)
        return CardAction.Prompt(
            if (n == null) tr(ALBUM_ALL_EN, ALBUM_ALL_KO) else tr("Make an album from photos $n", "사진 ${n}번으로 앨범 만들어줘"),
        )
    }

    /** The share sheet with the selected photos in card order; nothing selected shares them all. */
    fun sendPhotos(card: PhotosCard, selected: Set<String>): CardAction.SharePhotos {
        val chosen = card.uris.filter { it in selected }
        return CardAction.SharePhotos(chosen.ifEmpty { card.uris }.filter { isPhotoUri(it) })
    }

    /** "1, 3" for a partial selection; null when nothing or everything is selected (all photos). */
    private fun numbers(card: PhotosCard, selected: Set<String>): String? {
        val numbers = card.uris.mapIndexedNotNull { i, u -> if (u in selected) i + 1 else null }
        return if (numbers.isEmpty() || numbers.size == card.uris.size) null else numbers.joinToString(", ")
    }

    fun draftTone(tone: String) = CardAction.Prompt(tr("Rewrite the draft: $tone", "초안을 $tone 고쳐줘"))

    fun editDraft() = CardAction.Prompt(tr("I want to edit the draft", "초안 고칠래"))

    fun sendDraft(card: DraftCard) = CardAction.Prompt(
        when (card.channel) {
            "email" -> tr("Send this draft as an email", "이 초안 메일로 보내줘")
            "note" -> tr("Save this draft as a note", "이 초안 메모로 저장해줘")
            else -> tr("Send this draft as a text", "이 초안 문자로 보내줘")
        },
    )

    /** "요약 '답장 필요' 1번 항목: 답장 쓰기" — the item by position; group label and action are cut short. */
    fun digestItem(group: DigestGroup, index: Int, item: DigestItem): CardAction.Prompt {
        val action = item.action?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL) ?: tr("Handle it", "처리해줘")
        return CardAction.Prompt(
            tr(
                "Summary '${group.label.trim().take(MAX_LABEL)}' item ${index + 1}: $action",
                "요약 '${group.label.trim().take(MAX_LABEL)}' ${index + 1}번 항목: $action",
            ),
        )
    }

    fun requestPermission(card: PermissionCard) = CardAction.RequestPermission(card.permissions, card.label, card.settings)

    fun parcelLeaveAtDoor() = CardAction.Prompt(tr("Ask them to leave this parcel at the door", "이 택배 문 앞에 두라고 요청해줘"))

    fun parcelNotifyArrival() = CardAction.Prompt(tr("Tell me when this parcel arrives", "이 택배 도착하면 알려줘"))

    /** Sent after the user allowed a permission, so the task that needed it carries on. */
    fun permissionGranted(label: String) = CardAction.Prompt(tr("I allowed the $label permission. Please continue what you were doing", "$label 권한을 허용했어요. 아까 하던 것 이어서 해줘"))

    fun openResult(index: Int, item: ResultItem): CardAction =
        item.url?.let { CardAction.OpenUrl(it) } ?: CardAction.Prompt(tr("Tell me more about search result ${index + 1}", "검색 결과 ${index + 1}번 더 알려줘"))

    private const val MAX_LABEL = 20
    const val ALBUM_ALL_EN = "Make an album from all these photos"
    const val ALBUM_ALL_KO = "이 사진 전부로 앨범 만들어줘"
}

/** What the [권한 허용] button does, kept out of the UI so the denial paths are testable. */
object PermissionFlow {
    enum class Outcome { GRANTED, DENIED, OPEN_SETTINGS }

    /** [NOTIFICATION_LISTENER] is a special access: back from it, the app checks it and carries on if granted. */
    enum class SettingsPage { NOTIFICATIONS, APP_DETAILS, NOTIFICATION_LISTENER }

    /** Nothing Android can ask for (notifications below 13): straight to the settings page. */
    fun settingsOnly(request: CardAction.RequestPermission) = request.permissions.isEmpty()

    /**
     * After the system dialog: any grant is enough (approximate location still answers "near me"). Denied while
     * Android would still show the dialog: just say so. Denied with no dialog left ("don't ask again", or
     * denied twice): the settings page. An empty result (dialog dismissed by the system) is a plain denial.
     */
    fun after(result: Map<String, Boolean>, canAskAgain: Boolean): Outcome = when {
        result.values.any { it } -> Outcome.GRANTED
        result.isEmpty() || canAskAgain -> Outcome.DENIED
        else -> Outcome.OPEN_SETTINGS
    }

    fun settingsPage(request: CardAction.RequestPermission): SettingsPage = when {
        request.settings == PermissionCard.SETTINGS_NOTIFICATION_LISTENER -> SettingsPage.NOTIFICATION_LISTENER
        request.settings == PermissionCard.SETTINGS_NOTIFICATIONS || NOTIFICATIONS in request.permissions -> SettingsPage.NOTIFICATIONS
        else -> SettingsPage.APP_DETAILS
    }

    /** The request in flight as a saveable list, so its result is not lost if the process dies under the dialog. */
    fun save(request: CardAction.RequestPermission): ArrayList<String> =
        arrayListOf(request.label, request.settings.orEmpty()).apply { addAll(request.permissions) }

    fun restore(saved: List<String>?): CardAction.RequestPermission? {
        if (saved == null || saved.size < 2) return null
        return CardAction.RequestPermission(saved.drop(2), saved[0], saved[1].ifEmpty { null })
    }

    private const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
}
