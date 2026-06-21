package com.offlineai.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineai.app.engine.ModelState
import com.offlineai.app.ui.theme.Primary
import com.offlineai.app.ui.theme.Secondary
import kotlinx.coroutines.delay

/**
 * 启动页
 *
 * 首次启动：显示解压进度 → 加载进度 → 进入主界面
 * 后续启动：显示快速加载 → 进入主界面
 */
@Composable
fun SplashScreen(
    modelState: ModelState,
    progress: Float,
    errorMessage: String?,
    isFirstLaunch: Boolean,
    onFinished: () -> Unit
) {
    var displayProgress by remember { mutableStateOf(0f) }

    // 平滑动画过渡
    LaunchedEffect(progress) {
        // 简单线性插值使进度条平滑
        val target = progress
        val step = 0.01f
        while (displayProgress < target) {
            displayProgress = (displayProgress + step).coerceAtMost(target)
            delay(16) // ~60fps
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D1B2A),
                        Color(0xFF1B2838),
                        Color(0xFF0D1B2A)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo 区域
            Text(
                text = "📖",
                fontSize = 64.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "离线AI助手",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "完全本地运行 · 无需网络",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 状态指示
            when (modelState) {
                ModelState.IDLE -> {
                    // 初始状态，准备开始
                }
                ModelState.EXTRACTING -> {
                    StatusCard(
                        title = if (isFirstLaunch) "正在准备模型文件..." else "正在检查模型...",
                        subtitle = if (isFirstLaunch) "首次启动需要解压模型，约需10-15秒" else "",
                        progress = displayProgress,
                        progressLabel = "${(displayProgress * 100).toInt()}%"
                    )
                }
                ModelState.LOADING -> {
                    StatusCard(
                        title = "正在初始化AI模型...",
                        subtitle = "加载神经网络权重到内存",
                        progress = displayProgress,
                        progressLabel = "${(displayProgress * 100).toInt()}%"
                    )
                }
                ModelState.READY -> {
                    // 加载完成，触发跳转
                    LaunchedEffect(Unit) {
                        delay(300) // 短暂展示完成状态
                        onFinished()
                    }
                    StatusCard(
                        title = "✓ 初始化完成",
                        subtitle = "即将进入主界面",
                        progress = 1f,
                        progressLabel = "100%",
                        isComplete = true
                    )
                }
                ModelState.ERROR -> {
                    ErrorCard(
                        message = errorMessage ?: "未知错误",
                        onRetry = onFinished  // 简化：点击重试
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 底部品牌
            Text(
                text = "Powered by Qwen3.5 · llama.cpp",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.3f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    subtitle: String,
    progress: Float,
    progressLabel: String,
    isComplete: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.08f)
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 进度条
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = if (isComplete) Secondary else Primary,
                trackColor = Color.White.copy(alpha = 0.1f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = progressLabel,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isComplete) Secondary else Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFD93025).copy(alpha = 0.12f)
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "❌", fontSize = 36.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "初始化失败",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onRetry) {
                Text("重试", color = Primary)
            }
        }
    }
}
