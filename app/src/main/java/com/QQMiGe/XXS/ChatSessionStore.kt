package com.QQMiGe.XXS

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI 对话会话的持久化。
 *
 * 为什么落文件而不是 SharedPreferences：
 * 一条会话可能几十上百条消息，且 AI 回复动辄上千字。全部塞进一个 XML 里，
 * 每次读写都要整体序列化/反序列化 —— 消息攒多了就会在主线程抖。一条会话
 * 一个文件，读写只碰当前这条，代价与总量无关。
 *
 * 目录：`<app>/files/ai_chats/<id>.json`，不需要任何存储权限。
 *
 * 存两份消息，别嫌冗余：
 *  - `ui`  —— 界面上显示的那几条（角色 + 文本），用于恢复聊天记录视图
 *  - `api` —— 真正发给模型的完整上下文，含 assistant 的 tool_calls 与
 *             role=tool 的结果。这两者数量并不相等：一轮工具调用在界面上
 *             只占一条「正在执行 xxx」，在 api 里却是三条消息。
 *             只存一份就无法既还原界面又还原上下文。
 */
object ChatSessionStore {

    private const val DIR = "ai_chats"
    private const val MAX_SESSIONS = 100

    /** 一条会话。id 用创建时的毫秒时间戳，天然有序且不会重名。 */
    data class Session(
        val id: Long,
        val title: String,
        val updatedAt: Long,
        /** 界面消息：role / content */
        val ui: List<UiMessage>,
        /** 模型上下文：完整消息（含工具调用） */
        val api: List<AiClient.Message>
    ) {
        /** 消息条数按界面口径算，用户看到的是这个数 */
        val size: Int get() = ui.size
    }

    data class UiMessage(val role: String, val content: String)

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun fileOf(context: Context, id: Long): File = File(dir(context), "$id.json")

    /** 列出全部会话，最近更新的排前面 */
    fun list(context: Context): List<Session> {
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { readFile(it) }.sortedByDescending { it.updatedAt }
    }

    fun load(context: Context, id: Long): Session? = readFile(fileOf(context, id))

    /**
     * 异步落盘。
     *
     * 用一个模块级、永不关闭的单线程池，而不是各调用方自己的池 ——
     * 会话要在「AI 回复完成」和「弹窗关闭」两个时机存，后者发生时调用方的
     * 线程池往往已经 shutdownNow 了，提交进去的任务会被直接丢弃。
     */
    fun saveAsync(context: Context, session: Session) {
        val app = context.applicationContext
        io.execute { save(app, session) }
    }

    private val io: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 保存（新建或覆盖）。
     *
     * @return 落盘后的会话（标题可能被自动截取过）
     */
    fun save(context: Context, session: Session): Session {
        val f = fileOf(context, session.id)
        try {
            f.writeText(encode(session), Charsets.UTF_8)
        } catch (_: Exception) {
            // 落盘失败不该影响正在进行对话，静默即可
        }
        trim(context)
        return session
    }

    fun delete(context: Context, id: Long) {
        try {
            fileOf(context, id).delete()
        } catch (_: Exception) {
            // 忽略
        }
    }

    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /**
     * 从界面消息里生成标题：取第一条用户消息的前 18 个字符。
     * 没有用户消息（例如刚建就关掉）时回落到「未命名对话」，由调用方给文案。
     */
    fun titleOf(ui: List<UiMessage>, fallback: String): String {
        val first = ui.firstOrNull { it.role == "user" && it.content.isNotBlank() } ?: return fallback
        val oneLine = first.content.replace('\n', ' ').trim()
        return if (oneLine.length <= 18) oneLine else oneLine.take(18) + "…"
    }

    // ---------------- 序列化 ----------------

    private fun encode(s: Session): String = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("updatedAt", s.updatedAt)
        put("ui", JSONArray().apply {
            s.ui.forEach { m ->
                put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
        })
        put("api", JSONArray().apply {
            s.api.forEach { m -> put(encodeMessage(m)) }
        })
    }.toString()

    private fun encodeMessage(m: AiClient.Message): JSONObject = JSONObject().apply {
        put("role", m.role)
        put("content", m.content)
        m.toolCallId?.let { put("toolCallId", it) }
        if (m.toolCalls.isNotEmpty()) {
            put("toolCalls", JSONArray().apply {
                m.toolCalls.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("arguments", c.arguments)
                    })
                }
            })
        }
    }

    private fun readFile(f: File): Session? = try {
        val root = JSONObject(f.readText(Charsets.UTF_8))

        val ui = mutableListOf<UiMessage>()
        root.optJSONArray("ui")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                ui.add(UiMessage(o.optString("role", "assistant"), o.optString("content", "")))
            }
        }

        val api = mutableListOf<AiClient.Message>()
        root.optJSONArray("api")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val calls = mutableListOf<AiClient.ToolCall>()
                o.optJSONArray("toolCalls")?.let { ca ->
                    for (j in 0 until ca.length()) {
                        val c = ca.optJSONObject(j) ?: continue
                        calls.add(
                            AiClient.ToolCall(
                                id = c.optString("id", "call_$j"),
                                name = c.optString("name", ""),
                                arguments = c.optString("arguments", "{}")
                            )
                        )
                    }
                }
                api.add(
                    AiClient.Message(
                        role = o.optString("role", "user"),
                        content = o.optString("content", ""),
                        toolCalls = calls,
                        toolCallId = o.optString("toolCallId", "").ifBlank { null }
                    )
                )
            }
        }

        Session(
            id = root.optLong("id", f.nameWithoutExtension.toLongOrNull() ?: 0L),
            title = root.optString("title", ""),
            updatedAt = root.optLong("updatedAt", f.lastModified()),
            ui = ui,
            api = api
        )
    } catch (_: Exception) {
        // 单个文件损坏（比如写了一半被杀进程）不该让整个历史列表打不开
        null
    }

    /** 超出上限时删掉最旧的几条，避免目录无限膨胀 */
    private fun trim(context: Context) {
        val all = list(context)
        if (all.size <= MAX_SESSIONS) return
        all.drop(MAX_SESSIONS).forEach { delete(context, it.id) }
    }
}
