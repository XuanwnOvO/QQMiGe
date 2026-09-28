package com.QQMiGe.XXS

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.QQMiGe.XXS.databinding.ActivityEditorBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 第 2 层：项目编辑器。
 *
 * 交互设计：
 *  - 默认展示【文件查看器】（只读），不展示文件列表
 *  - 点左上角三条杠 -> 从左侧拉出【文件列表】抽屉
 *  - 抽屉里点【文件夹】-> 进入该目录，列表刷新，抽屉保持打开
 *  - 抽屉里点【文件】-> 关闭抽屉，把该文件全文载入查看器
 *  - 信息条上的【编辑】-> 切到 EditText 手工改，改完【完成】切回查看
 *  - 编辑态右下角 FAB 保存 -> 通过 su 以 base64 方式写回原文件
 *
 * 为什么分成「查看」和「编辑」两种态：
 * 查看用只读 TextView（StaticLayout），一次布局、按行按需绘制；编辑用
 * EditText（DynamicLayout），要为整个 Editable 维护「段 → 行」映射并随
 * 每次输入增量更新。后者的代价与文本量成正比且绕不过去 —— 这就是以前
 * 打开 MB 级文件直接卡死的原因。把查看和编辑拆开，才能既「不截断」又
 * 「不卡」。
 *
 * 高亮为什么按可视区算：
 * 查看态的文本可以到 8MB，全量扫一遍正则再几万个 setSpan 依旧要命。
 * Layout 提供了 offset ↔ line 的双向映射：`getLineForVertical` 由滚动位置
 * 定位到行，`getLineStart/getLineEnd` 由行反解出字符区间。于是只扫视口上下
 * 各 40 行的切片，滚动时重算 —— 代价与视口高度成正比，与文件大小无关。
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private val adapter by lazy { BrowserAdapter(onClick = { onEntryClick(it) }) }

    /** 当前浏览的目录栈，栈底为项目根目录 */
    private val pathStack = ArrayDeque<String>()

    /** 项目根目录，用于判断「上一级」的下界 */
    private lateinit var projectRoot: String

    /** 当前在编辑器里打开的文件完整路径；未打开文件时为 null */
    private var openFilePath: String? = null

    /** 打开文件时载入的原始内容，用于判断是否有改动 */
    private var originalContent: String = ""

    /** 当前是否处于编辑态（false = 只读查看态） */
    private var isEditing = false

    /**
     * 查看态的纯文本内容。
     *
     * 为什么不直接读 `tvViewer.text`：那是挂了高亮 Span 的 Spannable，
     * `toString()` 虽然能拿回文本，但每次切编辑都要重新遍历；更重要的是
     * 切编辑要测量行数/最长行，这些统计在载入时已在 IO 线程算好并缓存，
     * 这里只存纯文本本身。
     */
    private var plainViewerText: String = ""

    /** 查看文件的字节数；-1 表示取不到 */
    private var viewerSizeBytes: Long = -1

    /** 查看态是否被截断（只读到前 VIEWER_MAX_BYTES） */
    private var viewerTruncated = false

    /** 查看文件的总行数，载入时统计一次 */
    private var viewerLineCount = 0

    /** 查看文件的最长行字符数，载入时统计一次。切编辑前据此判断是否要警告 */
    private var viewerMaxLineChars = 0

    /**
     * 高亮代次。调度时自增，回写前比对 —— 对不上说明期间文本已变或又排了
     * 一次新的高亮，本次结果作废。扫描在后台跑，Span 坐标一旦对上不存在的
     * 文本，轻则错色重则越界崩。
     */
    private var highlightGen = 0

    private val highlightHandler = Handler(Looper.getMainLooper())

    /**
     * 查看态高亮的防抖任务。
     *
     * 为什么防抖：滚动会连续触发十几帧，每帧都扫一次正则纯属浪费。
     * 停 120ms 再算，滚动过程中只是暂时没有颜色，不会闪错色。
     */
    private val viewerHighlightRunnable = Runnable { highlightViewport() }

    /**
     * 编辑态语法高亮的防抖任务。
     *
     * 关键：这里【只原地改 Span，不调用 setText】。
     * JsHighlighter 拿到的是 EditText 自己的 Editable，直接在它上面增删 Span，
     * 既不会回调 TextWatcher（避免无限循环），也不用重新布局整段文本。
     */
    private val highlightRunnable = Runnable {
        val editor = binding.etEditor
        val editable = editor.text ?: return@Runnable
        val caret = editor.selectionStart.coerceAtLeast(0)
        JsHighlighter.apply(editable, JsHighlighter.scan(this, editable))
        editor.setSelection(caret.coerceAtMost(editable.length))
    }

    /** 输入防抖：停止输入 250ms 后才重算高亮 */
    private val highlightWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            highlightHandler.removeCallbacks(highlightRunnable)
            highlightHandler.postDelayed(highlightRunnable, 250)
        }
    }

    /** watcher 是否已挂在 EditText 上。载入大文本时先摘掉，避免 setText
     * 排队一次无谓的全量高亮；载入完再挂回。 */
    private var watcherAttached = false

    private fun attachWatcher() {
        if (watcherAttached) return
        binding.etEditor.addTextChangedListener(highlightWatcher)
        watcherAttached = true
    }

    private fun detachWatcher() {
        if (!watcherAttached) return
        highlightHandler.removeCallbacks(highlightRunnable)
        binding.etEditor.removeTextChangedListener(highlightWatcher)
        watcherAttached = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectRoot = intent.getStringExtra(EXTRA_PROJECT_PATH) ?: Config.MINIGAME_DIR
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME)

        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter

        binding.toolbar.title = projectName ?: getString(R.string.editor_untitled)
        binding.tvDrawerTitle.text = projectName ?: getString(R.string.drawer_title)

        // 三条杠 -> 拉出文件列表抽屉
        binding.toolbar.setNavigationOnClickListener {
            binding.drawer.openDrawer(GravityCompat.START)
        }

        binding.btnUp.setOnClickListener { goUp() }
        binding.btnRefresh.setOnClickListener { load(currentPath()) }
        binding.btnSave.setOnClickListener { saveCurrentFile() }
        binding.btnEdit.setOnClickListener { if (isEditing) leaveEditMode() else enterEditMode() }

        // 查看态：滚动停下后重算视口高亮
        binding.viewerScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (isEditing) return@setOnScrollChangeListener
            highlightHandler.removeCallbacks(viewerHighlightRunnable)
            highlightHandler.postDelayed(viewerHighlightRunnable, 120)
        }

        // 右上角：AI 配置（齿轮）与 AI 对话（机器人）
        binding.toolbar.inflateMenu(R.menu.menu_editor)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_ai_config -> {
                    AiConfigDialog().show(supportFragmentManager, "ai_config")
                    true
                }
                R.id.action_ai_chat -> {
                    AiChatDialog.newInstance(projectRoot)
                        .show(supportFragmentManager, "ai_chat")
                    true
                }
                R.id.action_about -> {
                    startActivity(android.content.Intent(this, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }

        attachWatcher()
        setMode(false)

        // 系统返回键：抽屉开着先关抽屉，编辑态先退出编辑，否则退回上一级，最后退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawer.isDrawerOpen(GravityCompat.START) ->
                        binding.drawer.closeDrawer(GravityCompat.START)
                    isEditing -> leaveEditMode()
                    pathStack.size > 1 -> goUp()
                    else -> finish()
                }
            }
        })

        pathStack.addLast(projectRoot)
        load(currentPath())
    }

    private fun currentPath(): String = pathStack.last()

    // ---------------- 文件列表 ----------------

    private fun onEntryClick(entry: RootShell.DirEntry) {
        if (entry.isDirectory) {
            // 进目录：刷新列表，抽屉保持打开，方便继续下钻
            pathStack.addLast(entry.fullPath)
            load(entry.fullPath)
        } else {
            // 开文件：关抽屉，载入查看器
            binding.drawer.closeDrawer(GravityCompat.START)
            openFile(entry)
        }
    }

    private fun goUp() {
        if (pathStack.size > 1) {
            pathStack.removeLast()
            load(currentPath())
        }
    }

    private fun load(path: String) {
        binding.drawerProgress.visibility = View.VISIBLE
        binding.tvDrawerEmpty.visibility = View.GONE
        binding.rvList.visibility = View.GONE
        binding.btnRefresh.isEnabled = false
        binding.btnUp.isEnabled = pathStack.size > 1
        binding.tvDrawerPath.text = path

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RootShell.listDir(path) }
            renderList(result)
        }
    }

    private fun renderList(result: RootShell.DirResult) {
        binding.drawerProgress.visibility = View.GONE
        binding.btnRefresh.isEnabled = true

        if (!result.ok) {
            adapter.submit(emptyList())
            binding.rvList.visibility = View.GONE
            binding.tvDrawerEmpty.visibility = View.VISIBLE
            val msg = when (result.error) {
                RootShell.DirError.PERMISSION_DENIED -> getString(R.string.browser_denied)
                RootShell.DirError.NOT_FOUND -> getString(R.string.browser_not_found)
                RootShell.DirError.NOT_ROOT -> getString(R.string.browser_not_root)
                else -> getString(R.string.browser_empty)
            }
            // 带上 su 身份行与原始输出，便于定位授权/路径问题
            val detail = buildString {
                append(msg)
                if (result.idLine.isNotBlank()) append("\n\nsu id: ").append(result.idLine)
                if (result.raw.isNotBlank()) append("\n\n").append(result.raw.take(1200))
            }
            binding.tvDrawerEmpty.text = detail
            return
        }

        adapter.submit(result.entries)
        if (result.entries.isEmpty()) {
            binding.tvDrawerEmpty.visibility = View.VISIBLE
            binding.tvDrawerEmpty.text = getString(R.string.browser_empty)
            binding.rvList.visibility = View.GONE
        } else {
            binding.tvDrawerEmpty.visibility = View.GONE
            binding.rvList.visibility = View.VISIBLE
        }
    }

    // ---------------- 查看态 ----------------

    /**
     * 打开文件，全文进只读查看器。
     *
     * 为什么查看态敢读全文：只读 TextView 用 StaticLayout，一次布局、按行
     * 按需绘制，没有可编辑状态要维护。旧实现把最多 512KB 全量塞进 EditText，
     * DynamicLayout 要为每一行算基线、全在主线程，几万行就是几万个 Layout
     * 单元 —— 卡死的是这里，不是传输。
     */
    private fun openFile(entry: RootShell.DirEntry) {
        detachWatcher()
        binding.progress.visibility = View.VISIBLE
        binding.editorCard.visibility = View.GONE
        binding.emptyGroup.visibility = View.VISIBLE
        binding.tvEmpty.text = getString(R.string.editor_loading)
        binding.tvRawError.visibility = View.GONE
        binding.previewBar.visibility = View.GONE

        lifecycleScope.launch {
            // 读取 + 统计（行数/最长行）都在 IO 线程一次做完，切编辑时零成本
            val (read, stats) = withContext(Dispatchers.IO) {
                val r = RootShell.readFileForView(entry.fullPath)
                r to if (r.ok && r.isText) measure(r.content) else (0 to 0)
            }
            binding.progress.visibility = View.GONE
            renderViewer(entry, read, stats.first, stats.second)
        }
    }

    /**
     * 统计行数与最长行字符数。
     *
     * 单遍扫描同时算出两个值：切分两轮就等于把大文件读两遍。最长行会在切编辑
     * 前用来判断是否需要警告 —— 压缩过的 bundle 整个文件就是一行，字符总数
     * 限制对它完全无效，只有单行宽度能反映换行器的真实代价。
     */
    private fun measure(content: String): Pair<Int, Int> {
        var lines = 1
        var maxLine = 0
        var cur = 0
        for (c in content) {
            if (c == '\n') {
                if (cur > maxLine) maxLine = cur
                cur = 0
                lines++
            } else {
                cur++
            }
        }
        if (cur > maxLine) maxLine = cur
        return lines to maxLine
    }

    private fun renderViewer(
        entry: RootShell.DirEntry,
        read: RootShell.FileRead,
        lines: Int,
        maxLineChars: Int
    ) {
        if (!read.ok) {
            // shell 拿不到内容：把原始输出贴出来，便于定位（授权/路径/命名空间）
            failToEmpty(entry, getString(R.string.editor_load_failed), read.raw)
            return
        }
        if (!read.isText) {
            // 二进制文件：不载入，避免把乱码写回
            openFilePath = null
            binding.editorCard.visibility = View.GONE
            binding.previewBar.visibility = View.GONE
            binding.emptyGroup.visibility = View.VISIBLE
            binding.tvEmpty.text = getString(R.string.editor_binary)
            binding.tvRawError.visibility = View.GONE
            binding.toolbar.title = entry.name
            binding.btnSave.isEnabled = true
            return
        }

        openFilePath = entry.fullPath
        originalContent = read.content
        plainViewerText = read.content
        viewerSizeBytes = read.sizeBytes
        viewerTruncated = read.truncated
        viewerLineCount = lines
        viewerMaxLineChars = maxLineChars

        binding.editorCard.visibility = View.VISIBLE
        binding.emptyGroup.visibility = View.GONE
        binding.tvPath.text = entry.fullPath
        binding.toolbar.title = entry.name

        // 铺纯文本进查看器。之后高亮只在这个 Spannable 上增删 Span，不再 setText ——
        // setText 会换掉 Spannable 实例，让在途的后台扫描结果全部失效。
        highlightGen++
        binding.tvViewer.text = SpannableString(read.content)
        binding.viewerScroll.scrollY = 0

        setMode(false)
        updateViewerInfo()

        // 双重 post：第一帧等 layout 走完，第二帧 TextLayout 才可用
        binding.tvViewer.post { binding.tvViewer.post { highlightViewport() } }
    }

    private fun failToEmpty(entry: RootShell.DirEntry, message: String, raw: String) {
        openFilePath = null
        binding.editorCard.visibility = View.GONE
        binding.previewBar.visibility = View.GONE
        binding.emptyGroup.visibility = View.VISIBLE
        binding.tvEmpty.text = message
        binding.tvRawError.visibility = if (raw.isBlank()) View.GONE else View.VISIBLE
        binding.tvRawError.text = raw.take(1200)
        binding.toolbar.title = entry.name
        binding.btnSave.isEnabled = true
    }

    /** 信息条：查看态显示大小 / 行数，截断时补上提示 */
    private fun updateViewerInfo() {
        val size = formatSize(viewerSizeBytes)
        binding.tvPreview.text = if (viewerTruncated) {
            getString(
                R.string.editor_viewer_truncated,
                size,
                viewerLineCount.toString(),
                formatSize(RootShell.VIEWER_MAX_BYTES.toLong())
            )
        } else {
            getString(R.string.editor_viewer_info, size, viewerLineCount.toString())
        }
    }

    /**
     * 按可视区重算查看态高亮。
     *
     * 定位链路：滚动位置 -> 行 -> 字符区间。
     *  - `getLineForVertical` 把 Y 坐标换成行号（对有换行的文本是二分查找）
     *  - `getLineStart/getLineEnd` 把行号换成 offset
     * 上下各留 40 行余量：滚动是跳跃的，只算严格视口会让边缘词在滚动中
     * 忽明忽暗。
     *
     * 超宽行（压缩过的 bundle 整个文件一行）会命中 [JsHighlighter.MAX_HIGHLIGHT_CHARS]
     * 上限，scan 返回空列表 —— 等价于「这文件不高亮」，与全量扫描时的行为一致。
     */
    private fun highlightViewport() {
        if (isEditing) return
        val view = binding.tvViewer
        val target = view.text as? Spannable ?: return
        val layout = view.layout ?: return
        if (layout.lineCount == 0) return

        val scroll = binding.viewerScroll
        val top = (scroll.scrollY - view.paddingTop).coerceAtLeast(0)
        val bottom = top + scroll.height

        val firstLine = layout.getLineForVertical(top)
        val lastLine = layout.getLineForVertical(bottom)
        val fromLine = (firstLine - 40).coerceAtLeast(0)
        val toLine = (lastLine + 40).coerceAtMost(layout.lineCount - 1)

        val start = layout.getLineStart(fromLine)
        val end = layout.getLineEnd(toLine)
        if (end <= start) return

        val slice = target.subSequence(start, end)
        val gen = ++highlightGen

        lifecycleScope.launch {
            val marks = withContext(Dispatchers.Default) { JsHighlighter.scan(this@BrowserActivity, slice) }
            // 期间文本已换 / 又排了一次新的高亮 / 已切到编辑态 -> 丢弃
            if (gen != highlightGen || isEditing) return@launch
            val current = binding.tvViewer.text as? Spannable ?: return@launch
            JsHighlighter.apply(current, marks, start)
        }
    }

    // ---------------- 编辑态 ----------------

    /**
     * 进入编辑态前的把关。
     *
     * 查看态不限量（8MB 也照读），但 EditText 不行：DynamicLayout 要为整个
     * Editable 维护「段 → 行」映射并随每次输入增量更新，代价与文本量成正比
     * 且绕不过去。所以超限不硬拦，而是把代价说清楚，由用户决定。
     *
     * 两个判据：字符总数、最长行宽度。后者是必要的 —— 压缩过的 JS 整个文件
     * 就是一行，一行 30 万字符和一万行各 30 字符总数一样，但换行器要在多长
     * 的范围内找断点完全不同，渲染代价差一个数量级。
     */
    private fun enterEditMode() {
        val path = openFilePath
        if (path == null) {
            Snackbar.make(binding.root, R.string.editor_edit_failed, Snackbar.LENGTH_SHORT).show()
            return
        }

        val content = plainViewerText
        val overChars = content.length > RootShell.EDITOR_MAX_CHARS
        val overLine = viewerMaxLineChars > RootShell.EDITOR_MAX_LINE_CHARS

        if (!overChars && !overLine) {
            switchToEditor(content)
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_edit_warn_title)
            .setMessage(
                getString(
                    R.string.editor_edit_warn_body,
                    formatSize(content.toByteArray(Charsets.UTF_8).size.toLong()),
                    viewerLineCount.toString()
                )
            )
            .setNegativeButton(R.string.project_delete_cancel, null)
            .setPositiveButton(R.string.editor_edit_warn_ok) { _, _ -> switchToEditor(content) }
            .show()
    }

    /** 真正切到 EditText：setText 是这一步唯一的重活，用户已在弹窗里确认过 */
    private fun switchToEditor(content: String) {
        detachWatcher()
        binding.etEditor.setText(content)
        binding.etEditor.setSelection(0)
        setMode(true)
        attachWatcher()
        highlightHandler.postDelayed(highlightRunnable, 250)
    }

    /** 退回查看态：把编辑结果铺回只读查看器，重算视口高亮 */
    private fun leaveEditMode() {
        val content = binding.etEditor.text?.toString().orEmpty()
        plainViewerText = content

        highlightGen++
        binding.tvViewer.text = SpannableString(content)
        setMode(false)
        updateViewerInfo()
        binding.tvViewer.post { binding.tvViewer.post { highlightViewport() } }
    }

    /** 两态切换：控件互斥、按钮文案、FAB 可见性都收在这里 */
    private fun setMode(editing: Boolean) {
        isEditing = editing
        binding.viewerScroll.visibility = if (editing) View.GONE else View.VISIBLE
        binding.etEditor.visibility = if (editing) View.VISIBLE else View.GONE
        binding.btnEdit.setText(if (editing) R.string.editor_done else R.string.editor_edit)
        binding.btnSave.visibility = if (editing) View.VISIBLE else View.GONE
        if (editing) {
            binding.tvPreview.setText(R.string.editor_mode_editing)
        } else {
            updateViewerInfo()
        }
    }

    // ---------------- 保存 ----------------

    private fun saveCurrentFile() {
        val path = openFilePath
        if (path == null) {
            Snackbar.make(binding.root, R.string.editor_no_file, Snackbar.LENGTH_SHORT).show()
            return
        }

        val content = binding.etEditor.text.toString()
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { RootShell.writeTextFile(path, content) }
            binding.btnSave.isEnabled = true
            if (ok) {
                originalContent = content
                // 保存后内容变了，统计要跟着走，否则退回查看态的行数是对不上的旧值
                val stats = withContext(Dispatchers.Default) { measure(content) }
                viewerLineCount = stats.first
                viewerMaxLineChars = stats.second
                viewerSizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                viewerTruncated = false
                Snackbar.make(binding.root, R.string.editor_saved, Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(binding.root, R.string.editor_save_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return getString(R.string.editor_size_unknown)
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
        }
    }

    companion object {
        const val EXTRA_PROJECT_PATH = "extra_project_path"
        const val EXTRA_PROJECT_NAME = "extra_project_name"
    }

    override fun onDestroy() {
        highlightHandler.removeCallbacks(highlightRunnable)
        highlightHandler.removeCallbacks(viewerHighlightRunnable)
        super.onDestroy()
    }
}
