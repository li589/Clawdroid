package com.clawdroid.app.ui

internal enum class ConsolePage {
    Overview,
    Chat,
    Settings
}

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
    val authHeaderPrefix: String = "Bearer"
) {
    OpenAI(
        displayName = "OpenAI",
        hint = "官方 OpenAI GPT 系列模型",
        defaultBaseUrl = "https://api.openai.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Anthropic(
        displayName = "Anthropic",
        hint = "官方 Claude 系列模型",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        apiPathStyle = ApiPathStyle.Anthropic,
        authHeaderName = "x-api-key",
        authHeaderPrefix = ""
    ),
    Gemini(
        displayName = "Gemini",
        hint = "Google Gemini 系列模型",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Deepseek(
        displayName = "DeepSeek",
        hint = "DeepSeek 系列模型",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Kimi(
        displayName = "Kimi",
        hint = "Moonshot Kimi 系列模型",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Qwen(
        displayName = "Qwen",
        hint = "阿里通义千问系列模型",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Zhipu(
        displayName = "智谱 GLM",
        hint = "智谱 AI GLM 系列模型",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    TencentHunyuan(
        displayName = "腾讯混元",
        hint = "腾讯混元大模型",
        defaultBaseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Baidu(
        displayName = "百度文心",
        hint = "百度文心一言 / ERNIE 系列",
        defaultBaseUrl = "https://qianfan.baidubce.com/v2",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    MiniMax(
        displayName = "MiniMax",
        hint = "MiniMax 海螺 AI",
        defaultBaseUrl = "https://api.minimax.chat/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    SiliconFlow(
        displayName = "硅基流动",
        hint = "SiliconFlow OpenAI 兼容接口（含 /v1/models）",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    OpenRouter(
        displayName = "OpenRouter",
        hint = "OpenRouter 聚合网关，支持 100+ 模型",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    TogetherAI(
        displayName = "Together AI",
        hint = "Together AI 聚合平台",
        defaultBaseUrl = "https://api.together.xyz/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    Groq(
        displayName = "Groq",
        hint = "Groq 超低延迟推理",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    // 协议兼容模式：用户填入自定义地址，程序按所选协议组装请求
    OpenAICompatible(
        displayName = "OpenAI 兼容",
        hint = "中转站 / NewAPI / OneAPI：Base 填到 /v1，自动拉 /models",
        defaultBaseUrl = "https://api.openai.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    AnthropicCompatible(
        displayName = "Anthropic 兼容",
        hint = "Claude 中转：Base 填到 /v1，使用 x-api-key",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        apiPathStyle = ApiPathStyle.Anthropic,
        authHeaderName = "x-api-key",
        authHeaderPrefix = ""
    ),
    // Claude Code / Codex 等专用工具接口
    ClaudeCode(
        displayName = "Claude Code",
        hint = "Anthropic Claude Code CLI 接口（需要项目令牌）",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        apiPathStyle = ApiPathStyle.Anthropic,
        authHeaderName = "x-api-key",
        authHeaderPrefix = ""
    ),
    Codex(
        displayName = "Codex (OpenAI)",
        hint = "OpenAI Codex 接口（Codex CLI / Copilot 底层）",
        defaultBaseUrl = "https://api.openai.com/v1",
        apiPathStyle = ApiPathStyle.OpenAI
    ),
    // 自定义：完全由用户控制 URL 和路径
    Custom(
        displayName = "自定义",
        hint = "完全自定义 URL 和请求参数",
        defaultBaseUrl = "",
        apiPathStyle = ApiPathStyle.Custom
    ),
    // 本地模型：Ollama / LM Studio / vLLM 等
    Local(
        displayName = "本地模型",
        hint = "Ollama / LM Studio / vLLM 等本地推理服务",
        defaultBaseUrl = "",
        apiPathStyle = ApiPathStyle.OpenAI,
        authHeaderPrefix = ""  // 本地通常不需要鉴权
    );

    companion object {
        /**
         * 官方支持的供应商（用于 UI 分类展示）
         */
        val officialProviders = listOf(
            OpenAI, Anthropic, Gemini
        )
        val chineseProviders = listOf(
            Deepseek, Kimi, Qwen, Zhipu, TencentHunyuan, Baidu, MiniMax, SiliconFlow
        )
        val aggregatorProviders = listOf(
            OpenRouter, TogetherAI, Groq
        )
        val protocolProviders = listOf(
            OpenAICompatible, AnthropicCompatible, ClaudeCode, Codex
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
    // 本地模型专用
    val localEndpoint: String = "http://127.0.0.1:11434/v1",
    val localModelName: String = "",
    // 自定义路径（仅 Custom / 部分协议兼容模式使用）
    val customApiPath: String = "/chat/completions",
    // 请求路径拼接策略：自动补全（默认）或用户输入完整路径
    val urlPathMode: UrlPathMode = UrlPathMode.AutoAppend,
    // 网络代理：跟随系统/VPN，或本地 HTTP/SOCKS 代理（Clash / V2Ray 等）
    val proxySettings: NetworkProxySettings = NetworkProxySettings(),
    // 上下文参数
    val contextSettings: ContextSettings = ContextSettings()
) {
    /**
     * 解析最终使用的 endpoint（自动去除末尾斜杠）
     */
    fun resolvedEndpoint(): String {
        return when (provider) {
            ModelProvider.Local -> localEndpoint.trimEnd('/')
            else -> baseUrl.trimEnd('/')
        }
    }

    /**
     * 解析最终使用的模型名称
     */
    fun resolvedModelName(): String {
        return when (provider) {
            ModelProvider.Local -> localModelName
            else -> modelName
        }
    }
}

/**
 * URL 路径拼接模式：
 * - AutoAppend: 自动拼接 /chat/completions、/messages 或列表时的 /models
 * - FullUrl: 用户输入完整 URL；拉模型列表时会把 chat/messages 后缀替换为 /models
 * - AppendCustom: 域名 + 自定义聊天路径；拉列表时自动改成 /models
 */
internal enum class UrlPathMode {
    AutoAppend,   // 自动补全（默认）：如用户填 https://xxx.com/v1，追加 /chat/completions
    FullUrl,      // 完整 URL：直接使用用户输入的 URL（列表请求会智能改写）
    AppendCustom  // 追加自定义路径：用户输入域名 + 手动填路径
}

/**
 * AI 请求网络出口：
 * - System: 跟随系统路由（含已连接的 VPN）
 * - Http: 本地/远程 HTTP 代理（常见 7890 Clash）
 * - Socks: SOCKS5 代理
 */
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
    val maxTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val topK: Int? = null,           // 仅 Anthropic 系列
    val stopSequences: List<String> = emptyList(),
    val thinkingBudget: Int? = null  // 仅 Claude 3.7+ 支持 extended thinking
) {
    companion object {
        const val MIN_MAX_TOKENS = 1
        const val MAX_MAX_TOKENS = 200000
        const val MIN_TEMPERATURE = 0f
        const val MAX_TEMPERATURE = 2f
        const val MIN_TOP_P = 0f
        const val MAX_TOP_P = 1f
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 4096
        const val MIN_THINKING_BUDGET = 1024
        const val MAX_THINKING_BUDGET = 200000
    }
}

// ---------------------------------------------------------------------------
// 辅助函数
// ---------------------------------------------------------------------------
internal fun defaultBaseUrlFor(provider: ModelProvider): String {
    return provider.defaultBaseUrl
}

/**
 * 获取模型供应商的显示标签
 */
internal fun modelProviderLabel(provider: ModelProvider): String = provider.displayName

/**
 * 获取模型供应商的简短提示
 */
internal fun modelProviderHint(provider: ModelProvider): String = provider.hint

// ---------------------------------------------------------------------------
// 聊天消息
// ---------------------------------------------------------------------------
internal enum class ChatRole {
    User,
    Assistant
}

internal enum class ChatMessageState {
    Final,
    Streaming
}

internal data class ChatMessage(
    val id: String = newChatMessageId(),
    val role: ChatRole,
    val content: String,
    val attachmentLabel: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val state: ChatMessageState = ChatMessageState.Final
)

internal fun newChatMessageId(): String {
    return "msg-${System.currentTimeMillis()}-${(0..9999).random()}"
}
