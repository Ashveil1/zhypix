package com.example

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.live.LiveController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveControllerTest {

  private fun controller(): LiveController = LiveController(
    context = ApplicationProvider.getApplicationContext<Context>(),
    apiKey = "k",
    modelId = "live-model-x"
  )

  @Test
  fun `sendText appends a user transcript entry even before connect`() {
    val c = controller()
    c.sendText("hello there")
    val t = c.transcript.value
    assertEquals(1, t.size)
    assertTrue(t[0].fromUser)
    assertEquals("hello there", t[0].text)
  }

  @Test
  fun `blank text is ignored`() {
    val c = controller()
    c.sendText("   ")
    assertTrue(c.transcript.value.isEmpty())
  }

  @Test
  fun `stop and destroy are safe without a session and idempotent`() {
    val c = controller()
    // No session was started: repeated stop/destroy must not throw.
    c.stop()
    c.stop()
    c.destroy()
    // No user-facing side effects either.
    assertTrue(c.transcript.value.isEmpty())
  }
}
