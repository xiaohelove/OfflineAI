package com.offlineai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.offlineai.app.data.ChatMessage
import com.offlineai.app.ui.theme.Primary

/**
 * 应用主界面
 *
 * 底部导航：
 * - 诊断（对话）
 * - 手册（知识库管理）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    manualVersion: Int,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    hasManualIndex: Boolean,
    chunkCount: Int,
    manualFiles: List<String>,
    chunkCounts: Map<String, Int>,
    isImporting: Boolean,
    importProgress: Float,
    onImportPdf: (java.io.File) -> Unit,
    onDeleteManuals: (List<String>) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    label = { Text("诊断") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Primary,
                        selectedTextColor = Primary,
                        indicatorColor = Primary.copy(alpha = 0.1f)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                    label = { Text("手册") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Primary,
                        selectedTextColor = Primary,
                        indicatorColor = Primary.copy(alpha = 0.1f)
                    )
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ChatScreen(
                    messages = messages,
                    isGenerating = isGenerating,
                    onSend = onSendMessage,
                    onClear = onClearChat
                )
                1 -> ManualScreen(
                    hasIndex = hasManualIndex,
                    chunkCount = chunkCount,
                    manualFiles = manualFiles,
                    chunkCounts = chunkCounts,
                    isImporting = isImporting,
                    importProgress = importProgress,
                    onImportPdf = onImportPdf,
                    onDeleteManuals = onDeleteManuals
                )
            }
        }
    }
}
