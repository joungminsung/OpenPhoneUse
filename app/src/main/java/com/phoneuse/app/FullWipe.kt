package com.phoneuse.app

import com.phoneuse.i18n.tr
import android.app.NotificationManager
import android.content.Context
import com.phoneuse.chat.ChatDb
import com.phoneuse.chat.ChatDbSchema
import com.phoneuse.phone.NotificationStore
import com.phoneuse.proactive.ProactiveSystem
import com.phoneuse.routine.DeferredRun
import com.phoneuse.routine.RoutineSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings "모든 데이터 지우기", all of it in one place and off the main thread: conversations, the
 * notification history, nudges and typed asks, usage counts, routines with their reminders, queued runs and
 * geofences, memories and app tips, the "나에 대해" text, this app's notifications in the shade, the camera
 * cache, and finally the database file is compacted so deleted text leaves the disk. Connector sign-ins stay
 * (the dialog says so). Each part fails on its own, so one broken store never keeps the rest; the result names
 * the parts that failed.
 */
object FullWipe {
    private val LOG: java.util.logging.Logger = java.util.logging.Logger.getLogger("FullWipe")

    /** Runs every part; returns the names of those that failed. */
    suspend fun run(context: Context): List<String> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        runParts(
            listOf(
                tr("Chats", "대화") to {
                    ReadAloud.stop()
                    for (c in AppGraph.chats.conversations().first()) AppGraph.chats.delete(c.id)
                },
                tr("Notification history", "알림 기록") to { NotificationStore.get(app).clear() },
                tr("Proactive help", "알아서 챙기기") to { ProactiveSystem.get(app).clearNow() },
                tr("Usage", "사용량") to { AppGraph.usage.clear() },
                tr("Routines", "루틴") to {
                    val routines = RoutineSystem.get(app)
                    val store = routines.store
                    for (r in store.all()) store.delete(r.id)
                    store.removeReminders(store.reminders().mapTo(HashSet()) { it.id })
                    store.editDeferred { emptyList<DeferredRun>() to Unit }
                    store.fires = emptyList()
                    routines.geofences.clear()
                    // Re-plans from the now-empty store: the next alarm is cancelled.
                    routines.changed()
                },
                tr("Memories", "기억") to {
                    AppGraph.memories.deleteAll()
                    AppGraph.memories.deleteAllTips()
                },
                tr("About me", "나에 대해") to {
                    val p = AppGraph.settings.personalization.first()
                    if (p.about.isNotEmpty()) AppGraph.settings.setPersonalization(p.copy(about = ""))
                },
                tr("Notifications", "알림") to { app.getSystemService(NotificationManager::class.java)?.cancelAll() },
                tr("Temporary photos", "사진 임시 파일") to { File(app.cacheDir, "camera").deleteRecursively() },
                // Last, after every delete: deleted text leaves the full-text segments and the free pages.
                tr("Storage cleanup", "저장 공간 정리") to {
                    val db = ChatDb.get(app).writable
                    ChatDbSchema.compact.forEach(db::execSQL)
                },
            ),
        )
    }

    /** Runs each part in order whatever the others do; the names of the parts that threw. */
    suspend fun runParts(parts: List<Pair<String, suspend () -> Unit>>): List<String> =
        parts.mapNotNull { (name, block) ->
            try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warning("wiping $name failed: ${e.javaClass.simpleName}")
                name
            }
        }

    /** What the toast says after [run]. */
    fun resultText(failed: List<String>): String =
        if (failed.isEmpty()) tr("Erased all chats and memories", "모든 대화와 기억을 지웠어요")
        else tr("Couldn't erase some items: ${failed.joinToString(", ")}", "일부를 지우지 못했어요: ${failed.joinToString(", ")}")
}
