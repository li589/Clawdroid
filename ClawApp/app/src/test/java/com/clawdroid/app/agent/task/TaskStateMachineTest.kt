package com.clawdroid.app.agent.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {
    @Test
    fun runningCanPauseButSucceededCannot() {
        assertTrue(TaskStateMachine.canTransition(TaskState.Running, TaskState.Paused))
        assertFalse(TaskStateMachine.canTransition(TaskState.Succeeded, TaskState.Running))
    }

    @Test
    fun cancelledIsTerminal() {
        assertFalse(TaskStateMachine.canTransition(TaskState.Cancelled, TaskState.Running))
        assertFalse(TaskStateMachine.canTransition(TaskState.Cancelled, TaskState.Cancelled))
    }
}
