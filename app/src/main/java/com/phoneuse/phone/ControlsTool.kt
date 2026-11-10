package com.phoneuse.phone

import android.view.KeyEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A volume the user can set: [id] is the tool argument, [label] its name in replies. */
enum class VolumeStream(val id: String, val label: String) {
    MEDIA("media", "미디어"),
    RING("ring", "벨소리"),
    ALARM("alarm", "알람"),
    CALL("call", "통화"),
    ;

    companion object {
        fun of(id: String): VolumeStream? = entries.firstOrNull { it.id == id }
    }
}

enum class RingerMode(val id: String, val label: String) {
    SOUND("sound", "소리"),
    VIBRATE("vibrate", "진동"),
    SILENT("silent", "무음"),
    ;

    companion object {
        fun of(id: String): RingerMode? = entries.firstOrNull { it.id == id }
    }
}

/** A media button: [keyCode] is its KeyEvent code, [phrase] what the reply says was asked for. */
enum class MediaAction(val id: String, val keyCode: Int, val phrase: String) {
    PLAY("play", KeyEvent.KEYCODE_MEDIA_PLAY, "재생을"),
    PAUSE("pause", KeyEvent.KEYCODE_MEDIA_PAUSE, "일시정지를"),
    TOGGLE("toggle", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "재생/일시정지 전환을"),
    NEXT("next", KeyEvent.KEYCODE_MEDIA_NEXT, "다음 곡을"),
    PREVIOUS("previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS, "이전 곡을"),
    STOP("stop", KeyEvent.KEYCODE_MEDIA_STOP, "정지를"),
    ;

    companion object {
        fun of(id: String): MediaAction? = entries.firstOrNull { it.id == id }
    }
}

/** What became of a torch change. */
enum class TorchResult { DONE, NO_FLASH, IN_USE, FAILED }

data class BatteryLevel(val percent: Int, val charging: Boolean)

/**
 * The phone's quick controls and state, through public APIs that need no runtime permission; Android's is
 * [AndroidControlsSource]. A status read may throw or answer null: the tool then leaves that field out.
 */
interface ControlsSource {
    /** Turns the back camera's flash on or off. */
    fun torch(on: Boolean): TorchResult

    /** True when the device has one fixed volume (a TV, some tablets): nothing below can change. */
    fun volumeFixed(): Boolean

    fun maxVolume(stream: VolumeStream): Int

    fun volume(stream: VolumeStream): Int

    /** Throws [SecurityException] when the change would switch Do Not Disturb (the app has no policy access). */
    fun setVolume(stream: VolumeStream, level: Int)

    fun ringer(): RingerMode?

    /** Throws [SecurityException] like [setVolume]. */
    fun setRinger(mode: RingerMode)

    /** Presses and releases a media button; whichever player holds the media session gets it. */
    fun mediaKey(keyCode: Int)

    fun musicActive(): Boolean

    fun battery(): BatteryLevel?

    fun doNotDisturb(): Boolean?

    fun airplaneMode(): Boolean

    /** "wifi", "cellular", "other" (connected some other way) or "none". */
    fun network(): String

