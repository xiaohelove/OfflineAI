package com.offlineai.app.rag

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

/**
 * RAG 全局配置参数
 */
object RAGConfig {
    /** 每个重叠分块包含的句子数（越大语义越完整，越小粒度越细） */
    const val CHUNK_SENTENCES = 3

    /** 相邻分块重叠的句子数（越大召回越高，存储越大） */
    const val OVERLAP_SENTENCES = 1

    /** 检索命中后窗口扩展的句子半径 */
    const val WINDOW_RADIUS = 4

    /** 单文档最大句子数上限 */
    const val MAX_SENTENCES = 2000

    /** 默认 Top-K 检索数量 */
    const val TOP_K = 5
}

/**
 * 句子记录
 *
 * 仅用于记录原文分句结果，供检索后的窗口重建使用，不再参与 embedding。
 */
data class SentenceRecord(
    val index: Int,
    val text: String,
    val sourceFile: String
)

/**
 * 小分块记录
 *
 * 索引和 embedding 的基本单元。由连续若干句子（CHUNK_SENTENCES）拼接而成，
 * 相邻块间有重叠（OVERLAP_SENTENCES 句）。
 *
 * @property sentenceStartIdx 覆盖的首句在全文中的索引（含）
 * @property sentenceEndIdx   覆盖的末句在全文中的索引（含）
 */
data class ChunkRecord(
    val index: Int,
    val text: String,
    val sourceFile: String,
    val sentenceStartIdx: Int,
    val sentenceEndIdx: Int
)

/**
 * 文档处理器
 *
 * 职责：
 * 1. 从 PDF / TXT 提取纯文本
 * 2. 按句子切分
 * 3. 基于句子构建重叠小分块（overlapping chunks）
 */
class DocumentProcessor(private val context: Context) {

    companion object {
        /** 中文断句标点 + 换行 */
        private val SENTENCE_SPLIT = Regex("(?<=[。！？；]|\\n)")
    }

    init {
        PDFBoxResourceLoader.init(context)
    }

    /**
     * 提取文本（自动识别格式）
     */
    suspend fun extractText(file: File): String {
        return withContext(Dispatchers.IO) {
            when (file.extension.lowercase()) {
                "pdf" -> extractPdf(file)
                "txt" -> extractTxt(file)
                else -> throw IllegalArgumentException("不支持的文件格式: ${file.extension}")
            }
        }
    }

    private fun extractPdf(pdfFile: File): String {
        return PDDocument.load(pdfFile).use { document ->
            val stripper = PDFTextStripper()
            stripper.getText(document)
        }
    }

    private fun extractTxt(txtFile: File): String {
        val bytes = txtFile.readBytes()
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            String(bytes, Charset.forName("GBK"))
        }
    }

    /**
     * 按句子切分
     *
     * 返回的句子列表供窗口重建使用。
     */
    fun splitSentences(text: String, sourceFile: String): List<SentenceRecord> {
        val normalized = text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        val sentences = normalized
            .split(SENTENCE_SPLIT)
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length > 2 }
            .let {
                if (it.size > RAGConfig.MAX_SENTENCES) {
                    android.util.Log.w("DocumentProcessor", "Document truncated: ${it.size} sentences → ${RAGConfig.MAX_SENTENCES} (source=$sourceFile)")
                    it.take(RAGConfig.MAX_SENTENCES)
                } else {
                    it
                }
            }

        if (sentences.isEmpty()) return emptyList()

        return sentences.mapIndexed { i, s ->
            SentenceRecord(
                index = i,
                text = s,
                sourceFile = sourceFile
            )
        }
    }

    /**
     * 基于句子列表构建重叠小分块
     *
     * 每个 chunk 由 [chunkSentences] 句拼接，相邻 chunk 间重叠 [overlapSentences] 句。
     * 例（chunk=2, overlap=1）：
     *   Chunk 0: S0+S1
     *   Chunk 1: S1+S2
     *   Chunk 2: S2+S3
     *   ...
     *
     * @param sentences       句子列表（应由 [splitSentences] 产生）
     * @param chunkSentences  每个 chunk 包含的句子数
     * @param overlapSentences 相邻 chunk 重叠的句子数
     */
    fun buildOverlappingChunks(
        sentences: List<SentenceRecord>,
        chunkSentences: Int = RAGConfig.CHUNK_SENTENCES,
        overlapSentences: Int = RAGConfig.OVERLAP_SENTENCES
    ): List<ChunkRecord> {
        if (sentences.isEmpty()) return emptyList()
        if (chunkSentences <= overlapSentences) {
            throw IllegalArgumentException("chunkSentences ($chunkSentences) must be > overlapSentences ($overlapSentences)")
        }

        val step = chunkSentences - overlapSentences
        if (step <= 0) {
            throw IllegalArgumentException("step ($step) must be > 0: check chunkSentences and overlapSentences")
        }

        val chunks = mutableListOf<ChunkRecord>()
        var i = 0
        var chunkIdx = 0

        while (i < sentences.size) {
            val end = (i + chunkSentences).coerceAtMost(sentences.size)
            val chunkText = (i until end).map { sentences[it].text }.joinToString("")
            chunks.add(
                ChunkRecord(
                    index = chunkIdx,
                    text = chunkText,
                    sourceFile = sentences[i].sourceFile,
                    sentenceStartIdx = sentences[i].index,
                    sentenceEndIdx = sentences[end - 1].index
                )
            )
            chunkIdx++
            if (end >= sentences.size) break
            i += step
        }

        android.util.Log.i("DocumentProcessor", "Built ${chunks.size} overlapping chunks from ${sentences.size} sentences (chunk=$chunkSentences, overlap=$overlapSentences)")
        return chunks
    }
}
