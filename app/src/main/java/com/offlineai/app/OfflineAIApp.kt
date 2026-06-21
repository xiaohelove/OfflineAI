package com.offlineai.app

import android.app.Application
import com.offlineai.app.data.AppDatabase
import com.offlineai.app.data.ChatRepository
import com.offlineai.app.engine.InferenceService
import com.offlineai.app.engine.ModelManager
import com.offlineai.app.rag.Retriever

/**
 * Application 入口
 *
 * 持有全局单例：数据库、模型管理器、推理服务、检索器
 */
class OfflineAIApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var modelManager: ModelManager
        private set

    lateinit var inferenceService: InferenceService
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var retriever: Retriever
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 初始化数据库
        database = AppDatabase.getInstance(this)

        // 初始化引擎层
        modelManager = ModelManager(this)
        inferenceService = InferenceService()

        // 初始化数据层
        chatRepository = ChatRepository(database)

        // 初始化 RAG
        retriever = Retriever(this)
    }

    override fun onTerminate() {
        modelManager.unload()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: OfflineAIApp
            private set
    }
}
