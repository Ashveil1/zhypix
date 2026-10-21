# The beta buildType runs R8 (minify + shrink). The app talks to Moshi,
# Retrofit, Room and kotlin-reflect, all of which resolve classes/members
# reflectively; without keeps, shrinking removes them and the beta APK
# crashes on the first API or DB call.

# App code: keep everything (correctness over obfuscation for now).
-keep class com.example.** { *; }
-keep class com.zhypix.** { *; }

# Moshi codegen adapters (looked up reflectively by Class.forName).
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **JsonAdapter { *; }
-keepnames @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# Retrofit service interfaces (invoked through dynamic proxies).
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation,allowshrinking class <1>
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Kotlin metadata and signatures for kotlin-reflect (Moshi KotlinJsonAdapterFactory).
-keepattributes Signature, *Annotation*, Exceptions, InnerClasses, EnclosingMethod
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { *; }
-keepclassmembers enum * { *; }

# Compose and terminal-view library classes ship their own consumer rules;
# keep Termux terminal session client callbacks (called from JNI-adjacent code).
-keep class com.termux.** { *; }

-dontwarn okio.**
-dontwarn okhttp3.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
