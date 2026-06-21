package com.offlineai.app.rag

import android.content.Context
import com.offlineai.app.engine.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt

@Serializable
private data class ChunkSaveData(
    val index: Int,
    val text: String,
    val sourceFile: String,
    val sentenceStartIdx: Int,
    val sentenceEndIdx: Int
)

@Serializable
private data class SentenceSaveData(
    val index: Int,
    val text: String,
    val sourceFile: String
)

/**
 * 检索结果
 */
data class SearchResult(
    val score: Float,
    val window: String,
    val sourceFile: String,
    val sentenceIndex: Int,
    val totalInDoc: Int
)

/**
 * 重叠小分块向量存储 + 句子窗口检索
 *
 * 分块策略：将连续句子按 [RAGConfig.CHUNK_SENTENCES] 句一组构建为小分块（chunk），
 * 相邻 chunk 间按 [RAGConfig.OVERLAP_SENTENCES] 句重叠。
 * 每个 chunk 独立 embedding 并索引，检索命中后通过 chunk 覆盖的句索引扩展为
 * 上下文窗口（前后各 [RAGConfig.WINDOW_RADIUS] 句）返回。
 */
class VectorStore(private val context: Context) {

    companion object {
        private const val CHUNKS_FILENAME = "chunks.json"
        private const val SENTENCES_FILENAME = "sentences.json"
        private const val EMBEDDINGS_FILENAME = "embeddings.bin"
        /** 二进制文件格式版本，数据模型变更时递增 */
        private const val BIN_VERSION = 4
    }

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val storeDir = File(context.filesDir, "vectors")

    /** 全量 chunk 列表（索引对象，与 embeddings 一一对应） */
    var chunks: List<ChunkRecord> = emptyList()
        private set

    /** 全量句子列表（仅用于窗口重建，不参与 embedding） */
    var sentences: List<SentenceRecord> = emptyList()
        private set

    /** 扁平 float[]，每 dim 个一组，与 chunks 一一对应 */
    private var embeddings: FloatArray = FloatArray(0)
    private var dim: Int = 0

    init {
        storeDir.mkdirs()
        loadFromDisk()
    }

    // ── 索引 ────────────────────────────────────────

    /**
     * 批量嵌入 chunks 并追加到存储
     *
     * @param chunks     待索引的分块列表
     * @param sentences  关联的句子列表（全文，用于窗口重建）
     * @return 成功嵌入的 chunk 数
     */
    suspend fun indexChunks(
        newChunks: List<ChunkRecord>,
        allSentences: List<SentenceRecord>
    ): Int = withContext(Dispatchers.IO) {
        if (newChunks.isEmpty()) return@withContext 0

        val successfulChunks = mutableListOf<ChunkRecord>()
        val newEmbs = mutableListOf<FloatArray>()
        var skipped = 0

        for (c in newChunks) {
            val emb = LlamaBridge.nativeGetEmbedding(c.text)
            if (emb != null) {
                if (dim == 0) {
                    dim = emb.size
                } else if (emb.size != dim) {
                    android.util.Log.e("VectorStore", "Embedding dimension mismatch: model=${emb.size}, stored=$dim. Clearing old index.")
                    chunks = emptyList()
                    sentences = emptyList()
                    embeddings = FloatArray(0)
                    dim = emb.size
                    successfulChunks.clear()
                    newEmbs.clear()
                    skipped = 0
                }
                newEmbs.add(emb)
                successfulChunks.add(c)
            } else {
                skipped++
            }
        }

        if (skipped > 0) {
            android.util.Log.w("VectorStore", "Skipped $skipped/${newChunks.size} chunks (embedding failed)")
        }
        if (newEmbs.isEmpty()) {
            android.util.Log.e("VectorStore", "All ${newChunks.size} chunk embeddings failed; is BGE model loaded?")
            // 诊断：试一个单字 embedding，区分"模型未加载"和"模型加载但 embedding 失败"
            val testEmb = LlamaBridge.nativeGetEmbedding("测")
            android.util.Log.e("VectorStore", "DIAGNOSTIC: test embedding (single char) returned ${if (testEmb != null) "OK (dim=${testEmb.size})" else "NULL — BGE model NOT loaded"}")
            return@withContext 0
        }

        // 合并 embedding 数组
        val merged = FloatArray(embeddings.size + newEmbs.size * dim)
        System.arraycopy(embeddings, 0, merged, 0, embeddings.size)
        var offset = embeddings.size
        for (emb in newEmbs) {
            System.arraycopy(emb, 0, merged, offset, dim)
            offset += dim
        }
        embeddings = merged

        // 追加 chunks（修正 index）
        val startIdx = chunks.size
        chunks = chunks + successfulChunks.mapIndexed { i, c ->
            c.copy(index = startIdx + i)
        }

        // 覆盖/追加 sentences（全文替换，保持与 chunk 一致）
        sentences = allSentences

        android.util.Log.i("VectorStore", "Indexed ${newEmbs.size} chunks (total=${chunks.size}, sentences=${sentences.size}, dim=$dim)")
        saveToDisk()
        newEmbs.size
    }

