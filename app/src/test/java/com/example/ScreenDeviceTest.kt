package com.example

import com.example.agent.*
import com.example.service.ZhypixAccessibilityService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ScreenDeviceTest {
    @Test fun outOfScreenPointsFailBeforeDispatch() = runTest {
        val service=Robolectric.buildService(ZhypixAccessibilityService::class.java).create().get()
        assertFalse(service.device.execute(ScreenAction.Click(-1,100)).success)
        assertFalse(service.device.execute(ScreenAction.Click(Int.MAX_VALUE,100)).success)
    }
    @Test fun rejectsTypingWithoutFocusedField() = runTest {
        val service=Robolectric.buildService(ZhypixAccessibilityService::class.java).create().get()
        assertFalse(service.device.execute(ScreenAction.Type("hello")).success)
    }
    @Test fun refusesOwnAppLaunch() = runTest {
        val service=Robolectric.buildService(ZhypixAccessibilityService::class.java).create().get()
        assertFalse(service.device.execute(ScreenAction.StartApp(service.packageName)).success)
    }
    @Test fun keepsReferenceDesignConstants() {
        assertEquals(150,ScreenPolicy.MAX_STEPS)
        assertEquals(125L,ScreenPolicy.TAP_MS)
        assertEquals(175L,ScreenPolicy.DOUBLE_GAP_MS)
        assertEquals(860L,ScreenPolicy.SWIPE_MS)
        assertEquals(listOf(0L,475L,675L),ScreenPolicy.talkBackStartsMs)
        assertEquals(780,ScreenPolicy.AGENT_IMAGE_LONG_SIDE)
        assertEquals(540,ScreenPolicy.BUBBLE_IMAGE_LONG_SIDE)
        assertEquals(75,ScreenPolicy.JPEG_QUALITY)
    }
}
