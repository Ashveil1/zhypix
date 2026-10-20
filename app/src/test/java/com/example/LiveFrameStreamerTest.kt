package com.example

import com.example.live.LiveFrameStreamer
import com.example.live.LiveSessionManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveFrameStreamerTest {

  private fun session() = LiveSessionManager(apiKey = "k", modelId = "live-model-x", systemPrompt = "s")

  @Test
  fun `tick without accessibility service sends nothing`() = runTest {
    val streamer = LiveFrameStreamer(session(), serviceProvider = { null })
    streamer.tick()
    assertEquals(0, streamer.framesSent)
  }

  @Test
  fun `tick skipped while session is not ready`() = runTest {
    // Session never connected: state is IDLE, so no capture is attempted
    // even with a service present.
    var serviceAccessed = false
    val streamer = LiveFrameStreamer(session(), serviceProvider = {
      serviceAccessed = true
      null
    })
    streamer.tick()
    assertFalse(serviceAccessed)
    assertEquals(0, streamer.framesSent)
  }
}
