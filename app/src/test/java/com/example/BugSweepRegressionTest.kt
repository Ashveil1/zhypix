package com.example

import com.example.service.ElementSelector
import com.example.ui.components.MarkdownBlock
import com.example.ui.components.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BugSweepRegressionTest {

  // ---- ElementSelector.parse ----

  @Test
  fun `selector parses parenthesized coordinates`() {
    val s = ElementSelector.parse("(100, 200)")
    assertEquals(100f, s.x)
    assertEquals(200f, s.y)
    assertNull(s.text)
  }

  @Test
  fun `selector parses bare and bracketed coordinates`() {
    val a = ElementSelector.parse("100,200")
    assertEquals(100f, a.x)
    assertEquals(200f, a.y)
    val b = ElementSelector.parse("[0.5, 0.25]")
    assertEquals(0.5f, b.x)
    assertEquals(0.25f, b.y)
  }

  @Test
  fun `selector parses resource id`() {
    val s = ElementSelector.parse("com.example:id/login_button")
    assertEquals("com.example:id/login_button", s.resourceId)
    assertNull(s.text)
  }

  @Test
  fun `selector falls back to text query`() {
    val s = ElementSelector.parse("Sign in")
    assertEquals("Sign in", s.text)
    assertEquals("Sign in", s.contentDesc)
  }

  @Test
  fun `selector blank query yields empty selector`() {
    val s = ElementSelector.parse("   ")
    assertNull(s.text)
    assertNull(s.x)
    assertNull(s.resourceId)
  }

  // ---- parseMarkdownBlocks ----

  @Test
  fun `markdown headers bullets and numbered items`() {
    val blocks = parseMarkdownBlocks("# Title\n\n* one\n- two\n1. first\n2. second")
    assertEquals(MarkdownBlock.Header(1, "Title"), blocks[0])
    assertEquals(MarkdownBlock.BulletItem("one"), blocks[1])
    assertEquals(MarkdownBlock.BulletItem("two"), blocks[2])
    assertEquals(MarkdownBlock.NumberedItem("1.", "first"), blocks[3])
    assertEquals(MarkdownBlock.NumberedItem("2.", "second"), blocks[4])
  }

  @Test
  fun `markdown hash without space is a paragraph not a header`() {
    val blocks = parseMarkdownBlocks("#hashtag")
    assertEquals(1, blocks.size)
    assertTrue(blocks[0] is MarkdownBlock.Paragraph)
  }

  @Test
  fun `markdown consecutive lines merge into one paragraph`() {
    val blocks = parseMarkdownBlocks("line one\nline two")
    assertEquals(listOf(MarkdownBlock.Paragraph("line one\nline two")), blocks)
  }
}
