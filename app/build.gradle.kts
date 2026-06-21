plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.offlineai.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.offlineai.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // 主流 ABI：arm64-v8a 覆盖绝大多数设备
            // x86_64 用于模拟器调试
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-O2",
                    "-DNDEBUG"
                )
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DLLAMA_OPENMP=OFF",
                    "-DBUILD_SHARED_LIBS=ON"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "none"
            }
        }
        debug {
            isMinifyEnabled = false
            ndk {
                debugSymbolLevel = "full"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 禁用 assets 中 .gguf 文件的压缩（已是量化格式，压缩无益）
    aaptOptions {
        noCompress += listOf("gguf")
    }
}

// 构建前从外部目录复制模型文件到 assets
tasks.register<Copy>("copyModelToAssets") {
    val modelSource = file("../../Qwen3.5-2B-Q4_K_M.gguf")
    val modelDest = file("src/main/assets/Qwen3.5-2B-Q4_K_M.gguf")

    // 只在文件不存在或源文件更新时复制
    onlyIf {
        !modelDest.exists() || modelSource.lastModified() > modelDest.lastModified()
    }

    from(modelSource)
    into(file("src/main/assets"))
    description = "Copy GGUF model file to assets before build"
}

tasks.register<Copy>("copyEmbeddingModelToAssets") {
    val modelSource = file("../../bge-small-zh-v1.5-q4_k_m.gguf")
    val modelDest = file("src/main/assets/bge-small-zh-v1.5-q4_k_m.gguf")
    onlyIf {
        modelSource.exists() && (!modelDest.exists() || modelSource.lastModified() > modelDest.lastModified())
    }
    from(modelSource)
    into(file("src/main/assets"))
    description = "Copy embedding model to assets"
}

tasks.named("preBuild") {
    dependsOn("copyModelToAssets", "copyEmbeddingModelToAssets")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.04.00")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Room 数据库
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // PDF 解析（文字可提取的电子 PDF）
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Splash Screen API
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Core KTX
    implementation("androidx.core:core-ktx:1.12.0")

    // 调试工具
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
