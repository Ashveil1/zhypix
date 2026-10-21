package com.zhypix.fresh

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test

class LaunchTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun chatScreenShows() {
        rule.onNodeWithText("Zhypix").assertIsDisplayed()
        rule.onNodeWithText("Providers").assertIsDisplayed()
    }
}
