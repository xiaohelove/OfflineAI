package com.offlineai.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模型加载状态
 */
enum class ModelState {
    /** 未初始化 */
    IDLE,
    /** 正在解压模型文件 */
    EXTRACTING,
    /** 正在加载到内存 */
    LOADING,
    /** 就绪 */
    READY,
    /** 出错 */
    ERROR
}

/**
 * 模型管理器
 *
 * 职责：
 * 1. 首次启动时从 APK assets 解压 GGUF 到应用私有目录
 * 2. 管理模型生命周期（加载 / 卸载）
 * 3. 暴露加载状态供 UI 观察
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val MODEL_FILENAME = "Qwen3.5-2B-Q4_K_M.gguf"
        private const val EMBEDDING_FILENAME = "bge-small-zh-v1.5-q4_k_m.gguf"
        private const val MODEL_EXTRACTED_FLAG = "model_extracted_v1"

        /** 模型解压目标目录 */
        fun modelDir(context: Context): File =
            File(context.filesDir, "models")

        /** 模型文件路径 */
        fun modelFile(context: Context): File =
            File(modelDir(context), MODEL_FILENAME)

        /** Embedding 模型文件路径 */
        fun embeddingFile(context: Context): File =
            File(modelDir(context), EMBEDDING_FILENAME)
    }

    private val _state = MutableStateFlow(ModelState.IDLE)
    val state: StateFlow<ModelState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /** Embedding 模型是否就绪（用于导入前的预检） */
    @Volatile
    var embeddingReady: Boolean = false
        private set

    /**
     * 初始化模型：解压（如需）+ 加载到内存
     *
     * @return true 表示模型就绪
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 步骤 1：确保模型文件存在
            if (!ensureModelExtracted()) {
                _state.value = ModelState.ERROR
                _errorMessage.value = "模型文件解压失败"
                return@withContext false
            }

            // 步骤 2：如果已加载，直接返回
            if (LlamaBridge.nativeIsLoaded()) {
                _state.value = ModelState.READY
                loadEmbeddingModel()
                return@withContext true
            }

            // 步骤 3：加载模型到内存
            _state.value = ModelState.LOADING
            _progress.value = 0.8f

            val modelPath = modelFile(context).absolutePath
            val success = LlamaBridge.nativeLoadModel(modelPath, nCtx = 2048)

            if (success) {
                _state.value = ModelState.READY
                _progress.value = 0.85f
                loadEmbeddingModel()
                _progress.value = 1.0f
                true
            } else {
                _state.value = ModelState.ERROR
                _errorMessage.value = "模型加载失败，请重启应用"
                false
            }
        } catch (e: Exception) {
            _state.value = ModelState.ERROR
            _errorMessage.value = "初始化异常: ${e.localizedMessage}"
            false
        }
    }

    /**
     * 加载 Embedding 模型，并验证是否可用
     */
    private fun loadEmbeddingModel() {
        val embdFile = embeddingFile(context)
        embdFile.absolutePath.let { path ->
            if (embdFile.exists() && embdFile.length() > 0) {
                android.util.Log.i("ModelManager", "Loading embedding model from: $path (${embdFile.length()} bytes)")
                val loaded = LlamaBridge.nativeLoadEmbeddingModel(path)
                if (!loaded) {
                    android.util.Log.e("ModelManager", "nativeLoadEmbeddingModel returned false — BGE model file may be corrupted or incompatible")
                    _errorMessage.value = "Embedding 模型加载失败，导入功能不可用"
                    embeddingReady = false
                    return
                }
                // 验证：试 embedding 一个短字符串
                val testEmb = LlamaBridge.nativeGetEmbedding("测试")
                if (testEmb == null) {
                    android.util.Log.e("ModelManager", "Embedding model loaded but test embedding returned null")
                    _errorMessage.value = "Embedding 模型验证失败，导入功能不可用"
                    embeddingReady = false
                    return
                }
                android.util.Log.i("ModelManager", "Embedding model verified OK (dim=${testEmb.size})")
                embeddingReady = true
            } else {
                android.util.Log.w("ModelManager", "Embedding model file not found at: $path")
                _errorMessage.value = "未找到 Embedding 模型文件，导入功能不可用"
                embeddingReady = false
            }
        }
    }

    /**
     * 确保模型文件已从 assets 解压到私有目录
     */
    /**
     * 从 assets 解压单个文件到 modelDir
     */
    private suspend fun extractAsset(filename: String) {
        val dest = File(modelDir(context), filename)
        if (dest.exists() && dest.length() > 0) return

        modelDir(context).mkdirs()
        context.assets.open(filename).use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                val totalSize = context.assets.openFd(filename).length
                var copied: Long = 0
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    copied += bytesRead
                    // 0.1 ~ 0.7 (Qwen) + 0.7 ~ 0.75 (BGE)
                    val frac = 0.1f + 0.65f * (copied.toFloat() / (totalSize + 1).toFloat())
                    _progress.value = frac.coerceAtMost(0.75f)
                }
                output.flush()
            }
        }
    }

    private suspend fun ensureModelExtracted(): Boolean {
        val modelFile = modelFile(context)
        val flagFile = File(modelDir(context), MODEL_EXTRACTED_FLAG)

        // 已解压且标记存在，跳过
        if (modelFile.exists() && modelFile.length() > 0 && flagFile.exists()) {
            return true
        }

        _state.value = ModelState.EXTRACTING
        _progress.value = 0.1f

        return try {
            // 解压 Qwen 模型
            extractAsset(MODEL_FILENAME)
            // 解压 BGE embedding 模型
            extractAsset(EMBEDDING_FILENAME)

            // 写入标记文件
            flagFile.createNewFile()

            _progress.value = 0.8f
            true
        } catch (e: Exception) {
            // 清理不完整的文件
            modelFile.delete()
            flagFile.delete()
            _errorMessage.value = "解压失败: ${e.localizedMessage}"
            false
        }
    }

    /**
     * 卸载模型（应用退出或内存紧张时调用）
     */
    fun unload() {
        LlamaBridge.nativeUnloadModel()
        _state.value = ModelState.IDLE
    }

    /**
     * 检查模型文件是否已存在于私有目录（用于快速判断是否需要解压）
     */
    fun isModelExtracted(): Boolean {
        val flagFile = File(modelDir(context), MODEL_EXTRACTED_FLAG)
        return modelFile(context).exists() && flagFile.exists()
    }
}
