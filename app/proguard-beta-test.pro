# Beta-only rules so the minified app remains instrumentable by the e2e test
# APK. AGP puts the app variant's library classpath on the test APK's R8
# library inputs, meaning library classes are never dexed into the test APK -
# they must survive in the app APK under their original names. R8 otherwise
# strips or renames any library class the app code itself does not use
# (kotlin.LazyKt was the first casualty), crashing instrumentation at startup.
#
# This effectively disables obfuscation and shrinking of library code in the
# beta build; app code in com.zhypix.fresh.** is covered by proguard-rules.pro.
# The release build does not use this file.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.** { *; }
-keep class com.squareup.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
