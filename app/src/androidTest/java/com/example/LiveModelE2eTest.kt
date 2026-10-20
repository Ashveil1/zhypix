package com.example

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.agent.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Opt-in live model test. Uses real Android UiAutomation because app service binding is unavailable in CI. */
@RunWith(AndroidJUnit4::class)
class LiveModelE2eTest {
    @Test fun modelNavigatesSettingsWithoutChangingAnything() = runBlocking {
        drive("Open the Network & internet page from Android Settings. Do not toggle or change anything. Once the page is visibly open, return finished.",setOf("Network & internet"),6) {device->
            val toolbar=device.findObject(By.res("com.android.settings:id/collapsing_toolbar"))
            assertEquals("Network & internet",toolbar?.contentDescription)
            assertNotNull("Destination page missing Internet row",device.findObject(By.text("Internet")))
        }
    }

    @Test fun modelOpensTwoLevelsDeepWithoutChangingAnything() = runBlocking {
        drive("In Android Settings, first open Network & internet, then open the Internet row inside it. Do not toggle or change anything. Once the Internet page is visibly open, return finished.",setOf("Network & internet","Internet"),8) {device->
            val toolbar=device.findObject(By.res("com.android.settings:id/collapsing_toolbar"))
            assertEquals("Internet",toolbar?.contentDescription)
        }
    }

    private suspend fun drive(task:String,allowedLabels:Set<String>,maxSteps:Int,verify:(UiDevice)->Unit) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        val configFile=java.io.File(context.filesDir,"live-model-test.json")
        require(configFile.exists()) {"Manual live-model workflow must supply private test config"}
        val config=JSONObject(configFile.readText())
        configFile.delete()
        val model=ScreenModel(config.getString("key"),config.getString("base"),config.getString("model"),false)
        context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")),10000))
        val evidence=java.io.File(context.filesDir,"live-model-evidence.txt")
        evidence.writeText("Live model: ${config.getString("model")}\nTask: $task\nScreen: ${device.displayWidth}x${device.displayHeight}\n")
        val loop=ScreenLoop(
            capture={
                device.waitForIdle(1000)
                val image=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                val w=image.width;val h=image.height
                val scale=(780f/maxOf(w,h)).coerceAtMost(1f)
                val scaled=Bitmap.createScaledBitmap(image,(w*scale).toInt(),(h*scale).toInt(),true)
                val bytes=ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,75,bytes)
                val sw=scaled.width;val sh=scaled.height
                if(scaled!==image)scaled.recycle();image.recycle()
                val root=instrumentation.uiAutomation.rootInActiveWindow ?: error("No active Android window")
                val text=StringBuilder("Current app: ${root.packageName}\nVisible elements, raw pixels:\n")
                var count=0
                fun visit(n:AccessibilityNodeInfo,depth:Int) {
                    if(count++>400 || depth>20)return
                    val label=(n.text ?: n.contentDescription)?.toString().orEmpty()
                    if(label.isNotBlank()) {
                        val r=Rect();n.getBoundsInScreen(r)
                        text.append("${JSONObject.quote(label)} center=${r.centerX()},${r.centerY()}\n")
                    }
                    for(i in 0 until n.childCount){val child=n.getChild(i)?:continue;try{visit(child,depth+1)}finally{child.recycle()}}
                }
                try{visit(root,0)}finally{root.recycle()}
                ScreenLoop.Observation(Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP),text.toString(),
                    JSONObject().put("screen_width",w).put("screen_height",h).put("scaled_screen_width",sw).put("scaled_screen_height",sh).toString())
            },
            decide={observation,history->
                val reply=try{model.decide(task,observation,history)}catch(e:Exception){evidence.appendText("Model error: ${e.message}\n");throw e}
                evidence.appendText("Reply: ${reply.take(300).replace("\n"," ")}\n")
                reply},
            execute={action->
                evidence.appendText("Action: $action\n")
                // Deliberately narrow: no typing, app launch, purchases, settings toggles or arbitrary intents.
                val success=when(action){
                    is ScreenAction.Click-> {
                        val hit=allowedLabels.any {label->device.findObjects(By.text(label)).any {it.visibleBounds.contains(action.x,action.y)}}
                        if(hit) device.click(action.x,action.y) else false
                    }
                    ScreenAction.PressBack->device.pressBack()
                    is ScreenAction.Wait->{kotlinx.coroutines.delay(action.durationMs.coerceAtMost(3000));true}
                    else->false
                }
                ScreenLoop.Execution(success,if(success)"Android input dispatched" else "Action outside the safe test subset")
            }
        )
        val result=loop.run(maxSteps=maxSteps)
        evidence.appendText("Terminal: $result\n")
        device.dumpWindowHierarchy(java.io.File(context.filesDir,"live-model-final.xml").absolutePath)
        assertTrue("Model did not report completion: $result",result is ScreenAction.Finished)
        verify(device)
        evidence.appendText("PASS: destination independently verified. No settings changed.\n")
    }
}
