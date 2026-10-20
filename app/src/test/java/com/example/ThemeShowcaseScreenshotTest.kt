package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
class ThemeShowcaseScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun theme_showcase() {
    composeTestRule.setContent { MyApplicationTheme(darkTheme = true) { ThemeShowcase() } }
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/theme_showcase.png")
  }
}

@Composable
private fun Swatch(name: String, color: Color, textColor: Color = Color.White) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      modifier =
        Modifier.size(56.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(color)
          .border(1.dp, Color(0xFF3A3A41), RoundedCornerShape(12.dp))
    )
    Spacer(Modifier.height(4.dp))
    Text(name, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
  }
}

@Composable
private fun ThemeShowcase() {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(BackgroundGradientStart, BackgroundGradientEnd)))
        .padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Zhypix Obsidian", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
    Text(
      "Monochrome foundation with semantic status hues",
      style = MaterialTheme.typography.bodyMedium,
      color = TextSecondary,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Swatch("base", ObsidianBase)
      Swatch("surface", SurfaceDark)
      Swatch("elevated", SurfaceElevated)
      Swatch("overlay", SurfaceOverlay)
      Swatch("accent", Accent)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Swatch("success", SuccessGreen)
      Swatch("warning", OrangeWarning)
      Swatch("error", CrimsonRose)
      Swatch("info", InfoBlue)
      Swatch("violet", VioletAccent)
    }

    // Sample chat bubbles
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Box(
        modifier =
          Modifier.clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).background(Accent).padding(horizontal = 14.dp, vertical = 10.dp)
      ) {
        Text("Open YouTube and play lofi", color = OnAccent, style = MaterialTheme.typography.bodyMedium)
      }
    }
    Surface(
      color = SurfaceDark,
      shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceLightBorder),
    ) {
      Text(
        "Sure - opening YouTube now and searching for lofi beats.",
        color = TextPrimary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      )
    }

    // Status row
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(8.dp).clip(CircleShape).background(SuccessGreen))
      Text("Agent active", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
      Spacer(Modifier.width(8.dp))
      Box(Modifier.size(8.dp).clip(CircleShape).background(OrangeWarning))
      Text("Confirm needed", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
  }
}
