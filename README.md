# OfflineAI - 完全离线 AI 诊断助手

基于 llama.cpp + Qwen3.5-2B 的 Android 离线 AI 故障诊断应用。

## 关于本项目

本项目旨在探索**小语言模型在端侧设备上的落地可能性**。通过将 Qwen3.5-2B 量化模型与 RAG（检索增强生成）技术结合，在 Android 手机上实现完全离线的知识库问答，不依赖任何云端服务。

### 当前已知问题

目前项目仍处于早期验证阶段，存在以下待优化项：

- **检索准确率偏低**：基于 BGE-small-zh 的嵌入检索在部分场景下 Top-5 准确率约 75%，有改善空间
- **推理速度较慢**：2B 模型在手机 CPU 上推理速度约 4-5 tok/s，单次回答需 1-2 分钟
- **输出格式不稳定**：模型有时不按要求的"来源：[编号]《文档名》"格式输出
- **Think 标签干扰**：模型内置的思维链输出（`<think>` 标签）会消耗 token 预算，需后处理清理
- **知识库管理简单**：当前仅支持单文件增删，缺少批量导入和全文搜索

### 欢迎参与

如果你对端侧 AI、离线 RAG 或嵌入式大模型应用感兴趣，欢迎在这个项目的基础上修改和迭代。你可以：

- 替换为其他量化模型（如 Qwen3.5-0.8B 以获得更快速度，或尝试 7B 模型以提升效果）
- 优化 RAG 分块和检索策略
- 改进 UI/UX 交互体验
- 添加 GPU 加速推理支持
- 修复已知问题或提交 Issue

## 应用场景

OfflineAI 面向无网络和涉密环境，核心场景包括：

| 场景 | 说明 | 网络环境 |
|------|------|---------|
| 工业设备维修 | 工厂车间、地下室等现场维修，查阅设备手册 | 无信号 |
| 野外勘探作业 | 地质勘探、石油钻井等野外设备故障排查 | 完全无网 |
| 户外应急救援 | 救援现场查询急救流程、装备操作规范 | 无覆盖 |
| 涉密内网环境 | 医院、军工等内部设备维护，数据不能外传 | 内网隔离 |

产品核心价值：
- **完全离线**：模型和数据均在本地，零网络依赖
- **数据安全**：手册内容不上传云端，敏感文档不外泄
- **精准溯源**：诊断建议引用手册原文编号，可追溯验证
- **即装即用**：首次启动自动解压模型，后续冷启动小于 5 秒

## 路线图

| 版本 | 阶段 | 内容 |
|------|------|------|
| v1.0 | 已完成 | 基础 RAG 问答、PDF/TXT 导入、知识库管理、对话历史 |
| v1.1 | 优化中 | Think 标签清理、来源引用格式、KV cache 复用优化 |
| v1.2 | 规划中 | 流式输出、推理加速、多轮对话、批量导入 |
| v1.3 | 远期规划 | 混合检索、OCR 支持、GPU 加速推理 |
| v2.0 | 企业版 | 多用户管理、工单集成、审计日志 |

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
