package com.offlineai.app.engine

/**
 * Kotlin ↔ llama.cpp JNI 桥接
 *
 * 所有方法均为静态，对应 llama-jni.cpp 中的 native 函数。
 * 调用方负责单线程串行化，此类不做同步保护。
 */
object LlamaBridge {

    init {
        System.loadLibrary("offlineai_jni")
    }

    /** 获取文本的 embedding 向量（Passage 侧，不加前缀指令，用于文档索引） */
    external fun nativeGetEmbedding(text: String): FloatArray?

    /** 获取文本的 embedding 向量（Query 侧，加 BGE 前缀指令，用于查询检索） */
    external fun nativeGetQueryEmbedding(text: String): FloatArray?

    /** 加载专用 Embedding 模型 */
    external fun nativeLoadEmbeddingModel(modelPath: String): Boolean

    /** 通过文件描述符加载模型（直接从 APK assets mmap，无需解压） */
    external fun nativeLoadModelFromFd(fd: Int, offset: Long, length: Long, nCtx: Int = 2048): Boolean

    /** 加载 GGUF 模型文件，线程数 0=自动检测 */
    external fun nativeLoadModel(modelPath: String, nCtx: Int = 2560, nGpuLayers: Int = 0, nThreads: Int = 0, nThreadsBatch: Int = 0): Boolean

    /** 使用 Chat Template 执行对话推理 */
    external fun nativeChatGenerate(messagesJson: String, maxTokens: Int = 256): String

    /** [兼容旧接口] 执行推理，返回生成文本 */
    @Deprecated("请使用 nativeChatGenerate")
    external fun nativeGenerate(prompt: String, maxTokens: Int = 256): String

    /** 卸载模型 */
    external fun nativeUnloadModel()

    /** 模型是否已加载 */
    external fun nativeIsLoaded(): Boolean

    /** 获取模型元信息 JSON */
    external fun nativeGetModelInfo(): String
}
