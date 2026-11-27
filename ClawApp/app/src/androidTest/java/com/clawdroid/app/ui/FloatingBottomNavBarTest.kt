package com.clawdroid.app.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawdroid.app.data.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingBottomNavBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allDestinationsAreVisibleAndClickable() {
        var selected: ConsolePage? = null
        composeRule.setContent {
            ClawdroidTheme(themeMode = ThemeMode.Dark) {
                FloatingBottomNavBar(
                    currentPage = ConsolePage.Overview,
                    onPageSelected = { selected = it }
                )
            }
        }

        composeRule.onNodeWithText("概览").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("聊天").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("任务").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText("设置").assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals(ConsolePage.Settings, selected) }
    }
}
