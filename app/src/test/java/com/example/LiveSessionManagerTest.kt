package com.example

import com.example.live.LiveSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric supplies a real org.json implementation for the protocol parsing.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveSessionManagerTest {

  private fun newSession(): LiveSessionManager =
    LiveSessionManager(apiKey = "k", modelId = "live-model-x", systemPrompt = "sys")

  @Test
  fun `setupComplete flips state to LISTENING and fires onReady`() {
    val s = newSession()
    var ready = false
    s.onReady = { ready = true }
    s.handleServerMessage("""{"setupComplete":{}}""")
    assertTrue(ready)
    assertEquals(LiveSessionManager.State.LISTENING, s.state.value)
  }

  @Test
  fun `modelTurn text is delivered with turn completion`() {
    val s = newSession()
    val got = mutableListOf<Pair<String, Boolean>>()
    s.onModelText = { t, done -> got.add(t to done) }
    s.handleServerMessage("""{"serverContent":{"modelTurn":{"parts":[{"text":"Hello "},{"text":"world"}]},"turnComplete":true}}""")
    assertEquals(listOf("Hello world" to true), got)
    assertEquals(LiveSessionManager.State.LISTENING, s.state.value)
  }

  @Test
  fun `toolCall parses function calls with ids and args`() {
    val s = newSession()
    var calls: List<LiveSessionManager.ToolCallRequest>? = null
    s.onToolCalls = { calls = it }
    s.handleServerMessage("""{"toolCall":{"functionCalls":[{"id":"c1","name":"perform_android_action","args":{"action":"CLICK","target":"OK"}}]}}""")
    val c = calls!!.single()
    assertEquals("c1", c.id)
    assertEquals("perform_android_action", c.name)
    assertEquals("CLICK", c.args.getString("action"))
    assertEquals(LiveSessionManager.State.ACTING, s.state.value)
  }

  @Test
  fun `server error surfaces and sets ERROR state`() {
    val s = newSession()
    s.handleServerMessage("""{"error":{"message":"bad key"}}""")
    assertEquals("bad key", s.lastError.value)
    assertEquals(LiveSessionManager.State.ERROR, s.state.value)
  }

  @Test
  fun `garbage message is ignored`() {
    val s = newSession()
    s.handleServerMessage("not json at all")
    assertNull(s.lastError.value)
  }
}
