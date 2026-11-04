package com.phoneuse.agent

import com.phoneuse.chat.ConversationSearch
import com.phoneuse.memory.InMemoryMemoryStore
import com.phoneuse.model.ToolSpec
import com.phoneuse.phone.AlarmSource
import com.phoneuse.phone.AlarmTool
import com.phoneuse.phone.CalendarSource
import com.phoneuse.phone.CalendarTool
import com.phoneuse.phone.ContactsSource
import com.phoneuse.phone.ContactsTool
import com.phoneuse.phone.LocationSource
import com.phoneuse.phone.LocationTool
import com.phoneuse.phone.NotificationSource
import com.phoneuse.phone.NotificationTool
import com.phoneuse.phone.PhotoSource
import com.phoneuse.phone.PhotosTool
import com.phoneuse.phone.PlaceSearch
import com.phoneuse.phone.SmsSource
import com.phoneuse.phone.SmsTool
import com.phoneuse.routine.ReminderScheduler
import com.phoneuse.screen.ScreenSnapshot
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.MessageDigest

/**
 * Pins the tool list the model sees: names, order, descriptions and schemas. They are part of the prompt and
 * its cache, so a refactor of how tools are declared must not change a byte of them. When a tool really
 * changes, update the expected names and hashes here on purpose.
 */
class ToolSpecsPinTest {
    /** A source that is never called: the tools are only built so that their specs are offered. */
    private inline fun <reified T> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

    private fun device() = FakeDeviceController(listOf(ScreenSnapshot("com.app", 1080, 2400, emptyList())))

    private val connector = object : ConnectorTools {
        override val specs = listOf(ToolSpec("mcp__notion__search", "Search", JsonObject(emptyMap())))
        override fun handles(name: String) = name == "mcp__notion__search"
        override suspend fun call(name: String, args: JsonObject, gate: UserGate) = ToolResult("", null, null, null)
    }

    /** Every optional part present: the longest tool list a run can have. */
    private fun full() = ToolExecutor(
        device(), FakeUserGate(),
        memory = MemoryAccess(InMemoryMemoryStore(), ConversationSearch { _, _, _ -> emptyList() }),
        phone = PhoneAccess(
            LocationTool(stub<LocationSource>()), stub<PlaceSearch>(), stub<ReminderScheduler>(),
            contacts = ContactsTool(stub<ContactsSource>()),
            calendar = CalendarTool(stub<CalendarSource>()) { emptyList() },
            alarms = AlarmTool(stub<AlarmSource>()),
            photos = PhotosTool(stub<PhotoSource>(), null, { emptyList() }),
        ),
        connectors = connector,
        messaging = MessagingAccess(NotificationTool(stub<NotificationSource>()), SmsTool(stub<SmsSource>())),
        routines = RoutineAccess(null, null),
    )

    private fun hash(specs: List<ToolSpec>): String {
        val text = specs.joinToString("\u0002") { "${it.name}\u0001${it.description}\u0001${it.parameters}" }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun fullToolListIsPinned() {
        val specs = full().specs
        assertEquals(FULL_NAMES, specs.map { it.name })
        assertEquals(FULL_HASH, hash(specs))
    }

    @Test fun bareToolListIsPinned() {
        val specs = ToolExecutor(device(), FakeUserGate()).specs
        assertEquals(BARE_NAMES, specs.map { it.name })
        assertEquals(BARE_HASH, hash(specs))
    }

    private companion object {
        val BARE_NAMES = listOf(
            "tap", "long_press", "type_text", "scroll", "swipe", "pinch", "press_key", "open_app", "open_uri", "list_apps",
            "screenshot", "wait", "batch", "delegate", "get_screen",
            "list_dir", "search_files", "file_info", "mkdir", "move_file", "copy_file", "rename_file", "trash_files",
            "ask_user", "hand_to_user", "open_route", "show_card", "finish", "update_plan",
        )
        val FULL_NAMES = listOf(
            "tap", "long_press", "type_text", "scroll", "swipe", "pinch", "press_key", "open_app", "open_uri", "list_apps",
            "screenshot", "wait", "batch", "delegate", "get_screen",
            "list_dir", "search_files", "file_info", "mkdir", "move_file", "copy_file", "rename_file", "trash_files",
            "ask_user", "hand_to_user",
            "remember", "forget", "recall", "search_conversations",
            "get_location", "geocode", "open_route", "remind_at", "find_contact", "calendar_list", "calendar_add",
            "next_alarm", "set_alarm", "photos_find", "photos_look", "photos_album", "set_wallpaper",
            "make_routine", "show_card", "finish",
            "notifications_recent", "dismiss_notifications", "sms_search", "send_sms",
            "update_plan",
            "mcp__notion__search",
        )
        const val BARE_HASH = "3658ed250939353057bae640e4ff7cc0428c00fd045cdead11bf5740b90c3fef"
        const val FULL_HASH = "2392524c54e157692df3d0a7bb1dad5243195acddbd717497059bf90fd684abf"
    }
}