    // ── 检索 ────────────────────────────────────────

    /**
     * chunk 级 dense 检索 + 句子窗口重建
     *
     * 流程：
     * 1. 查询向量化
     * 2. 余弦相似度 → 对所有 chunk 排序
     * 3. Top-K → 通过 chunk 覆盖的句索引扩展窗口 → 去重
     */
    fun search(query: String, topK: Int = RAGConfig.TOP_K): List<SearchResult> {
        val numChunks = chunks.size
        if (numChunks == 0 || embeddings.isEmpty() || dim == 0) {
            android.util.Log.w("VectorStore", "search aborted: chunks=$numChunks embLen=${embeddings.size} dim=$dim")
            return emptyList()
        }

        val queryEmb = LlamaBridge.nativeGetQueryEmbedding(query)
        if (queryEmb == null) {
            android.util.Log.e("VectorStore", "Query embedding failed; BGE model loaded?")
            return emptyList()
        }

        if (queryEmb.size != dim) {
            android.util.Log.e("VectorStore", "Dimension mismatch: query=${queryEmb.size}, stored=$dim. Index may be stale.")
            return emptyList()
        }

        // 预计算查询向量模长
        var qNorm = 0f
        for (j in 0 until dim) qNorm += queryEmb[j] * queryEmb[j]
        qNorm = sqrt(qNorm)
        if (qNorm < 1e-8f) { android.util.Log.w("VectorStore", "search: qNorm near zero, aborting"); return emptyList() }

        // 余弦相似度（基于 chunk）
        val scores = mutableListOf<Pair<Float, Int>>()
        for (i in 0 until numChunks) {
            var dot = 0f
            var dNorm = 0f
            val off = i * dim
            for (j in 0 until dim) {
                val dv = embeddings[off + j]
                dot += queryEmb[j] * dv
                dNorm += dv * dv
            }
            val docNorm = sqrt(dNorm.coerceAtLeast(1e-8f))
            val sim = dot / (qNorm * docNorm)
            scores.add(sim to i)
        }
        scores.sortByDescending { it.first }

        // Top-K + 句子窗口重建 + 去重
        val results = mutableListOf<SearchResult>()
        val seenSentences = mutableSetOf<Int>()

        for ((score, idx) in scores) {
            val chunk = chunks[idx]
            val window = buildSentenceWindow(chunk, seenSentences)
            if (window.isBlank()) continue

            val totalInDoc = sentences.count { it.sourceFile == chunk.sourceFile }
            results.add(
                SearchResult(
                    score = score,
                    window = window,
                    sourceFile = chunk.sourceFile,
                    sentenceIndex = chunk.sentenceStartIdx,
                    totalInDoc = totalInDoc
                )
            )

            if (results.size >= topK) break
        }

        android.util.Log.i("VectorStore", "search done: chunks=$numChunks dim=$dim results=${results.size} topScore=${results.firstOrNull()?.score ?: "N/A"}")
        return results
    }

