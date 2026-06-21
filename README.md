# OfflineAI - 完全离线 AI 诊断助手 / Fully Offline AI Diagnostic Assistant

基于 llama.cpp + Qwen3.5-2B 的 Android 离线 AI 故障诊断应用。

An Android offline AI diagnostic application built on llama.cpp + Qwen3.5-2B.

## 关于本项目 / About

本项目旨在探索**小语言模型在端侧设备上的落地可能性**。通过将 Qwen3.5-2B 量化模型与 RAG（检索增强生成）技术结合，在 Android 手机上实现完全离线的知识库问答，不依赖任何云端服务。

This project explores the feasibility of **deploying small language models on edge devices**. By combining the quantized Qwen3.5-2B model with RAG (Retrieval-Augmented Generation) technology, it enables fully offline knowledge base Q&A on Android phones with zero cloud dependency.

### 当前已知问题 / Known Issues

项目仍处于早期验证阶段，以下问题期待技术大佬或感兴趣的同学一起优化 / This project is still in early validation — the following issues need improvement, contributions from developers and enthusiasts are welcome:

1. **检索准确率偏低**：基于 BGE-small-zh 的嵌入检索仍有改善空间 / *Low retrieval accuracy: The BGE-small-zh based embedding retrieval needs improvement*
2. **推理速度较慢**：2B 模型在手机 CPU 上推理速度约 4-5 tok/s，单次回答需 1-2 分钟 / *Slow inference: ~4-5 tok/s on phone CPU, 1-2 minutes per response*
3. **输出格式不稳定**：模型有时不按要求的格式输出来源引用 / *Unstable output format: the model sometimes fails to follow the required citation format*
4. **Think 标签干扰**：模型内置的思维链输出（`<think>` 标签）会消耗 token 预算，需后处理清理 / *Think tag interference: the model's built-in chain-of-thought output (`<think>` tags) consumes token budget and requires post-processing*
5. **知识库管理简单**：当前仅支持单文件增删，缺少批量导入和全文搜索 / *Simple knowledge base management: currently only supports single file add/delete, lacks batch import and full-text search*

### 欢迎参与 / Contributing

如果你对端侧 AI、离线 RAG 或嵌入式大模型应用感兴趣，欢迎点个星星，并在项目基础上进行修改和迭代。你可以 / If you're interested in edge AI, offline RAG, or embedded LLM applications, feel free to star this repo and build upon it. You can:

1. 替换为其他量化模型（如 Qwen3.5-0.8B 以获得更快速度，或尝试 7B 模型以提升效果）/ Swap in other quantized models (e.g., Qwen3.5-0.8B for faster speed, or try a 7B model for better quality)
2. 优化 RAG 分块和检索策略 / Optimize RAG chunking and retrieval strategies
3. 改进 UI/UX 交互体验 / Improve UI/UX interaction
4. 添加 GPU 加速推理支持 / Add GPU-accelerated inference support
5. 修复已知问题或提交 Issue / Fix known issues or submit issues

## 应用场景 / Use Cases

OfflineAI 面向无网络和涉密环境，核心场景包括 / OfflineAI targets offline and classified environments. Core use cases include:

| 场景 / Scenario | 说明 / Description | 网络环境 / Network |
|------|------|---------|
| 工业设备维修 / Industrial Maintenance | 工厂车间、地下室等现场维修，查阅设备手册 / On-site equipment repair referencing manuals | 无信号 / No signal |
| 野外勘探作业 / Field Exploration | 地质勘探、石油钻井等野外设备故障排查 / Field equipment troubleshooting | 完全无网 / Fully offline |
| 户外应急救援 / Outdoor Emergency Rescue | 救援现场查询急救流程、装备操作规范 / On-site first aid and equipment operation lookup | 无覆盖 / No coverage |
| 涉密内网环境 / Classified Intranet | 医院、军工等内部设备维护，数据不能外传 / Internal equipment maintenance, data cannot leave the premises | 内网隔离 / Air-gapped |

产品核心价值 / Core Value:
1. **完全离线 / Fully Offline**：模型和数据均在本地，零网络依赖 / Models and data are local, zero network dependency
2. **数据安全 / Data Security**：手册内容不上传云端，敏感文档不外泄 / Manuals never uploaded to cloud, sensitive documents stay local
3. **精准溯源 / Traceable Citations**：诊断建议引用手册原文编号，可追溯验证 / Diagnostic suggestions cite original source numbers for verification
4. **即装即用 / Ready to Use**：首次启动自动解压模型，后续冷启动小于 5 秒 / Auto-extracts models on first launch, subsequent cold starts under 5 seconds

## 路线图 / Roadmap

