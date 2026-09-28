package com.QQMiGe.XXS

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.QQMiGe.XXS.databinding.ActivityAboutBinding
import com.QQMiGe.XXS.databinding.ItemAboutRepoBinding
import com.google.android.material.snackbar.Snackbar

/**
 * 关于页。
 *
 * 三块内容：
 *  1. 作者与「小小宣」的资料卡 —— 头像走腾讯 qlogo 实时接口，QQ 号可一键加好友
 *  2. QQ 群一键加群
 *  3. 本项目实际引用到的开源仓库清单，点击用系统浏览器打开
 *
 * 头像为什么是「实时」的：qlogo 是腾讯官方头像服务，只要拿到 QQ 号就能取到
 * 当前头像，用户换头像后下一次请求立即生效。页面每次 onResume 都会重新拉一遍
 * （带 no-cache 与时间戳参数），点头像也能手动刷新。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private val repoAdapter = RepoAdapter()

    /** 本项目真实引用到的开源仓库。这里只列 build.gradle.kts 里实际声明的依赖，
     *  一条都不多写 —— 关于页列出没引用的东西属于虚假陈述。 */
    private val repos = listOf(
        Repo(
            "AndroidX (core-ktx / appcompat / drawerlayout / recyclerview)",
            "基础兼容库、AppCompat、侧滑抽屉与列表控件",
            "https://github.com/androidx/androidx"
        ),
        Repo(
            "ConstraintLayout",
            "约束布局",
            "https://github.com/androidx/constraintlayout"
        ),
        Repo(
            "Material Components for Android",
            "Material 3 组件（卡片、按钮、输入框、底部弹窗）",
            "https://github.com/material-components/material-components-android"
        ),
        Repo(
            "Kotlin",
            "开发语言与标准库",
            "https://github.com/JetBrains/kotlin"
        ),
        Repo(
            "kotlinx.coroutines",
            "协程，用于把文件读写与网络请求切到后台线程",
            "https://github.com/Kotlin/kotlinx.coroutines"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvVersion.text = getString(R.string.about_version, versionName())

        // ---- 作者卡：超绝小学生233 ----
        binding.tvAuthorQq.text = getString(R.string.about_qq_format, AUTHOR_QQ)
        binding.btnAuthorAdd.setOnClickListener { openPerson(AUTHOR_QQ, R.string.about_author_name) }
        binding.ivAuthorAvatar.setOnClickListener { refreshAvatar(AUTHOR_QQ, binding.ivAuthorAvatar) }
        binding.cardAuthor.setOnClickListener { openPerson(AUTHOR_QQ, R.string.about_author_name) }

        // ---- 爱人卡：小小宣 ----
        QqAvatarLoader.load(LOVER_QQ, binding.ivLoverAvatar, placeholderRes = R.drawable.bg_avatar_circle)
        binding.tvLoverQq.text = getString(R.string.about_qq_format, LOVER_QQ)
        binding.btnLoverAdd.setOnClickListener { openPerson(LOVER_QQ, R.string.about_lover_name) }
        binding.ivLoverAvatar.setOnClickListener { refreshAvatar(LOVER_QQ, binding.ivLoverAvatar) }
        binding.cardLover.setOnClickListener { openPerson(LOVER_QQ, R.string.about_lover_name) }

        // ---- 群卡 ----
        binding.tvGroupNo.text = getString(R.string.about_qq_format, GROUP_NO)
        binding.btnJoinGroup.setOnClickListener { openGroup() }
        binding.cardGroup.setOnClickListener { openGroup() }

        // ---- 引用仓库 ----
        binding.rvRepos.layoutManager = LinearLayoutManager(this)
        binding.rvRepos.adapter = repoAdapter
        repoAdapter.submit(repos)

        // 头像实时刷新：每次回到前台都重拉，保证换过头像后立刻看到新的
        refreshAllAvatars(force = false)
    }

    override fun onResume() {
        super.onResume()
        // 从 QQ 改完头像切回来，这里能拿到新图
        refreshAllAvatars(force = true)
    }

    private fun refreshAllAvatars(force: Boolean) {
        QqAvatarLoader.load(
            AUTHOR_QQ, binding.ivAuthorAvatar,
            force = force, placeholderRes = R.drawable.bg_avatar_circle
        )
        QqAvatarLoader.load(
            LOVER_QQ, binding.ivLoverAvatar,
            force = force, placeholderRes = R.drawable.bg_avatar_circle
        )
    }

    /** 点头像 = 手动刷新（清缓存后强制重下） */
    private fun refreshAvatar(qq: String, view: android.widget.ImageView) {
        QqAvatarLoader.invalidate(qq)
        snack(getString(R.string.about_avatar_refreshing))
        QqAvatarLoader.load(qq, view, force = true, placeholderRes = R.drawable.bg_avatar_circle) { ok ->
            snack(
                if (ok) getString(R.string.about_avatar_refreshed)
                else getString(R.string.about_avatar_failed, qq)
            )
        }
    }

    private fun openPerson(qq: String, nameRes: Int) {
        if (QqLinks.openPerson(this, qq)) {
            snack(getString(R.string.about_jump_qq, getString(nameRes)))
        } else {
            // 连网页都没跳成：至少把号码复制给用户，别让这条路彻底断掉
            copyToClipboard(qq)
            snack(getString(R.string.about_copy_fallback, qq))
        }
    }

    private fun openGroup() {
        if (QqLinks.openGroup(this, GROUP_NO)) {
            snack(getString(R.string.about_jump_group, GROUP_NO))
        } else {
            copyToClipboard(GROUP_NO)
            snack(getString(R.string.about_copy_fallback, GROUP_NO))
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("qq", text))
    }

    private fun snack(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).show()
    }

    /** 版本号从 PackageManager 读，避免额外开 BuildConfig 开关 */
    private fun versionName(): String = try {
        val pm = packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        info.versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    // ---------------- 引用仓库列表 ----------------

    private data class Repo(val name: String, val desc: String, val url: String)

    private inner class RepoAdapter : RecyclerView.Adapter<RepoAdapter.VH>() {
        private val items = mutableListOf<Repo>()

        fun submit(list: List<Repo>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemAboutRepoBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(repo: Repo) {
                b.tvRepoName.text = repo.name
                b.tvRepoDesc.text = repo.desc
                b.tvRepoUrl.text = repo.url
                b.root.setOnClickListener {
                    if (QqLinks.openUrl(this@AboutActivity, repo.url)) {
                        snack(getString(R.string.about_open_ref, repo.name))
                    } else {
                        copyToClipboard(repo.url)
                        snack(getString(R.string.about_copy_fallback, repo.url))
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemAboutRepoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    }

    companion object {
        /** 作者 */
        const val AUTHOR_QQ = "790399726"
        /** 作者的爱人 */
        const val LOVER_QQ = "3193583971"
        /** QQ 群 */
        const val GROUP_NO = "1040835997"
    }
}
