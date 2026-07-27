package com.clawdroid.app.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelApiClientTest {
    @Test
    fun extractOpenAiMessageContentHandlesStringContent() {
        val message = JSONObject().put("role", "assistant").put("content", "  hello  ")
        assertEquals("hello", ModelApiClient.extractOpenAiMessageContent(message))
    }

    @Test
    fun extractOpenAiMessageContentJoinsTextParts() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "part-a"))
            .put(JSONObject().put("type", "text").put("text", "part-b"))
        val message = JSONObject().put("content", content)
        assertEquals("part-a\npart-b", ModelApiClient.extractOpenAiMessageContent(message))
    }

    @Test
    fun extractAnthropicTextContentSkipsThinkingBlocks() {
        val root = JSONObject().put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "secret"))
                .put(
                    JSONObject().put("type", "text").put(
                        "text",
                        """{"mode":"chat","reply":"完成","tool":"","arguments":{},"reason":"ok"}"""
                    )
                )
        )
        val text = ModelApiClient.extractAnthropicTextContent(root)
        assertEquals(
            """{"mode":"chat","reply":"完成","tool":"","arguments":{},"reason":"ok"}""",
            text
        )
    }

    @Test
    fun extractAnthropicTextContentReturnsNullWhenOnlyThinking() {
        val root = JSONObject().put(
            "content",
            JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "only"))
        )
        assertNull(ModelApiClient.extractAnthropicTextContent(root))
    }

    @Test
    fun parseOpenAiGenerationReadsToolCalls() {
        val response = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", JSONObject.NULL)
                            .put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "call_abc")
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", "capture_screen")
                                                .put("arguments", """{"read_after_capture":true}""")
                                        )
                                )
                            )
                    )
                )
            )
            .toString()
        val result = ModelApiClient.parseOpenAiGeneration(response)
        assertTrue(result.text.isBlank())
        assertEquals(1, result.toolCalls.size)
        assertEquals("capture_screen", result.toolCalls[0].name)
        assertTrue(result.toolCalls[0].argumentsJson.contains("read_after_capture"))
    }

    @Test
    fun parseAnthropicGenerationReadsToolUse() {
        val response = JSONObject()
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "thinking").put("thinking", "plan"))
                    .put(JSONObject().put("type", "text").put("text", "我先探测。"))
                    .put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", "toolu_1")
                            .put("name", "probe_session")
                            .put("input", JSONObject())
                    )
            )
            .toString()
        val result = ModelApiClient.parseAnthropicGeneration(response)
        assertEquals("我先探测。", result.text)
        assertEquals(1, result.toolCalls.size)
        assertEquals("probe_session", result.toolCalls[0].name)
    }

    @Test
    fun buildOpenAiUserContentIncludesImageUrlPart() {
        val image = ModelUserImage(mimeType = "image/jpeg", base64Data = "abc123")
        val content = ModelApiClient.buildOpenAiUserContent("看这张图", image) as JSONArray
        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("看这张图", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(
            content.getJSONObject(1).getJSONObject("image_url").getString("url")
                .startsWith("data:image/jpeg;base64,")
        )
    }

    @Test
    fun buildAnthropicUserContentIncludesBase64Image() {
        val image = ModelUserImage(mimeType = "image/png", base64Data = "xyz")
        val content = ModelApiClient.buildAnthropicUserContent("describe", image) as JSONArray
        assertEquals(2, content.length())
        assertEquals("image", content.getJSONObject(1).getString("type"))
        val source = content.getJSONObject(1).getJSONObject("source")
        assertEquals("base64", source.getString("type"))
        assertEquals("image/png", source.getString("media_type"))
        assertEquals("xyz", source.getString("data"))
    }
}