    fun freeStorageBytes(): Long
}

/**
 * flashlight, set_volume, media_control and device_status: one-call controls that need no screen work. Nothing
 * here asks for confirmation, since every change is harmless and undone by the opposite call. [pause] waits for
 * a player to react before media_control reads whether sound is playing.
 */
class ControlsTool(
    private val source: ControlsSource,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun flashlight(on: Boolean): PhoneReply = when (source.torch(on)) {
        TorchResult.DONE -> PhoneReply.Ok(if (on) "OK: 손전등을 켰어요" else "OK: 손전등을 껐어요")
        TorchResult.NO_FLASH -> PhoneReply.Failed("이 휴대폰에는 손전등(카메라 플래시)이 없어요")
        TorchResult.IN_USE -> PhoneReply.Failed("다른 앱이 카메라를 쓰고 있어 손전등을 바꿀 수 없어요. 카메라를 쓰는 앱을 닫고 다시 하세요.")
        TorchResult.FAILED -> PhoneReply.Failed("손전등을 바꾸지 못했어요")
    }

    /** Sets [stream]'s level to [percent] of its maximum and/or the ringer mode; a blank [stream] is media. */
    fun volume(stream: String?, percent: Int?, ringer: String?): PhoneReply {
        val target = stream.wire()?.let { VolumeStream.of(it) ?: return PhoneReply.Failed("stream must be one of ${VolumeStream.entries.map { s -> s.id }}") }
            ?: VolumeStream.MEDIA
        val mode = ringer.wire()?.let { RingerMode.of(it) ?: return PhoneReply.Failed("ringer must be one of ${RingerMode.entries.map { m -> m.id }}") }
        if (percent == null && mode == null) return PhoneReply.Failed("percent or ringer required")
        if (percent != null && percent !in 0..100) return PhoneReply.Failed("percent must be 0-100")
        if (source.volumeFixed()) return PhoneReply.Failed("이 기기는 음량이 고정되어 있어 바꿀 수 없어요")

        var levelNow: String? = null
        var ringerSet = false
        try {
            if (mode != null) {
                source.setRinger(mode)
                val now = source.ringer()
                if (now != null && now != mode) return PhoneReply.Failed("벨 모드가 바뀌지 않았어요 (지금: ${now.label}). $USE_QUICK_SETTINGS")
                ringerSet = true
            }
            if (percent != null) {
                val max = source.maxVolume(target)
                if (max <= 0) return PhoneReply.Failed("${target.label} 음량을 읽지 못했어요")
                val wanted = level(percent, max)
                source.setVolume(target, wanted)
                // Read back: the phone may keep a minimum (alarm, call) or refuse the change in its current mode.
                val now = source.volume(target)
                levelNow = "${target.label} 음량 ${percentOf(now, max)}% ($now/$max)" +
                    if (now == wanted) "" else " — 요청한 $percent%와 달라요 (이 음량의 허용 범위나 지금 모드 때문일 수 있어요)"
            }
        } catch (e: SecurityException) {
            return PhoneReply.Failed(DND_BLOCKED + if (ringerSet) " (벨 모드는 ${mode?.label}(으)로 바꿨어요)" else "")
        }
        // The ringer is read last: a ring level of 0 or above 0 moves it too.
        val ringerNow = if (mode == null) null else "벨 모드 ${(runCatching { source.ringer() }.getOrNull() ?: mode).label}"
        return PhoneReply.Ok("OK: " + listOfNotNull(ringerNow, levelNow).joinToString(", "))
    }

    /**
     * Sends a media button. Nothing tells the app whether a player took it, so the reply says only that it was
     * asked for, plus whether sound is playing a moment later.
     */
    fun media(action: String): PhoneReply {
        val a = action.wire()?.let { MediaAction.of(it) } ?: return PhoneReply.Failed("action must be one of ${MediaAction.entries.map { it.id }}")
        try {
            source.mediaKey(a.keyCode)
        } catch (e: RuntimeException) {
            return PhoneReply.Failed("미디어 버튼을 보내지 못했어요")
        }
        pause(SETTLE_MS)
        val playing = runCatching { source.musicActive() }.getOrNull()
        val state = when {
            playing == null -> ""
            a == MediaAction.PLAY && !playing -> " 아직 소리가 나지 않아요: 받은 플레이어가 없을 수 있으니, 필요하면 음악 앱을 열어 재생하세요."
            (a == MediaAction.PAUSE || a == MediaAction.STOP) && playing -> " 아직 소리가 나고 있어요: 미디어 버튼을 받지 않는 앱일 수 있어요."
            playing -> " 지금 소리가 나고 있어요."
            else -> " 지금 나는 소리는 없어요."
        }
        return PhoneReply.Ok("OK: ${a.phrase} 요청했어요 (플레이어가 받았는지는 알 수 없어요).$state")
    }

    /** The phone's state as compact JSON; a value the phone would not give is left out. */
    fun status(): PhoneReply {
        val json = buildJsonObject {
            read { source.battery() }?.let { b ->
                putJsonObject("battery") {
                    put("percent", b.percent)
                    put("charging", b.charging)
                }
            }
            val volumes = STATUS_STREAMS.mapNotNull { s -> read { percentOrNull(source.volume(s), source.maxVolume(s)) }?.let { s.id to it } }
            if (volumes.isNotEmpty()) putJsonObject("volume") { volumes.forEach { (id, percent) -> put(id, percent) } }
            read { source.ringer() }?.let { put("ringer", it.id) }
            read { source.doNotDisturb() }?.let { put("do_not_disturb", it) }
            read { source.airplaneMode() }?.let { put("airplane_mode", it) }
            read { source.network() }?.let { put("network", it) }
            // Decimal gigabytes with one decimal, as the phone's own storage screen counts.
            read { source.freeStorageBytes() }?.takeIf { it >= 0 }?.let { put("storage_free_gb", (it / 1e8).roundToLong() / 10.0) }
        }
        if (json.isEmpty()) return PhoneReply.Failed("휴대폰 상태를 읽지 못했어요")
        return PhoneReply.Ok(json.toString())
    }

    private inline fun <T : Any> read(block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    /** A tool argument as its lower-case id; blank reads as not given. */
    private fun String?.wire(): String? = this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun percentOrNull(level: Int, max: Int): Int? = if (max > 0) percentOf(level, max) else null

    companion object {
        /** How long a player gets to react before media_control reads whether sound is playing. */
        const val SETTLE_MS = 400L

        private val STATUS_STREAMS = listOf(VolumeStream.MEDIA, VolumeStream.RING, VolumeStream.ALARM)

        private const val USE_QUICK_SETTINGS = "화면에서 빠른 설정을 열어 직접 바꾸세요."

        /** For the model: Android refused because the change would switch Do Not Disturb, which only the user can allow. */
        const val DND_BLOCKED = "방해 금지 모드가 바뀌는 변경이라, 사용자가 휴대폰 설정의 '방해 금지 접근'에서 이 앱을 허용해야 앱이 할 수 있어요. " +
            "허용하기 전에는 $USE_QUICK_SETTINGS"

        /** [percent] of [max] as a volume step, rounded; anything above 0% is at least step 1 (never silent). */
        fun level(percent: Int, max: Int): Int {
            val scaled = (percent * max / 100.0).roundToInt()
            return if (percent > 0 && scaled == 0) 1 else scaled
        }

        fun percentOf(level: Int, max: Int): Int = (level * 100.0 / max).roundToInt().coerceIn(0, 100)
    }
}
