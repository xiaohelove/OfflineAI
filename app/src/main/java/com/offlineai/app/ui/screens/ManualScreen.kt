package com.offlineai.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineai.app.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ManualScreen(
    hasIndex: Boolean,
    chunkCount: Int,
    manualFiles: List<String>,
    chunkCounts: Map<String, Int>,
    isImporting: Boolean,
    importProgress: Float,
    onImportPdf: (File) -> Unit,
    onDeleteManuals: (List<String>) -> Unit
) {
    val context = LocalContext.current
    var selectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<String>()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 退出选择模式
    fun exitSelection() {
        selectionMode = false
        selectedFiles = emptySet()
    }

    // 文件选择器（PDF / TXT）
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            var originalName = "document_${System.currentTimeMillis()}"
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        originalName = cursor.getString(nameIdx)
                    }
                }
            }
            val ext = originalName.substringAfterLast('.', "").lowercase()
            if (ext !in listOf("pdf", "txt")) {
                android.widget.Toast.makeText(context, "仅支持 PDF / TXT", android.widget.Toast.LENGTH_SHORT).show()
                return@let
            }
            // 去重：如果同名文件已存在，加序号
            val manualsDir = File(context.filesDir, "manuals")
            manualsDir.mkdirs()
            var destFile = File(manualsDir, originalName)
            var counter = 1
            while (destFile.exists()) {
                val dot = originalName.lastIndexOf('.')
                val base = if (dot > 0) originalName.substring(0, dot) else originalName
                val ext = if (dot > 0) originalName.substring(dot) else ".pdf"
                destFile = File(manualsDir, "${base}($counter)$ext")
                counter++
            }
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                onImportPdf(destFile)
            } catch (e: Exception) {
                destFile.delete()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) "已选择 ${selectedFiles.size} 项" else "知识库",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (selectionMode) {
                        // 选择模式：删除 + 取消
                        IconButton(
                            onClick = {
                                if (selectedFiles.isNotEmpty()) {
                                    showDeleteDialog = true
                                }
                            },
                            enabled = selectedFiles.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Delete, "删除", tint = ErrorRed)
                        }
                        TextButton(onClick = { exitSelection() }) {
                            Text("取消")
                        }
                    } else {
                        // 普通模式：导入按钮
                        IconButton(onClick = { filePicker.launch(arrayOf("application/pdf", "text/plain")) }) {
                            Icon(Icons.Default.Add, "导入手册")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (!hasIndex && !isImporting) {
                // 空书架引导
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📚", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("书架空空", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "点击右上角 + 导入 PDF 手册",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { filePicker.launch(arrayOf("application/pdf", "text/plain")) }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导入第一本手册")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 导入中占位
                    if (isImporting) {
                        item {
                            ImportingCard(importProgress)
                        }
                    }

                    items(manualFiles, key = { it }) { fileName ->
                        val isSelected = fileName in selectedFiles
                        val bgColor by animateColorAsState(
                            if (isSelected) Primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface,
                            label = "cardBg"
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selectedFiles = if (isSelected)
                                                selectedFiles - fileName
                                            else
                                                selectedFiles + fileName
                                        }
                                    },
                                    onLongClick = {
                                        if (!selectionMode) {
                                            selectionMode = true
                                            selectedFiles = setOf(fileName)
                                        }
                                    }
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = bgColor),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isSelected) 4.dp else 1.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // 选择指示
                                if (selectionMode) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Icon(
                                            if (isSelected) Icons.Default.CheckCircle
                                            else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) Primary else DisclaimerGray,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // 书本图标
                                Surface(
                                    modifier = Modifier.size(56.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Primary.copy(alpha = 0.08f)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.MenuBook,
                                            contentDescription = null,
                                            tint = Primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // 文件名
                                Text(
                                    text = fileName.removeSuffix(".pdf"),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // 片段数
                                Text(
                                    text = "${chunkCounts[fileName] ?: 0} 片段",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 删除确认弹窗
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text("确认删除") },
                    text = {
                        Text(
                            if (selectedFiles.size == 1)
                                "确定要删除「${selectedFiles.first().removeSuffix(".pdf")}」吗？\n索引数据将被永久清除。"
                            else
                                "确定要删除 ${selectedFiles.size} 个手册吗？\n索引数据将被永久清除。"
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteDialog = false
                                onDeleteManuals(selectedFiles.toList())
                                exitSelection()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
                        ) {
                            Text("删除")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ImportingCard(progress: Float) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = Primary,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("导入中...", fontSize = 13.sp, color = Primary)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("${(progress * 100).toInt()}%", fontSize = 11.sp, color = DisclaimerGray)
        }
    }
}
