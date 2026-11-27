package com.clawdroid.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawdroid.app.data.model.ModelSettings
import com.clawdroid.app.data.model.ThemeMode
import com.clawdroid.app.ui.settings.SettingsReadinessCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsReadinessCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readinessCardOmitsRedundantTitle() {
        composeRule.setContent {
            ClawdroidTheme(themeMode = ThemeMode.Dark) {
                SettingsReadinessCard(
                    currentThemeMode = ThemeMode.FollowSystem,
                    modelSettings = ModelSettings(),
                    connectionSummary = "未测试模型接口",
                    validationMessage = null
                )
            }
        }

        composeRule.onNodeWithText("设置总览").assertDoesNotExist()
        composeRule.onNodeWithText("主题 跟随系统").assertExists()
    }
}
