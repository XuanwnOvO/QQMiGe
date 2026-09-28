package com.QQMiGe.XXS

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.QQMiGe.XXS.databinding.ActivityMinigameBinding
import com.QQMiGe.XXS.databinding.DialogNoteBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 第 1 层：小游戏「项目」列表。
 *
 * 列出 /data/data/com.tencent.mobileqq/files/minigame/ 下的每个项目文件夹。
 * 点某个项目 -> 进入 [BrowserActivity]（第 2 层编辑器）。
 * 长按某个项目 -> 弹菜单：备注（改显示名）/ 删除（rm -rf 整个目录）。
 */
class ProjectListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMinigameBinding

    /**
     * 适配器带长按菜单与备注显示。
     *
     * 备注从 [Notes] 读：它是本地小文件，但读盘 + 解析 JSON 会发生在
     * onBindViewHolder 里（每滚一屏调几十次），所以这里按需缓存，
     * 任何写操作都调 [invalidateNotes] 让缓存失效。
     */
    private var notes: Map<String, String> = emptyMap()

    private val adapter by lazy {
        BrowserAdapter(
            onClick = { onProjectClick(it) },
            onLongClick = { showProjectMenu(it) },
            noteOf = { notes[it.fullPath] }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMinigameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvList.layoutManager = LinearLayoutManager(this)
        binding.rvList.adapter = adapter
        binding.tvPath.text = Config.MINIGAME_DIR

        binding.btnRefresh.setOnClickListener { load() }

        load()
    }

    private fun invalidateNotes() {
        notes = Notes.all(this)
    }

    private fun load() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyGroup.visibility = View.GONE
        binding.rvList.visibility = View.GONE
        binding.btnRefresh.isEnabled = false
        invalidateNotes()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { RootShell.listDir(Config.MINIGAME_DIR) }
            render(result)
        }
    }

    private fun render(result: RootShell.DirResult) {
        binding.progress.visibility = View.GONE
        binding.btnRefresh.isEnabled = true

        if (!result.ok) {
            adapter.submit(emptyList())
            binding.rvList.visibility = View.GONE
            binding.emptyGroup.visibility = View.VISIBLE
            binding.tvEmpty.text = when (result.error) {
                RootShell.DirError.PERMISSION_DENIED -> getString(R.string.browser_denied)
                RootShell.DirError.NOT_FOUND -> getString(R.string.browser_not_found)
                RootShell.DirError.NOT_ROOT -> getString(R.string.browser_not_root)
                else -> getString(R.string.browser_empty)
            }
            // root 已失效：清掉授权凭据，这样从本页返回 RootActivity 时会重新显示申请界面
            if (result.error == RootShell.DirError.NOT_ROOT) {
                RootActivity.clearGrant(this)
            }
            val detail = buildString {
                if (result.idLine.isNotBlank()) append("su id: ").append(result.idLine).append("\n")
                if (result.namespace.isNotBlank()) append("mnt ns: ").append(result.namespace).append("\n")
                if (result.raw.isNotBlank()) append("\n").append(result.raw)
            }
            binding.tvRawError.text = detail.trim()
            binding.tvRawError.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
            return
        }

        binding.tvRawError.visibility = View.GONE
        // 第 1 层只列「项目」= 子文件夹
        val projects = result.entries.filter { it.isDirectory }
        adapter.submit(projects)

        if (projects.isEmpty()) {
            binding.emptyGroup.visibility = View.VISIBLE
            binding.rvList.visibility = View.GONE
            binding.tvEmpty.text = getString(R.string.browser_empty)
        } else {
            binding.emptyGroup.visibility = View.GONE
            binding.rvList.visibility = View.VISIBLE
        }
    }

    /** 点击：进入第 2 层编辑器。有备注时把备注名当标题传给编辑器，标题保持一致 */
    private fun onProjectClick(entry: RootShell.DirEntry) {
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_PROJECT_PATH, entry.fullPath)
                .putExtra(BrowserActivity.EXTRA_PROJECT_NAME, notes[entry.fullPath] ?: entry.name)
        )
    }

    // ---------------- 长按菜单 ----------------

    private fun showProjectMenu(entry: RootShell.DirEntry) {
        val display = notes[entry.fullPath] ?: entry.name
        val items = arrayOf(
            getString(R.string.project_menu_note),
            getString(R.string.project_menu_delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(display)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showNoteDialog(entry)
                    1 -> confirmDelete(entry)
                }
            }
            .show()
    }

    private fun showNoteDialog(entry: RootShell.DirEntry) {
        val noteBinding = DialogNoteBinding.inflate(layoutInflater)
        noteBinding.etNote.setText(notes[entry.fullPath].orEmpty())
        noteBinding.etNote.setSelection(noteBinding.etNote.text?.length ?: 0)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.project_note_title, entry.name))
            .setView(noteBinding.root)
            .setNegativeButton(R.string.project_delete_cancel, null)
            .setPositiveButton(R.string.project_note_save) { _, _ ->
                val input = noteBinding.etNote.text?.toString().orEmpty()
                Notes.set(this, entry.fullPath, input)
                invalidateNotes()
                // 备注只改显示，不必重新走一遍 su 列目录
                adapter.notifyDataSetChanged()
                Snackbar.make(
                    binding.root,
                    if (input.isBlank()) R.string.project_note_cleared else R.string.project_note_saved,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun confirmDelete(entry: RootShell.DirEntry) {
        val display = notes[entry.fullPath] ?: entry.name
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.project_delete_title)
            .setMessage(getString(R.string.project_delete_msg, display))
            .setNegativeButton(R.string.project_delete_cancel, null)
            .setPositiveButton(R.string.project_delete_confirm) { _, _ -> doDelete(entry) }
            .show()
    }

    private fun doDelete(entry: RootShell.DirEntry) {
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { RootShell.deletePath(entry.fullPath) }
            binding.progress.visibility = View.GONE

            if (ok) {
                // 目录没了，备注也跟着清掉，不留指向已删除路径的野数据
                Notes.remove(this@ProjectListActivity, entry.fullPath)
                Snackbar.make(
                    binding.root,
                    getString(R.string.project_deleted, notes[entry.fullPath] ?: entry.name),
                    Snackbar.LENGTH_SHORT
                ).show()
                load()
            } else {
                Snackbar.make(binding.root, R.string.project_delete_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }
}
