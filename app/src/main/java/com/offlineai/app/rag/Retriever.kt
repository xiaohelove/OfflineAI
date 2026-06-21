package com.offlineai.app.rag

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class Retriever(private val context: Context) {

    private val processor = DocumentProcessor(context)
    private val store = VectorStore(context)

    val hasIndex: Boolean get() = store.chunks.isNotEmpty()
    val chunkCount: Int get() = store.chunks.size
    val manualFiles: List<String> get() = store.getManualFiles()
    fun getChunkCount(fileName: String): Int = store.getChunkCountForFile(fileName)

    /**
     * 导入手册文档
     *
     * 流程：提取文本 → 句子切分 → 重叠小分块 → embedding 索引
     */
    suspend fun importManual(
        pdfFile: File,
        onProgress: (Float) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        onProgress(0.05f)
        val text = processor.extractText(pdfFile)
        onProgress(0.2f)

        // 步骤 1：句子切分
        val sentences = processor.splitSentences(text, pdfFile.name)
        onProgress(0.35f)

        // 步骤 2：构建重叠小分块
        val chunks = processor.buildOverlappingChunks(sentences)
        onProgress(0.5f)

        // 步骤 3：索引 embedding
        val count = store.indexChunks(chunks, sentences)
        onProgress(1.0f)
        count
    }

    /**
     * 基于用户查询检索相关上下文
     */
    suspend fun retrieveContext(query: String, topK: Int = RAGConfig.TOP_K): String {
        return withContext(Dispatchers.IO) {
            android.util.Log.d("RAG", "retrieveContext: hasIndex=$hasIndex, chunks=${store.chunks.size}, sentences=${store.sentences.size}, query=${query.take(30)}")
            if (!hasIndex) return@withContext ""

            val results = store.search(query, topK)

            if (results.isEmpty()) {
                android.util.Log.w("RAG", "search returned 0 results for: ${query.take(40)}")
                return@withContext ""
            }

            android.util.Log.d("RAG", "found ${results.size} results, top=${results[0].score}, src=${results[0].sourceFile}")

            val contextStr = results.mapIndexed { idx, result ->
                val src = result.sourceFile.removeSuffix(".pdf").removeSuffix(".txt")
                val percent = if (result.totalInDoc > 0)
                    ((result.sentenceIndex.toFloat() / result.totalInDoc) * 100).toInt()
                else 0
                val preview = if (result.window.length > 400) result.window.take(400) + "..." else result.window
                "[${idx + 1}]《$src》(${percent}%) $preview"
            }.joinToString("\n")

            android.util.Log.d("RAG", "Context content:\n$contextStr")
            return@withContext contextStr
        }
    }

    fun deleteManual(fileName: String) {
        store.deleteManual(fileName)
        File(context.filesDir, "manuals/$fileName").delete()
    }

    fun clear() {
        store.clear()
        val manualsDir = context.filesDir.resolve("manuals")
        if (manualsDir.exists()) {
            manualsDir.deleteRecursively()
        }
    }
}
