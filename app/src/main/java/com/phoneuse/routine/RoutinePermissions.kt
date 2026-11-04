package com.phoneuse.routine

import com.phoneuse.i18n.tr
import com.phoneuse.cards.PermissionCard

/**
 * The runtime permissions a routine needs to fire, in the order to ask for them, as the generic [권한 허용]
 * card. A place routine needs precise location and then background location: Android asks for "항상 허용"
 * separately, after location (on 11+ the button opens the settings page). The required permission of a need
 * is the one that must be granted; the card may ask for more (coarse with fine).
 */
object RoutinePermissions {
    const val FINE = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
    const val BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION"
    const val BLUETOOTH = "android.permission.BLUETOOTH_CONNECT"
    const val CALENDAR = "android.permission.READ_CALENDAR"
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /** Not a runtime permission: notification access (Settings › 알림 읽기), which notification triggers read. */
    const val LISTENER = "phoneuse:notification_listener"

    /** The label of the background-location card. */
    val ALWAYS_LABEL: String get() = tr("Location: Allow all the time", "위치 '항상 허용'")

    /** Guidance shown with the background-location card. */
    val ALWAYS_HINT: String get() = tr(
        "In Settings, change the location permission to \"Allow all the time\" so arrivals and departures are noticed even when the app isn't in use.",
        "설정에서 위치 권한을 '항상 허용'으로 바꿔 주세요. 그래야 앱을 안 쓰고 있을 때도 도착·출발을 알아요.",
    )

    data class Need(val required: String, val card: PermissionCard)

    private val location get() = Need(FINE, PermissionCard(tr("Location", "위치"), listOf(FINE, COARSE)))
    private val always get() = Need(BACKGROUND, PermissionCard(ALWAYS_LABEL, listOf(BACKGROUND)))

    fun needs(routine: Routine, sdk: Int): List<Need> = buildList {
        when (val t = routine.trigger) {
            is Trigger.Place -> {
                add(location)
                add(always)
            }
            is Trigger.Device -> when (t.kind) {
                DeviceKind.BLUETOOTH_CONNECTED -> if (sdk >= 31) add(Need(BLUETOOTH, PermissionCard(tr("Nearby devices", "근처 기기"), listOf(BLUETOOTH))))
                // The Wi-Fi name is location data: precise location, and in the background.
                DeviceKind.WIFI_CONNECTED -> if (t.detail != null) {
                    add(location)
                    add(always)
                }
                else -> Unit
            }
            is Trigger.BeforeEvent -> add(Need(CALENDAR, PermissionCard(tr("Calendar", "캘린더"), listOf(CALENDAR))))
            is Trigger.Notification -> add(Need(LISTENER, PermissionCard(tr("Notification access", "알림 읽기"), emptyList(), PermissionCard.SETTINGS_NOTIFICATION_LISTENER)))
            else -> Unit
        }
        // Results and "잠금 해제 후 실행" are notifications.
        if (sdk >= 33) add(Need(NOTIFICATIONS, PermissionCard(tr("Notifications", "알림"), listOf(NOTIFICATIONS), PermissionCard.SETTINGS_NOTIFICATIONS)))
    }

    /**
     * Why a routine that has every permission still cannot work, shown on its row and preview card; null when
     * nothing is in the way. Device triggers are watched by the accessibility service, and operating apps needs
     * it too; [placesPaused]: Play services dropped the geofences because location is off.
     */
    fun warning(routine: Routine, accessibilityOn: Boolean, placesPaused: Boolean): String? = when {
        routine.trigger is Trigger.Place && placesPaused -> tr("Paused because location is off. Turn location on to resume.", "위치가 꺼져 있어 멈췄어요. 위치를 켜면 다시 시작해요.")
        !accessibilityOn && routine.trigger is Trigger.Device -> tr("Won't run because the accessibility service is off. Turn on OpenPhoneUse in Settings › Accessibility.", "접근성 서비스가 꺼져 있어 실행되지 않아요. 설정 › 접근성에서 OpenPhoneUse를 켜 주세요.")
        !accessibilityOn && routine.needsDevice -> tr("Can't operate apps because the accessibility service is off. Turn on OpenPhoneUse in Settings › Accessibility.", "접근성 서비스가 꺼져 있어 앱을 조작할 수 없어요. 설정 › 접근성에서 OpenPhoneUse를 켜 주세요.")
        else -> null
    }

    /** The card for the first permission still missing, or null when the routine can fire. */
    fun firstMissing(routine: Routine, sdk: Int, granted: (String) -> Boolean): PermissionCard? =
        needs(routine, sdk).firstOrNull { !granted(it.required) }?.card
}
