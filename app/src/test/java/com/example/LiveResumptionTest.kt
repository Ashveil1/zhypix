package com.example

import com.example.live.LiveSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveResumptionTest {

  @Test
  fun `resumption handle is stored from server updates`() {
    val s = LiveSessionManager(apiKey = "k", modelId = "m", systemPrompt = "s")
    assertNull(s.resumptionHandle)
    s.handleServerMessage("""{"sessionResumptionUpdate":{"newHandle":"handle-abc"}}""")
    assertEquals("handle-abc", s.resumptionHandle)
  }

  @Test
  fun `empty resumption handle is ignored`() {
    val s = LiveSessionManager(apiKey = "k", modelId = "m", systemPrompt = "s")
    s.handleServerMessage("""{"sessionResumptionUpdate":{"newHandle":""}}""")
    assertNull(s.resumptionHandle)
  }
}