    /**
     * 从命中 chunk 的句索引范围扩展为上下文窗口
     *
     * 以 chunk 覆盖的句子为中心，前后各取 [RAGConfig.WINDOW_RADIUS] 句，
     * 仅拼接同源文件内的句子。已覆盖的句子通过 [seenSet] 去重。
     */
    private fun buildSentenceWindow(chunk: ChunkRecord, seenSet: MutableSet<Int>): String {
        val srcFile = chunk.sourceFile
        val radius = RAGConfig.WINDOW_RADIUS

        // 确定窗口范围
        val rangeStart = (chunk.sentenceStartIdx - radius).coerceAtLeast(0)
        val rangeEnd = (chunk.sentenceEndIdx + radius).coerceAtMost(sentences.size - 1)

        // 收集尚未被覆盖的句子
        val parts = mutableListOf<String>()
        for (si in rangeStart..rangeEnd) {
            val sentence = sentences[si]
            if (sentence.sourceFile != srcFile) continue
            if (si in seenSet) continue  // 去重
            seenSet.add(si)
            parts.add(sentence.text)
        }

        return parts.joinToString("")
    }

    // ── 管理 ────────────────────────────────────────

    fun getManualFiles(): List<String> = chunks.map { it.sourceFile }.distinct()

    fun getChunkCountForFile(fileName: String): Int = chunks.count { it.sourceFile == fileName }

    fun deleteManual(fileName: String) {
        val keepChunkIndices = chunks.indices.filter { chunks[it].sourceFile != fileName }

        if (keepChunkIndices.isEmpty()) {
            clear()
            return
        }

        // 重建 chunks
        val newChunks = keepChunkIndices.map { oldIdx ->
            chunks[oldIdx].copy(index = keepChunkIndices.indexOf(oldIdx))
        }

        // 重建 embeddings
        val newEmbs = FloatArray(keepChunkIndices.size * dim)
        for ((newIdx, oldIdx) in keepChunkIndices.withIndex()) {
            System.arraycopy(embeddings, oldIdx * dim, newEmbs, newIdx * dim, dim)
        }

        // 重建 sentences（仅保留未被删除的句子 + 未被删除文件对应的句子）
        val newSentences = sentences.filter { it.sourceFile != fileName }

        chunks = newChunks
        embeddings = newEmbs
        sentences = newSentences
        saveToDisk()
    }

    fun clear() {
        chunks = emptyList()
        sentences = emptyList()
        embeddings = FloatArray(0)
        dim = 0
        File(storeDir, CHUNKS_FILENAME).delete()
        File(storeDir, SENTENCES_FILENAME).delete()
        File(storeDir, EMBEDDINGS_FILENAME).delete()
    }

    // ── 持久化 ──────────────────────────────────────

