package com.example.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.agent.ScreenLoop
import com.example.agent.ScreenPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Single frame plus phone state; no legacy cache or speculative coordinate conversion. */
class ScreenObservation(private val service: ZhypixAccessibilityService) {
    private val queue = Semaphore(ScreenPolicy.CAPTURE_QUEUE_SLOTS)
    private val shutter = Mutex()
    private var lastCaptureMs=0L
    data class Frame(val base64:String,val width:Int,val height:Int)

    suspend fun capture(): ScreenLoop.Observation = coroutineScope {
        val state = async(Dispatchers.Main.immediate) { phoneState() }
        val frame = screenshot() ?: throw IllegalStateException("Cannot capture the current screen. Unlock the device and check accessibility permission.")
        val dm=service.resources.displayMetrics
        val packages=withContext(Dispatchers.IO) {
            service.packageManager.getInstalledApplications(0).map{it.packageName}.sorted().joinToString(",")
        }
        val meta=JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
            .put("installed_packages",packages).put("device",Build.DEVICE).put("brand",Build.BRAND).put("product",Build.PRODUCT).put("hardware",Build.HARDWARE)
            .put("os_version",Build.VERSION.RELEASE).put("sdk_int",Build.VERSION.SDK_INT)
            .put("screen_width",dm.widthPixels).put("screen_height",dm.heightPixels)
            .put("scaled_screen_width",frame.width).put("scaled_screen_height",frame.height)
        ScreenLoop.Observation(frame.base64,state.await(),meta.toString())
    }

    suspend fun screenshot(): Frame? = queue.withPermit {
        shutter.withLock {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withLock null
            val wait=ScreenPolicy.CAPTURE_MIN_INTERVAL_MS-(SystemClock.elapsedRealtime()-lastCaptureMs)
            if(wait>0) delay(wait)
            withContext(Dispatchers.Main.immediate) {
                val floating=FloatingAgentService.instance
                floating?.hideFloatingView()
                try {
                    // Allow the overlay hide to reach the compositor. This delay is a local default, not a study value.
                    delay(32)
                    val pending=CompletableDeferred<Bitmap?>()
                    lastCaptureMs=SystemClock.elapsedRealtime()
                    service.takeScreenshot(Display.DEFAULT_DISPLAY,service.mainExecutor,object:AccessibilityService.TakeScreenshotCallback {
                        override fun onFailure(errorCode:Int) {pending.complete(null)}
                        override fun onSuccess(result:AccessibilityService.ScreenshotResult) {
                            var wrapped:Bitmap?=null
                            var software:Bitmap?=null
                            try {
                                wrapped=Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace)
                                software=wrapped?.copy(Bitmap.Config.ARGB_8888,false)
                            } catch (_:Exception) { software=null } finally {wrapped?.recycle();result.hardwareBuffer.close()}
                            if(!pending.complete(software)) software?.recycle()
                        }
                    })
                    val bitmap=try {
                        withTimeoutOrNull(ScreenPolicy.CAPTURE_TIMEOUT_MS){pending.await()}
                    } finally {
                        // A screenshot callback can arrive after cancellation/timeout.
                        if (!pending.isCompleted) pending.cancel()
                    } ?: return@withContext null
                    withContext(Dispatchers.Default) {
                        try {
                            val scale=(ScreenPolicy.AGENT_IMAGE_LONG_SIDE.toFloat()/maxOf(bitmap.width,bitmap.height)).coerceAtMost(1f)
                            val w=(bitmap.width*scale).toInt().coerceAtLeast(1)
                            val h=(bitmap.height*scale).toInt().coerceAtLeast(1)
                            val scaled=Bitmap.createScaledBitmap(bitmap,w,h,true)
                            try {
                                val bytes=ByteArrayOutputStream()
                                check(scaled.compress(Bitmap.CompressFormat.JPEG,ScreenPolicy.JPEG_QUALITY,bytes))
                                Frame(Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP),w,h)
                            } finally {if(scaled!==bitmap)scaled.recycle()}
                        } finally {bitmap.recycle()}
                    }
                } finally {floating?.showFloatingView()}
            }
        }
    }

    fun phoneState(): String {
        val root=service.rootInActiveWindow ?: return "Current app: unknown Keyboard visible: false\nFocused element: none"
        try {
            val pkg=root.packageName?.toString().orEmpty()
            val label=try {service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg,0)).toString()}catch(_:Exception){pkg}
            val keyboard=service.windows?.any{it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}==true
            val focused=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val focus=try {if(focused==null)"none" else if(focused.isPassword)"password field (redacted)" else "${focused.className} - ${focused.text ?: focused.contentDescription ?: ""}"}finally{focused?.recycle()}
            val output=StringBuilder("Current app: $label ($pkg) Keyboard visible: $keyboard\nFocused element: $focus\nVisible elements (RAW screen pixels):\n")
            var nodes=0
            fun visit(node:AccessibilityNodeInfo,depth:Int) {
                if(nodes++ >= ScreenPolicy.TREE_NODE_LIMIT || depth>ScreenPolicy.TREE_DEPTH_LIMIT)return
                if(node.isVisibleToUser && !node.isPassword) {
                    val text=(node.text ?: node.contentDescription)?.toString()?.take(200).orEmpty()
                    if(text.isNotBlank() || node.isClickable || node.isEditable) {
                        val r=Rect();node.getBoundsInScreen(r)
                        output.append("id=${node.viewIdResourceName ?: ""} text=${JSONObject.quote(text)} bounds=${r.toShortString()} center=${r.centerX()},${r.centerY()} editable=${node.isEditable}\n")
                    }
                }
                for(i in 0 until node.childCount) {
                    if(nodes>=ScreenPolicy.TREE_NODE_LIMIT)break
                    val child=node.getChild(i)?:continue
                    try {visit(child,depth+1)}finally{child.recycle()}
                }
            }
            visit(root,0)
            return output.toString()
        }finally{root.recycle()}
    }
}
