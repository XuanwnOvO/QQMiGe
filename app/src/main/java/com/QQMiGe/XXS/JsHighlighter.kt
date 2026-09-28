package com.QQMiGe.XXS

import android.text.Spannable
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import android.content.Context

/**
 * QQ 小游戏的 Cocos2d JS 语法高亮。
 *
 * 设计取舍（为什么不上 sora-editor 之类）：
 *  - 无状态：只产出一批 Span 交给 Spannable，不保留词法树，内存几乎零增量
 *  - 单遍正则：一次扫描同时匹配所有 token 类别，避免多轮遍历
 *  - 大文件保护：超过 [MAX_HIGHLIGHT_CHARS] 就放弃高亮（纯文本显示），
 *    避免在低端机上因重算全量 Span 造成卡顿
 *
 * 支持语言：JavaScript（Cocos 脚本）、JSON（配置/清单）、XML（plist 资源）
 */
object JsHighlighter {

    /**
     * 超过这个字符数就不高亮。
     *
     * 为什么从 300_000 砍到 80_000：300K 从来没被验证过，它只是「看起来
     * 很大所以应该安全」。实际上这正则是 9 个分支的交替式，每命中一个词都
     * 要回溯确认；一段 256KB 的 JS 会产生几万个匹配、几万个 setSpan。
     * 在手机上这就是秒级阻塞。
     *
     * 更糟的是它和 [RootShell.PREVIEW_MAX_BYTES] 差点撞上：预览上限 256KB，
     * 而纯 ASCII 的 256KB 恰好是 262144 个字符 —— 刚好卡在 300K 门槛之内。
     * 于是「把预览从 512KB 降到 256KB」这个本意是提速的改动，反而把高亮
     * 从「跳过」翻成了「执行」，直接造成卡死。阈值必须留出安全余量。
     */
    const val MAX_HIGHLIGHT_CHARS = 80_000

    /**
     * 一处高亮标记：起止下标 + 颜色。
     *
     * 刻意不带任何 View / Spannable 引用 —— 正因为它是纯数据，[scan] 才能
     * 拿到后台线程去跑。整个高亮链路里最贵的就是正则扫描，把它留在主线程
     * 是之前卡死的直接原因。
     */
    class Mark(val start: Int, val end: Int, val color: Int)

    /**
     * 纯计算：扫描 [text] 产出高亮标记，不接触任何 Spannable。
     *
     * 可在任意线程调用。超出上限时返回空列表 —— 调用方拿到空列表会清掉
     * 旧 Span，等价于「这个文件不高亮」，语义自洽。
     */
    fun scan(context: Context, text: CharSequence): List<Mark> {
        if (text.length > MAX_HIGHLIGHT_CHARS) return emptyList()

        val colors = Palette(context)
        // 预分配：经验上标记数约为字符数的 1/8，省掉几轮扩容拷贝
        val marks = ArrayList<Mark>(text.length / 8)

        for (m in TOKEN_REGEX.findAll(text)) {
            val color = when {
                m.groups["comment"] != null -> colors.comment
                m.groups["string"] != null -> colors.string
                m.groups["number"] != null -> colors.number
                m.groups["keyword"] != null -> colors.keyword
                m.groups["literal"] != null -> colors.literal
                m.groups["cocos"] != null -> colors.cocos
                m.groups["func"] != null -> colors.func
                // 普通标识符保持默认前景色（?attr/colorOnSurface），不再上色
                else -> continue
            }
            marks.add(Mark(m.range.first, m.range.last + 1, color))
        }
        return marks
    }

