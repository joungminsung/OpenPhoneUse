package com.phoneuse.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone in memory. Like Android, it refuses a change into or out of silent with a SecurityException (no
 * notification-policy access), moves the ringer with the ring level, and keeps a stream at its minimum.
 */
internal class FakeControls : ControlsSource {
    var torchResult = TorchResult.DONE
    var fixed = false
    val max = mutableMapOf(VolumeStream.MEDIA to 15, VolumeStream.RING to 7, VolumeStream.ALARM to 7, VolumeStream.CALL to 5)
    val levels = mutableMapOf(VolumeStream.MEDIA to 5, VolumeStream.RING to 4, VolumeStream.ALARM to 6, VolumeStream.CALL to 3)
    val min = mutableMapOf<VolumeStream, Int>()
    var ringerMode: RingerMode? = RingerMode.SOUND
    var playing = false
    var batteryLevel: BatteryLevel? = BatteryLevel(82, charging = false)
    var dnd: Boolean? = false
    var airplane = false
    var net = "wifi"
    var freeBytes = 23_456_000_000L

    /** Reads that throw: battery, volume, ringer, dnd, airplane, network, storage, music. */
    var broken = emptySet<String>()

    val torch = mutableListOf<Boolean>()
    val keys = mutableListOf<Int>()

    /** Every change that went through, in order. */
    val changes = mutableListOf<String>()

    private fun read(name: String) {
        if (name in broken) throw IllegalStateException("$name unavailable")
    }

    override fun torch(on: Boolean): TorchResult {
        if (torchResult == TorchResult.DONE) torch += on
        return torchResult
    }

    override fun volumeFixed() = fixed

    override fun maxVolume(stream: VolumeStream) = max.getValue(stream)

    override fun volume(stream: VolumeStream): Int {
        read("volume")
        return levels.getValue(stream)
    }

    override fun setVolume(stream: VolumeStream, level: Int) {
        if (stream == VolumeStream.RING) {
            if (ringerMode == RingerMode.SILENT && level > 0) throw SecurityException("Not allowed to change Do Not Disturb state")
            ringerMode = if (level > 0) RingerMode.SOUND else RingerMode.VIBRATE
        }
        levels[stream] = level.coerceIn(min[stream] ?: 0, max.getValue(stream))
        changes += "${stream.id}=$level"
    }

    override fun ringer(): RingerMode? {
        read("ringer")
        return ringerMode
    }

    override fun setRinger(mode: RingerMode) {
        if ((mode == RingerMode.SILENT) != (ringerMode == RingerMode.SILENT)) throw SecurityException("Not allowed to change Do Not Disturb state")
        ringerMode = mode
        changes += "ringer=${mode.id}"
    }

    override fun mediaKey(keyCode: Int) {
        keys += keyCode
    }

    override fun musicActive(): Boolean {
        read("music")
        return playing
    }

    override fun battery(): BatteryLevel? {
        read("battery")
        return batteryLevel
    }

    override fun doNotDisturb(): Boolean? {
        read("dnd")
        return dnd
    }

    override fun airplaneMode(): Boolean {
        read("airplane")
        return airplane
    }

    override fun network(): String {
        read("network")
        return net
    }

    override fun freeStorageBytes(): Long {
        read("storage")
        return freeBytes
    }
}

class ControlsToolTest {
    private val source = FakeControls()
    private val waits = mutableListOf<Long>()
    private val tool = ControlsTool(source) { waits += it }

    private fun json(reply: PhoneReply) = Json.parseToJsonElement(reply.text).jsonObject

    @Test
    fun flashlightTurnsOnAndOff() {
        assertEquals(PhoneReply.Ok("OK: 손전등을 켰어요"), tool.flashlight(true))
        assertEquals(PhoneReply.Ok("OK: 손전등을 껐어요"), tool.flashlight(false))
        assertEquals(listOf(true, false), source.torch)
    }

    @Test
    fun aPhoneWithoutAFlashOrWithABusyCameraFailsClearly() {
        source.torchResult = TorchResult.NO_FLASH
        val none = tool.flashlight(true)
        assertTrue(none.text, none is PhoneReply.Failed && none.text.startsWith("ERROR: ") && "손전등" in none.text && "없어요" in none.text)
        source.torchResult = TorchResult.IN_USE
        val busy = tool.flashlight(true)
        assertTrue(busy.text, busy is PhoneReply.Failed && "카메라" in busy.text)
        source.torchResult = TorchResult.FAILED
        assertTrue(tool.flashlight(false) is PhoneReply.Failed)
        assertTrue(source.torch.isEmpty())
    }

