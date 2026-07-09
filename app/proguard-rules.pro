# Keep model classes
-keep class com.nekobot.app.data.model.** { *; }

# Retrofit / OkHttp / Gson
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature
-keepattributes *Annotation*
