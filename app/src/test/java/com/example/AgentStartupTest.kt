package com.example

import android.app.Application
import com.example.viewmodel.AgentViewModel
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AgentStartupTest {
    @Test fun processingFlowExistsBeforeCollectorStarts() {
        val vm=AgentViewModel(RuntimeEnvironment.getApplication<Application>())
        assertFalse(vm.isProcessing.value)
    }
}
