package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListToolSchemaTest {

    @Test
    fun fileListExistsInRegistry() {
        val tool = ClawTool.entries.firstOrNull { it.toolId == "file_list" }
        assertNotNull(tool)
        assertEquals(ClawTool.FILE_LIST, tool)
    }

    @Test
    fun fileListSchemaRequiresPath() {
        val def = ClawToolCatalog.definitions(
            context = null,
            includePlanned = true,
            onlyEnabled = false,
            respectAllowlist = false
        ).first { it.tool == ClawTool.FILE_LIST }
        val required = def.inputSchema.optJSONArray("required")
        assertNotNull(required)
        val keys = (0 until required!!.length()).map { required.getString(it) }
        assertTrue(keys.contains("path"))
        val props = def.inputSchema.getJSONObject("properties")
        assertTrue(props.has("offset"))
        assertTrue(props.has("limit"))
    }

    @Test
    fun defaultAllowlistIncludesFileList() {
        val allow = com.clawdroid.app.ui.AgentOrchestrationSettings.defaultAllowlist()
        assertTrue(allow.contains("file_list"))
        assertTrue(allow.contains("run_agents_parallel"))
        assertTrue(allow.contains("termux_exec"))
        assertTrue(allow.contains("execute_shell_limited"))
    }
}
