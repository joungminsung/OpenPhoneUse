package com.phoneuse.routine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Routines, pending reminders and the scheduler's state in one JSON file in the app's private storage.
 * A file, not a chat.db table: it is a short settings-like list (at most a few dozen routines) that the alarm,
 * boot and geofence receivers read at once, and it keeps the chat database's schema out of this feature.
 * Writes work like Android's AtomicFile: the new content goes to `routines.json.new`, is synced to disk, then
 * atomically moved over the old file, so a crash or power loss never leaves half a file. (AtomicFile itself
 * cannot replace a file on the JVM the tests run on.) A file that still cannot be read is moved aside to `routines.broken-<time>.json` (never overwritten) and
 * [brokenAside] names it so the app can tell the user.
 */
class RoutineStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val fresh = File(file.path + ".new")

    /** The unreadable file this store moved aside when it opened, if any. */
    var brokenAside: File? = null
        private set

    private data class Data(
        val nextId: Long = 1,
        val routines: List<Routine> = emptyList(),
        val reminders: List<Reminder> = emptyList(),
        val inside: Set<Long> = emptySet(),
        val checkedAt: Long? = null,
        val fires: List<EventFire> = emptyList(),
        val calendarAt: Long? = null,
        val deferred: List<DeferredRun> = emptyList(),
        val fenceKey: String? = null,
        val fencesAt: Long? = null,
        val placesPaused: Boolean = false,
    )

    private var data: Data = load()
    private val _routines = MutableStateFlow(data.routines)

    /** Every routine, in the order they were made. */
    val routines: StateFlow<List<Routine>> = _routines.asStateFlow()

    fun all(): List<Routine> = synchronized(lock) { data.routines }

    fun get(id: Long): Routine? = synchronized(lock) { data.routines.firstOrNull { it.id == id } }

    /** Adds [routine] (id 0) or replaces the one with its id; returns it as stored. */
    fun save(routine: Routine): Routine = edit { d ->
        if (routine.id == 0L || d.routines.none { it.id == routine.id }) {
            val stored = routine.copy(id = if (routine.id == 0L) d.nextId else routine.id)
            d.copy(nextId = maxOf(d.nextId, stored.id) + 1, routines = d.routines + stored) to stored
        } else {
            d.copy(routines = d.routines.map { if (it.id == routine.id) routine else it }) to routine
        }
    }

    /** Changes the routine [id] if it still exists; returns the new value. */
    fun update(id: Long, change: (Routine) -> Routine): Routine? = edit { d ->
        val old = d.routines.firstOrNull { it.id == id } ?: return@edit d to null
        val new = change(old).copy(id = id)
        d.copy(routines = d.routines.map { if (it.id == id) new else it }) to new
    }

    fun delete(id: Long) {
        edit { d ->
            d.copy(routines = d.routines.filter { it.id != id }, inside = d.inside - id, fires = d.fires.filter { it.routineId != id }) to Unit
        }
    }

    fun reminders(): List<Reminder> = synchronized(lock) { data.reminders }

    fun addReminder(at: Long, text: String): Reminder = edit { d ->
        val r = Reminder(d.nextId, at, text)
        d.copy(nextId = d.nextId + 1, reminders = d.reminders + r) to r
    }

    fun removeReminders(ids: Set<Long>) {
        edit { d -> d.copy(reminders = d.reminders.filter { it.id !in ids }) to Unit }
    }

    /** Routine ids whose geofence was last entered. */
    var inside: Set<Long>
        get() = synchronized(lock) { data.inside }
        set(v) {
            edit { d -> d.copy(inside = v) to Unit }
        }

    /** When the scheduler last handled due runs (epoch ms); null before the first check. */
    var checkedAt: Long?
        get() = synchronized(lock) { data.checkedAt }
        set(v) {
            edit { d -> d.copy(checkedAt = v) to Unit }
        }

    /** BeforeEvent runs planned from the last calendar read. */
    var fires: List<EventFire>
        get() = synchronized(lock) { data.fires }
        set(v) {
            edit { d -> d.copy(fires = v) to Unit }
        }

    /** When the calendar is next read; null when no routine needs it. */
    var calendarAt: Long?
        get() = synchronized(lock) { data.calendarAt }
        set(v) {
            edit { d -> d.copy(calendarAt = v) to Unit }
        }

    /** Runs waiting for the runner, oldest first (survive a process death). */
    var deferred: List<DeferredRun>
        get() = synchronized(lock) { data.deferred }
        set(v) {
            edit { d -> d.copy(deferred = v) to Unit }
        }

    /** Changes the deferred queue atomically; returns what [change] returns. */
    fun <T> editDeferred(change: (List<DeferredRun>) -> Pair<List<DeferredRun>, T>): T = edit { d ->
        val (next, result) = change(d.deferred)
        d.copy(deferred = next) to result
    }

    /** What the registered geofences are (see RoutineGeofences.key); null = must register again. */
    var fenceKey: String?
        get() = synchronized(lock) { data.fenceKey }
        set(v) {
            edit { d -> d.copy(fenceKey = v) to Unit }
        }

    /** When geofences were last registered: their "already inside" reports come right after. */
    var fencesAt: Long?
        get() = synchronized(lock) { data.fencesAt }
        set(v) {
            edit { d -> d.copy(fencesAt = v) to Unit }
        }

    /** Play services dropped the geofences (location turned off): place routines wait for location. */
    var placesPaused: Boolean
        get() = synchronized(lock) { data.placesPaused }
        set(v) {
            edit { d -> d.copy(placesPaused = v) to Unit }
        }

    private val _paused = MutableStateFlow(data.placesPaused)

    /** [placesPaused] as a flow, for the routines screen. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /**
     * A change the user made on a screen ([block] calls [save]/[update]/[delete]): false, with nothing changed,
     * when routines.json could not be written (read-only or full storage), so the screen can say so instead of
     * crashing. Call it off the main thread (it writes the file).
     */
    fun userEdit(block: RoutineStore.() -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: java.io.IOException) {
            false
        }

    private fun <T> edit(change: (Data) -> Pair<Data, T>): T = synchronized(lock) {
        val (next, result) = change(data)
        if (next != data) {
            write(next)
            data = next
            _routines.value = next.routines
            _paused.value = next.placesPaused
        }
        result
    }

    // ---- file -----------------------------------------------------------------------------------

    private fun load(): Data {
        // A write that never finished leaves only .new: the old file is still whole.
        fresh.delete()
        if (!file.exists()) return Data()
        val o = try {
            Json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            // Unreadable: keep it for the user instead of overwriting it with an empty list.
            moveAside()
            return Data()
        }
        // A routine that no longer reads (a rule got stricter) is dropped rather than losing the rest.
        val routines = (o["routines"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonObject)?.let { (RoutineJson.parse(it, fromModel = false) as? RoutineParse.Ok)?.routine }
        }
        val reminders = (o["reminders"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            Reminder(r.long("id") ?: return@mapNotNull null, r.long("at") ?: return@mapNotNull null, r.str("text") ?: return@mapNotNull null)
        }
        val fires = (o["fires"] as? JsonArray).orEmpty().mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            EventFire(
                f.long("routine_id") ?: return@mapNotNull null,
                f.long("at") ?: return@mapNotNull null,
                f.str("title") ?: "",
                f.str("location") ?: "",
                f.long("starts_at") ?: 0,
            )
        }
        val maxId = (routines.map { it.id } + reminders.map { it.id }).maxOrNull() ?: 0
        return Data(
            nextId = maxOf(o.long("next_id") ?: 1, maxId + 1),
            routines = routines,
            reminders = reminders,
            inside = (o["inside"] as? JsonArray).orEmpty().mapNotNullTo(HashSet()) { (it as? JsonPrimitive)?.longOrNull },
            checkedAt = o.long("checked_at"),
            fires = fires,
            calendarAt = o.long("calendar_at"),
            deferred = (o["deferred"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(DeferredRun::read) },
            fenceKey = o.str("fence_key"),
            fencesAt = o.long("fences_at"),
            placesPaused = (o["places_paused"] as? JsonPrimitive)?.contentOrNull == "true",
        )
    }

    /** Set when an unreadable file could not be moved aside: nothing is written over it. */
    private var readOnly = false

    private fun moveAside() {
        val aside = File(file.parentFile, "routines.broken-${clock()}.json")
        val moved = file.renameTo(aside) || try {
            file.copyTo(aside, overwrite = false)
            file.delete()
            true
        } catch (e: Exception) {
            false
        }
        if (moved) brokenAside = aside else readOnly = true
    }

    private fun write(d: Data) {
        val json = buildJsonObject {
            put("version", 1)
            put("next_id", d.nextId)
            put("routines", JsonArray(d.routines.map { RoutineJson.encode(it) }))
            put(
                "reminders",
                buildJsonArray {
                    for (r in d.reminders) add(buildJsonObject { put("id", r.id); put("at", r.at); put("text", r.text) })
                },
            )
            put("inside", JsonArray(d.inside.sorted().map { JsonPrimitive(it) }))
            d.checkedAt?.let { put("checked_at", it) }
            put(
                "fires",
                buildJsonArray {
                    for (f in d.fires) {
                        add(
                            buildJsonObject {
                                put("routine_id", f.routineId)
                                put("at", f.at)
                                put("title", f.title)
                                put("location", f.location)
                                put("starts_at", f.startsAt)
                            },
                        )
                    }
                },
            )
            d.calendarAt?.let { put("calendar_at", it) }
            put("deferred", JsonArray(d.deferred.map { it.json() }))
            d.fenceKey?.let { put("fence_key", it) }
            d.fencesAt?.let { put("fences_at", it) }
            put("places_paused", d.placesPaused)
        }
        if (readOnly) throw java.io.IOException("${file.name} is unreadable and could not be kept aside; not overwriting it")
        file.parentFile?.mkdirs()
        try {
            FileOutputStream(fresh).use { out ->
                out.write(json.toString().toByteArray())
                out.fd.sync()
            }
            Files.move(fresh.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            syncDirectory()
        } catch (e: Exception) {
            fresh.delete()
            throw java.io.IOException("could not write ${file.name}", e)
        }
    }

    /** Makes the rename itself durable where the platform allows opening a directory (Linux, so Android). */
    private fun syncDirectory() {
        val dir = file.parentFile ?: return
        try {
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Not supported here (e.g. Windows): the file itself was synced.
        }
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val FILE_NAME = "routines.json"
    }
}
