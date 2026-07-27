package com.clawdroid.app.data.model

internal enum class ThemeMode {
    FollowSystem,
    Dark,
    Light
}

// ---------------------------------------------------------------------------
// 模型供应商
// ---------------------------------------------------------------------------
internal enum class ModelProvider(
    val displayName: String,
    val hint: String,
    val defaultBaseUrl: String,
    val apiPathStyle: ApiPathStyle,
    val supportsStreaming: Boolean = true,
    val supportsSystemPrompt: Boolean = true,
    val authHeaderName: String = "Authorization",
    val authHeaderPrefix: String = "Bearer",
    val defaultModelName: String = ""
) {
    OpenAI(
        displayName = "OpenAI",
        hint = "官方 GPT / o 系列 · OpenAI 协议 · Bearer",
        defaultBaseUrl = "https://api.openai.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Anthropic(
        displayName = "Anthropic",
        hint = "官方 Claude · Anthropic Messages · x-api-key",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        apiPathStyle = ApiPathStyle.Anthropic,
        authHeaderName = "x-api-key",
        authHeaderPrefix = ""
    ),
    Gemini(
        displayName = "Gemini",
        hint = "Google Gemini · OpenAI 兼容网关 · Bearer",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Deepseek(
        displayName = "DeepSeek",
        hint = "DeepSeek Chat/Reasoner · OpenAI 兼容 · Bearer",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Kimi(
        displayName = "Kimi",
        hint = "Moonshot Kimi · OpenAI 兼容 · Bearer",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Qwen(
        displayName = "Qwen",
        hint = "阿里通义千问 · 兼容模式 · Bearer",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Zhipu(
        displayName = "智谱 GLM",
        hint = "智谱 GLM · OpenAI 兼容 · Bearer",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    TencentHunyuan(
        displayName = "腾讯混元",
        hint = "腾讯混元官方 · OpenAI 兼容 · Bearer",
        defaultBaseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    TencentTokenHub(
        displayName = "腾讯云 TokenHub",
        hint = "腾讯云 MaaS TokenHub · OpenAI 兼容 · 免费额度 · 默认 hy3",
        defaultBaseUrl = "https://tokenhub.tencentmaas.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI,
        defaultModelName = "hy3"
    ),
    Baidu(
        displayName = "百度文心",
        hint = "文心 / ERNIE · 千帆 OpenAI 兼容 · Bearer",
        defaultBaseUrl = "https://qianfan.baidubce.com/v2",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    MiniMax(
        displayName = "MiniMax",
        hint = "MiniMax 海螺 · OpenAI 兼容 · Bearer",
        defaultBaseUrl = "https://api.minimax.chat/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    SiliconFlow(
        displayName = "硅基流动",
        hint = "SiliconFlow 聚合 · OpenAI 兼容 · 支持 /models",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    OpenRouter(
        displayName = "OpenRouter",
        hint = "多模型聚合网关 · 需 /api/v1 · Bearer",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    TogetherAI(
        displayName = "Together AI",
        hint = "Together 聚合推理 · OpenAI 兼容 · Bearer",
        defaultBaseUrl = "https://api.together.xyz/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Groq(
        displayName = "Groq",
        hint = "Groq 低延迟推理 · OpenAI 兼容 · Bearer",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    OpenAICompatible(
        displayName = "OpenAI 兼容",
        hint = "中转站 / NewAPI / OneAPI · Base 填到 /v1 · Bearer",
        defaultBaseUrl = "https://api.openai.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    AnthropicCompatible(
        displayName = "Anthropic 兼容",
        hint = "Claude 中转 · Base 填到 /v1 · x-api-key",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        apiPathStyle = ApiPathStyle.Anthropic,
        authHeaderName = "x-api-key",
        authHeaderPrefix = ""
    ),
    ClaudeCode(
        displayName = "Claude Code",
        hint = "Claude Code / 项目令牌 · Anthropic Messages · x-api-key",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        apiPathStyle = ApiPathStyle.Anthropic,
        authHeaderName = "x-api-key",
        authHeaderPrefix = ""
    ),
    Codex(
        displayName = "Codex (OpenAI)",
        hint = "OpenAI Codex / Copilot 底层 · OpenAI 协议 · Bearer",
        defaultBaseUrl = "https://api.openai.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Custom(
        displayName = "自定义",
        hint = "完全自定义 URL 与路径 · 自行指定协议形态",
        defaultBaseUrl = "",
        apiPathStyle = ApiPathStyle.Custom
    ),
    Local(
        displayName = "本地模型",
        hint = "Ollama / LM Studio / vLLM · 本机 OpenAI 兼容 · 通常无需 Key",
        defaultBaseUrl = "",
        apiPathStyle = ApiPathStyle.OpenAI,
        authHeaderPrefix = ""
    );

    fun protocolLabel(): String = when (apiPathStyle) {
        ApiPathStyle.OpenAI -> "OpenAI /chat/completions"
        ApiPathStyle.Anthropic -> "Anthropic /messages"
        ApiPathStyle.Custom -> "自定义路径"
    }

    fun authLabel(): String {
        if (authHeaderName.equals("x-api-key", ignoreCase = true) || authHeaderPrefix.isBlank()) {
            return if (this == Local) {
                "通常无需鉴权（可留空）"
            } else {
                "Header: x-api-key（兼容 Bearer）"
            }
        }
        return "Header: $authHeaderName = $authHeaderPrefix …"
    }

    fun baseUrlGuidance(): String = when (this) {
        OpenAICompatible -> "中转站示例: https://你的域名/v1（不要填到 chat/completions）"
        AnthropicCompatible -> "Claude 中转示例: https://你的域名/v1"
        SiliconFlow -> "默认: https://api.siliconflow.cn/v1"
        OpenRouter -> "必须含 /api/v1，例如 https://openrouter.ai/api/v1"
        TencentTokenHub -> "默认: https://tokenhub.tencentmaas.com/v1（模型可填 hy3）"
        Custom -> "填写完整 URL，包含路径"
        Local -> "Ollama 默认 http://127.0.0.1:11434/v1"
        else -> if (defaultBaseUrl.isNotBlank()) {
            "默认: $defaultBaseUrl"
        } else {
            "按供应商文档填写 Base URL"
        }
    }

    fun unifiedDescription(): String = buildString {
        append(hint)
        append("\n协议:")
        append(protocolLabel())
        append("\n鉴权:")
        append(authLabel())
        if (defaultBaseUrl.isNotBlank()) {
            append("\n默认地址:")
            append(defaultBaseUrl)
        }
        if (defaultModelName.isNotBlank()) {
            append("\n默认模型:")
            append(defaultModelName)
        }
        if (this@ModelProvider == Local) {
            append("\n说明: 填写本机接口与模型名；可点连通探测验证")
        }
    }

    companion object {
        val officialProviders = listOf(
            OpenAI, Anthropic, Gemini
        )
        val chineseProviders = listOf(
            Deepseek, Kimi, Qwen, Zhipu, TencentHunyuan, TencentTokenHub,
            Baidu, MiniMax, SiliconFlow
        )
        val aggregatorProviders = listOf(
            OpenRouter, TogetherAI, Groq
        )
        val protocolProviders = listOf(
            OpenAICompatible, AnthropicCompatible, ClaudeCode, Codex
        )
        val otherProviders = listOf(
            Custom, Local
        )
        val all = entries.toList()
    }
}

/**
 * API 路径拼接风格：
 * - OpenAI: /chat/completions
 * - Anthropic: /messages
 * - Custom: 不自动拼接，由用户完全控制
 */
internal enum class ApiPathStyle {
    OpenAI,
    Anthropic,
    Custom
}

// ---------------------------------------------------------------------------
// 模型设置
// ---------------------------------------------------------------------------
internal data class ModelSettings(
    val provider: ModelProvider = ModelProvider.OpenAI,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelName: String = "",
    val localEndpoint: String = "http://127.0.0.1:11434/v1",
    val localModelName: String = "",
    val customApiPath: String = "/chat/completions",
    val urlPathMode: UrlPathMode = UrlPathMode.AutoAppend,
    val proxySettings: NetworkProxySettings = NetworkProxySettings(),
    val contextSettings: ContextSettings = ContextSettings()
) {
    fun resolvedEndpoint(): String {
        return when (provider) {
            ModelProvider.Local -> localEndpoint.trimEnd('/')
            else -> baseUrl.trimEnd('/')
        }
    }

    fun resolvedModelName(): String {
        return when (provider) {
            ModelProvider.Local -> localModelName
            else -> modelName
        }
    }
}

internal enum class UrlPathMode {
    AutoAppend,
    FullUrl,
    AppendCustom
}

internal enum class NetworkProxyMode(val displayName: String) {
    System("跟随系统 / VPN"),
    Http("HTTP 代理"),
    Socks("SOCKS5 代理")
}

internal data class NetworkProxySettings(
    val mode: NetworkProxyMode = NetworkProxyMode.System,
    val host: String = "127.0.0.1",
    val port: Int = 7890,
    val username: String = "",
    val password: String = ""
) {
    fun summary(): String {
        return when (mode) {
            NetworkProxyMode.System -> "系统路由（VPN 生效时自动走 VPN）"
            NetworkProxyMode.Http -> "HTTP $host:$port"
            NetworkProxyMode.Socks -> "SOCKS5 $host:$port"
        }
    }

    fun isCustomProxy(): Boolean = mode != NetworkProxyMode.System
}

// ---------------------------------------------------------------------------
// 上下文设置
// ---------------------------------------------------------------------------
internal data class ContextSettings(
    val systemPrompt: String = "",
    /** Max tokens for a single model response (completion). */
    val maxTokens: Int = 4096,
    /**
     * Model context window (input+output budget) in tokens.
     * 0 = auto from [ModelContextWindowCatalog] by model name.
     */
    val contextWindowTokens: Int = 0,
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val topK: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val thinkingBudget: Int? = null
) {
    companion object {
        const val MIN_MAX_TOKENS = 1
        const val MAX_MAX_TOKENS = 200000
        const val MIN_CONTEXT_WINDOW = 0
        const val MAX_CONTEXT_WINDOW = 2_000_000
        const val MIN_TEMPERATURE = 0f
        const val MAX_TEMPERATURE = 2f
        const val MIN_TOP_P = 0f
        const val MAX_TOP_P = 1f
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 4096
        const val MIN_THINKING_BUDGET = 1024
        const val MAX_THINKING_BUDGET = 200000
    }

    fun effectiveContextWindow(modelName: String): Int {
        if (contextWindowTokens > 0) return contextWindowTokens.coerceIn(1024, MAX_CONTEXT_WINDOW)
        return ModelContextWindowCatalog.resolve(modelName)
    }
}

// ---------------------------------------------------------------------------
// Agent 编排设置
// ---------------------------------------------------------------------------
internal data class AgentOrchestrationSettings(
    val maxToolLoopTurns: Int = DEFAULT_MAX_TOOL_LOOP_TURNS,
    val maxModelApiCalls: Int = DEFAULT_MAX_MODEL_API_CALLS,
    val contextCompressionEnabled: Boolean = true,
    val toolAllowlist: Set<String> = emptySet(),
    val toolAllowlistCustomized: Boolean = false,
    /** When true, dispatcher rejects tools outside the effective allowlist. */
    val enforceToolAllowlist: Boolean = true,
    /** When false, run_agent / run_agents_parallel are blocked. */
    val agentCallsEnabled: Boolean = true,
    /** When true, dangerous tools require an in-chat confirm before execute. */
    val requireCommandReview: Boolean = false
) {
    fun effectiveAllowlist(): Set<String> =
        if (toolAllowlistCustomized) toolAllowlist else Companion.defaultAllowlist()

    fun isToolAllowed(toolId: String): Boolean {
        if (!enforceToolAllowlist) return true
        return toolId in effectiveAllowlist()
    }

    fun isAgentCallTool(toolId: String): Boolean =
        toolId == "run_agent" || toolId == "run_agents_parallel"

    fun needsCommandReview(toolId: String): Boolean =
        requireCommandReview && toolId in defaultDangerousTools()

    companion object {
        const val DEFAULT_MAX_TOOL_LOOP_TURNS = 16
        const val DEFAULT_MAX_MODEL_API_CALLS = 1000
        const val MIN_TOOL_LOOP_TURNS = 1
        const val MAX_TOOL_LOOP_TURNS_CAP = 64
        const val MIN_MODEL_API_CALLS = 10
        const val MAX_MODEL_API_CALLS_CAP = 10000

        fun defaultDangerousTools(): Set<String> = setOf(
            "sandbox_shell",
            "termux_exec",
            "execute_shell_limited",
            "shizuku_exec",
            "shizuku_request",
            "file_write",
            "file_replace",
            "camera_capture",
            "camera_record",
            "ftp_transfer",
            "app_launch"
        )

        fun defaultAllowlist(): Set<String> = setOf(
            "file_read",
            "file_write",
            "file_replace",
            "file_stat",
            "file_list",
            "runtime_ping",
            "get_version",
            "get_health",
            "get_runtime_status",
            "get_last_error",
            "probe_session",
            "get_capabilities",
            "capture_screen",
            "read_latest_capture",
            "run_agent",
            "run_agents_parallel",
            "list_agents",
            "list_skills",
            "get_skill",
            "list_tools",
            "get_tool",
            "task_get",
            "task_list",
            "task_submit",
            "task_cancel",
            "task_wait",
            "assist_status",
            "assist_ping",
            "assist_list_tools",
            "assist_call_tool",
            "sandbox_shell",
            "termux_exec",
            "execute_shell_limited",
            "app_list",
            "app_info",
            "app_launch",
            "web_preview",
            "web_search",
            "notification_list",
            "download_start",
            "download_status",
            "download_cancel",
            "download_verify"
        )
    }
}

// ---------------------------------------------------------------------------
// 辅助函数
// ---------------------------------------------------------------------------
internal fun defaultBaseUrlFor(provider: ModelProvider): String = provider.defaultBaseUrl

internal fun modelProviderLabel(provider: ModelProvider): String = provider.displayName

internal fun modelProviderHint(provider: ModelProvider): String = provider.hint

internal fun modelProviderDescription(provider: ModelProvider): String = provider.unifiedDescription()
