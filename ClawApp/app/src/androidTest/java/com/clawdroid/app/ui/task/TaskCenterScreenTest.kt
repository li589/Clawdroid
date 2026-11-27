package com.clawdroid.app.ui.task

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawdroid.app.agent.task.ExecutionStep
import com.clawdroid.app.agent.task.ExecutionTask
import com.clawdroid.app.agent.task.StepKind
import com.clawdroid.app.agent.task.TaskCheckpoint
import com.clawdroid.app.agent.task.TaskFailure
import com.clawdroid.app.agent.task.TaskState
import com.clawdroid.app.data.model.ThemeMode
import com.clawdroid.app.ui.ClawdroidTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskCenterScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsFailureDiagnosticRetryAndExpandableTimeline() {
        var retried = false
        var selectedId by mutableStateOf<String?>(null)
        val task = ExecutionTask(
            id = "t1",
            title = "页面点击",
            originPrompt = "点击继续",
            steps = listOf(
                ExecutionStep("confirm", "页面确认", StepKind.ConfirmPage),
                ExecutionStep("tap", "安全点击", StepKind.SafeTap)
            ),
            state = TaskState.Failed,
            checkpoint = TaskCheckpoint(
                completedStepIds = setOf("confirm"),
                runtimeTaskId = "rt-1"
            ),
            failure = TaskFailure("target_not_found", "未找到目标")
        )
        composeRule.setContent {
            ClawdroidTheme(themeMode = ThemeMode.Dark) {
                TaskCenterScreen(
                    state = TaskCenterUiState(history = listOf(task), selectedTaskId = selectedId),
                    onPause = {},
                    onResume = {},
                    onRetry = { retried = true },
                    onCancel = {},
                    onSelect = { selectedId = if (selectedId == task.id) null else task.id }
                )
            }
        }

        composeRule.onNodeWithText("重试").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("target_not_found").assertIsDisplayed()
        composeRule.onNodeWithText("详情").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("步骤时间线").assertIsDisplayed()
        composeRule.onNodeWithText("Runtime 任务：rt-1").assertIsDisplayed()
        composeRule.onNodeWithText("收起").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("步骤时间线").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(retried) }
    }
}
