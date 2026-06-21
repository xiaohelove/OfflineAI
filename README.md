# OfflineAI - 完全离线 AI 诊断助手

基于 llama.cpp + Qwen3.5-2B 的 Android 离线 AI 故障诊断应用。

## 技术栈

| 层级 | 技术 |
|------|------|
| 推理引擎 | llama.cpp (C++ → Android NDK .so) |
| 模型 | Qwen3.5-2B Q4_K_M GGUF (1.3GB) |
| Embedding | BGE-small-zh v1.5 Q4_K_M |
| UI | Kotlin + Jetpack Compose + Material 3 |
| 向量检索 | 句子级 dense 检索 (BGE embedding + 窗口重建) |
| 数据库 | Room (对话历史) |
| PDF 解析 | PDFBox Android |
| 构建 | Gradle 8.5 + AGP 8.2.2 + CMake 3.22.1 |

## 模型文件

构建和运行需要两个模型文件：

| 模型 | 文件名 | 大小 | 用途 |
|------|--------|------|------|
| 生成模型 | `Qwen3.5-2B-Q4_K_M.gguf` | ~1.3GB | 对话推理 |
| Embedding 模型 | `bge-small-zh-v1.5-q4_k_m.gguf` | ~15MB | 文本向量化 |

### 下载地址

- Qwen3.5-2B: https://huggingface.co/Qwen/Qwen3.5-2B-GGUF
- BGE-small-zh: https://huggingface.co/maidalun1020/bce-embedding-base_v1/tree/main

### 模型放置路径

默认情况下，模型放在项目根目录的上一级（与 `OfflineAI/` 目录平级）：

```
你的工作目录/
├── Qwen3.5-2B-Q4_K_M.gguf          ← 生成模型
├── bge-small-zh-v1.5-q4_k_m.gguf    ← Embedding 模型
└── OfflineAI/                        ← 本项目
```

构建时会自动从上级目录复制到 `app/src/main/assets/`。

### 自定义模型路径

如需修改模型路径，改动两个文件：

**1. app/build.gradle.kts**（约第 112 行和第 125 行）

```kotlin
// 修改模型源路径
val modelSource = file("你的路径/Qwen3.5-2B-Q4_K_M.gguf")
val embdSource = file("你的路径/bge-small-zh-v1.5-q4_k_m.gguf")
```

**2. app/src/main/java/com/offlineai/app/engine/ModelManager.kt**（约第 37-38 行）

```kotlin
private const val MODEL_FILENAME = "Qwen3.5-2B-Q4_K_M.gguf"
private const val EMBEDDING_FILENAME = "bge-small-zh-v1.5-q4_k_m.gguf"
```

如果只改路径不改文件名，仅需修改 `build.gradle.kts` 中的源路径即可。

## 构建前提

1. **Android Studio** Hedgehog (2023.1) 或更高版本
2. **NDK** 26.x+（通过 SDK Manager 安装）
3. **CMake** 3.22.1+（通常随 NDK 安装）
4. 至少 **16GB 内存**（C++ 编译需要大量内存）
5. 模型文件下载并放到指定路径

## 构建步骤

```bash
# 1. 克隆项目
git clone https://github.com/你的用户名/OfflineAI.git
cd OfflineAI

# 2. 下载模型文件放到项目上级目录
# 目录结构：
#   父目录/
#   ├── Qwen3.5-2B-Q4_K_M.gguf
#   ├── bge-small-zh-v1.5-q4_k_m.gguf
#   └── OfflineAI/          ← 当前目录

# 3. 用 Android Studio 打开项目，或命令行构建：
./gradlew assembleDebug

# APK 输出在：app/build/outputs/apk/debug/app-debug.apk
```

> 如果模型放在其他位置，参考上方"自定义模型路径"修改配置。

## 首次启动流程

1. 用户点击应用图标
2. 显示启动页（品牌 logo + 进度条）
3. 后台自动解压 GGUF 模型（约 20-30 秒，仅首次）
4. 加载模型到内存（约 3-5 秒）
5. 自动进入主界面（诊断 Tab）

**后续启动**：模型文件已解压，跳过解压步骤，3-5 秒即可进入。

## 性能目标（骁龙 8 Gen 3 / 小米14）

| 指标 | 目标 |
|------|------|
| 首次启动（解压+加载） | < 40 秒 |
| 冷启动（已解压） | < 5 秒 |
| 单次推理 | < 120 秒 |
| APK 体积 | ~1.4GB |

## 项目结构

```
OfflineAI/
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts
├── gradle.properties
├── PRODUCT_DOCUMENT.md           # 产品需求文档
├── app/
│   ├── build.gradle.kts          # 应用模块（NDK/CMake/Compose）
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt    # 原生编译配置
│       │   └── llama-jni.cpp     # JNI 桥接
│       ├── assets/               # 模型文件（构建时复制）
│       ├── res/                  # 资源文件
│       └── java/com/offlineai/app/
│           ├── OfflineAIApp.kt           # Application
│           ├── MainActivity.kt           # 唯一 Activity
│           ├── engine/
│           │   ├── LlamaBridge.kt        # JNI 声明
│           │   ├── ModelManager.kt       # 模型生命周期
│           │   └── InferenceService.kt   # 推理 API
│           ├── rag/
│           │   ├── DocumentProcessor.kt  # PDF 解析 + 分块
│           │   ├── VectorStore.kt        # 向量索引 + 检索
│           │   └── Retriever.kt          # RAG 编排
│           ├── data/
│           │   ├── AppDatabase.kt        # Room DB
│           │   └── ChatRepository.kt     # 对话仓库
│           └── ui/
│               ├── theme/
│               │   ├── Color.kt
│               │   └── Theme.kt
│               └── screens/
│                   ├── SplashScreen.kt   # 启动页
│                   ├── HomeScreen.kt     # 主界面（Tab）
│                   ├── ChatScreen.kt     # 对话界面
│                   └── ManualScreen.kt   # 手册管理
```

## RAG 检索策略

采用**重叠小分块 + 句子窗口检索**策略：

1. **分块**：按中文标点断句，每 3 句一组构建分块，相邻块间重叠 1 句
2. **索引**：每个分块独立 BGE embedding（512 维向量）
3. **检索**：查询向量化后余弦相似度排序，取 Top-5 分块
4. **窗口重建**：每个命中分块前后各取 4 句拼接为上下文窗口，去重后作为参考资料

详细设计见 [PRODUCT_DOCUMENT.md](PRODUCT_DOCUMENT.md) 第 8 章。

## 许可

MIT