    private fun saveToDisk() {
        try {
            // chunks.json
            val chunkData = chunks.map {
                ChunkSaveData(it.index, it.text, it.sourceFile, it.sentenceStartIdx, it.sentenceEndIdx)
            }
            File(storeDir, CHUNKS_FILENAME).writeText(json.encodeToString(chunkData))

            // sentences.json
            val sentData = sentences.map {
                SentenceSaveData(it.index, it.text, it.sourceFile)
            }
            File(storeDir, SENTENCES_FILENAME).writeText(json.encodeToString(sentData))

            // embeddings.bin
            if (embeddings.isNotEmpty()) {
                File(storeDir, EMBEDDINGS_FILENAME).outputStream().use { out ->
                    val buf = ByteArray(4)
                    // 版本标记（字节 0）
                    out.write(BIN_VERSION)
                    // dim（字节 1-4）
                    buf[0] = (dim shr 24).toByte()
                    buf[1] = (dim shr 16).toByte()
                    buf[2] = (dim shr 8).toByte()
                    buf[3] = dim.toByte()
                    out.write(buf)
                    for (v in embeddings) {
                        val bits = java.lang.Float.floatToRawIntBits(v)
                        buf[0] = (bits shr 24).toByte()
                        buf[1] = (bits shr 16).toByte()
                        buf[2] = (bits shr 8).toByte()
                        buf[3] = bits.toByte()
                        out.write(buf)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VectorStore", "save failed", e)
        }
    }

    private fun loadFromDisk() {
        try {
            // 1. 加载 chunks.json
            val chunkFile = File(storeDir, CHUNKS_FILENAME)
            if (chunkFile.exists()) {
                val raw = json.decodeFromString<List<ChunkSaveData>>(chunkFile.readText())
                chunks = raw.map {
                    ChunkRecord(it.index, it.text, it.sourceFile, it.sentenceStartIdx, it.sentenceEndIdx)
                }
            }

            // 2. 加载 sentences.json
            val sentFile = File(storeDir, SENTENCES_FILENAME)
            if (sentFile.exists()) {
                val raw = json.decodeFromString<List<SentenceSaveData>>(sentFile.readText())
                sentences = raw.map {
                    SentenceRecord(it.index, it.text, it.sourceFile)
                }
            }

            // 3. 加载 embeddings.bin
            val embFile = File(storeDir, EMBEDDINGS_FILENAME)
            if (embFile.exists() && embFile.length() >= 5) {
                val bytes = embFile.readBytes()
                val version = bytes[0].toInt() and 0xFF
                if (version != BIN_VERSION) {
                    android.util.Log.w("VectorStore", "Embedding binary version mismatch (file=$version, code=$BIN_VERSION), discarding old index")
                    discardIndex()
                    return
                }
                // 字节 1-4：dim
                dim = ((bytes[1].toInt() and 0xFF) shl 24) or
                        ((bytes[2].toInt() and 0xFF) shl 16) or
                        ((bytes[3].toInt() and 0xFF) shl 8) or
                        (bytes[4].toInt() and 0xFF)
                val floatCount = (bytes.size - 5) / 4
                embeddings = FloatArray(floatCount)
                for (i in embeddings.indices) {
                    val off = 5 + i * 4
                    val bits = ((bytes[off].toInt() and 0xFF) shl 24) or
                            ((bytes[off + 1].toInt() and 0xFF) shl 16) or
                            ((bytes[off + 2].toInt() and 0xFF) shl 8) or
                            (bytes[off + 3].toInt() and 0xFF)
                    embeddings[i] = java.lang.Float.intBitsToFloat(bits)
                }
                // 一致性校验：embeddings 数量必须 = chunks.size * dim
                val expectedFloats = chunks.size * dim
                if (floatCount != expectedFloats) {
                    android.util.Log.e("VectorStore", "Data inconsistency: embeddings $floatCount floats, expected $expectedFloats (chunks=${chunks.size}, dim=$dim), discarding")
                    discardIndex()
                }
            } else if (chunks.isNotEmpty()) {
                // 有 chunks.json 但无 embeddings.bin → 数据损坏
                android.util.Log.e("VectorStore", "chunks.json exists but embeddings.bin missing, discarding")
                discardIndex()
            }
        } catch (e: Exception) {
            android.util.Log.e("VectorStore", "load failed", e)
            discardIndex()
        }
    }

    private fun discardIndex() {
        chunks = emptyList()
        sentences = emptyList()
        embeddings = FloatArray(0)
        dim = 0
        File(storeDir, CHUNKS_FILENAME).delete()
        File(storeDir, SENTENCES_FILENAME).delete()
        File(storeDir, EMBEDDINGS_FILENAME).delete()
    }
}