| 版本 / Version | 阶段 / Phase | 内容 / Content |
|------|------|------|
| v1.0 | 已完成 / Done | 基础 RAG 问答、PDF/TXT 导入、知识库管理、对话历史 / Basic RAG Q&A, PDF/TXT import, knowledge base management, chat history |
| v1.1 | 优化中 / In Progress | Think 标签清理、来源引用格式、KV cache 复用优化 / Think tag cleanup, citation format, KV cache reuse |
| v1.2 | 规划中 / Planned | 流式输出、推理加速、多轮对话、批量导入 / Streaming output, inference acceleration, multi-turn对话, batch import |
| v1.3 | 远期规划 / Future | 混合检索、OCR 支持、GPU 加速推理 / Hybrid retrieval, OCR support, GPU-accelerated inference |
| v2.0 | 企业版 / Enterprise | 多用户管理、工单集成、审计日志 / Multi-user management, work order integration, audit logs |

## 技术栈 / Tech Stack

| 层级 / Layer | 技术 / Technology |
|------|------|
| 推理引擎 / Inference Engine | llama.cpp (C++ → Android NDK .so) |
| 模型 / Model | Qwen3.5-2B Q4_K_M GGUF (1.3GB) |
| Embedding | BGE-small-zh v1.5 Q4_K_M |
| UI | Kotlin + Jetpack Compose + Material 3 |
| 向量检索 / Vector Search | 句子级 dense 检索 (BGE embedding + 窗口重建) / Sentence-level dense retrieval (BGE embedding + window reconstruction) |
| 数据库 / Database | Room (对话历史 / Chat history) |
| PDF 解析 / PDF Parsing | PDFBox Android |
| 构建 / Build | Gradle 8.5 + AGP 8.2.2 + CMake 3.22.1 |

## 模型文件 / Model Files

构建和运行需要两个模型文件 / Two model files are required to build and run:

| 模型 / Model | 文件名 / Filename | 大小 / Size | 用途 / Purpose |
|------|--------|------|------|
| 生成模型 / Generation Model | `Qwen3.5-2B-Q4_K_M.gguf` | ~1.3GB | 对话推理 / Chat inference |
| Embedding 模型 / Embedding Model | `bge-small-zh-v1.5-q4_k_m.gguf` | ~15MB | 文本向量化 / Text vectorization |

### 下载地址 / Download Links

1. Qwen3.5-2B: https://huggingface.co/Qwen/Qwen3.5-2B-GGUF
2. BGE-small-zh: https://huggingface.co/maidalun1020/bce-embedding-base_v1/tree/main

### 模型放置路径 / Model Path

默认情况下，模型放在项目根目录的上一级（与 `OfflineAI/` 目录平级）/ By default, models should be placed in the parent directory of the project root (sibling to `OfflineAI/`):

```
你的工作目录 / Your workspace/
├── Qwen3.5-2B-Q4_K_M.gguf          ← 生成模型 / Generation model
├── bge-small-zh-v1.5-q4_k_m.gguf    ← Embedding 模型 / Embedding model
└── OfflineAI/                        ← 本项目 / This project
```

构建时会自动从上级目录复制到 `app/src/main/assets/` / The build script automatically copies them from the parent directory to `app/src/main/assets/`.

### 自定义模型路径 / Custom Model Paths

如需修改模型路径，改动两个文件 / To change model paths, modify two files:

**1. app/build.gradle.kts**（约第 112 行和第 125 行 / around lines 112 and 125）

```kotlin
// 修改模型源路径 / Modify model source paths
val modelSource = file("你的路径/Qwen3.5-2B-Q4_K_M.gguf")
val embdSource = file("你的路径/bge-small-zh-v1.5-q4_k_m.gguf")
```

**2. app/src/main/java/com/offlineai/app/engine/ModelManager.kt**（约第 37-38 行 / around lines 37-38）

```kotlin
private const val MODEL_FILENAME = "Qwen3.5-2B-Q4_K_M.gguf"
private const val EMBEDDING_FILENAME = "bge-small-zh-v1.5-q4_k_m.gguf"
```

如果只改路径不改文件名，仅需修改 `build.gradle.kts` 中的源路径即可 / If you only need to change the path (not the filenames), just modify the source paths in `build.gradle.kts`.

## 构建前提 / Prerequisites

1. **Android Studio** Hedgehog (2023.1) 或更高版本 / or later
2. **NDK** 26.x+（通过 SDK Manager 安装 / install via SDK Manager）
3. **CMake** 3.22.1+（通常随 NDK 安装 / usually bundled with NDK）
4. 至少 **16GB 内存** / At least **16GB RAM**（C++ 编译需要大量内存 / C++ compilation is memory-intensive）
5. 模型文件下载并放到指定路径 / Download model files and place them in the specified path

## 构建步骤 / Build Steps

```bash
# 1. 克隆项目 / Clone the project
git clone https://github.com/你的用户名/OfflineAI.git
cd OfflineAI

# 2. 下载模型文件放到项目上级目录 / Download model files to parent directory
# 目录结构 / Directory structure：
#   父目录 / parent directory/
#   ├── Qwen3.5-2B-Q4_K_M.gguf
#   ├── bge-small-zh-v1.5-q4_k_m.gguf
#   └── OfflineAI/          ← 当前目录 / current directory

# 3. 用 Android Studio 打开项目，或命令行构建 / Open with Android Studio or build via CLI：
./gradlew assembleDebug

# APK 输出在 / APK output at：app/build/outputs/apk/debug/app-debug.apk
```

