# Regras ProGuard para o projeto Player
# Gerado automaticamente — ajuste conforme necessário

# ===== Media3 / ExoPlayer =====
# Manter todas as classes do Media3 que usam @UnstableApi (anotações de reflexão)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Manter classes de formato de mídia
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ===== OkHttp =====
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ===== Kotlin Coroutines =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ===== Jetpack Compose =====
# Compose é compatível com R8/ProGuard por padrão — nenhuma regra adicional necessária

# ===== Geral =====
# Manter anotações usadas em tempo de execução
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions

# Manter nomes de campos usados via serialização/reflexão
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
