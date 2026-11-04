package com.phoneuse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Debug-only experiments on what an accessibility service can drive besides the app in front:
 * a hidden virtual display, split screen and a floating window. Results go to logcat under [TAG].
 */
class DebugProbes(private val service: AccessibilityService) {

    suspend fun run(test: String, pkg: String) {
        when (test) {
            "windows" -> logWindows("now")
            "vd" -> virtualDisplay(pkg, 0, "private")
            "vd-public" -> virtualDisplay(
                pkg,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                "public+own",
            )
            "adjacent" -> adjacent(pkg)
            "freeform" -> freeform(pkg)
            "vd-close" -> closeDisplay()
            "spec-on", "spec-off" -> {
                com.phoneuse.agent.Speculation.enabled = test == "spec-on"
                log("speculation ${com.phoneuse.agent.Speculation.enabled}")
            }
            else -> log("unknown test $test")
        }
    }

    private fun launchIntent(pkg: String): Intent? =
        service.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts [pkg] on a display only this app can see, then tries to read, click and tap inside it. */
    private suspend fun virtualDisplay(pkg: String, flags: Int, label: String) {
        closeDisplay()
        val dm = service.getSystemService(DisplayManager::class.java)
        val reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        val vd = try {
            dm.createVirtualDisplay("pu-probe", W, H, DPI, reader.surface, flags)
        } catch (e: Exception) {
            log("vd[$label] create FAILED: $e")
            return
        }
        display = vd
        imageReader = reader
        val id = vd.display.displayId
        log("vd[$label] created id=$id flags=$flags")
        val intent = launchIntent(pkg) ?: return log("no launch intent for $pkg")
        try {
            service.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
            log("vd[$label] startActivity($pkg) on display $id: no exception")
        } catch (e: Exception) {
            log("vd[$label] startActivity FAILED: $e")
            return
        }
        delay(2500)
        val image = reader.acquireLatestImage()
        if (image == null) {
            log("vd[$label] frame: none rendered")
        } else {
            val plane = image.planes[0]
            val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, H, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            image.close()
            val file = File(service.getExternalFilesDir(null), "probe_vd.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            log("vd[$label] frame saved ${file.absolutePath}")
        }
        val windows = service.windowsOnAllDisplays
        log("vd[$label] displays with windows: ${(0 until windows.size()).map { windows.keyAt(it) }}")
        val onVd = windows.get(id).orEmpty()
        log("vd[$label] windows on display $id: ${onVd.map { describe(it) }}")
        val root = onVd.firstNotNullOfOrNull { it.root }
        if (root == null) {
            log("vd[$label] no readable window on display $id")
        } else {
            val texts = ArrayList<String>()
            collectTexts(root, texts)
            log("vd[$label] read ${texts.size} texts: ${texts.take(12)}")
            val target = findClickable(root)
            if (target != null) {
                val label2 = target.text ?: target.contentDescription
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                log("vd[$label] ACTION_CLICK on '$label2': $ok")
                delay(1500)
                val after = service.windowsOnAllDisplays.get(id).orEmpty().firstNotNullOfOrNull { it.root }
                val afterTexts = ArrayList<String>().also { l -> after?.let { collectTexts(it, l) } }
                log("vd[$label] after click texts: ${afterTexts.take(12)}")
            }
        }
        val tapped = tapOnDisplay(id, W / 2f, H / 3f)
        log("vd[$label] dispatchGesture tap on display $id: $tapped")
        logWindows("after vd[$label]")
    }

    private fun closeDisplay() {
        display?.release()
        imageReader?.close()
        display = null
        imageReader = null
    }

    /** Splits the screen and opens [pkg] next to this app, then checks both are readable. */
    private suspend fun adjacent(pkg: String) {
        val own = service.packageManager.getLaunchIntentForPackage(service.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(own)
        delay(1200)
        val split = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        log("adjacent: toggle split screen = $split")
        delay(1500)
        val intent = launchIntent(pkg) ?: return log("no launch intent for $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try {
            service.startActivity(intent)
            log("adjacent: started $pkg adjacent")
        } catch (e: Exception) {
            log("adjacent: start FAILED: $e")
        }
        delay(2000)
        logWindows("adjacent")
        val other = service.windows.firstOrNull { it.root?.packageName == pkg }
        val root = other?.root
        if (root == null) {
            log("adjacent: $pkg window not readable")
            return
        }
        val target = findClickable(root)
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        log("adjacent: ACTION_CLICK on '${target?.text ?: target?.contentDescription}' in $pkg = $ok")
        delay(1500)
        logWindows("adjacent after click")
    }

    /** Opens [pkg] in a small window over the current app, if the device allows free-form windows. */
    private suspend fun freeform(pkg: String) {
        val intent = launchIntent(pkg) ?: return log("no launch intent for $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val bounds = Rect(80, 300, 1000, 1500)
        try {
            service.startActivity(intent, ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle())
            log("freeform: started $pkg with bounds $bounds")
        } catch (e: Exception) {
            log("freeform: start FAILED: $e")
        }
        delay(2000)
        logWindows("freeform")
    }

    private suspend fun tapOnDisplay(displayId: Int, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .setDisplayId(displayId)
            .build()
        return suspendCancellableCoroutine { cont ->
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                },
                null,
            )
            if (!accepted && cont.isActive) cont.resume(false)
        }
    }

    private fun logWindows(label: String) {
        val all = service.windowsOnAllDisplays
        for (i in 0 until all.size()) {
            val d = all.keyAt(i)
            log("$label display $d: ${all.valueAt(i).map { describe(it) }}")
        }
    }

    private fun describe(w: AccessibilityWindowInfo): String {
        val r = Rect().also { w.getBoundsInScreen(it) }
        val type = when (w.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
            else -> "t${w.type}"
        }
        return "$type:${w.root?.packageName}${if (w.isActive) "*" else ""}@${r.toShortString()}"
    }

    private fun collectTexts(n: AccessibilityNodeInfo, out: MutableList<String>) {
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collectTexts(it, out) }
    }

    private fun findClickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val hasLabel = !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
        if (n.isClickable && hasLabel && n.childCount == 0) return n
        if (n.isClickable && n.childCount > 0) {
            val texts = ArrayList<String>().also { collectTexts(n, it) }
            if (texts.isNotEmpty()) return n
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> findClickable(c)?.let { return it } }
        return null
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    private companion object {
        const val TAG = "PU_PROBE"
        const val W = 1080
        const val H = 2400
        const val DPI = 420
        var display: VirtualDisplay? = null
        var imageReader: ImageReader? = null
    }
}
