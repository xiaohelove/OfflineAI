package com.offlineai.app.data

import com.offlineai.app.engine.InferenceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI 层消息模型
 */
data class ChatMessage(
    val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 对话仓库
 *
 * 管理消息列表状态和持久化
 */
class ChatRepository(private val database: AppDatabase) {

    private val dao = database.messageDao()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val currentSessionId = "default"

    /** 从数据库加载历史消息 */
    suspend fun loadHistory() {
        val entities = dao.getSessionMessages(currentSessionId)
        _messages.value = entities.map {
            ChatMessage(
                id = it.id,
                role = it.role,
                content = it.content,
                timestamp = it.timestamp
            )
        }
    }

    /** 添加用户消息 */
    suspend fun addUserMessage(content: String) {
        val entity = MessageEntity(
            sessionId = currentSessionId,
            role = "user",
            content = content
        )
        val id = dao.insert(entity)
        _messages.value = _messages.value + ChatMessage(
            id = id,
            role = "user",
            content = content
        )
    }

    /** 添加助手消息（完整） */
    suspend fun addAssistantMessage(content: String) {
        val entity = MessageEntity(
            sessionId = currentSessionId,
            role = "assistant",
            content = content
        )
        val id = dao.insert(entity)
        _messages.value = _messages.value + ChatMessage(
            id = id,
            role = "assistant",
            content = content
        )
    }

    /** 清空当前会话 */
    suspend fun clearSession() {
        dao.deleteSession(currentSessionId)
        _messages.value = emptyList()
    }

    /** 获取对话历史（用于 prompt 构建），最多保留最近 N 对。免责声明会被剥离 */
    fun getHistoryPairs(maxPairs: Int = 3): List<Pair<String, String>> {
        val msgs = _messages.value
        val pairs = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < msgs.size - 1) {
            if (msgs[i].role == "user" && msgs[i + 1].role == "assistant") {
                pairs.add(msgs[i].content to stripDisclaimer(msgs[i + 1].content))
                i += 2
            } else {
                i++
            }
        }
        return if (pairs.size > maxPairs) pairs.takeLast(maxPairs) else pairs
    }

    /** 剥离末尾的免责声明，避免污染后续 prompt */
    private fun stripDisclaimer(text: String): String {
        val marker = InferenceService.DISCLAIMER_TEXT.substringBefore("⚠️")
        val idx = text.indexOf(marker)
        return if (idx >= 0) text.substring(0, idx) else text
    }
}
