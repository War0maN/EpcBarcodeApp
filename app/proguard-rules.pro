# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ─────────────────────────────────────────────────────────────────
# Chainway C5 RFID SDK — keep everything. We access it via reflection
# (Class.forName, getMethod, Proxy.newProxyInstance), so R8 mustn't
# strip any classes or methods.
# ─────────────────────────────────────────────────────────────────
-keep class com.rscja.** { *; }
-keep interface com.rscja.** { *; }
-dontwarn com.rscja.**

# Native libraries packaged in the SDK
-keepclasseswithmembers class * {
    native <methods>;
}

# Reflection-friendly classes in our own code
-keepclasseswithmembernames class * {
    @androidx.compose.runtime.Stable <fields>;
}