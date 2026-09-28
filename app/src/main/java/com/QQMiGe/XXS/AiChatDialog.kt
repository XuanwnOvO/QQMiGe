package com.QQMiGe.XXS

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.QQMiGe.XXS.databinding.DialogAiChatBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * AI 对话弹窗（底部全高 Sheet）。
 *
 * 核心是一个【Agent 循环】：
 *
 *   用户输入 → 请求模型
 *              ├─ 模型返回文本 → 显示，结束
 *              └─ 模型请求工具 → 本地执行工具 → 把结果作为 role=tool 回传 → 再请求模型
 *
 * 循环上限 [MAX_TOOL_ROUNDS] 轮，防止模型陷入无限调用。
 * 所有网络与 shell 操作都在单线程池里跑，UI 更新一律 post 回主线程。
 */
class AiChatDialog : BottomSheetDialogFragment() {

    private var _binding: DialogAiChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var tools: AiTools
    private lateinit var adapter: AiMessageAdapter

    /** 完整对话历史（发给模型的内容，与界面消息一一对应） */
    private val history = mutableListOf<AiClient.Message>()

    /**
     * 当前会话 id。
     *
     * 直接用创建时的毫秒时间戳：既天然递增（列表排序靠它），又不会重名，
     * 省掉一套 id 分配逻辑。切换历史会话时会被替换成那条会话的 id，
     * 之后的保存就是覆盖那条会话，而不是又新建一条。
     */
    private var sessionId: Long = System.currentTimeMillis()

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /**
     * View 是否已销毁。
     *
     * 为什么必须有它：ui() 用的是 main.post，那是【投递】不是【执行】。
     * 它只保证代码将来跑在主线程，不保证跑的时候对话框还在。Agent 循环在
     * IO 线程跑网络和 su，动辄几十秒；用户中途关掉弹窗，onDestroyView 把
     * _binding 置空，而此时可能已有一个 setBusy 的 runnable 排在主线程队列
     * 里 —— 投递时 binding 还在，执行时已死，于是 _binding!! 抛 NPE。
     *
     * 所以存活检查要放在【执行时】做，投递时的检查只是省一次排队。
     * 写只发生在 onDestroyView（主线程），读会发生在 IO 线程（runIo 提前
     * 退出），故加 @Volatile。
     */
    @Volatile
    private var destroyed = false

    override fun getTheme(): Int =
        com.google.android.material.R.style.Theme_Material3_DayNight_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAiChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val projectRoot = requireArguments().getString(ARG_ROOT) ?: "/"
        tools = AiTools(projectRoot)

        binding.tvSandbox.text = getString(R.string.ai_sandbox, tools.sandboxRoot())

