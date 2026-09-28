package com.QQMiGe.XXS

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容协议的 Chat Completions 客户端，支持 Function Calling。
 *
 * 协议要点（HTTP 层）：
 *  - POST {base}/chat/completions
 *  - Header: Authorization: Bearer <key>, Content-Type: application/json
 *  - Body: { model, messages[], tools[], tool_choice: "auto", temperature, max_tokens }
 *  - 返回: choices[0].message，可能带 tool_calls[]
 *
 * 只依赖 HttpURLConnection 与 org.json（Android 内置），不引入 OkHttp/Retrofit，
 * 保持零新增依赖。
 */
object AiClient {

    /** 单轮对话消息 */
    data class Message(
        /** system / user / assistant / tool */
        val role: String,
        val content: String,
        /** assistant 发起的工具调用（原样回传给下一轮） */
        val toolCalls: List<ToolCall> = emptyList(),
        /** role=tool 时对应的调用 id */
        val toolCallId: String? = null
    )

    data class ToolCall(
        val id: String,
        val name: String,
        /** 模型给出的参数 JSON 字符串 */
        val arguments: String
    )

    /** 一轮请求的结果：要么是文本回复，要么是要执行的工具调用 */
    data class Reply(
        val content: String,
        val toolCalls: List<ToolCall>
    )

    class AiException(message: String) : Exception(message)

    /**
     * 发起一次对话请求。
     *
     * @param tools 工具声明（JSON Schema 数组），传空数组表示禁用工具
     */
    fun chat(
        config: AiConfig.Data,
        messages: List<Message>,
        tools: JSONArray
    ): Reply {
        if (!config.usable) {
            throw AiException("AI 未配置：请在右上角设置里填写 API Key 与模型名")
        }

        val body = JSONObject().apply {
            put("model", config.model)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("messages", JSONArray().apply {
                messages.forEach { put(it.toJson()) }
            })
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        val raw = post(config, body.toString())
        return parseReply(raw)
    }

    private fun Message.toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        when {
            toolCalls.isNotEmpty() -> {
                // assistant 带工具调用时 content 可为空，但字段必须在
                put("content", if (content.isEmpty()) JSONObject.NULL else content)
                put("tool_calls", JSONArray().apply {
                    toolCalls.forEach { call ->
                        put(
                            JSONObject().apply {
                                put("id", call.id)
                                put("type", "function")
                                put(
                                    "function", JSONObject().apply {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    }
                                )
                            }
                        )
                    }
                })
            }
            role == "tool" -> {
                put("content", content)
                put("tool_call_id", toolCallId ?: "")
            }
            else -> put("content", content)
        }
    }

    /** 发 HTTP 请求，返回响应体文本；非 2xx 抛异常并带上服务端的错误原文 */
    private fun post(config: AiConfig.Data, json: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""

            if (code !in 200..299) {
                throw AiException("HTTP $code\n${extractError(text)}")
            }
            text
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("请求失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    /** 从服务端错误体里挖出可读信息，兼容 OpenAI / 各类中转的字段差异 */
    private fun extractError(text: String): String = try {
        val obj = JSONObject(text)
        val err = obj.opt("error")
        when (err) {
            is JSONObject -> err.optString("message", text)
            is String -> err
            else -> obj.optString("message", text)
        }
    } catch (e: Exception) {
        text.take(600)
    }

    private fun parseReply(raw: String): Reply {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw AiException("响应不是合法 JSON：\n${raw.take(600)}")
        }

        val choices = root.optJSONArray("choices")
            ?: throw AiException("响应缺少 choices 字段：\n${raw.take(600)}")
        if (choices.length() == 0) throw AiException("响应 choices 为空：\n${raw.take(600)}")

        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw AiException("响应缺少 message 字段：\n${raw.take(600)}")

        val content = message.optString("content", "")

        val calls = mutableListOf<ToolCall>()
        val arr = message.optJSONArray("tool_calls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val fn = item.optJSONObject("function") ?: continue
                calls.add(
                    ToolCall(
                        id = item.optString("id", "call_$i"),
                        name = fn.optString("name", ""),
                        arguments = fn.optString("arguments", "{}")
                    )
                )
            }
        }

        return Reply(content, calls)
    }
}