> 如果模型放在其他位置，参考上方"自定义模型路径"修改配置 / If models are in a different location, see "Custom Model Paths" above.

## 首次启动流程 / First Launch Flow

1. 用户点击应用图标 / User taps the app icon
2. 显示启动页（品牌 logo + 进度条）/ Splash screen with logo and progress bar
3. 后台自动解压 GGUF 模型（约 20-30 秒，仅首次）/ Auto-extracts GGUF models in background (~20-30s, first launch only)
4. 加载模型到内存（约 3-5 秒）/ Loads models into memory (~3-5s)
5. 自动进入主界面（诊断 Tab）/ Auto-enters main interface (Diagnostics tab)

**后续启动 / Subsequent launches**：模型文件已解压，跳过解压步骤，3-5 秒即可进入 / Models already extracted, skip decompression, ready in 3-5 seconds.

## 性能目标（骁龙 8 Gen 3 / 小米14）/ Performance Targets (Snapdragon 8 Gen 3 / Xiaomi 14)

| 指标 / Metric | 目标 / Target |
|------|------|
| 首次启动（解压+加载）/ First launch (extract + load) | < 40 秒 / s |
| 冷启动（已解压）/ Cold start (already extracted) | < 5 秒 / s |
| 单次推理 / Single inference | < 120 秒 / s |
| APK 体积 / APK Size | ~1.4GB |

## 项目结构 / Project Structure

```
OfflineAI/
├── build.gradle.kts              # 根构建配置 / Root build config
├── settings.gradle.kts
├── gradle.properties
├── PRODUCT_DOCUMENT.md           # 产品需求文档 / Product requirements doc
├── app/
│   ├── build.gradle.kts          # 应用模块（NDK/CMake/Compose）/ App module
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt    # 原生编译配置 / Native build config
│       │   └── llama-jni.cpp     # JNI 桥接 / JNI bridge
│       ├── assets/               # 模型文件（构建时复制）/ Models (copied at build time)
│       ├── res/                  # 资源文件 / Resources
│       └── java/com/offlineai/app/
│           ├── OfflineAIApp.kt           # Application
│           ├── MainActivity.kt           # 唯一 Activity / Single Activity
│           ├── engine/
│           │   ├── LlamaBridge.kt        # JNI 声明 / JNI declarations
│           │   ├── ModelManager.kt       # 模型生命周期 / Model lifecycle
│           │   └── InferenceService.kt   # 推理 API / Inference API
│           ├── rag/
│           │   ├── DocumentProcessor.kt  # PDF 解析 + 分块 / PDF parsing + chunking
│           │   ├── VectorStore.kt        # 向量索引 + 检索 / Vector index + retrieval
│           │   └── Retriever.kt          # RAG 编排 / RAG orchestration
│           ├── data/
│           │   ├── AppDatabase.kt        # Room DB
│           │   └── ChatRepository.kt     # 对话仓库 / Chat repository
│           └── ui/
│               ├── theme/
│               │   ├── Color.kt
│               │   └── Theme.kt
│               └── screens/
│                   ├── SplashScreen.kt   # 启动页 / Splash screen
│                   ├── HomeScreen.kt     # 主界面（Tab）/ Main screen (Tabs)
│                   ├── ChatScreen.kt     # 对话界面 / Chat screen
│                   └── ManualScreen.kt   # 手册管理 / Manual management
```

## RAG 检索策略 / RAG Retrieval Strategy

采用**重叠小分块 + 句子窗口检索**策略 / Uses **overlapping small chunks + sentence window retrieval**:

1. **分块 / Chunking**：按中文标点断句，每 3 句一组构建分块，相邻块间重叠 1 句 / Split by Chinese punctuation, group every 3 sentences into a chunk with 1-sentence overlap
2. **索引 / Indexing**：每个分块独立 BGE embedding（512 维向量）/ Each chunk gets an independent BGE embedding (512-dim vector)
3. **检索 / Retrieval**：查询向量化后余弦相似度排序，取 Top-5 分块 / Cosine similarity ranking after query vectorization, take Top-5 chunks
4. **窗口重建 / Window Reconstruction**：每个命中分块前后各取 4 句拼接为上下文窗口，去重后作为参考资料 / Expand each hit chunk with 4 sentences of context on each side, deduplicate, and use as reference

详细设计见 [PRODUCT_DOCUMENT.md](PRODUCT_DOCUMENT.md) 第 8 章 / See Chapter 8 of [PRODUCT_DOCUMENT.md](PRODUCT_DOCUMENT.md) for detailed design.

## 许可 / License

MIT
