package com.example

import com.example.agent.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenLoopTest {
    private fun observation() = ScreenLoop.Observation(null, "Current app: Settings", "")
    @Test fun capturesAgainAfterEveryAction() = runTest {
        var captures=0; var decisions=0; var executions=0
        val loop=ScreenLoop({ captures++;observation() }, { _, _ ->
            if (decisions++ == 0) "press_back()" else "finished({\"message\":\"Done\"})"
        }, { executions++;ScreenLoop.Execution(true,"ok") }, sleep={})
        assertTrue(loop.run() is ScreenAction.Finished)
        assertEquals(2,captures);assertEquals(1,executions)
    }
    @Test fun uncertainActionIsNeverRepeated() = runTest {
        var executions=0
        val loop=ScreenLoop({observation()}, { _, _ -> "press_back()" }, {
            executions++;ScreenLoop.Execution(false,"timeout",false)
        },sleep={})
        assertTrue(loop.run() is ScreenAction.FailedToCompleteTask);assertEquals(1,executions)
    }
    @Test fun definitePreDispatchFailureMayRetryOnceAfterOneSecond() = runTest {
        var executions=0;val delays=mutableListOf<Long>()
        val loop=ScreenLoop({observation()}, { _, _ -> "press_back()" }, {
            executions++;ScreenLoop.Execution(false,"not dispatched",true)
        },sleep={delays.add(it)})
        loop.run();assertEquals(2,executions);assertEquals(listOf(1000L),delays)
    }
    @Test fun confirmationStopsBeforeDispatch() = runTest {
        val loop=ScreenLoop({observation()}, { _, _ -> "press_back()" }, {fail("must not execute");ScreenLoop.Execution(true,"")},sleep={},confirmBefore={"Confirm?"})
        assertEquals(ScreenAction.NeedsConfirmation("Confirm?"),loop.run())
    }
    @Test fun cancellationNeverRetries() = runTest {
        var captures=0
        val loop=ScreenLoop({captures++;throw CancellationException()}, { _, _ -> "" }, {ScreenLoop.Execution(true,"")},sleep={fail("must not sleep")})
        try {loop.run();fail("must cancel")}catch (_:CancellationException){}
        assertEquals(1,captures)
    }
    @Test fun maxStepsStopsWithoutExtraExecution() = runTest {
        var executions=0
        val loop=ScreenLoop({observation()}, { _, _ -> "press_back()" }, {executions++;ScreenLoop.Execution(true,"")},sleep={})
        assertTrue(loop.run(2) is ScreenAction.FailedToCompleteTask);assertEquals(2,executions)
    }
}
