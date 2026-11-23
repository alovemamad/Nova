# ProGuard rules for Nova VPN
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.novavpn.domain.model.**$$serializer { *; }
-keepclassmembers class app.novavpn.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class app.novavpn.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# libbox (sing-box) & hev-socks5-tunnel JNI classes
-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**
-keep class hev.htproxy.** { *; }
-dontwarn hev.htproxy.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
