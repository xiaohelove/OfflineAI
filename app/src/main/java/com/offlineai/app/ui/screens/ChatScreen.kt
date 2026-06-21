package com.offlineai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineai.app.data.ChatMessage
import com.offlineai.app.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 对话界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    // 新消息时自动滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI助手", fontWeight = FontWeight.Bold)
                        Text(
                            "本地AI · 离线运行",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Delete, contentDescription = "清空对话")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // 输入区域
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "描述设备故障现象...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    FilledIconButton(
                        onClick = {
                            val text = inputText.trim()
                            if (text.isNotEmpty() && !isGenerating) {
                                onSend(text)
                                inputText = ""
                            }
                            // 发送后滚动到底部
                            coroutineScope.launch {
                                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount)
                            }
                        },
                        enabled = inputText.isNotBlank() && !isGenerating,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Primary
                        )
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "发送")
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (messages.isEmpty()) {
            // 空状态引导
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = "📖", fontSize = 56.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "离线AI助手",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "导入产品手册后，AI 将基于文档内容精准回答。\n所有分析完全在本地完成，无需联网。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                // 快捷问题示例
                SuggestionChip(
                    text = "手机屏幕闪烁是什么原因？",
                    onClick = {
                        onSend("手机屏幕闪烁是什么原因？")
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SuggestionChip(
                    text = "设备无法开机怎么排查？",
                    onClick = {
                        onSend("设备无法开机怎么排查？")
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                SuggestionChip(
                    text = "充电接口接触不良如何处理？",
                    onClick = {
                        onSend("充电接口接触不良如何处理？")
                    }
                )
            }
        } else {
            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // 助手头像
        if (!isUser) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = RoundedCornerShape(16.dp),
                color = Primary.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🤖", fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) Primary else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = if (isUser)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                modifier = Modifier.size(32.dp),
                shape = RoundedCornerShape(16.dp),
                color = Primary.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("👤", fontSize = 14.sp)
                }
            }
        }
    }
}
