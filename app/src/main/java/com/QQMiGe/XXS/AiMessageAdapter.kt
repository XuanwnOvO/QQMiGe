package com.QQMiGe.XXS

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.QQMiGe.XXS.databinding.ItemAiMessageBinding

/**
 * AI 对话消息列表适配器。
 *
 * 三条规则：
 *  - 用 ViewBinding 逐个 inflate，【绝不把多个条目的文本拼在一起渲染】
 *  - 靠 margin 左右推移区分角色，不使用气泡背景 drawable，减少资源
 *  - 每次提交都重新计算 margins，避免复用时残留上一次的缩进
 *
 * 线程安全：所有会触达 RecyclerView 的写操作一律在方法内部切到主线程。
 * 原因是 notifyItemInserted 会触发 requestLayout，一旦在后台线程调用就抛
 * CalledFromWrongThreadException。把守卫放在这一层，调用方（Agent 循环）
 * 就不必关心自己身处哪个线程，也就不会出现「某处漏切」的崩溃。
 */
class AiMessageAdapter(
    private val onLongClick: (ChatMessage) -> Unit
) : RecyclerView.Adapter<AiMessageAdapter.VH>() {

    /**
     * @param role user / assistant / tool / error
     */
    data class ChatMessage(
        val role: String,
        val content: String,
        /** 流式占位消息在完成后需要就地替换，用 id 定位 */
        val id: Long = System.nanoTime()
    )

    private val items = mutableListOf<ChatMessage>()
    private val main = Handler(Looper.getMainLooper())

    /** 主线程执行；已在主线程则直接调用，避免无谓延迟 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun submit(message: ChatMessage) = onMain {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    /** 把最后一条占位消息替换为最终内容（用于「思考中…」这类状态） */
    fun replaceLast(message: ChatMessage) = onMain {
        if (items.isEmpty()) {
            items.add(message)
            notifyItemInserted(0)
        } else {
            items[items.size - 1] = message
            notifyItemChanged(items.size - 1)
        }
    }

    fun updateById(id: Long, content: String) = onMain {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(content = content)
            notifyItemChanged(idx)
        }
    }

    fun clear() = onMain {
        val n = items.size
        items.clear()
        notifyItemRangeRemoved(0, n)
    }

    /**
     * 抓取当前全部界面消息的快照。
     *
     * 注意：`items` 只允许在主线程访问，本方法必须在主线程调用（AiChatDialog
     * 通过它的 ui {} 包装来保证）。返回的是副本，调用方随意持有。
     */
    fun snapshot(): List<ChatMessage> = items.toList()

    /** 整体替换列表内容，用于把一条历史会话铺回界面 */
    fun submitAll(list: List<ChatMessage>) = onMain {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = items.isEmpty()

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAiMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemAiMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(msg: ChatMessage) {
            val res = binding.root.resources

            val (roleLabel, cardColor) = when (msg.role) {
                "user" -> res.getString(R.string.ai_role_user) to
                    com.google.android.material.R.attr.colorPrimaryContainer
                "tool" -> res.getString(R.string.ai_role_tool) to
                    com.google.android.material.R.attr.colorTertiaryContainer
                "error" -> res.getString(R.string.ai_role_error) to
                    com.google.android.material.R.attr.colorErrorContainer
                else -> res.getString(R.string.ai_role_assistant) to
                    com.google.android.material.R.attr.colorSurfaceContainerHigh
            }

            binding.tvRole.text = roleLabel
            binding.tvContent.text = msg.content

            val attrs = intArrayOf(cardColor)
            val ta = binding.root.context.obtainStyledAttributes(attrs)
            val color = ta.getColor(0, ContextCompat.getColor(binding.root.context, android.R.color.darker_gray))
            ta.recycle()
            binding.cardMessage.setCardBackgroundColor(color)

            // 用户消息靠右、AI 靠左；用 layoutParams 现算，避免复用残留
            val lp = binding.root.layoutParams as? RecyclerView.LayoutParams
            val margin = (res.displayMetrics.density * 40).toInt()
            lp?.let {
                it.marginStart = if (msg.role == "user") margin else (res.displayMetrics.density * 16).toInt()
                it.marginEnd = if (msg.role == "user") (res.displayMetrics.density * 16).toInt() else margin
                binding.root.layoutParams = it
            }

            binding.root.setOnLongClickListener {
                onLongClick(msg)
                true
            }
        }
    }
}
