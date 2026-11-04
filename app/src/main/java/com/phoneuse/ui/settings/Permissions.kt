package com.phoneuse.ui.settings

import com.phoneuse.i18n.tr
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.phoneuse.phone.PhoneUseNotificationListener
import com.phoneuse.service.PhoneUseAccessibilityService

enum class PermissionKey { ACCESSIBILITY, ALL_FILES, NOTIFICATIONS, NOTIFICATION_ACCESS, ASSISTANT, BATTERY }

data class PermissionStatus(
    val key: PermissionKey,
    val title: String,
    /** Why the app needs it. */
    val purpose: String,
    val granted: Boolean,
    /** Extra guidance shown while not granted (or always, for the assistant row). */
    val hint: String?,
)

/** The permission checklist of the settings screen: what is granted, and where to grant it. */
object Permissions {
    fun check(context: Context): List<PermissionStatus> = PermissionKey.entries.map { status(context, it) }

    fun status(context: Context, key: PermissionKey): PermissionStatus = when (key) {
        PermissionKey.ACCESSIBILITY -> PermissionStatus(
            key, tr("Accessibility service", "접근성 서비스"), tr("Reads the screen and taps for you", "화면을 읽고 대신 눌러요"), accessibilityOn(context),
            tr("If OpenPhoneUse is greyed out in the list: Settings › Apps › OpenPhoneUse › top-right ⋮ › Allow restricted settings, then turn it on again.", "목록에서 OpenPhoneUse가 회색으로 막혀 있으면: 설정 › 애플리케이션 › OpenPhoneUse › 오른쪽 위 ⋮ › ‘제한된 설정 허용’ 후 다시 켜 주세요."),
        )
        PermissionKey.ALL_FILES -> PermissionStatus(
            key, tr("All files access", "모든 파일 접근"), tr("Tidies your Downloads and Photos folders", "다운로드·사진 폴더를 정리해요"), Environment.isExternalStorageManager(), null,
        )
        PermissionKey.NOTIFICATIONS -> PermissionStatus(
            key, tr("Notifications", "알림"), tr("Tells you the result even when the app is closed", "앱을 닫아도 작업 결과를 알려줘요"),
            context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true, null,
        )
        PermissionKey.NOTIFICATION_ACCESS -> PermissionStatus(
            key, tr("Notification access", "알림 읽기"), tr("Groups incoming notifications into replies, money and parcels", "받은 알림을 답장·돈·택배로 묶어 요약해요"), PhoneUseNotificationListener.enabled(context),
            tr("Notification text stays on your phone for 7 days and goes to the model only when you ask for a summary.", "알림 내용은 폰 안에만 7일 동안 두고, 요약을 부탁할 때만 모델에 보내요."),
        )
        PermissionKey.ASSISTANT -> PermissionStatus(
            key, tr("Default digital assistant", "기본 디지털 어시스턴트"), tr("Call it with the side button", "측면 버튼으로 바로 불러요"), assistantHeld(context),
            tr("Galaxy: Settings › Advanced features › Side key › Press and hold › choose Wake digital assistant to call it with the side button.", "갤럭시: 설정 › 유용한 기능 › 측면 버튼 › 길게 누르기 › ‘디지털 어시스턴트 깨우기’를 고르면 측면 버튼으로 불러요."),
        )
        PermissionKey.BATTERY -> PermissionStatus(
            key, tr("Battery optimization exemption", "배터리 최적화 예외"), tr("Long tasks won't be cut off midway", "긴 작업이 중간에 끊기지 않아요"),
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
            null,
        )
    }

    private fun accessibilityOn(context: Context): Boolean {
        if (PhoneUseAccessibilityService.connected.value) return true
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, PhoneUseAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun assistantHeld(context: Context): Boolean {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    /** Opens the system screen for [key]; the first intent that resolves wins. */
    fun open(context: Context, key: PermissionKey) {
        val pkg = Uri.parse("package:${context.packageName}")
        val candidates = when (key) {
            PermissionKey.ACCESSIBILITY -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            PermissionKey.ALL_FILES -> listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
            PermissionKey.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            )
            PermissionKey.NOTIFICATION_ACCESS -> listOf(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, PhoneUseNotificationListener::class.java).flattenToString(),
                ),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
            PermissionKey.ASSISTANT -> listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            )
            PermissionKey.BATTERY -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        Toast.makeText(context, tr("Couldn't open the settings screen", "설정 화면을 열 수 없어요"), Toast.LENGTH_SHORT).show()
    }
}
