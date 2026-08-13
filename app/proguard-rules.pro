# Keep model classes
-keep class com.nekobot.app.data.model.** { *; }
# 本地备份、增量清单和 OAuth/模型快照使用 Gson 反射读取字段；字段名属于持久化格式。
-keepclassmembers class com.nekobot.app.data.local.** { <fields>; }

# Retrofit / OkHttp / Gson
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
# PDFBox 的 JPEG 2000 解码器是可选依赖；未打包时 JPX 图片会按库自身的回退路径处理。
-dontwarn com.gemalto.jp2.JP2Decoder
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