        adapter = AiMessageAdapter { msg ->
            // 长按复制该条内容，方便把报错拿去排查
            val cm = requireContext().getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("ai", msg.content))
            toast("已复制")
        }

        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = adapter

        binding.btnSend.setOnClickListener { send() }
        binding.btnNewChat.setOnClickListener { startNewChat() }
        binding.btnHistory.setOnClickListener { openHistory() }

        // 弹窗高度撑到屏幕 92%，保证消息区域足够
        dialog?.setOnShowListener {
            val sheet = dialog?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.let {
                val lp = it.layoutParams
                lp.height = (resources.displayMetrics.heightPixels * 0.92f).toInt()
                it.layoutParams = lp
            }
        }

        showWelcome()
    }

    private fun showWelcome() {
        val cfg = AiConfig.load(requireContext())
        val text = if (cfg.usable) {
            "已连接模型：${cfg.model}\n我可以读写本项目下的文件。告诉我改什么，例如：\n· 把出牌速度调快一点\n· 找到所有 hardcode 的伤害数值\n· 在 game.js 里加一行日志"
        } else {
            getString(R.string.ai_not_configured)
        }
        adapter.submit(AiMessageAdapter.ChatMessage("assistant", text))
    }

    // ---------------- 会话管理 ----------------

    /**
     * 新建对话。
     *
     * 先把当前会话落盘再清空 —— 否则用户点「新建」时上一轮对话就丢了。
     * 新会话与旧会话是两条独立记录，id 换成当前时间戳，不会覆盖。
     */
    private fun startNewChat() {
        persist()
        adapter.clear()
        history.clear()
        sessionId = System.currentTimeMillis()
        showWelcome()
        toast(getString(R.string.chat_new_started))
    }

    /** 打开历史列表；选中某条后把它铺回界面 */
    private fun openHistory() {
        // 打开列表前先存一次，保证当前这轮在列表里可见
        persist()
        ChatHistoryDialog()
            .setOnPickListener { id -> loadSession(id) }
            .show(childFragmentManager, ChatHistoryDialog.TAG)
    }

    private fun loadSession(id: Long) {
        val ctx = context?.applicationContext ?: return
        runIo {
            val session = ChatSessionStore.load(ctx, id)
            ui {
                if (session == null) {
                    toast(getString(R.string.chat_history_empty))
                    return@ui
                }
                sessionId = session.id
                history.clear()
                history.addAll(session.api)
                adapter.submitAll(
                    session.ui.map { AiMessageAdapter.ChatMessage(it.role, it.content) }
                )
                scrollToBottom()
                toast(getString(R.string.chat_restored, session.title))
            }
        }
    }

    /**
     * 把当前对话落盘。
     *
     * 两个时机调用：每轮结束时（有内容可存），以及弹窗关闭前。
     * 抓界面快照必须在主线程（adapter.items 是主线程私有的），写文件甩到
     * [ChatSessionStore.saveAsync] 的常驻线程池 —— 那个池不随 View 销毁而关，
     * 所以「关闭弹窗时保存」这条路径不会被 shutdownNow 截断。
     */
    private fun persist() {
        ui { persistNow() }
    }

    /**
     * 落盘的实际动作，**必须在主线程调用**。
     *
     * 拆出来是因为 onDestroyView 和 setBusy(false) 本身就在主线程上，
     * 再套一层 ui{}（内部是 main.post）只会把保存推迟到下一轮消息循环，
     * 而 onDestroyView 之后 _binding 已被置空，那一帧就再也不会执行了 ——
     * 结果就是「关弹窗时这次对话没存上」。
     */
    private fun persistNow() {
        if (history.isEmpty()) return
        val ctx = context?.applicationContext ?: return
        val uiMsgs = adapter.snapshot().map {
            ChatSessionStore.UiMessage(it.role, it.content)
        }
        if (uiMsgs.isEmpty()) return
        val title = ChatSessionStore.titleOf(uiMsgs, getString(R.string.chat_untitled))
        ChatSessionStore.saveAsync(
            ctx,
            ChatSessionStore.Session(
                id = sessionId,
                title = title,
                updatedAt = System.currentTimeMillis(),
                ui = uiMsgs,
                api = history.toList()
            )
        )
    }

    // ---------------- 发送与 Agent 循环 ----------------

    private fun send() {
        val input = binding.etInput.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) return

        val cfg = AiConfig.load(requireContext())
        if (!cfg.usable) {
            toast(getString(R.string.ai_not_configured))
            return
        }

        binding.etInput.setText("")
        adapter.submit(AiMessageAdapter.ChatMessage("user", input))
        history.add(AiClient.Message("user", input))

        // 系统提示词每轮都放在最前面，用户可在设置里改。
        // 用户清空提示词时不发这条：空的 system 消息在部分中转站会被判 400
        val request = buildList {
            cfg.systemPrompt.takeIf { it.isNotBlank() }
                ?.let { add(AiClient.Message("system", it)) }
            addAll(history)
        }

        setBusy(true)
        val thinking = AiMessageAdapter.ChatMessage("assistant", getString(R.string.ai_thinking))
        adapter.submit(thinking)

        runIo { runAgent(cfg, request, thinking.id, round = 1) }
    }

    /**
     * 单轮：请求模型 → 有工具调用就执行并把结果回传，然后递归进入下一轮。
     * 结果直接写入 [history]，保证多轮上下文连续。
     */
    private fun runAgent(
        cfg: AiConfig.Data,
        request: List<AiClient.Message>,
        placeholderId: Long,
        round: Int
    ) {
        // 弹窗已关：不再发请求、不再跑工具。后面每轮递归进来都会在这里止步。
        if (destroyed) return

        val reply = try {
            AiClient.chat(cfg, request, tools.declarations())
        } catch (e: Exception) {
            setBusy(false)
            adapter.updateById(placeholderId, "请求失败：${e.message}")
            return
        }

        // 情况一：模型给出最终文本，循环结束
        if (reply.toolCalls.isEmpty()) {
            val text = reply.content.ifBlank { "（模型没有返回内容）" }
            history.add(AiClient.Message("assistant", text))
            setBusy(false)
            adapter.updateById(placeholderId, text)
            scrollToBottom()
            return
        }

        // 情况二：模型要调工具 —— 先把「思考文本 + 工具调用」记入历史
        val names = reply.toolCalls.joinToString("、") { it.name }
        adapter.updateById(placeholderId, getString(R.string.ai_tool_running, names))

        history.add(
            AiClient.Message(
                role = "assistant",
                content = reply.content,
                toolCalls = reply.toolCalls
            )
        )

        // 本地依次执行，结果作为 role=tool 回传
        for (call in reply.toolCalls) {
            val result = tools.execute(call.name, call.arguments)
            history.add(
                AiClient.Message(
                    role = "tool",
                    content = result,
                    toolCallId = call.id
                )
            )
            // 界面上把工具执行结果也显示出来，方便用户审查 AI 到底改了什么
            val shown = result.take(600) + if (result.length > 600) "\n…（已截断）" else ""
            adapter.updateById(placeholderId, "▶ ${call.name}\n$shown")
            scrollToBottom()
        }

        if (round >= MAX_TOOL_ROUNDS) {
            setBusy(false)
            adapter.submit(
                AiMessageAdapter.ChatMessage("error", getString(R.string.ai_max_rounds))
            )
            scrollToBottom()
            return
        }

        // 下一轮：新建占位消息，把最新历史重新包上 system 再请求。
        // 注意 adapter.submit 内部已做线程切换，这里在 IO 线程调用是安全的。
        val nextPlaceholder =
            AiMessageAdapter.ChatMessage("assistant", getString(R.string.ai_thinking))
        adapter.submit(nextPlaceholder)

        val nextRequest = buildList {
            cfg.systemPrompt.takeIf { it.isNotBlank() }
                ?.let { add(AiClient.Message("system", it)) }
            addAll(history)
        }
        runIo { runAgent(cfg, nextRequest, nextPlaceholder.id, round + 1) }
    }

    // ---------------- 小工具 ----------------

    /**
     * 所有 UI 更新统一走这里。
     *
     * 两层防线：
     *  1. 投递前看一眼 destroyed，省掉必然被丢弃的排队；
     *  2. 执行时再确认 View 真的还在 —— 这才是防崩溃的那一道。
     *     从 post 到真正执行之间，用户完全可能已经关掉弹窗。
     *
     * 适配器内部也有主线程守卫，但那只能防「线程不对」，
     * 防不了「View 已死」，两者是不同性质的问题。
     */
    private fun ui(block: () -> Unit) {
        if (destroyed) return
        main.post {
            if (destroyed || _binding == null || !isAdded) return@post
            block()
        }
    }

    /**
     * 统一的 IO 提交口。
     *
     * shutdownNow 之后任何 execute 都会抛 RejectedExecutionException，
     * 而 Agent 循环是多轮递归提交的 —— 关弹窗正好卡在两轮之间就会踩到，
     * 且抛在 IO 线程上无人捕获 = 进程崩溃。这里吞掉它：
     * 关池就意味着不再需要结果，丢弃是正确行为而非容错。
     */
    private fun runIo(block: () -> Unit) {
        if (destroyed) return
        try {
            io.execute(block)
        } catch (_: RejectedExecutionException) {
            // 收尾竞争，正常
        }
    }

    private fun setBusy(busy: Boolean) = ui {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnSend.isEnabled = !busy
        binding.etInput.isEnabled = !busy
        // 一轮跑完就落盘：此时历史与界面都已定型，正是保存的最佳时机
        if (!busy) persistNow()
    }

    private fun scrollToBottom() = ui {
        val n = adapter.itemCount
        if (n > 0) binding.rvMessages.smoothScrollToPosition(n - 1)
    }

    /** requireContext() 在 detach 后会抛 IllegalStateException，用可空 context 兜底 */
    private fun toast(text: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        // 关弹窗前把这次对话存下来。必须在 destroyed = true 之前、且直接调用
        // persistNow（不经过 ui{} 的 main.post），否则 _binding 一置空就再也执行不到。
        persistNow()
        // 先立旗再关池：旗子立了，之后所有 ui() 回调都会被丢弃；
        // shutdownNow 会 interrupt 阻塞中的网络/su 调用，让它尽早醒来。
        destroyed = true
        io.shutdownNow()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_ROOT = "arg_root"
        private const val MAX_TOOL_ROUNDS = 8

        /** @param projectRoot AI 的可写范围（项目根目录绝对路径） */
        fun newInstance(projectRoot: String): AiChatDialog = AiChatDialog().apply {
            arguments = Bundle().apply { putString(ARG_ROOT, projectRoot) }
        }
    }
}
