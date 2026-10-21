-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.squareup.moshi.**
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.security.**
# Tink references errorprone annotations that are compile-time only
-dontwarn com.google.errorprone.annotations.**

# Classes the instrumented e2e tests reference directly - keep their names so
# the test APK can instrument the minified beta build (same package, debug key).
-keep class com.zhypix.fresh.MainActivity { *; }
-keep class com.zhypix.fresh.service.** { *; }
-keep class com.zhypix.fresh.data.** { *; }
-keep class com.zhypix.fresh.agent.** { *; }
-keep class com.zhypix.fresh.api.** { *; }

# The beta androidTest APK is minified too (testBuildType = beta). Keep the
# test class names so `am instrument -e class ...` resolves them, and silence
# errorprone's compile-time-only annotation references on the test classpath.
-keep class com.zhypix.fresh.LaunchTest { *; }
-keep class com.zhypix.fresh.LiveModelE2eTest { *; }
-keep class com.zhypix.fresh.ChatFlowE2eTest { *; }
-keep class com.zhypix.fresh.GestureE2eTest { *; }
-dontwarn javax.lang.model.**
