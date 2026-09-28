package com.QQMiGe.XXS

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.QQMiGe.XXS.databinding.ActivityRootBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root 申请页。
 * 流程：点击按钮 -> 调用 su -> 判断 id 是否 uid=0 -> 成功则写授权凭据文件 -> 跳转项目列表。
 *
 * ── 授权凭据（「授权页只出现一次」）────────────────────────────
 * 授权成功后会在应用私有目录写一个 [GRANT_FLAG] 文件，记录当时 su 的 `id` 输出。
 * 下次启动时若该文件存在，就【不渲染本页】，直接进 [ProjectListActivity]。
 *
 * 为什么用文件而不是每次都重新问一遍 su：冷启动必须重新创建 su 进程，
 * 那是一次真实的授权请求。是否弹系统窗口由 KernelSU/Magisk 的「记住」开关决定，
 * App 无法控制；但 App 自己能控制的是【不再把 Root 申请页摆在用户面前】。
 *
 * 自愈：若凭据文件在、实际 root 却已失效（被撤销授权/换设备），
 * 进入项目列表会拿到 NOT_ROOT，[ProjectListActivity] 会清掉该文件，
 * 返回本页时就会重新显示申请界面。
 */
class RootActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRootBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已授权过：直接进项目列表，本页不渲染、不显示
        if (isGranted(this)) {
            startActivity(Intent(this, ProjectListActivity::class.java))
            finish()
            return
        }

        binding = ActivityRootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRequestRoot.setOnClickListener { requestRoot() }
    }

    private fun requestRoot() {
        binding.btnRequestRoot.isEnabled = false
        binding.tvStatus.text = getString(R.string.root_checking)
        binding.progress.visibility = View.VISIBLE
        binding.dot.visibility = View.GONE

        lifecycleScope.launch {
            // su 弹窗期间会阻塞等待用户点击，放到 IO 线程避免卡 UI
            val granted = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }

            binding.progress.visibility = View.GONE
            binding.dot.visibility = View.VISIBLE

            if (granted) {
                binding.tvStatus.text = getString(R.string.root_granted)
                markGranted(this@RootActivity)
                startActivity(Intent(this@RootActivity, ProjectListActivity::class.java))
                finish()
            } else {
                binding.tvStatus.text = getString(R.string.root_denied)
                binding.btnRequestRoot.isEnabled = true
            }
        }
    }

    companion object {
        /** 授权凭据文件名，位于 /data/data/<本包>/files/ 下 */
        private const val GRANT_FLAG = "root_granted.flag"

        private fun flagFile(context: Context): File = File(context.filesDir, GRANT_FLAG)

        /** 记录「已授权」，内容存 su 的 id 输出，便于排查 */
        fun markGranted(context: Context) {
            try {
                flagFile(context).writeText(RootShell.whoami())
            } catch (_: Exception) {
                // 写不进去不影响本次使用，只是下次仍会看到申请页
            }
        }

        /** 是否已授权过 */
        fun isGranted(context: Context): Boolean = flagFile(context).exists()

        /** 清除凭据，使下次启动重新显示申请页 */
        fun clearGrant(context: Context) {
            try {
                flagFile(context).delete()
            } catch (_: Exception) {
            }
        }
    }
}
