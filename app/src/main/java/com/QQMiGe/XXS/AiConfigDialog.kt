package com.QQMiGe.XXS

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.QQMiGe.XXS.databinding.DialogAiConfigBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * AI 模型配置对话框。
 *
 * 「测试连接」不是发一条真对话（费 token），而是带 tools 发一个极小的
 * 请求：只要模型返回合法的 choices 结构，就说明 Key / 端点 / 模型名都对。
 * 失败时把服务端原始错误原文显示出来，方便排查中转站配置。
 */
class AiConfigDialog : DialogFragment() {

    private var _binding: DialogAiConfigBinding? = null
    private val binding get() = _binding!!

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogAiConfigBinding.inflate(layoutInflater)

        val cfg = AiConfig.load(requireContext())
        binding.etBase.setText(cfg.baseUrl)
        binding.etKey.setText(cfg.apiKey)
        binding.etModel.setText(cfg.model)
        binding.etTemp.setText(cfg.temperature.toString())
        binding.etMaxTokens.setText(cfg.maxTokens.toString())
        binding.etSystem.setText(cfg.systemPrompt)

        binding.btnTest.setOnClickListener { testConnection() }

        // 两个按钮都只改输入框内容，不落盘 —— 是否保存交给「确定」，
        // 免得误点一下就把已存好的提示词冲掉
        binding.btnSystemClear.setOnClickListener {
            binding.etSystem.setText("")
            toast(getString(R.string.ai_cfg_system_cleared))
        }
        binding.btnSystemReset.setOnClickListener {
            binding.etSystem.setText(AiConfig.SYSTEM_TEMPLATE)
            binding.etSystem.setSelection(binding.etSystem.text?.length ?: 0)
            toast(getString(R.string.ai_cfg_system_reset_done))
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_config_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ -> save() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun collect(): AiConfig.Data = AiConfig.Data(
        baseUrl = binding.etBase.text?.toString().orEmpty().ifBlank { AiConfig.DEFAULT_BASE },
        apiKey = binding.etKey.text?.toString().orEmpty(),
        model = binding.etModel.text?.toString().orEmpty().ifBlank { AiConfig.DEFAULT_MODEL },
        temperature = binding.etTemp.text?.toString()?.toDoubleOrNull() ?: AiConfig.DEFAULT_TEMP,
        maxTokens = binding.etMaxTokens.text?.toString()?.toIntOrNull() ?: AiConfig.DEFAULT_MAX_TOKENS,
        // 不写 ifBlank{模板}：清空按钮就是要把提示词清空，
        // 这里再回落等于用户永远清不掉它
        systemPrompt = binding.etSystem.text?.toString().orEmpty()
    )

    private fun save() {
        val data = collect()
        AiConfig.save(requireContext(), data)
        if (data.usable) {
            toast(getString(R.string.ai_cfg_saved))
        } else {
            toast(getString(R.string.ai_cfg_key_required))
        }
    }

    private fun testConnection() {
        val data = collect()
        if (data.apiKey.isBlank()) {
            showResult(getString(R.string.ai_cfg_key_required), error = true)
            return
        }

        binding.btnTest.isEnabled = false
        showResult(getString(R.string.ai_cfg_testing), error = false)

        io.execute {
            val message = try {
                // 极小的探测请求：一条 user 消息，不带工具
                val reply = AiClient.chat(
                    data,
                    listOf(AiClient.Message("user", "hi")),
                    JSONArray()
                )
                "连接成功 ✓\n模型回复：${reply.content.take(120).ifBlank { "（空）" }}"
            } catch (e: Exception) {
                "连接失败 ✗\n${e.message}"
            }
            activity?.runOnUiThread { showResult(message, error = message.startsWith("连接失败")) }
        }
    }

    private fun showResult(text: String, error: Boolean) {
        val b = _binding ?: return
        b.btnTest.isEnabled = true
        b.tvTestResult.visibility = View.VISIBLE
        b.tvTestResult.text = text
        val attr = if (error) {
            com.google.android.material.R.attr.colorError
        } else {
            com.google.android.material.R.attr.colorPrimary
        }
        val ta = requireContext().obtainStyledAttributes(intArrayOf(attr))
        b.tvTestResult.setTextColor(ta.getColor(0, 0))
        ta.recycle()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = null

    private fun toast(text: String) =
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        io.shutdown()
        _binding = null
        super.onDestroyView()
    }
}
