package com.example

import com.example.api.Content
import com.example.api.FunctionResponse
import com.example.api.Part
import com.example.utils.ContextBudget
import com.example.utils.RunningActionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

  private fun treeMsg(tree: String): Content = Content(
    parts = listOf(
      Part(
        functionResponse = FunctionResponse(
          name = "perform_android_action",
          response = mapOf("status" to "success", "current_screen_hierarchy" to tree)
        )
      )
    ),
    role = "function"
  )

  @Test
  fun `estimation counts text images and system overhead`() {
    val history = listOf(
      Content(parts = listOf(Part(text = "a".repeat(400))), role = "user"),
      Content(parts = listOf(Part(inlineData = com.example.api.InlineData("image/jpeg", "xx"))), role = "user")
    )
    val est = ContextBudget.estimateHistoryTokens(history)
    // 100 (text) + 258 (image) + overhead allowance
    assertEquals(ContextBudget.SYSTEM_OVERHEAD_ALLOWANCE + 358, est)
  }

  @Test
  fun `prune keeps only latest tree in function responses`() {
    val history = mutableListOf(
      treeMsg("OLD_TREE_A"),
      treeMsg("OLD_TREE_B"),
      treeMsg("LATEST_TREE")
    )
    ContextBudget.pruneOldHierarchies(history, 1)
    val r0 = history[0].parts[0].functionResponse!!.response["current_screen_hierarchy"]
    val r1 = history[1].parts[0].functionResponse!!.response["current_screen_hierarchy"]
    val r2 = history[2].parts[0].functionResponse!!.response["current_screen_hierarchy"]
    assertEquals("[pruned: older screen state]", r0)
    assertEquals("[pruned: older screen state]", r1)
    assertEquals("LATEST_TREE", r2)
  }

  @Test
  fun `prune rewrites old text-embedded trees and keeps the head`() {
    val old = Content(
      parts = listOf(Part(text = "guard header\nLatest Screen Layout Hierarchy:\nBIG_OLD_TREE\n\ntail")),
      role = "user"
    )
    val latest = treeMsg("LATEST_TREE")
    val history = mutableListOf(old, latest)
    ContextBudget.pruneOldHierarchies(history, 1)
    val t = history[0].parts[0].text!!
    assertTrue(t.startsWith("guard header"))
    assertTrue(t.contains("[pruned: older screen state]"))
    assertFalse(t.contains("BIG_OLD_TREE"))
    assertEquals("LATEST_TREE", history[1].parts[0].functionResponse!!.response["current_screen_hierarchy"])
  }

  @Test
  fun `running log caps entries and renders compactly`() {
    val log = RunningActionLog(cap = 3)
    log.add("CLICK", "Login", true)
    log.add("TYPE", "password", true)
    log.add("SCROLL", "DOWN", true)
    log.add("CLICK", "Submit", false, "not found")
    assertEquals(3, log.size)
    val rendered = log.render()
    assertFalse(rendered.contains("Login"))
    assertTrue(rendered.contains("TYPE 'password' -> ok"))
    assertTrue(rendered.contains("CLICK 'Submit' -> failed (not found)"))
  }
}
