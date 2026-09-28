package com.QQMiGe.XXS

import android.content.Context

/**
 * AI 模型配置的持久化（SharedPreferences）。
 *
 * 只存纯文本，不加密：API Key 明文存在应用私有目录，
 * 未 root 的其他应用无法读取；已 root 的环境本就不在此防护范围内。
 */
object AiConfig {

    private const val PREF = "ai_config"
    private const val KEY_BASE = "base_url"
    private const val KEY_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_TEMP = "temperature"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_SYSTEM = "system_prompt"

    const val DEFAULT_BASE = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"
    const val DEFAULT_TEMP = 0.2
    const val DEFAULT_MAX_TOKENS = 4096

    /**
     * 系统提示词默认值。
     *
     * 首选项默认就是这份模板；配置页另有「清空」与「恢复默认」两个按钮，
     * 分别把输入框置空 / 填回这里，都只改输入框内容，是否落盘由「确定」决定。
     */
    const val SYSTEM_TEMPLATE =
        "你是 QQ 小游戏（Cocos2d-JS）代码修改助手。" +
            "你可以调用工具查看当前项目目录、读取文件、写入文件。" +
            "修改代码前先读原文件确认上下文，保持原有代码风格与缩进。" +
            "只改必要的地方，不要重构无关代码。写入文件后会告知用户结果。"

    data class Data(
        val baseUrl: String = DEFAULT_BASE,
        val apiKey: String = "",
        val model: String = DEFAULT_MODEL,
        val temperature: Double = DEFAULT_TEMP,
        val maxTokens: Int = DEFAULT_MAX_TOKENS,
        /** 系统提示词；空串表示不发 system 消息 */
        val systemPrompt: String = SYSTEM_TEMPLATE
    ) {
        /** 配置是否可用：至少要有 Key 和模型名 */
        val usable: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()

        /** 拼接 chat/completions 端点，容忍用户填末尾斜杠或直接填到 /chat/completions */
        val endpoint: String
            get() {
                val base = baseUrl.trim().trimEnd('/')
                return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            }
    }

    fun load(context: Context): Data {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Data(
            baseUrl = sp.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE,
            apiKey = sp.getString(KEY_KEY, "") ?: "",
            model = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            temperature = sp.getString(KEY_TEMP, null)?.toDoubleOrNull() ?: DEFAULT_TEMP,
            maxTokens = sp.getString(KEY_MAX_TOKENS, null)?.toIntOrNull() ?: DEFAULT_MAX_TOKENS,
            // 没存过就用模板；用户主动清空存了空串，那就保持空串
            systemPrompt = sp.getString(KEY_SYSTEM, SYSTEM_TEMPLATE) ?: SYSTEM_TEMPLATE
        )
    }

    fun save(context: Context, data: Data) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE, data.baseUrl.trim())
            .putString(KEY_KEY, data.apiKey.trim())
            .putString(KEY_MODEL, data.model.trim())
            .putString(KEY_TEMP, data.temperature.toString())
            .putString(KEY_MAX_TOKENS, data.maxTokens.toString())
            .putString(KEY_SYSTEM, data.systemPrompt)
            .apply()
    }
}