    @Test
    fun percentScalesToTheStreamMaximumAndReadsItBack() {
        assertEquals("OK: 미디어 음량 47% (7/15)", tool.volume("media", 47, null).text)
        assertEquals(7, source.levels[VolumeStream.MEDIA])
        assertEquals("OK: 미디어 음량 0% (0/15)", tool.volume("media", 0, null).text)
        assertEquals("OK: 미디어 음량 100% (15/15)", tool.volume("media", 100, null).text)
        // Half of 15 steps rounds up to 8.
        assertEquals("OK: 미디어 음량 53% (8/15)", tool.volume("media", 50, null).text)
        assertEquals("OK: 통화 음량 60% (3/5)", tool.volume("call", 60, null).text)
        assertEquals("OK: 알람 음량 43% (3/7)", tool.volume(" Alarm ", 40, null).text)
        assertEquals(listOf("media=7", "media=0", "media=15", "media=8", "call=3", "alarm=3"), source.changes)
    }

    @Test
    fun aPercentAboveZeroNeverRoundsToSilence() {
        // 1% of 7 steps is 0.07: step 1, not 0.
        assertEquals("OK: 벨소리 음량 14% (1/7)", tool.volume("ring", 1, null).text)
        assertEquals(RingerMode.SOUND, source.ringerMode)
        assertEquals(1, ControlsTool.level(1, 15))
        assertEquals(1, ControlsTool.level(3, 15))
        assertEquals(1, ControlsTool.level(4, 15))
        assertEquals(0, ControlsTool.level(0, 15))
        assertEquals(15, ControlsTool.level(100, 15))
        assertEquals(14, ControlsTool.level(95, 15))
    }

    @Test
    fun theDefaultStreamIsMedia() {
        assertEquals("OK: 미디어 음량 20% (3/15)", tool.volume(null, 20, null).text)
        assertEquals("OK: 미디어 음량 80% (12/15)", tool.volume("", 80, null).text)
        assertEquals(4, source.levels[VolumeStream.RING])
    }

    @Test
    fun badArgumentsAreRejectedBeforeAnythingChanges() {
        for (percent in listOf(-1, 101, 1000)) {
            val r = tool.volume("media", percent, null)
            assertTrue(r.text, r is PhoneReply.Failed && "0-100" in r.text)
        }
        val stream = tool.volume("bluetooth", 50, null)
        assertTrue(stream.text, stream is PhoneReply.Failed && "stream" in stream.text)
        // The ringer is checked before the level is set.
        val ringer = tool.volume("media", 50, "loud")
        assertTrue(ringer.text, ringer is PhoneReply.Failed && "ringer" in ringer.text)
        val neither = tool.volume("ring", null, null)
        assertTrue(neither.text, neither is PhoneReply.Failed && "percent or ringer" in neither.text)
        assertTrue(tool.volume(null, null, " ") is PhoneReply.Failed)
        assertTrue(source.changes.isEmpty())
    }

    @Test
    fun ringerModeAloneOrWithALevel() {
        assertEquals("OK: 벨 모드 진동", tool.volume(null, null, "vibrate").text)
        assertEquals(RingerMode.VIBRATE, source.ringerMode)
        assertEquals(5, source.levels[VolumeStream.MEDIA])
        assertEquals("OK: 벨 모드 소리, 알람 음량 100% (7/7)", tool.volume("alarm", 100, "sound").text)
        assertEquals(listOf("ringer=vibrate", "ringer=sound", "alarm=7"), source.changes)
    }

    @Test
    fun aChangeThatWouldSwitchDoNotDisturbIsLeftToTheScreen() {
        // Into silent: Android throws without notification-policy access.
        val silent = tool.volume(null, null, "silent")
        assertTrue(silent.text, silent is PhoneReply.Failed && "방해 금지" in silent.text && "빠른 설정" in silent.text)
        assertEquals(RingerMode.SOUND, source.ringerMode)
        // Out of silent by raising the ring level: the same refusal.
        source.ringerMode = RingerMode.SILENT
        val ring = tool.volume("ring", 50, null)
        assertTrue(ring.text, ring is PhoneReply.Failed && "빠른 설정" in ring.text)
        assertEquals(4, source.levels[VolumeStream.RING])
        assertTrue(source.changes.isEmpty())
        // Media is not part of Do Not Disturb.
        assertTrue(tool.volume("media", 50, null) is PhoneReply.Ok)
    }

    @Test
    fun aFixedVolumeDeviceFails() {
        source.fixed = true
        val r = tool.volume("media", 50, "vibrate")
        assertTrue(r.text, r is PhoneReply.Failed && "고정" in r.text)
        assertTrue(source.changes.isEmpty())
    }

