package com.phoneuse.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationToolTest {
    private class FakeSource(
        var permitted: Boolean = true,
        var enabled: Boolean = true,
        var fix: LocationFix? = LocationFix(37.4979, 127.0276, 12.5f),
    ) : LocationSource {
        var reads = 0
        override fun hasPermission() = permitted
        override fun isEnabled() = enabled
        override suspend fun current(): LocationFix? {
            reads++
            return fix
        }
    }

    @Test
    fun returnsPermissionNeededWhenDenied() = runBlocking {
        val source = FakeSource(permitted = false)
        val reply = LocationTool(source).get()
        assertEquals(LocationReply.PermissionNeeded, reply)
        assertEquals("권한 필요: 위치", reply.text)
        // Never touches the location provider without the permission.
        assertEquals(0, source.reads)
    }

    @Test
    fun reportsFixWithAddress() = runBlocking {
        val reply = LocationTool(FakeSource()) { lat, lng -> "서울 강남구 ($lat,$lng)" }.get()
        val ok = reply as LocationReply.Ok
        assertEquals(37.4979, ok.lat, 0.0)
        assertTrue(ok.text, ok.text.contains("\"lat\":37.4979") && ok.text.contains("\"lng\":127.0276"))
        assertTrue(ok.text, ok.text.contains("\"accuracy_m\":13"))
        assertTrue(ok.text, ok.text.contains("서울 강남구"))
    }

    @Test
    fun locationOffOrNoFixIsAnError() = runBlocking {
        val off = LocationTool(FakeSource(enabled = false)).get()
        assertTrue(off.text, off.text.startsWith("ERROR:"))
        val none = LocationTool(FakeSource(fix = null)).get()
        assertTrue(none.text, none.text.startsWith("ERROR:"))
        // A failing address lookup still returns the fix.
        val noAddress = LocationTool(FakeSource()) { _, _ -> throw java.io.IOException("offline") }.get()
        assertTrue(noAddress is LocationReply.Ok && noAddress.address == null)
    }
}
