package com.offlineai.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 推理结果
 */
data class InferenceResult(
    val text: String,
    val tokensGenerated: Int,
    val timeMs: Long
)

/**
 * 推理服务
 *
 * 封装 llama.cpp 推理调用，提供高阶 API：
 * - 编号引用式提示词
 * - 免责声明自动追加
 */
class InferenceService {

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val SYSTEM_PROMPT = """你是严格的文档检索机器人。
规则：只使用【参考资料】中的原文回答。必须引用编号来源。
如果参考资料无相关内容，只回复：根据提供的资料，未找到相关信息。
直接给出最终答案。禁止思考推理过程。"""

    private val DISCLAIMER = DISCLAIMER_TEXT

    companion object {
        /** 免责声明，供 UI 层直接追加时复用 */
        val DISCLAIMER_TEXT = "\n\n---\n⚠️ 以上回答由本地AI生成，仅供参考。请以文档原文为准。"
    }

    /**
     * 执行文档问答推理
     *
     * @param userMessage      当前用户问题
     * @param retrievedContext RAG 检索到的编号片段（可为空）
     * @param maxTokens        最大生成 token 数
     */
    suspend fun generateDiagnosis(
        userMessage: String,
        retrievedContext: String = "",
        maxTokens: Int = 256
    ): InferenceResult = withContext(Dispatchers.IO) {
        _isGenerating.value = true
        try {
            val startTime = System.currentTimeMillis()
            val messagesJson = buildMessagesJson(userMessage, retrievedContext)
            val rawOutput = LlamaBridge.nativeChatGenerate(messagesJson, maxTokens)
            val elapsed = System.currentTimeMillis() - startTime

            // 调试：记录原始输出前 200 字符
            android.util.Log.d("InferenceService", "Raw output (first 300 chars): ${rawOutput.take(300).replace("\n", "\\n")}")
            android.util.Log.d("InferenceService", "Contains <think>: ${rawOutput.contains("<think>")}, contains </think>: ${rawOutput.contains("</think>")}")
            android.util.Log.d("InferenceService", "Output length: ${rawOutput.length}")

            // 清理输出：去除 think 块和控制 token
            var cleaned = rawOutput

            // 策略：优先找 </think> 后的正式回答
            val thinkEndIdx = cleaned.indexOf("</think>")
            if (thinkEndIdx >= 0) {
                // 有闭合标签 → 取 </think> 之后的内容作为回答
                cleaned = cleaned.substring(thinkEndIdx + 8).trim()
                android.util.Log.d("InferenceService", "Found </think>, using post-think content (${cleaned.length} chars)")
            } else if (cleaned.contains("<think>")) {
                // 无闭合标签 → 模型 token 耗尽还在思考
                // 尝试在 think 内容中找 "答案：" 或 "回答：" 标记
                android.util.Log.w("InferenceService", "Unclosed <think>, trying to extract answer from thinking content")
                val answerMarkers = listOf("答案：", "回答：", "答案是", "因此，", "所以", "结论：")
                val thinkStart = cleaned.indexOf("<think>")
                val afterThink = cleaned.substring(thinkStart + 7)
                var foundAnswer = false
                for (marker in answerMarkers) {
                    val mi = afterThink.indexOf(marker)
                    if (mi >= 0) {
                        cleaned = afterThink.substring(mi).trim()
                        android.util.Log.d("InferenceService", "Extracted answer after marker '$marker' (${cleaned.length} chars)")
                        foundAnswer = true
                        break
                    }
                }
                if (!foundAnswer) {
                    // 完全找不到答案标记 → 取 think 内容的最后 200 字符作为应急输出
                    cleaned = if (afterThink.length > 200) {
                        android.util.Log.d("InferenceService", "No answer marker found, using last 200 chars of thinking")
                        "…" + afterThink.takeLast(200)
                    } else {
                        android.util.Log.d("InferenceService", "No answer marker found, using all thinking content")
                        afterThink
                    }
                }
            }

            // 清理残余标签和 control tokens
            cleaned = cleaned
                .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<think>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("</think>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<\\|im_start\\|>.*?<\\|im_end\\|>", RegexOption.DOT_MATCHES_ALL), "")
                .trim()
            android.util.Log.d("InferenceService", "Cleaned output (first 200 chars): ${cleaned.take(200).replace("\n", "\\n")}")

            val finalOutput = if (cleaned.isNotBlank()) {
                cleaned + DISCLAIMER
            } else {
                "模型未能生成有效回复，请重试。" + DISCLAIMER
            }

            InferenceResult(
                text = finalOutput,
                tokensGenerated = rawOutput.length / 2,
                timeMs = elapsed
            )
        } finally {
            _isGenerating.value = false
        }
    }

    /**
     * 构建 JSON 消息：系统规则 + 用户消息（参考资料 + 问题）
     *
     * 【参考资料】放在 user 消息中紧邻问题，模型更容易注意到它。
     */
    private fun buildMessagesJson(userMessage: String, context: String): String {
        val userContent = buildString {
            if (context.isNotBlank()) {
                append("【参考资料】\n")
                append(context)
                append("\n\n")
            }
            append("问题：")
            append(userMessage)
        }

        return buildString {
            append("""[{"role":"system","content":"${escapeJson(SYSTEM_PROMPT)}"}""")
            append(""",{"role":"user","content":"${escapeJson(userContent)}"}]""")
        }
    }

    /** JSON 字符串转义 */
    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")
    }
}