    @Test
    fun aLevelThePhoneKeepsIsReportedAsItIs() {
        // Android keeps the alarm at step 1 or more.
        source.min[VolumeStream.ALARM] = 1
        val r = tool.volume("alarm", 0, null)
        assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("OK: 알람 음량 14% (1/7)") && "달라요" in r.text)
    }

    @Test
    fun mediaActionsPressTheirKeys() {
        // KeyEvent.KEYCODE_MEDIA_PLAY, _PAUSE, _PLAY_PAUSE, _NEXT, _PREVIOUS, _STOP.
        val codes = linkedMapOf("play" to 126, "pause" to 127, "toggle" to 85, "next" to 87, "previous" to 88, "stop" to 86)
        for ((action, code) in codes) {
            val r = tool.media(action)
            assertTrue(r.text, r is PhoneReply.Ok && r.text.startsWith("OK: ") && "요청했어요" in r.text)
            assertEquals(action, code, source.keys.last())
        }
        assertEquals(codes.values.toList(), source.keys)
        assertEquals(codes.keys.toList(), MediaAction.entries.map { it.id })
        // One wait per press, before reading whether sound is playing.
        assertEquals(List(6) { ControlsTool.SETTLE_MS }, waits)
        // Case and spaces around the name do not matter.
        tool.media(" Next ")
        assertEquals(87, source.keys.last())
    }

    @Test
    fun anUnknownMediaActionPressesNothing() {
        for (action in listOf("rewind", "", "play pause")) {
            val r = tool.media(action)
            assertTrue(r.text, r is PhoneReply.Failed && "action" in r.text)
        }
        assertTrue(source.keys.isEmpty())
        assertTrue(waits.isEmpty())
    }

    @Test
    fun mediaReplySaysWhetherSoundIsPlayingAfterwards() {
        val silentPlay = tool.media("play").text
        assertTrue(silentPlay, "아직 소리가 나지 않아요" in silentPlay)
        val silentNext = tool.media("next").text
        assertTrue(silentNext, "지금 나는 소리는 없어요" in silentNext)
        source.playing = true
        val loudPlay = tool.media("play").text
        assertTrue(loudPlay, "지금 소리가 나고 있어요" in loudPlay)
        val loudPause = tool.media("pause").text
        assertTrue(loudPause, "아직 소리가 나고 있어요" in loudPause)
        // The state is only a hint: without it the request is still reported.
        source.broken = setOf("music")
        assertEquals("OK: 정지를 요청했어요 (플레이어가 받았는지는 알 수 없어요).", tool.media("stop").text)
    }

    @Test
    fun statusCarriesEveryField() {
        val r = tool.status()
        assertTrue(r.text, r is PhoneReply.Ok)
        val o = json(r)
        assertEquals(setOf("battery", "volume", "ringer", "do_not_disturb", "airplane_mode", "network", "storage_free_gb"), o.keys)
        val battery = o.getValue("battery").jsonObject
        assertEquals(82, battery.getValue("percent").jsonPrimitive.int)
        assertFalse(battery.getValue("charging").jsonPrimitive.boolean)
        val volume = o.getValue("volume").jsonObject
        assertEquals(setOf("media", "ring", "alarm"), volume.keys)
        assertEquals(33, volume.getValue("media").jsonPrimitive.int)
        assertEquals(57, volume.getValue("ring").jsonPrimitive.int)
        assertEquals(86, volume.getValue("alarm").jsonPrimitive.int)
        assertEquals("sound", o.getValue("ringer").jsonPrimitive.content)
        assertFalse(o.getValue("do_not_disturb").jsonPrimitive.boolean)
        assertFalse(o.getValue("airplane_mode").jsonPrimitive.boolean)
        assertEquals("wifi", o.getValue("network").jsonPrimitive.content)
        assertEquals(23.5, o.getValue("storage_free_gb").jsonPrimitive.double, 0.0001)
    }

    @Test
    fun statusReflectsThePhone() {
        source.batteryLevel = BatteryLevel(7, charging = true)
        source.ringerMode = RingerMode.VIBRATE
        source.dnd = true
        source.airplane = true
        source.net = "none"
        val o = json(tool.status())
        assertEquals("""{"percent":7,"charging":true}""", o.getValue("battery").toString())
        assertEquals("vibrate", o.getValue("ringer").jsonPrimitive.content)
        assertTrue(o.getValue("do_not_disturb").jsonPrimitive.boolean)
        assertTrue(o.getValue("airplane_mode").jsonPrimitive.boolean)
        assertEquals("none", o.getValue("network").jsonPrimitive.content)
    }

    @Test
    fun statusLeavesOutWhatThrowsOrIsUnknown() {
        source.broken = setOf("battery", "network", "volume")
        source.dnd = null
        val o = json(tool.status())
        assertEquals(setOf("ringer", "airplane_mode", "storage_free_gb"), o.keys)

        source.broken = setOf("battery", "volume", "ringer", "dnd", "airplane", "network", "storage")
        val nothing = tool.status()
        assertTrue(nothing.text, nothing is PhoneReply.Failed)
    }
}
