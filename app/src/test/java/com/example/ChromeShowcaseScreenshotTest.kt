package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.model.ChatMessage
import com.example.ui.ClaudeGroupedCard
import com.example.ui.ClaudeSettingRow
import com.example.ui.WizardChecklistRow
import com.example.ui.components.SegmentedControlGroup
import com.example.ui.components.SubScreenHeader
import com.example.ui.components.SystemMessageItem
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
class ChromeShowcaseScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun chrome_showcase() {
    composeTestRule.setContent { MyApplicationTheme(darkTheme = true) { ChromeShowcase() } }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/chrome_showcase.png")
  }
}

@Composable
private fun ChromeShowcase() {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(BackgroundGradientStart, BackgroundGradientEnd)))
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SubScreenHeader(title = "AI Provider", onBack = {})

    SegmentedControlGroup(
      items = listOf("a" to "Gemini", "b" to "OpenAI", "c" to "Claude"),
      selectedItem = "a",
      onItemSelected = {},
    )

    ClaudeGroupedCard {
      ClaudeSettingRow(
        icon = Icons.Default.Person,
        title = "Provider profile",
        subtitle = "API key, model and endpoint",
        showDivider = true,
        onClick = {},
      )
      ClaudeSettingRow(
        icon = Icons.Default.VolumeUp,
        title = "Voice replies",
        subtitle = "Read AI answers aloud",
        badgeText = "ON",
        showDivider = false,
        onClick = {},
      )
    }

    WizardChecklistRow(
      title = "Accessibility service",
      description = "Lets Zhypix see and tap the screen for you.",
      isActive = true,
      actionLabel = "Enable",
      onClick = {},
    )
    WizardChecklistRow(
      title = "Overlay permission",
      description = "Shows the floating bubble over other apps.",
      isActive = false,
      actionLabel = "Grant",
      onClick = {},
    )

    SystemMessageItem(
      ChatMessage.System(
        text = "Zhypix wants to CLICK \"Send\" - this may send, pay, or confirm something. Reply 'confirm' to proceed or 'cancel' to skip."
      )
    )
  }
}
