package com.example

import com.example.live.LiveSessionManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveAudioProtocolTest {

  private fun session() = LiveSessionManager(apiKey = "k", modelId = "live-model-x", systemPrompt = "s")

  @Test
  fun `inline audio parts are decoded and delivered with mime type`() {
    val s = session()
    val got = mutableListOf<Pair<ByteArray, String>>()
    s.onModelAudio = { bytes, mime -> got.add(bytes to mime) }
    val b64 = android.util.Base64.encodeToString(byteArrayOf(1, 2, 3, 4), android.util.Base64.NO_WRAP)
    s.handleServerMessage("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]}}}""")
    assertEquals(1, got.size)
    assertArrayEquals(byteArrayOf(1, 2, 3, 4), got[0].first)
    assertEquals("audio/pcm;rate=24000", got[0].second)
  }

  @Test
  fun `interrupted server message fires onInterrupted and resets state`() {
    val s = session()
    var interrupted = false
    s.onInterrupted = { interrupted = true }
    s.handleServerMessage("""{"setupComplete":{}}""")
    s.handleServerMessage("""{"serverContent":{"interrupted":true}}""")
    assertTrue(interrupted)
    assertEquals(LiveSessionManager.State.LISTENING, s.state.value)
  }

  @Test
  fun `mixed text and audio turn delivers both`() {
    val s = session()
    var text: String? = null
    var audioBytes = 0
    s.onModelText = { t, _ -> text = t }
    s.onModelAudio = { b, _ -> audioBytes += b.size }
    val b64 = android.util.Base64.encodeToString(ByteArray(8), android.util.Base64.NO_WRAP)
    s.handleServerMessage("""{"serverContent":{"modelTurn":{"parts":[{"text":"hi"},{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"turnComplete":true}}""")
    assertEquals("hi", text)
    assertEquals(8, audioBytes)
  }
}
