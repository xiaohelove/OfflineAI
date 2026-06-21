package com.offlineai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlineai.app.engine.ModelState
import com.offlineai.app.ui.screens.HomeScreen
import com.offlineai.app.ui.screens.SplashScreen
import com.offlineai.app.ui.theme.OfflineAITheme
import kotlinx.coroutines.launch

/**
 * 唯一 Activity
 *
 * 路由：SplashScreen → HomeScreen
 * 完全离线，无网络权限依赖
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as OfflineAIApp

        setContent {
            OfflineAITheme {
                MainApp(app)
            }
        }
    }
}

@Composable
private fun MainApp(app: OfflineAIApp) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showSplash by remember { mutableStateOf(true) }
    var isFirstLaunch by remember { mutableStateOf(!app.modelManager.isModelExtracted()) }

    // 收集模型状态
    val modelState by app.modelManager.state.collectAsStateWithLifecycle()
    val modelProgress by app.modelManager.progress.collectAsStateWithLifecycle()
    val errorMessage by app.modelManager.errorMessage.collectAsStateWithLifecycle()

    // 收集对话状态
    val messages by app.chatRepository.messages.collectAsStateWithLifecycle()
    val isGenerating by app.inferenceService.isGenerating.collectAsStateWithLifecycle()

    // RAG 导入状态
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf(0f) }
    // 知识库刷新触发器
    var manualVersion by remember { mutableIntStateOf(0) }

    // 初始化模型
    LaunchedEffect(Unit) {
        app.modelManager.initialize()
    }

    if (showSplash) {
        SplashScreen(
            modelState = modelState,
            progress = modelProgress,
            errorMessage = errorMessage,
            isFirstLaunch = isFirstLaunch,
            onFinished = {
                showSplash = false
            }
        )
    } else {
        val scope = rememberCoroutineScope()

        // 加载历史消息（仅一次）
        LaunchedEffect(Unit) {
            app.chatRepository.loadHistory()
        }

        HomeScreen(
            messages = messages,
            isGenerating = isGenerating,
            manualVersion = manualVersion,
            onSendMessage = { text ->
                scope.launch {
                    app.chatRepository.addUserMessage(text)

                    try {
                        // RAG 检索
                        val ragContext = app.retriever.retrieveContext(text, topK = 5)
                        android.util.Log.d("MainActivity", "RAG context length: ${ragContext.length}, hasIndex=${app.retriever.hasIndex}")

                        // 无上下文直接拒答
                        if (ragContext.isBlank()) {
                            val msg = if (app.retriever.hasIndex)
                                "抱歉，未在文档中找到相关内容。"
                            else
                                "请先导入手册文档。"
                            app.chatRepository.addAssistantMessage(msg + com.offlineai.app.engine.InferenceService.DISCLAIMER_TEXT)
                            return@launch
                        }

                        // 检索场景：每问独立，不带历史
                        val result = app.inferenceService.generateDiagnosis(
                            userMessage = text,
                            retrievedContext = ragContext,
                            maxTokens = 1024
                        )

                        app.chatRepository.addAssistantMessage(result.text)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Inference failed", e)
                        app.chatRepository.addAssistantMessage("推理失败: ${e.localizedMessage}" + com.offlineai.app.engine.InferenceService.DISCLAIMER_TEXT)
                    }
                }
            },
            onClearChat = {
                scope.launch {
                    app.chatRepository.clearSession()
                }
            },
            hasManualIndex = app.retriever.hasIndex,
            chunkCount = app.retriever.chunkCount,
            manualFiles = remember(manualVersion) { app.retriever.manualFiles },
            chunkCounts = remember(manualVersion) { app.retriever.manualFiles.associate { it to app.retriever.getChunkCount(it) } },
            isImporting = isImporting,
            importProgress = importProgress,
            onImportPdf = { pdfFile ->
                scope.launch {
                    if (!app.modelManager.embeddingReady) {
                        android.widget.Toast.makeText(context, "Embedding 模型未就绪，无法导入：${app.modelManager.errorMessage.value ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    isImporting = true
                    importProgress = 0f
                    try {
                        val count = app.retriever.importManual(pdfFile) { progress ->
                            importProgress = progress
                        }
                        if (count == 0) {
                            android.widget.Toast.makeText(context, "导入完成，但未能提取到有效内容（所有分块 embedding 失败）。请检查 logcat 中 VectorStore/ModelManager 的日志。", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "导入失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                    } finally {
                        isImporting = false
                        manualVersion++
                    }
                }
            },
            onDeleteManuals = { files ->
                files.forEach { app.retriever.deleteManual(it) }
                manualVersion++
            }
        )
    }
}
