package com.QQMiGe.XXS

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * QQ 跳转链接工具。
 *
 * 说明一下这三类接口的差别，因为「能不能跳」取决于用户手机里装了什么：
 *
 * 1. 打开个人资料卡（加好友）—— 移动端专用 scheme：
 *    `mqqapi://card/show_pslcard?src_type=internal&version=1&uin={QQ}&card_type=person`
 *    装了 QQ 就能直接拉起资料卡；没装会 ActivityNotFoundException。
 *
 * 2. 打开群资料卡（加群）—— 同一个 scheme，换个 card_type：
 *    `mqqapi://card/show_pslcard?src_type=internal&version=1&uin={群号}&card_type=group`
 *    这是 QQ 群「一键加群」在客户端内的原生实现路径，比网页版少一次跳转。
 *
 * 3. 网页兜底 —— 没装 QQ 或 scheme 被拦截时用：
 *    加好友 `https://wpa.qq.com/msgrd?v=3&uin={QQ}&site=qq&menu=yes`
 *    加群   `https://qm.qq.com/cgi-bin/qm/qr?k=&group_code={群号}`
 *
 * 统一策略：先试 scheme，抛异常再退回 https。这样两种环境都能走通。
 */
object QqLinks {

    /** 个人资料卡 scheme */
    fun personScheme(qq: String): String =
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$qq&card_type=person"

    /** 群资料卡 scheme */
    fun groupScheme(group: String): String =
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$group&card_type=group"

    /** 加好友网页兜底 */
    fun personWeb(qq: String): String =
        "https://wpa.qq.com/msgrd?v=3&uin=$qq&site=qq&menu=yes"

    /** 加群网页兜底 */
    fun groupWeb(group: String): String =
        "https://qm.qq.com/cgi-bin/qm/qr?k=&group_code=$group"

    /**
     * 打开个人资料卡。
     *
     * @return true = 成功拉起 QQ；false = 连网页兜底都失败（没有浏览器）
     */
    fun openPerson(context: Context, qq: String): Boolean =
        openWithFallback(context, personScheme(qq), personWeb(qq))

    /** 打开群资料卡，语义同 [openPerson] */
    fun openGroup(context: Context, group: String): Boolean =
        openWithFallback(context, groupScheme(group), groupWeb(group))

    private fun openWithFallback(context: Context, scheme: String, web: String): Boolean {
        // 先试 QQ 客户端。FLAG_ACTIVITY_NEW_TASK 是必需的 ——
        // 从非 Activity 上下文启动，不带这个 flag 会直接抛异常。
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(scheme))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (_: Exception) {
            // 没装 QQ / scheme 被 ROM 拦截，走网页
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(web))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 用系统浏览器打开任意链接（引用仓库跳转用） */
    fun openUrl(context: Context, url: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}
