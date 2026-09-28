package com.QQMiGe.XXS

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.QQMiGe.XXS.databinding.ItemMinigameBinding

/**
 * 文件/文件夹列表适配器（MD3 卡片列表）。
 *
 * 同一个适配器服务两处：
 *  - 第 1 层项目列表：带长按菜单，且显示备注名
 *  - 第 2 层文件抽屉：只要点击，不显示备注
 * 所以长按回调与备注查询都是可选的。
 */
class BrowserAdapter(
    private val onClick: (RootShell.DirEntry) -> Unit,
    /** 长按回调；为 null 表示该列表不支持长按 */
    private val onLongClick: ((RootShell.DirEntry) -> Unit)? = null,
    /** 备注查询；返回非空时用它当主标题，原始名降为副标题 */
    private val noteOf: ((RootShell.DirEntry) -> String?)? = null
) : RecyclerView.Adapter<BrowserAdapter.Holder>() {

    private val items = mutableListOf<RootShell.DirEntry>()

    fun submit(list: List<RootShell.DirEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemMinigameBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemMinigameBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = items[position]
        val note = noteOf?.invoke(entry)?.takeIf { it.isNotBlank() }

        // 有备注：主标题显示备注名，副标题保留原始 hash 目录名（不然用户认不出是哪个）
        holder.binding.tvName.text = note ?: entry.name
        holder.binding.tvSub.text = entry.name
        holder.binding.tvSub.visibility = if (note != null) View.VISIBLE else View.GONE

        holder.binding.ivType.setImageResource(
            if (entry.isDirectory) android.R.drawable.ic_menu_agenda
            else android.R.drawable.ic_menu_edit
        )
        holder.binding.ivArrow.visibility =
            if (entry.isDirectory) View.VISIBLE else View.INVISIBLE
        holder.binding.root.setOnClickListener { onClick(entry) }
        holder.binding.root.setOnLongClickListener {
            val handler = onLongClick ?: return@setOnLongClickListener false
            handler(entry)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
