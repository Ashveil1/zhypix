package com.example

import com.example.agent.ActionParseResult
import com.example.agent.ActionParser
import com.example.agent.ScreenAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenActionsTest {

    private fun ok(reply: String): ScreenAction {
        val r = ActionParser.parse(reply)
        assertTrue("expected Ok, got $r", r is ActionParseResult.Ok)
        return (r as ActionParseResult.Ok).action
    }

    @Test
    fun clickParsesCoordinates() {
        val a = ok("""click({"x": 540, "y": 1230})""")
        assertEquals(ScreenAction.Click(540, 1230), a)
    }

    @Test
    fun numbersArrivingAsStringsAreCoerced() {
        val a = ok("""click({"x": "540", "y": "1230"})""")
        assertEquals(ScreenAction.Click(540, 1230), a)
    }

    @Test
    fun doubleClickAndHold() {
        assertEquals(ScreenAction.DoubleClick(10, 20), ok("""double_click({"x":10,"y":20})"""))
        val hold = ok("""press_and_hold({"x":1,"y":2,"duration_ms":900})""")
        assertEquals(ScreenAction.PressAndHold(1, 2, 900L), hold)
        val holdDefault = ok("""press_and_hold({"x":1,"y":2})""")
        assertEquals(ScreenAction.PressAndHold(1, 2, null), holdDefault)
    }

    @Test
    fun dragAndDragAndDrop() {
        val d = ok("""drag({"start_x":1,"start_y":2,"end_x":3,"end_y":4,"duration_ms":860})""")
        assertEquals(ScreenAction.Drag(1, 2, 3, 4, 860L), d)
        val dd = ok("""drag_and_drop({"start_x":5,"start_y":6,"end_x":7,"end_y":8})""")
        assertEquals(ScreenAction.DragAndDrop(5, 6, 7, 8), dd)
    }

    @Test
    fun typeWaitNavAndStartApp() {
        assertEquals(ScreenAction.Type("hello world"), ok("""type({"text":"hello world"})"""))
        assertEquals(ScreenAction.Wait(1500L), ok("""wait({"seconds":1.5})"""))
        assertEquals(ScreenAction.PressBack, ok("press_back()"))
        assertEquals(ScreenAction.PressHome, ok("""press_home({})"""))
        assertEquals(ScreenAction.StartApp("com.android.settings"), ok("""start_app({"package_name":"com.android.settings"})"""))
    }

    @Test
    fun walkthroughCarriesSteps() {
        val w = ok("""walkthrough({"content":"1. Open Settings\n2. Tap Wi-Fi"})""")
        assertTrue(w is ScreenAction.Walkthrough)
        assertTrue((w as ScreenAction.Walkthrough).steps.contains("Wi-Fi"))
    }

    @Test
    fun terminals() {
        assertEquals(ScreenAction.Finished("done"), ok("""finished({"message":"done"})"""))
        assertEquals(ScreenAction.FailedToCompleteTask("no button"), ok("""failed_to_complete_task({"reason":"no button"})"""))
        assertEquals(ScreenAction.MessageUser("hi"), ok("""message_user({"message":"hi"})"""))
        assertEquals(ScreenAction.NeedsConfirmation("send it?"), ok("""needs_confirmation({"question":"send it?"})"""))
        assertEquals(ScreenAction.NeedLogin("sign in"), ok("""need_login({"content":"sign in"})"""))
        assertEquals(ScreenAction.NeedLoginDetails("creds"), ok("""need_login_details({"content":"creds"})"""))
        assertEquals(ScreenAction.EncounteredCaptcha("captcha"), ok("""encountered_captcha({"message":"captcha"})"""))
    }

    @Test
    fun proseAroundActionIsRejected() {
        assertEquals(ActionParseResult.NotAnAction, ActionParser.parse("I will tap.\nclick({\"x\":100,\"y\":200})"))
    }

    @Test
    fun nonFiniteAndNegativeCoordinatesAreRejected() {
        assertTrue(ActionParser.parse("""click({"x":"NaN","y":2})""") is ActionParseResult.Invalid)
        assertTrue(ActionParser.parse("""click({"x":-1,"y":2})""") is ActionParseResult.Invalid)
        assertTrue(ActionParser.parse("""wait({"seconds":-1})""") is ActionParseResult.Invalid)
    }

    @Test
    fun plainProseIsNotAnAction() {
        assertEquals(ActionParseResult.NotAnAction, ActionParser.parse("Just chatting, nothing to do."))
        assertEquals(ActionParseResult.NotAnAction, ActionParser.parse(""))
    }

    @Test
    fun malformedArgsAreInvalid() {
        val r = ActionParser.parse("""click({"x": true, "y": 1})""")
        assertTrue(r is ActionParseResult.Invalid)
        val r2 = ActionParser.parse("""click({"x":1})""")
        assertTrue(r2 is ActionParseResult.Invalid)
        val r3 = ActionParser.parse("""click({not json})""")
        assertTrue(r3 is ActionParseResult.Invalid)
    }

    @Test
    fun multipleActionsAreRejected() {
        assertTrue(ActionParser.parse("""click({"x":1,"y":2})
press_home({})""") is ActionParseResult.Invalid)
    }

    @Test
    fun unknownNameIsInvalid() {
        val r = ActionParser.parse("""teleport({"x":1,"y":2})""")
        assertTrue(r is ActionParseResult.Invalid)
    }
}

class ScreenActionsNormalizeTest {
    @org.junit.Test fun acceptsNameKeyedObject() {
        val r = com.example.agent.ActionParser.parse("""{"name":"click","x":155,"y":303}""")
        org.junit.Assert.assertTrue(r is com.example.agent.ActionParseResult.Ok)
    }
    @org.junit.Test fun acceptsActionKeyedObject() {
        val r = com.example.agent.ActionParser.parse("""{"click":{"x":155,"y":303}}""")
        org.junit.Assert.assertTrue(r is com.example.agent.ActionParseResult.Ok)
    }
    @org.junit.Test fun acceptsFencedAction() {
        val r = com.example.agent.ActionParser.parse("```\nclick({\"x\":1,\"y\":2})\n```")
        org.junit.Assert.assertTrue(r is com.example.agent.ActionParseResult.Ok)
    }
    @org.junit.Test fun stillRejectsProseAndMultipleCalls() {
        org.junit.Assert.assertFalse(com.example.agent.ActionParser.parse("I will click({\"x\":1,\"y\":2})") is com.example.agent.ActionParseResult.Ok)
        org.junit.Assert.assertFalse(com.example.agent.ActionParser.parse("click({\"x\":1,\"y\":2})\nclick({\"x\":3,\"y\":4})") is com.example.agent.ActionParseResult.Ok)
    }
}
