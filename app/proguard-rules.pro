# OfflineAI ProGuard 规则

# ─── Kotlin 序列化 ─────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.offlineai.app.**$$serializer { *; }
-keepclassmembers class com.offlineai.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.offlineai.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ─── Room ──────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class com.offlineai.app.data.** { *; }

# ─── JNI ───────────────────────────────────────────
-keep class com.offlineai.app.engine.LlamaBridge { *; }
-keep class com.offlineai.app.rag.VectorBridge { *; }

# ─── PDFBox ────────────────────────────────────────
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# ─── Compose ───────────────────────────────────────
-dontwarn androidx.compose.**
