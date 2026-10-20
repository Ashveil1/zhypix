package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.live.LiveSessionManager
import com.example.ui.live.LiveStatePill
import com.example.ui.theme.*
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class LiveUiScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun live_states_showcase() {
    composeTestRule.setContent { MyApplicationTheme(darkTheme = true) { LiveStates() } }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/live_states.png")
  }
}

@Composable
private fun LiveStates() {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(BackgroundGradientStart, BackgroundGradientEnd)))
        .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      "Live session states",
      style = MaterialTheme.typography.titleLarge,
      color = TextPrimary,
    )
    LiveSessionManager.State.values().forEach { state ->
      LiveStatePill(state)
    }
  }
}
