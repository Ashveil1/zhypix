package com.zhypix.fresh.agent

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import org.junit.Assert.*
import org.junit.Test

class ActionParserTest {

    private val adapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val json: (String) -> Map<String, Any?>? = { s ->
        try { adapter.fromJson(s) } catch (e: Exception) { null }
    }

    @Test
    fun `canonical click parses with coordinates`() {
        val r = ActionParser.parse("""click({"x":540,"y":1200})""", json)
        assertTrue(r is ActionParser.Result.One)
        assertEquals(AgentAction.Click(540, 1200), (r as ActionParser.Result.One).action)
    }

    @Test
    fun `prose around a single action is tolerated`() {
        val r = ActionParser.parse("""I see the settings icon. click({"x":100,"y":200}) now""", json)
        assertTrue(r is ActionParser.Result.One)
        val one = r as ActionParser.Result.One
        assertEquals(AgentAction.Click(100, 200), one.action)
        assertTrue(one.prose.contains("settings icon"))
    }

    @Test
    fun `fenced json object form parses`() {
        val reply = """
            |Here you go:
            |```json
            |{"action": "type", "text": "hello world"}
            |```
        """.trimMargin()
        val r = ActionParser.parse(reply, json)
        assertTrue(r is ActionParser.Result.One)
        assertEquals(AgentAction.TypeText("hello world"), (r as ActionParser.Result.One).action)
    }

    @Test
    fun `name with arguments object parses`() {
        val r = ActionParser.parse("""{"name": "start_app", "arguments": {"package_name": "com.android.settings"}}""", json)
        assertTrue(r is ActionParser.Result.One)
        assertEquals(AgentAction.StartApp("com.android.settings"), (r as ActionParser.Result.One).action)
    }

    @Test
    fun `two actions are rejected`() {
        val r = ActionParser.parse("""click({"x":1,"y":2}) click({"x":3,"y":4})""", json)
        assertTrue(r is ActionParser.Result.Several)
        assertEquals(2, (r as ActionParser.Result.Several).count)
    }

    @Test
    fun `plain prose yields none`() {
        val r = ActionParser.parse("I am not sure what is on screen, can you tell me more?", json)
        assertTrue(r is ActionParser.Result.None)
    }

    @Test
    fun `terminal actions parse`() {
        val r = ActionParser.parse("""finished({"message":"opened settings"})""", json)
        val one = r as ActionParser.Result.One
        assertEquals(AgentAction.Finished("opened settings"), one.action)
        assertTrue(one.action.isTerminal)

        val r2 = ActionParser.parse("""needs_confirmation({"question":"Send the message?"})""", json)
        assertEquals(AgentAction.NeedsConfirmation("Send the message?"), (r2 as ActionParser.Result.One).action)
    }

    @Test
    fun `missing coordinates are invalid`() {
        val r = ActionParser.parse("""click({"x":"oops"})""", json)
        assertTrue(r is ActionParser.Result.Invalid)
    }

    @Test
    fun `quoted keys and thai text inside type survive`() {
        val r = ActionParser.parse("""type({"text":"สวัสดี (ครับ) \"ทดสอบ\""})""", json)
        assertTrue(r is ActionParser.Result.One)
        assertEquals(AgentAction.TypeText("สวัสดี (ครับ) \"ทดสอบ\""), (r as ActionParser.Result.One).action)
    }

    @Test
    fun `wait clamps to sane range`() {
        val r = ActionParser.parse("""wait({"seconds":999})""", json)
        assertEquals(AgentAction.Wait(30.0), (r as ActionParser.Result.One).action)
    }

    @Test
    fun `drag with x1 y1 aliases parses`() {
        val r = ActionParser.parse("""drag({"x1":1,"y1":2,"x2":3,"y2":4})""", json)
        assertEquals(AgentAction.Drag(1, 2, 3, 4), (r as ActionParser.Result.One).action)
    }
}
