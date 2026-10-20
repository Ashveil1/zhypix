package com.example

import com.example.live.LiveSessionManager
import com.example.live.LiveToolBridge
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveToolBridgeTest {

  private fun bridge(confirm: (suspend (String, String) -> Boolean)? = null): LiveToolBridge {
    val b = LiveToolBridge(serviceProvider = { null }, contextProvider = { null })
    b.confirmAction = confirm
    return b
  }

  private fun call(name: String, args: JSONObject) =
    LiveSessionManager.ToolCallRequest(id = "id1", name = name, args = args)

  @Test
  fun `screen tools are unavailable during clean rebuild`() = runTest {
    val b = bridge()
    assertEquals(0, b.toolsPayload().length())
    val response = b.execute(call("computer_use_action", JSONObject()))
    assertEquals("error", response.getString("status"))
    assertTrue(response.getString("message").contains("being rebuilt"))
  }

  @Test
  fun `response includes hierarchy only when present`() {
    val b = bridge()
    val withTree = b.buildResponse(true, "ok", "tree-here")
    assertEquals("tree-here", withTree.getString("current_screen_hierarchy"))
    val without = b.buildResponse(true, "ok", "")
    assertFalse(without.has("current_screen_hierarchy"))
  }
}
