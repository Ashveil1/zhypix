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
  fun `new action tool is declared`() {
    val declaration=bridge().toolsPayload().getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
    assertEquals("screen_action",declaration.getString("name"))
  }

  @Test
  fun `legacy screen tool is rejected`() = runTest {
    val response=bridge().execute(call("computer_use_action",JSONObject()))
    assertEquals("error",response.getString("status"))
  }

  @Test
  fun `new action requires accessibility service`() = runTest {
    val response=bridge().execute(call("screen_action",JSONObject().put("action","press_home({})")))
    assertEquals("error",response.getString("status"))
    assertTrue(response.getString("message").contains("accessibility"))
  }

  @Test
  fun `invalid action is rejected before dispatch`() = runTest {
    val response=bridge().execute(call("screen_action",JSONObject().put("action","invalid")))
    assertEquals("error",response.getString("status"))
    assertTrue(response.getString("message").contains("Invalid action"))
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
