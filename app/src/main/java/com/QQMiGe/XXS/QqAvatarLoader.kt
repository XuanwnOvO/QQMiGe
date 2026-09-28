package com.QQMiGe.XXS

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * QQ 头像加载器（实时拉取，零第三方依赖）。
 *
 * 接口：腾讯官方 qlogo 头像服务
 *  - `https://q1.qlogo.cn/g?b=qq&nk={QQ}&s=640`
 *  - `https://q.qlogo.cn/headimg_dl?dst_uin={QQ}&spec=640&img_type=jpg`
 *
 * 两个都是【实时】接口：用户在 QQ 里换了头像，这里下一次请求拿到的就是新图，
 * 不存在「同步延迟」或需要授权的问题 —— 所以不需要账号密码，只需要 QQ 号。
 *
 * 实时性怎么保证（关键）：
 *  1. 请求头带 `Cache-Control: no-cache` / `Pragma: no-cache`，绕开 HTTP 层缓存；
 *  2. URL 末尾追加 `&_t=<毫秒时间戳>`，绕开 CDN 边缘节点与本地 DNS 缓存 ——
 *     同一 URL 第二次请求可能直接命中 CDN，加时间戳后每次都是「新 URL」；
 *  3. 内存 [LruCache] 只做同一会话内的复用，[load] 传入 force=true 时跳过它。
 *
 * 线程模型：下载在固定线程池，回调 post 回主线程。
 * 用 `ImageView.tag` 存「本次请求期望的 QQ 号」，回写前比对 ——
 * 列表复用时旧请求晚到会覆盖新头像，这个比对就是防它。
 */
object QqAvatarLoader {

    /** 头像尺寸档位：640 是官方接口提供的最大档，够 96dp 圆形头像高清显示 */
    private const val SPEC = 640

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)

    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /**
     * 拉取并显示 QQ 头像。
     *
     * @param qq 纯数字 QQ 号
     * @param force true 时跳过内存缓存强制重新下载（用于「刷新头像」）
     * @param onResult 主线程回调，true 表示成功（失败时 view 保持原样）
     */
    fun load(
        qq: String,
        view: ImageView,
        force: Boolean = false,
        placeholderRes: Int? = null,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        if (qq.isBlank()) return
        view.tag = qq

        if (!force) {
            cache.get(qq)?.let {
                view.setImageBitmap(it)
                onResult?.invoke(true)
                return
            }
        }

        placeholderRes?.let { view.setImageResource(it) }

        io.execute {
            val bmp = download(qq)
            main.post {
                // 复用防错：View 已被绑到别的 QQ 号，本次结果作废
                if (view.tag != qq) {
                    onResult?.invoke(false)
                    return@post
                }
                if (bmp != null) {
                    cache.put(qq, bmp)
                    view.setImageBitmap(bmp)
                }
                onResult?.invoke(bmp != null)
            }
        }
    }

    /** 同步下载，失败返回 null（网络异常 / 该 QQ 号无头像时接口返回默认灰头像，仍算成功） */
    private fun download(qq: String): Bitmap? {
        // 两个域名依次尝试：q1 是主域名，headimg_dl 是备用路径
        val urls = listOf(
            "https://q1.qlogo.cn/g?b=qq&nk=$qq&s=$SPEC&_t=${System.currentTimeMillis()}",
            "https://q.qlogo.cn/headimg_dl?dst_uin=$qq&spec=$SPEC&img_type=jpg&_t=${System.currentTimeMillis()}"
        )

        for (u in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) QQMiniGameEditor")
                }
                if (conn.responseCode !in 200..299) continue
                conn.inputStream.use { return BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                // 换下一个域名重试
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    /** 主动丢弃某号缓存，供「刷新头像」用 */
    fun invalidate(qq: String) {
        cache.remove(qq)
    }
}
