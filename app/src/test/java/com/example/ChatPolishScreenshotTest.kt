package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.model.AgentAction
import com.example.model.ChatMessage
import com.example.ui.components.*
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
class ChatPolishScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun chat_polish_showcase() {
    composeTestRule.setContent { MyApplicationTheme(darkTheme = true) { ChatShowcase() } }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/chat_polish.png")
  }
}

@Composable
private fun ChatShowcase() {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(BackgroundGradientStart, BackgroundGradientEnd)))
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SystemMessageItem(ChatMessage.System(text = "Session started"))
    UserMessageBubble(ChatMessage.User(text = "Open YouTube and play something chill"))
    AgentMessageBubble(
      ChatMessage.Agent(
        text =
          "Sure - here is the plan:\n\n1. Open YouTube\n2. Search for lofi\n\n```bash\nam start -a android.intent.action.VIEW -d https://youtube.com\n```"
      )
    )
    TaskExecutionItem(
      ChatMessage.TaskExecution(
        action = AgentAction(actionType = "CLICK", target = "com.google.android.youtube:id/search"),
        status = "Completed",
        resultSnippet = null,
      )
    )
    ProviderConfigRequiredBubble(ChatMessage.ProviderConfigRequired())
    ThinkingIndicator()
  }
}
