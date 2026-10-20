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
  fun `tools payload declares computer_use_action with required args`() {
    val payload = bridge().toolsPayload()
    val decl = payload.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
    assertEquals("computer_use_action", decl.getString("name"))
    val required = decl.getJSONObject("parameters").getJSONArray("required")
    assertEquals("action_type", required.getString(0))
    assertEquals("target", required.getString(1))
  }

  @Test
  fun `unknown tool returns error payload`() = runTest {
    val resp = bridge().execute(call("nope", JSONObject()))
    assertEquals("error", resp.getString("status"))
    assertTrue(resp.getString("message").contains("Unknown tool"))
  }

  @Test
  fun `missing action_type returns error payload`() = runTest {
    val resp = bridge().execute(call("computer_use_action", JSONObject().put("target", "OK")))
    assertEquals("error", resp.getString("status"))
    assertTrue(resp.getString("message").contains("action_type"))
  }

  @Test
  fun `unconfirmed sensitive action is blocked without executing`() = runTest {
    val resp = bridge(confirm = { _, _ -> false }).execute(
      call("computer_use_action", JSONObject().put("action_type", "CLICK").put("target", "Send"))
    )
    assertEquals("blocked", resp.getString("status"))
  }

  @Test
  fun `missing accessibility service returns actionable error`() = runTest {
    val resp = bridge().execute(
      call("computer_use_action", JSONObject().put("action_type", "CLICK").put("target", "OK"))
    )
    assertEquals("error", resp.getString("status"))
    assertTrue(resp.getString("message").contains("Accessibility Service"))
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
