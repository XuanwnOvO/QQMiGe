package com.QQMiGe.XXS

import org.json.JSONArray
import org.json.JSONObject

/**
 * 提供给 AI 的文件操作工具集。
 *
 * 沙箱原则：AI 的读写范围被【硬限制在项目根目录之内】。
 *  - 所有路径先做 textual 归一化（折叠 "//"、解析 "." 与 ".."）
 *  - 归一化后必须以 projectRoot 开头，否则直接拒绝
 *  - 拒绝符号链接逃逸的兜底：写文件前用 `readlink -f` 解析真实路径再校验一次
 *
 * 这条边界不是「建议」而是「强制」：即使模型被提示词诱导去写
 * /system 或其它项目目录，也会在执行层被拒。
 */
class AiTools(private val projectRoot: String) {

    init {
        require(projectRoot.startsWith("/")) { "projectRoot 必须是绝对路径" }
    }

    private val root = normalize(projectRoot)

    /** 工具声明，直接塞进 Chat Completions 的 tools 字段 */
    fun declarations(): JSONArray = JSONArray().apply {
        put(
            tool(
                "list_dir",
                "列出目录下的文件和子目录。路径相对于项目根目录，或留空表示项目根目录。",
                JSONObject().apply {
                    put("type", "object")
                    put(
                        "properties", JSONObject().apply {
                            put(
                                "path", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "相对项目根的路径，留空为项目根目录")
                                }
                            )
                        }
                    )
                }
            )
        )
        put(
            tool(
                "read_file",
                "读取文本文件的完整内容。用于修改前确认上下文。",
                JSONObject().apply {
                    put("type", "object")
                    put(
                        "properties", JSONObject().apply {
                            put(
                                "path", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "相对项目根的路径")
                                }
                            )
                        }
                    )
                    put("required", JSONArray().put("path"))
                }
            )
        )
        put(
            tool(
                "write_file",
                "把内容写入文件（覆盖写）。文件不存在则创建，父目录不存在会自动创建。",
                JSONObject().apply {
                    put("type", "object")
                    put(
                        "properties", JSONObject().apply {
                            put(
                                "path", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "相对项目根的路径")
                                }
                            )
                            put(
                                "content", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "要写入的完整文件内容")
                                }
                            )
                        }
                    )
                    put("required", JSONArray().put("path").put("content"))
                }
            )
        )
        put(
            tool(
                "search_text",
                "在项目内递归搜索包含指定文本的文件，返回 文件:行号:内容。用于定位代码位置。",
                JSONObject().apply {
                    put("type", "object")
                    put(
                        "properties", JSONObject().apply {
                            put(
                                "keyword", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "要搜索的文本")
                                }
                            )
                            put(
                                "path", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "搜索起点，相对项目根，留空为项目根")
                                }
                            )
                        }
                    )
                    put("required", JSONArray().put("keyword"))
                }
            )
        )
    }

    private fun tool(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject().apply {
            put("type", "function")
            put(
                "function", JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("parameters", parameters)
                }
            )
        }

    /**
     * 执行一次工具调用，返回给模型的文本结果。
     * 任何异常都转成可读文本回传，而不是抛出中断对话。
     */
    fun execute(name: String, argumentsJson: String): String = try {
        val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        when (name) {
            "list_dir" -> listDir(args.optString("path", ""))
            "read_file" -> readFile(args.optString("path", ""))
            "write_file" -> writeFile(args.optString("path", ""), args.optString("content", ""))
            "search_text" -> searchText(args.optString("keyword", ""), args.optString("path", ""))
            else -> "未知工具：$name"
        }
    } catch (e: AiToolsException) {
        "错误：${e.message}"
    } catch (e: Exception) {
        "错误：${e.message ?: e.javaClass.simpleName}"
    }

    private class AiToolsException(message: String) : Exception(message)

    // ---------------- 各工具实现 ----------------

    private fun listDir(rel: String): String {
        val target = resolve(rel)
        val result = RootShell.listDir(target)
        if (!result.ok) return "无法列出目录：${result.error}"
        if (result.entries.isEmpty()) return "目录为空：$target"
        return buildString {
            append("目录：").append(target).append('\n')
            result.entries.forEach {
                append(if (it.isDirectory) "[目录] " else "[文件] ").append(it.name).append('\n')
            }
        }.trimEnd()
    }

    private fun readFile(rel: String): String {
        val target = resolve(rel)
        // 与编辑器走同一条读取路径：一次 su 往返拿回类型 + 大小 + 前缀内容
        val read = RootShell.readFileSmart(target)
        if (!read.ok) return "错误：读取失败：$rel"
        if (!read.isText) return "错误：$rel 不是文本文件，拒绝读取"
        if (read.content.isEmpty()) {
            // 空内容可能是真空文件，也可能是读取失败；用大小区分
            if (read.sizeBytes < 0) return "错误：文件不存在或无法读取：$rel"
            if (read.sizeBytes == 0L) return "文件为空：$rel"
        }
        return read.content
    }

    private fun writeFile(rel: String, content: String): String {
        val target = resolve(rel)

        // 二次校验：即使 resolve 通过，也要防「路径里某级是符号链接指向外部」。
        // 路径先按当前数据通道映射（可能带 /proc/1/root 前缀），
        // 校验时再还原成逻辑路径，否则前缀会让「是否在项目内」的判断误判成逃逸。
        val real = RootShell.exec("readlink -f \"${RootShell.mapPath(target)}\" 2>/dev/null")
            .output.trim().let { RootShell.unmapPath(it) }
        if (real.isNotEmpty() && real.startsWith("/") && !real.startsWith(root)) {
            throw AiToolsException("拒绝写入：真实路径 $real 逃出项目根目录 $root")
        }

        // 父目录不存在则创建
        val parent = target.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != root) {
            RootShell.exec("mkdir -p \"${RootShell.mapPath(parent)}\"")
        }

        val ok = RootShell.writeTextFile(target, content)
        if (!ok) throw AiToolsException("写入失败：$rel")
        return "已写入 $rel（${content.toByteArray(Charsets.UTF_8).size} 字节）"
    }

    private fun searchText(keyword: String, rel: String): String {
        if (keyword.isBlank()) throw AiToolsException("keyword 不能为空")
        val target = resolve(rel)
        val safe = keyword.replace("'", "'\\''")
        // -I 跳过二进制；限制条数避免把上下文撑爆
        val r = RootShell.exec(
            "grep -rIn --include='*' -e '$safe' \"${RootShell.mapPath(target)}\" 2>/dev/null | head -80"
        )
        val out = r.output.trim()
        return out.ifBlank { "未找到匹配：$keyword" }
    }

    // ---------------- 路径沙箱 ----------------

    /**
     * 把相对路径解析为项目内的绝对路径，并强制校验不越界。
     *
     * 归一化规则：折叠重复斜杠、丢弃 "."、遇到 ".." 弹出一级。
     * 这是纯文本层面的解析 —— 之所以还要在写文件时补一次 readlink 校验，
     * 是因为文本归一化挡不住「项目内某个目录本身是指向外部的符号链接」。
     */
    private fun resolve(rel: String): String {
        val cleaned = rel.trim().trimStart('/')
        val parts = ArrayDeque<String>()
        cleaned.split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        val abs = if (parts.isEmpty()) root else "$root/${parts.joinToString("/")}"
        if (abs != root && !abs.startsWith("$root/")) {
            throw AiToolsException("拒绝访问项目根目录之外的路径：$rel")
        }
        return abs
    }

    /** 暴露给界面展示，让用户清楚 AI 的可写范围 */
    fun sandboxRoot(): String = root

    private fun normalize(path: String): String {
        val t = path.trim().trimEnd('/')
        return t.ifEmpty { "/" }
    }
}