    /**
     * 把标记刷到目标 Spannable 上。
     *
     * 必须在主线程：这里改的是挂在 EditText 上的那个 Editable。
     * 先清旧 Span 再逐条添加，避免多次高亮叠加导致颜色混乱。
     *
     * 越界标记直接丢弃：扫描期间用户可能已经改了文本，坐标就对不上了。
     * 与其抛 IndexOutOfBounds，不如少画几个词的色。
     */
    fun apply(target: Spannable, marks: List<Mark>) {
        clear(target)
        for (mk in marks) {
            if (mk.start < 0 || mk.end > target.length) continue
            target.setSpan(
                ForegroundColorSpan(mk.color),
                mk.start,
                mk.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** [apply] 的可平移版本：标记坐标是在 [offset] 处切片的局部坐标，
     * 回写时要整体平移回全文坐标。 */
    fun apply(target: Spannable, marks: List<Mark>, offset: Int) {
        if (offset <= 0) {
            apply(target, marks)
            return
        }
        clear(target)
        for (mk in marks) {
            val s = mk.start + offset
            val e = mk.end + offset
            if (s < 0 || e > target.length) continue
            target.setSpan(
                ForegroundColorSpan(mk.color), s, e,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** 清掉全部前景色 Span，恢复默认前景色 */
    fun clear(target: Spannable) {
        target.getSpans(0, target.length, ForegroundColorSpan::class.java)
            .forEach { target.removeSpan(it) }
    }

    private const val F_COMMENT = 1 shl 0
    private const val F_STRING = 1 shl 1
    private const val F_NUMBER = 1 shl 2
    private const val F_KEYWORD = 1 shl 3
    private const val F_LITERAL = 1 shl 4
    private const val F_COCOS = 1 shl 5
    private const val F_FUNC = 1 shl 6

    /**
     * 关键字：JS 保留字 + 声明/控制流 + 常用运算符词。
     * 顺序无关，正则用 \b 边界。
     */
    private const val KEYWORDS =
        "abstract|arguments|async|await|break|case|catch|class|const|constructor|continue|" +
        "debugger|default|delete|do|else|enum|eval|export|extends|final|finally|for|from|" +
        "function|get|if|implements|import|in|instanceof|interface|let|native|new|of|package|" +
        "private|protected|public|return|set|static|super|switch|this|throw|try|typeof|var|void|" +
        "while|with|yield"

    /** 字面量常量 */
    private const val LITERALS = "true|false|null|undefined|NaN|Infinity"

    /**
     * Cocos2d / Cocos Creator / 微信小游戏 的常用全局对象与 API。
     * 单独配色，方便在几千行脚本里一眼定位引擎调用。
     */
    private const val COCOS =
        "cc|ccclass|ccnode|cccomponent|ccsprite|ccLabel|ccDirector|director|ccgame|game|" +
        "ccwindow|window|sys|ccsys|resources|assetManager|loader|audioEngine|" +
        "Node|Scene|Component|Sprite|Label|Layout|Widget|Button|ScrollView|Prefab|SpriteFrame|" +
        "Texture2D|Animation|AnimationClip|Tween|Vec2|Vec3|Color|Size|Rect|math|" +
        "ccclass|property|executeInEditMode|menu|requireComponent|disallowMultiple|" +
        "instantiate|find|destroy|schedule|unschedule|on|off|emit|once|" +
        "wx|tt|qg|swan|my|JDGame|ks|getApp|App|Page|Component"

    /**
     * 单遍交替正则。
     *
     * 分支顺序是正确性的关键：
     *  1. 注释、字符串必须最先 —— 否则 "//" 会被当运算符，
     *     字符串里的 "for"、URL 里的 "//" 都会被误判成关键字/注释
     *  2. 数字在标识符之前 —— 否则 "0x1F" 的 "x1F" 会被当标识符
     *  3. 函数调用在标识符之前 —— 才能吃到标识符后面的 "("
     */
    private val TOKEN_REGEX = Regex(
        buildString {
            append("(?<comment>/\\*[\\s\\S]*?\\*/|//[^\\n]*)")
            append("|(?<string>\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`(?:\\\\.|[^`\\\\])*`)")
            append("|(?<number>\\b(?:0[xX][0-9a-fA-F]+|0[bB][01]+|\\d+\\.?\\d*(?:[eE][+-]?\\d+)?|\\.\\d+)\\b)")
            append("|(?<keyword>\\b(?:$KEYWORDS)\\b)")
            append("|(?<literal>\\b(?:$LITERALS)\\b)")
            append("|(?<cocos>\\b(?:$COCOS)\\b)")
            append("|(?<func>\\b[A-Za-z_$][\\w$]*(?=\\s*\\())")
            append("|(?<ident>\\b[A-Za-z_$][\\w$]*\\b)")
        }
    )

    /** 从资源里取色，一次性读齐，避免循环内反复查资源 */
    private class Palette(context: Context) {
        val comment = color(context, R.color.syn_comment)
        val string = color(context, R.color.syn_string)
        val number = color(context, R.color.syn_number)
        val keyword = color(context, R.color.syn_keyword)
        val literal = color(context, R.color.syn_literal)
        val cocos = color(context, R.color.syn_cocos)
        val func = color(context, R.color.syn_func)

        private fun color(context: Context, resId: Int) =
            ContextCompat.getColor(context, resId)
    }
}
