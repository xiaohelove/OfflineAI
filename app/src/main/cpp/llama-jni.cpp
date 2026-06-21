/**
 * llama-jni.cpp
 *
 * Kotlin ↔ llama.cpp JNI 桥接层
 * 职责单一：将 Kotlin 调用翻译为 llama.cpp C API 调用
 *
 * 线程安全：调用方（Kotlin 层）负责串行化，JNI 层不做锁保护
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <cmath>
#include <thread>

#include "llama.h"
#include "common.h"

#define TAG "OfflineAI-JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── 全局状态 ────────────────────────────────────────
// 同一时刻只持有一个模型实例
static llama_model     *g_model   = nullptr;
static llama_context   *g_ctx     = nullptr;
static const llama_vocab *g_vocab = nullptr;
static llama_sampler   *g_smpl    = nullptr;

static int g_threads = 4;
static int g_threads_batch = 4;
static bool g_loaded = false;

// ─── Embedding 模型（独立于生成模型） ──────────────
// context 按需创建/销毁，避免 KV cache 溢出
static llama_model     *g_embd_model = nullptr;
static const llama_vocab *g_embd_vocab = nullptr;
static int              g_embd_dim   = 0;

// ─── 辅助函数 ────────────────────────────────────────

/**
 * 将 JNI jstring 转为 C++ std::string
 */
