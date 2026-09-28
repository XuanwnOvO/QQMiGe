package com.QQMiGe.XXS

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 项目备注（别名）的本地存储。
 *
 * 为什么存在 QQMIGe 自己的私有目录，而不是写进项目目录里：
 * 备注是「我给这个项目起的名字」，属于编辑器自己的元数据。
 * 往 QQ 的游戏数据目录里塞一个 note.txt 只会污染游戏本体，
 * 万一被小游戏的文件校验扫到还多一份风险。
 *
 * 为什么 key 用绝对路径：小游戏目录名是随机 hash（形如
 * 6A3BB7D3D4349E2050E7B701AAD51600_03dc8333...），项目重装后
 * 换的是 hash 不是路径含义，路径才是稳定标识。
 */
object Notes {

    private const val FILE_NAME = "notes.json"

    /** 备注长度上限，避免超长文本把列表撑爆 */
    private const val MAX_LEN = 64

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 读取全部备注：绝对路径 -> 备注名 */
    @Synchronized
    fun all(context: Context): Map<String, String> {
        val f = file(context)
        if (!f.exists()) return emptyMap()
        return try {
            val json = JSONObject(f.readText())
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                val value = json.optString(key, "")
                if (value.isNotEmpty()) map[key] = value
            }
            map
        } catch (_: Exception) {
            // 文件损坏就当没有备注，不要因为一份元数据让列表打不开
            emptyMap()
        }
    }

    /** 取单条备注，没有则返回 null */
    fun get(context: Context, path: String): String? = all(context)[path]

    /** 设置备注；传空串等于清除备注 */
    @Synchronized
    fun set(context: Context, path: String, note: String) {
        val map = all(context).toMutableMap()
        val trimmed = note.trim().take(MAX_LEN)
        if (trimmed.isEmpty()) map.remove(path) else map[path] = trimmed
        write(context, map)
    }

    /** 删除项目的备注记录（删目录时一并清掉，避免留下野数据） */
    @Synchronized
    fun remove(context: Context, path: String) {
        val map = all(context).toMutableMap()
        if (map.remove(path) != null) write(context, map)
    }

    private fun write(context: Context, map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        file(context).writeText(json.toString())
    }
}
