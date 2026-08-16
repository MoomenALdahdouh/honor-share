package com.honor.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsPrimaryActions() {
        rule.onNodeWithText("Direct Share").assertIsDisplayed()
        rule.onNodeWithText("Send files").assertIsDisplayed()
        rule.onNodeWithText("Receive").assertIsDisplayed()
    }
}