static std::string jstringToString(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

/**
 * 安全释放模型资源
 */
static void cleanupModel() {
    if (g_smpl) {
        llama_sampler_free(g_smpl);
        g_smpl = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
    g_loaded = false;
    LOGI("Model resources released");
}

static void cleanupEmbeddingModel() {
    if (g_embd_model) {
        llama_free_model(g_embd_model);
        g_embd_model = nullptr;
    }
    g_embd_vocab = nullptr;
    g_embd_dim = 0;
}

// ─── JNI 导出函数 ────────────────────────────────────

extern "C" {

/**
 * 加载 GGUF 模型文件
 *
 * @param modelPath 模型文件的绝对路径（已解压到私有目录）
 * @param nCtx      上下文窗口大小（token 数），默认 2560
 * @param nGpuLayers 卸载到 GPU 的层数（0=纯 CPU）
 * @return true 表示加载成功
 */
JNIEXPORT jboolean JNICALL
Java_com_offlineai_app_engine_LlamaBridge_nativeLoadModel(
    JNIEnv *env,
    jclass /* clazz */,
    jstring modelPath,
    jint nCtx,
    jint nGpuLayers,
    jint nThreads,
    jint nThreadsBatch
) {
    auto path = jstringToString(env, modelPath);

    if (g_loaded) {
        LOGI("Model already loaded, reusing");
        return JNI_TRUE;
    }

    // 线程数自动检测
    int hwConcurrency = (int)std::thread::hardware_concurrency();
    int threads = nThreads > 0 ? nThreads : std::max(2, hwConcurrency - 1);
    int threadsBatch = nThreadsBatch > 0 ? nThreadsBatch : std::max(2, hwConcurrency);
    g_threads = threads;
    g_threads_batch = threadsBatch;

    LOGI("Loading model: %s (ctx=%d, hw_concurrency=%d, threads=%d/%d)", path.c_str(), nCtx, hwConcurrency, threads, threadsBatch);

    // 初始化 llama 后端（CPU）
    llama_backend_init();

    // 模型参数
    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = static_cast<int>(nGpuLayers);

    // 加载模型
    g_model = llama_load_model_from_file(path.c_str(), modelParams);
    if (!g_model) {
        LOGE("Failed to load model from %s", path.c_str());
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);

    // 上下文参数
    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(nCtx);
    ctxParams.n_batch = 2048;
    ctxParams.n_threads = threads;
    ctxParams.n_threads_batch = threadsBatch;

    g_ctx = llama_new_context_with_model(g_model, ctxParams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_free_model(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        return JNI_FALSE;
    }

    // 采样器：贪婪（llama.cpp 版本 bug：temp/top-k/penalty 采样均触发 ggml_abort）
    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_smpl,
        llama_sampler_init_greedy());

    g_loaded = true;
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

/**
 * 加载专用 Embedding 模型（轻量，仅用于向量化）
 */
JNIEXPORT jboolean JNICALL
Java_com_offlineai_app_engine_LlamaBridge_nativeLoadEmbeddingModel(
    JNIEnv *env,
    jclass /* clazz */,
    jstring modelPath
) {
    auto path = jstringToString(env, modelPath);

    if (g_embd_model) {
        LOGI("Embedding model already loaded");
        return JNI_TRUE;
    }

    LOGI("Loading embedding model: %s", path.c_str());

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    modelParams.use_mmap = true;

    g_embd_model = llama_load_model_from_file(path.c_str(), modelParams);
    if (!g_embd_model) {
        LOGE("Failed to load embedding model");
        return JNI_FALSE;
    }

    g_embd_vocab = llama_model_get_vocab(g_embd_model);
    g_embd_dim = llama_model_n_embd(g_embd_model);

    // 不创建持久 context — 每次调用临时创建/销毁
    LOGI("Embedding model loaded, dim=%d (per-call context)", g_embd_dim);
    return JNI_TRUE;
}

/**
 * 简单 JSON 字符串提取：找到 key 对应的 value
 * 仅支持 "key":"value" 格式，不处理转义
 */
static std::string jsonExtractString(const std::string &json, const std::string &key, size_t startPos = 0) {
    std::string search = "\"" + key + "\"";
    size_t keyPos = json.find(search, startPos);
    if (keyPos == std::string::npos) return "";
    size_t colonPos = json.find(':', keyPos + search.length());
    if (colonPos == std::string::npos) return "";
    size_t valStart = json.find('"', colonPos + 1);
    if (valStart == std::string::npos) return "";
    size_t valEnd = json.find('"', valStart + 1);
    if (valEnd == std::string::npos) return "";
    return json.substr(valStart + 1, valEnd - valStart - 1);
}

/**
 * 使用 llama_chat_apply_template 执行对话推理
 *
 * @param messagesJson JSON 消息数组: [{"role":"system","content":"..."},{"role":"user","content":"..."}]
 * @param maxTokens    最大生成 token 数
 * @return 生成的文本
 */
JNIEXPORT jstring JNICALL
Java_com_offlineai_app_engine_LlamaBridge_nativeChatGenerate(
    JNIEnv *env,
    jclass /* clazz */,
    jstring messagesJson,
    jint maxTokens
) {
    if (!g_loaded || !g_ctx || !g_model || !g_vocab || !g_smpl) {
        LOGE("Model not loaded, cannot generate");
        return env->NewStringUTF("[错误] 模型未加载");
    }

    auto json = jstringToString(env, messagesJson);
    int nPredict = std::min(maxTokens, 1024);

    // ── 解析 JSON 消息数组 ──────────────────────────
    std::vector<llama_chat_message> messages;
    std::vector<std::string> roleBufs;
    std::vector<std::string> contentBufs;
    // 预分配防止扩容导致 c_str() 指针悬空
    roleBufs.reserve(4);
    contentBufs.reserve(4);

    size_t objStart = 0;
    while ((objStart = json.find("{\"role\"", objStart)) != std::string::npos) {
        std::string role = jsonExtractString(json, "role", objStart);
        std::string content = jsonExtractString(json, "content", objStart);
        if (!role.empty()) {
            roleBufs.push_back(role);
            contentBufs.push_back(content);
            llama_chat_message msg;
            msg.role = roleBufs.back().c_str();
            msg.content = contentBufs.back().c_str();
            messages.push_back(msg);
        }
        objStart = json.find('}', objStart);
        if (objStart != std::string::npos) objStart++;
    }

    LOGD("Parsed %zu chat messages", messages.size());

    // ── 清空 KV cache（复用 context，避免销毁重建的开销）────
    if (g_ctx) {
        llama_memory_t mem = llama_get_memory(g_ctx);
        llama_memory_clear(mem, true);  // 清空所有序列的 KV cache + 数据
    } else {
        LOGE("Context is null, cannot generate");
        return env->NewStringUTF("[错误] 上下文为空");
    }
    // 采样器（贪婪）无状态，无需重建

    // ── 用 chat template 格式化 prompt ───────────────
    // 先试探所需缓冲区大小（传 nullptr + 0 获取所需长度）
    int32_t formattedLen = llama_chat_apply_template(
        nullptr,           // 使用模型内置模板（Qwen 默认模板最兼容）
        messages.data(),
        messages.size(),
        true,              // add_ass: 追加 assistant 提示
        nullptr,
        0
    );

    if (formattedLen < 0) {
        LOGE("chat_apply_template probe failed: %d", formattedLen);
        return env->NewStringUTF("[错误] 模板格式化失败");
    }

    std::vector<char> formattedBuf(formattedLen + 1);
    int32_t actualLen = llama_chat_apply_template(
        nullptr,
        messages.data(),
        messages.size(),
        true,
        formattedBuf.data(),
        static_cast<int32_t>(formattedBuf.size())
    );

    if (actualLen < 0) {
        LOGE("chat_apply_template failed: %d", actualLen);
        return env->NewStringUTF("[错误] 模板格式化失败");
    }

    std::string formattedPrompt(formattedBuf.data(), actualLen);
    LOGD("Formatted prompt length: %d chars", actualLen);

    // ── Tokenize 格式化后的 prompt ───────────────────
    std::vector<llama_token> tokens(formattedPrompt.length() + 64);

    int tokenCount = llama_tokenize(
        g_vocab,
        formattedPrompt.c_str(),
        static_cast<int>(formattedPrompt.length()),
        tokens.data(),
        static_cast<int>(tokens.size()),
        true,   // add_special (BOS)
        true    // parse_special — chat template 已正确插入特殊 token
    );

    if (tokenCount < 0) {
        int needed = -tokenCount;
        tokens.resize(needed + 64);
        tokenCount = llama_tokenize(
            g_vocab,
            formattedPrompt.c_str(),
            static_cast<int>(formattedPrompt.length()),
            tokens.data(),
            static_cast<int>(tokens.size()),
            true,
            true
        );
    }

    if (tokenCount <= 0) {
        LOGE("Tokenization failed: %d", tokenCount);
        return env->NewStringUTF("");
    }

    tokens.resize(tokenCount);
    LOGD("Tokenized to %d tokens (threads=%d/%d)", tokenCount, g_threads, g_threads_batch);

    // ── Eval 输入（Prompt 阶段） ──────────────────────
    llama_batch batch = llama_batch_get_one(tokens.data(), tokenCount);
    int64_t tEvalStart = llama_time_us();
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Decode failed for input batch (%d tokens)", tokenCount);
        return env->NewStringUTF("[错误] 推理失败");
    }
    int64_t tEvalEnd = llama_time_us();
    float promptMs = (tEvalEnd - tEvalStart) / 1000.0f;
    LOGD("Prompt eval done: %d tokens in %.0f ms (%.1f tok/s)", tokenCount, promptMs, tokenCount / (promptMs / 1000.0f));

    // ── 逐 token 生成 ────────────────────────────────
    std::string output;
    output.reserve(nPredict * 4);

    llama_token newToken;
    const llama_token eosToken = llama_vocab_eos(g_vocab);
    const llama_token eotToken = llama_vocab_eot(g_vocab);

    int genTokens = 0;
    int64_t tGenStart = llama_time_us();
    for (int i = 0; i < nPredict; i++) {
        newToken = llama_sampler_sample(g_smpl, g_ctx, -1);

        if (newToken == eosToken || newToken == eotToken) {
            LOGD("Generation stopped at token %d (EOS/EOT)", i);
            break;
        }

        char buf[256];
        int len = llama_token_to_piece(g_vocab, newToken, buf, sizeof(buf), 0, false);
        if (len > 0) {
            output.append(buf, len);
        }

        batch = llama_batch_get_one(&newToken, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Decode failed at token %d", i);
            break;
        }
        genTokens++;
    }
    int64_t tGenEnd = llama_time_us();
    float genMs = (tGenEnd - tGenStart) / 1000.0f;

    LOGD("Generated %zu chars / %d tokens in %.0f ms (%.1f tok/s), total=%.0f ms",
         output.length(), genTokens, genMs,
         genTokens / (genMs / 1000.0f),
         promptMs + genMs);
    return env->NewStringUTF(output.c_str());
}

/**
 * 字符串 JSON 转义（仅处理必要字符）
 */
static std::string escapeJsonString(const std::string &s) {
    std::string out;
    out.reserve(s.length() + 16);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out += c;
        }
    }
    return out;
}

/**
 * 执行推理，生成文本（兼容旧接口，内部转调 nativeChatGenerate）
 *
 * @param prompt     输入提示词（废弃，请用 nativeChatGenerate）
 * @param maxTokens  最大生成 token 数
 * @return 生成的文本
 */
JNIEXPORT jstring JNICALL
Java_com_offlineai_app_engine_LlamaBridge_nativeGenerate(
    JNIEnv *env,
    jclass clazz,
    jstring prompt,
    jint maxTokens
) {
    auto text = jstringToString(env, prompt);
    std::string escaped = escapeJsonString(text);
    std::string json = "[{\"role\":\"user\",\"content\":\"" + escaped + "\"}]";
    jstring jsonStr = env->NewStringUTF(json.c_str());
    jstring result = Java_com_offlineai_app_engine_LlamaBridge_nativeChatGenerate(env, clazz, jsonStr, maxTokens);
    env->DeleteLocalRef(jsonStr);
    return result;
}

/**
 * 卸载模型，释放内存
 */
JNIEXPORT void JNICALL
Java_com_offlineai_app_engine_LlamaBridge_nativeUnloadModel(
    JNIEnv * /* env */,
    jclass /* clazz */
) {
    LOGI("Unloading all models");
    cleanupModel();
    cleanupEmbeddingModel();
    llama_backend_free();
}

/**
 * 检查模型是否已加载
 */
JNIEXPORT jboolean JNICALL
Java_com_offlineai_app_engine_LlamaBridge_nativeIsLoaded(
    JNIEnv * /* env */,
    jclass /* clazz */
) {
    return g_loaded ? JNI_TRUE : JNI_FALSE;
}

/**
 * 获取模型元信息（JSON 格式）
 */
JNIEXPORT jstring JNICALL
Java_com_offlineai_app_engine_LlamaBridge_nativeGetModelInfo(
    JNIEnv *env,
    jclass /* clazz */
) {
    if (!g_model) {
        return env->NewStringUTF("{}");
    }

    char buf[512];
    int nCtxTrain = llama_model_n_ctx_train(g_model);
    int nEmbd = llama_model_n_embd(g_model);
    int nLayer = llama_model_n_layer(g_model);
    int nHead = llama_model_n_head(g_model);
    int nVocab = llama_n_vocab(g_vocab ? g_vocab : llama_model_get_vocab(g_model));

    snprintf(buf, sizeof(buf),
        "{\"ctx_train\":%d,\"embd\":%d,\"layers\":%d,\"heads\":%d,\"vocab\":%d}",
        nCtxTrain, nEmbd, nLayer, nHead, nVocab);

    return env->NewStringUTF(buf);
}

/**
 * ─── 轻量向量相似度（替代 FAISS） ─────────────────
 * 对小型手册（< 500 段）使用暴力余弦相似度已足够
 *
 * @param queryVec   查询向量（float[]）
 * @param docVecs    文档向量（扁平 float[]，每 N 个一组）
 * @param dim        向量维度
 * @param topK       返回前 K 个结果
 * @return 相似度最高的片段索引 + 分数，JSON: [{"index":0,"score":0.95},...]
 */
JNIEXPORT jstring JNICALL
Java_com_offlineai_app_rag_VectorBridge_nativeSearchSimilar(
    JNIEnv *env,
    jclass /* clazz */,
    jfloatArray queryVec,
    jfloatArray docVecs,
    jint dim,
    jint topK
) {
    jsize qLen = env->GetArrayLength(queryVec);
    jsize dLen = env->GetArrayLength(docVecs);

    if (qLen != dim || dLen % dim != 0) {
        return env->NewStringUTF("[]");
    }

    jfloat *qData = env->GetFloatArrayElements(queryVec, nullptr);
    jfloat *dData = env->GetFloatArrayElements(docVecs, nullptr);

    int numDocs = dLen / dim;

    // 计算余弦相似度
    struct ScoredIndex {
        int index;
        float score;
    };
    std::vector<ScoredIndex> scores(numDocs);

    // 预计算查询向量的模
    float qNorm = 0.0f;
    for (int i = 0; i < dim; i++) qNorm += qData[i] * qData[i];
    qNorm = sqrtf(qNorm);
    if (qNorm < 1e-8f) qNorm = 1.0f;

    for (int d = 0; d < numDocs; d++) {
        float dot = 0.0f;
        float dNorm = 0.0f;
        const float *doc = dData + d * dim;

        for (int i = 0; i < dim; i++) {
            dot += qData[i] * doc[i];
            dNorm += doc[i] * doc[i];
        }

        dNorm = sqrtf(dNorm);
        if (dNorm < 1e-8f) dNorm = 1.0f;

        scores[d] = {d, dot / (qNorm * dNorm)};
    }

    // 部分排序取 Top-K
    int k = std::min(topK, numDocs);
    std::partial_sort(scores.begin(), scores.begin() + k, scores.end(),
        [](const ScoredIndex &a, const ScoredIndex &b) {
            return a.score > b.score;
        });

    // 构造 JSON 输出
    std::string json = "[";
    for (int i = 0; i < k; i++) {
        if (i > 0) json += ",";
        char buf[128];
        snprintf(buf, sizeof(buf), "{\"index\":%d,\"score\":%.4f}",
                 scores[i].index, scores[i].score);
        json += buf;
    }
    json += "]";

    env->ReleaseFloatArrayElements(queryVec, qData, JNI_ABORT);
    env->ReleaseFloatArrayElements(docVecs, dData, JNI_ABORT);

    return env->NewStringUTF(json.c_str());
}

/**
 * ─── Embedding 提取（使用专用 BGE 模型） ────────────
 */
JNIEXPORT jfloatArray JNICALL
Java_com_offlineai_app_engine_LlamaBridge_nativeGetEmbedding(
    JNIEnv *env,
    jclass /* clazz */,
    jstring text
) {
    if (!g_embd_model || !g_embd_vocab || g_embd_dim <= 0) {
        LOGE("Embedding model not loaded");
        return nullptr;
    }

    auto input = jstringToString(env, text);
    if (input.empty()) return nullptr;

    // ── 每次调用创建临时 context，用完立即销毁 ──
    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = 512;
    ctxParams.n_batch = 512;
    ctxParams.embeddings = true;
    ctxParams.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    int hwThreads = (int)std::thread::hardware_concurrency();
    int embdThreads = std::max(2, std::min(hwThreads, 8));
    ctxParams.n_threads = embdThreads;
    ctxParams.n_threads_batch = embdThreads;

    auto *tmpCtx = llama_new_context_with_model(g_embd_model, ctxParams);
    if (!tmpCtx) {
        LOGE("Failed to create temp embedding context");
        return nullptr;
    }

    // Passage 侧：不加前缀指令，直接使用原始文本（BGE v1.5 要求）
    // Query 侧的前缀在 nativeGetQueryEmbedding 中添加
    std::string prefixed = input;

    std::vector<llama_token> tokens(prefixed.length() + 64);
    int tokenCount = llama_tokenize(
        g_embd_vocab, prefixed.c_str(), static_cast<int>(prefixed.length()),
        tokens.data(), static_cast<int>(tokens.size()),
        true, true
    );
    if (tokenCount < 0) {
        int needed = -tokenCount;
        tokens.resize(needed + 64);
        tokenCount = llama_tokenize(
            g_embd_vocab, prefixed.c_str(), static_cast<int>(prefixed.length()),
            tokens.data(), static_cast<int>(tokens.size()),
            true, true
        );
    }
    if (tokenCount <= 0 || tokenCount > 512) {
        llama_free(tmpCtx);
        return nullptr;
    }
    tokens.resize(tokenCount);

    LOGD("Passage embedding forward: %d tokens (threads=%d)", tokenCount, embdThreads);
    // 前向传播
    int64_t tStart = llama_time_us();
    llama_batch batch = llama_batch_get_one(tokens.data(), tokenCount);
    if (llama_decode(tmpCtx, batch) != 0) {
        LOGE("Embedding decode failed");
        llama_free(tmpCtx);
        return nullptr;
    }
    int64_t tEnd = llama_time_us();
    LOGD("Passage embedding done in %.0f ms", (tEnd - tStart) / 1000.0f);

    int dim = g_embd_dim;

    // 优先用内置池化，不行则回退到手动 mean pooling
    const float *emb = llama_get_embeddings_seq(tmpCtx, 0);
    std::vector<float> pooled;
    if (emb) {
        // 内置池化成功
        jfloatArray result = env->NewFloatArray(dim);
        env->SetFloatArrayRegion(result, 0, dim, emb);
        llama_free(tmpCtx);
        return result;
    }

    // 回退：手动 mean pooling（pooling_type == NONE 时 _ith 可用）
    LOGD("Passage embedding: seq pooling unavailable, falling back to manual mean pooling");
    const float *allEmb = llama_get_embeddings(tmpCtx);
    if (!allEmb) {
        LOGE("Passage embedding: both seq and raw embeddings are null");
        llama_free(tmpCtx);
        return nullptr;
    }
    pooled.resize(dim, 0.0f);
    int validTokens = 0;
    for (int t = 0; t < tokenCount; t++) {
        const float *tokEmb = llama_get_embeddings_ith(tmpCtx, t);
        if (tokEmb) {
            for (int d = 0; d < dim; d++) pooled[d] += tokEmb[d];
            validTokens++;
        }
    }
    if (validTokens == 0) {
        LOGE("Passage embedding: no valid token embeddings");
        llama_free(tmpCtx);
        return nullptr;
    }
    float norm = 0.0f;
    for (int d = 0; d < dim; d++) {
        pooled[d] /= static_cast<float>(validTokens);
        norm += pooled[d] * pooled[d];
    }
    norm = sqrtf(norm);
    if (norm > 1e-8f) {
        for (int d = 0; d < dim; d++) pooled[d] /= norm;
    }
    jfloatArray result = env->NewFloatArray(dim);
    env->SetFloatArrayRegion(result, 0, dim, pooled.data());
    llama_free(tmpCtx);
    return result;
}

/**
 * ─── Embedding 提取（Query 侧，加前缀指令） ────────
 *
 * 用于用户查询向量化。BGE v1.5 训练范式：query 需要前缀指令，
 * passage 不需要。此函数与 nativeGetEmbedding 的区别仅在于加前缀。
 */
JNIEXPORT jfloatArray JNICALL
Java_com_offlineai_app_engine_LlamaBridge_nativeGetQueryEmbedding(
    JNIEnv *env,
    jclass /* clazz */,
    jstring text
) {
    if (!g_embd_model || !g_embd_vocab || g_embd_dim <= 0) {
        LOGE("Embedding model not loaded");
        return nullptr;
    }

    auto input = jstringToString(env, text);
    if (input.empty()) return nullptr;

    // ── 每次调用创建临时 context，用完立即销毁 ──
    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = 512;
    ctxParams.n_batch = 512;
    ctxParams.embeddings = true;
    ctxParams.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    int hwThreads = (int)std::thread::hardware_concurrency();
    int embdThreads = std::max(2, std::min(hwThreads, 8));
    ctxParams.n_threads = embdThreads;
    ctxParams.n_threads_batch = embdThreads;

    auto *tmpCtx = llama_new_context_with_model(g_embd_model, ctxParams);
    if (!tmpCtx) {
        LOGE("Failed to create temp embedding context");
        return nullptr;
    }

    // BGE 模型：query 侧需要前缀指令
    std::string prefixed = "为这个句子生成表示以用于检索相关文章：" + input;

    std::vector<llama_token> tokens(prefixed.length() + 64);
    int tokenCount = llama_tokenize(
        g_embd_vocab, prefixed.c_str(), static_cast<int>(prefixed.length()),
        tokens.data(), static_cast<int>(tokens.size()),
        true, true
    );
    if (tokenCount < 0) {
        int needed = -tokenCount;
        tokens.resize(needed + 64);
        tokenCount = llama_tokenize(
            g_embd_vocab, prefixed.c_str(), static_cast<int>(prefixed.length()),
            tokens.data(), static_cast<int>(tokens.size()),
            true, true
        );
    }
    if (tokenCount <= 0 || tokenCount > 512) {
        llama_free(tmpCtx);
        return nullptr;
    }
    tokens.resize(tokenCount);

    LOGD("Query embedding forward: %d tokens (threads=%d)", tokenCount, embdThreads);
    int64_t tStart = llama_time_us();
    llama_batch batch = llama_batch_get_one(tokens.data(), tokenCount);
    if (llama_decode(tmpCtx, batch) != 0) {
        LOGE("Query embedding decode failed");
        llama_free(tmpCtx);
        return nullptr;
    }
    int64_t tEnd = llama_time_us();
    LOGD("Query embedding done in %.0f ms", (tEnd - tStart) / 1000.0f);

    int dim = g_embd_dim;

    // 优先用内置池化，不行则回退到手动 mean pooling
    const float *emb = llama_get_embeddings_seq(tmpCtx, 0);
    std::vector<float> pooled;
    if (emb) {
        jfloatArray result = env->NewFloatArray(dim);
        env->SetFloatArrayRegion(result, 0, dim, emb);
        llama_free(tmpCtx);
        return result;
    }

    // 回退：手动 mean pooling
    LOGD("Query embedding: seq pooling unavailable, falling back to manual mean pooling");
    const float *allEmb = llama_get_embeddings(tmpCtx);
    if (!allEmb) {
        LOGE("Query embedding: both seq and raw embeddings are null");
        llama_free(tmpCtx);
        return nullptr;
    }
    pooled.resize(dim, 0.0f);
    int validTokens = 0;
    for (int t = 0; t < tokenCount; t++) {
        const float *tokEmb = llama_get_embeddings_ith(tmpCtx, t);
        if (tokEmb) {
            for (int d = 0; d < dim; d++) pooled[d] += tokEmb[d];
            validTokens++;
        }
    }
    if (validTokens == 0) {
        LOGE("Query embedding: no valid token embeddings");
        llama_free(tmpCtx);
        return nullptr;
    }
    float norm = 0.0f;
    for (int d = 0; d < dim; d++) {
        pooled[d] /= static_cast<float>(validTokens);
        norm += pooled[d] * pooled[d];
    }
    norm = sqrtf(norm);
    if (norm > 1e-8f) {
        for (int d = 0; d < dim; d++) pooled[d] /= norm;
    }
    jfloatArray result = env->NewFloatArray(dim);
    env->SetFloatArrayRegion(result, 0, dim, pooled.data());
    llama_free(tmpCtx);
    return result;
}

} // extern "C"
