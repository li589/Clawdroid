package com.clawdroid.app.mcp

import com.clawdroid.app.tools.ClawTool
import com.clawdroid.app.tools.ClawToolCallResult
import com.clawdroid.app.tools.ClawToolCatalog
import com.clawdroid.app.tools.ClawToolDispatcher
import com.clawdroid.app.tools.ClawToolExecutor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpJsonRpcHandlerTest {
    @Test
    fun toolsListExposesCatalog() = runBlocking {
        val executor = mockk<ClawToolExecutor>(relaxed = true)
        val handler = McpJsonRpcHandler(ClawToolDispatcher(executor))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
        )!!
        val json = JSONObject(response)
        val tools = json.getJSONObject("result").getJSONArray("tools")
        assertEquals(ClawToolCatalog.definitions().size, tools.length())
        assertTrue(tools.getJSONObject(0).has("inputSchema"))
    }

    @Test
    fun toolsCallRoutesToDispatcher() = runBlocking {
        val executor = mockk<ClawToolExecutor>()
        coEvery { executor.ping() } returns ClawToolCallResult(
            success = true,
            output = "pong-ok"
        )
        val handler = McpJsonRpcHandler(ClawToolDispatcher(executor))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"runtime_ping","arguments":{}}}"""
        )!!
        val json = JSONObject(response)
        val result = json.getJSONObject("result")
        assertFalse(result.getBoolean("isError"))
        assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").contains("pong-ok"))
    }

    @Test
    fun initializeReturnsToolsCapability() = runBlocking {
        val handler = McpJsonRpcHandler(ClawToolDispatcher(mockk(relaxed = true)))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        )!!
        val result = JSONObject(response).getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
        assertEquals("clawdroid-assist", result.getJSONObject("serverInfo").getString("name"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
        assertTrue(result.getJSONObject("capabilities").has("prompts"))
        assertTrue(result.getJSONObject("capabilities").has("resources"))
    }

    @Test
    fun initializeFallsBackForUnknownProtocolVersion() = runBlocking {
        val handler = McpJsonRpcHandler(ClawToolDispatcher(mockk(relaxed = true)))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":9,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}"""
        )!!
        val result = JSONObject(response).getJSONObject("result")
        assertEquals("2024-11-05", result.getString("protocolVersion"))
    }

    @Test
    fun promptsGetUnknownReturnsInvalidParams() = runBlocking {
        val handler = McpJsonRpcHandler(ClawToolDispatcher(mockk(relaxed = true)))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":10,"method":"prompts/get","params":{"name":"no-such-skill"}}"""
        )!!
        val error = JSONObject(response).getJSONObject("error")
        assertEquals(-32602, error.getInt("code"))
    }

    @Test
    fun toolsCallUncaughtBecomesJsonRpcInternalError() = runBlocking {
        val dispatcher = mockk<ClawToolDispatcher>()
        coEvery { dispatcher.execute(any<String>(), any()) } throws RuntimeException("simulated-mcp-boom")
        val handler = McpJsonRpcHandler(dispatcher)
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"runtime_ping","arguments":{}}}"""
        )!!
        val error = JSONObject(response).getJSONObject("error")
        assertEquals(-32603, error.getInt("code"))
        assertTrue(error.getString("message").contains("mcp_internal"))
        assertEquals(42, JSONObject(response).getInt("id"))
    }

    @Test
    fun toolsCallExecutorThrowIsIsolatedAsToolError() = runBlocking {
        val executor = mockk<ClawToolExecutor>()
        coEvery { executor.ping() } throws RuntimeException("executor-boom")
        val handler = McpJsonRpcHandler(ClawToolDispatcher(executor))
        val response = handler.handle(
            """{"jsonrpc":"2.0","id":43,"method":"tools/call","params":{"name":"runtime_ping","arguments":{}}}"""
        )!!
        val json = JSONObject(response)
        assertFalse(json.has("error"))
        val result = json.getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
        assertTrue(result.getJSONArray("content").getJSONObject(0).getString("text").contains("内部错误已隔离"))
    }
}

class ClawToolCatalogTest {
    @Test
    fun everyToolHasSchema() {
        ClawTool.entries.forEach { tool ->
            val def = ClawToolCatalog.definition(tool.toolId)
            requireNotNull(def)
            assertEquals("object", def.inputSchema.getString("type"))
        }
    }
}
