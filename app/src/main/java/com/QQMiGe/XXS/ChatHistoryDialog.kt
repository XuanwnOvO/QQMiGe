package com.QQMiGe.XXS

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.QQMiGe.XXS.databinding.DialogChatHistoryBinding
import com.QQMiGe.XXS.databinding.ItemChatHistoryBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 历史对话列表（底部弹窗，和 AI 对话同一套视觉）。
 *
 * 数据来自 [ChatSessionStore]：一条会话一个 JSON 文件。读取与删除都丢到
 * 后台线程 —— 目录里攒到几十条时，逐个 readText + JSON 解析放主线程会掉帧。
 *
 * 交互：
 *  - 点击一条 -> 回调 id，由 AiChatDialog 把会话内容铺回界面
 *  - 每条右侧的删除按钮 -> 二次确认后删除该文件
 *  - 底部「清空全部」-> 二次确认后清空目录
 */
class ChatHistoryDialog : BottomSheetDialogFragment() {

    private var _binding: DialogChatHistoryBinding? = null
    private val binding get() = _binding!!

    private val io = Executors.newSingleThreadExecutor()
    private var adapter: HistoryAdapter? = null

    /**
     * View 是否已销毁。
     *
     * 同 AiChatDialog 的理由：列表读取走 io.execute，回写走 runOnUiThread ——
     * 那是投递不是执行，从投递到执行之间用户完全可能已经关掉弹窗。
     * 所以存活检查要放在执行时做，_binding 才能安全读取。
     */
    @Volatile
    private var destroyed = false

    /** 选中某条会话时回调（参数为会话 id） */
    private var onPick: ((Long) -> Unit)? = null

    fun setOnPickListener(listener: (Long) -> Unit): ChatHistoryDialog = apply {
        onPick = listener
    }

    override fun getTheme(): Int =
        com.google.android.material.R.style.Theme_Material3_DayNight_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogChatHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        val a = HistoryAdapter { session ->
            onPick?.invoke(session.id)
            dismiss()
        }
        adapter = a
        binding.rvHistory.adapter = a

        binding.btnClearAll.setOnClickListener { confirmClearAll() }

        reload()
    }

    private fun reload() {
        if (destroyed) return
        val ctx = requireContext().applicationContext
        io.execute {
            val list = ChatSessionStore.list(ctx)
            onUi {
                adapter?.submit(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.rvHistory.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chat_clear_title)
            .setMessage(R.string.chat_clear_msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.chat_history_delete) { _, _ ->
                val ctx = requireContext().applicationContext
                io.execute {
                    ChatSessionStore.clearAll(ctx)
                    onUi {
                        toast(getString(R.string.chat_history_deleted))
                        reload()
                    }
                }
            }
            .show()
    }

    private fun confirmDelete(s: ChatSessionStore.Session, title: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chat_history_delete_title)
            .setMessage(getString(R.string.chat_history_delete_msg, title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.chat_history_delete) { _, _ ->
                val ctx = requireContext().applicationContext
                io.execute {
                    ChatSessionStore.delete(ctx, s.id)
                    onUi {
                        toast(getString(R.string.chat_history_deleted))
                        reload()
                    }
                }
            }
            .show()
    }

    /** 与 ui() 同构：投递前省一次排队，执行时再确认 View 还活着 */
    private fun onUi(block: () -> Unit) {
        if (destroyed) return
        activity?.runOnUiThread {
            if (destroyed || _binding == null) return@runOnUiThread
            block()
        }
    }

    private fun toast(text: String) {
        val c = context ?: return
        Toast.makeText(c, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        destroyed = true
        io.shutdownNow()
        _binding = null
        super.onDestroyView()
    }

    // ---------------- 列表适配器 ----------------

    private inner class HistoryAdapter(
        private val onItemClick: (ChatSessionStore.Session) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val items = mutableListOf<ChatSessionStore.Session>()

        fun submit(list: List<ChatSessionStore.Session>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemChatHistoryBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(s: ChatSessionStore.Session) {
                val title = s.title.ifBlank { getString(R.string.chat_untitled) }
                b.tvTitle.text = title
                b.tvSub.text = getString(R.string.chat_msg_count, s.size.toString()) +
                    " · " + fmt(s.updatedAt)

                b.root.setOnClickListener { onItemClick(s) }
                b.btnDeleteItem.setOnClickListener { confirmDelete(s, title) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemChatHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    }

    private fun fmt(time: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))

    companion object {
        const val TAG = "chat_history"
    }
}
