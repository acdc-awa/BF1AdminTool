# Keep Room entities
-keep class com.bf1.admin.tool.data.local.entity.** { *; }
# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep JavascriptInterface methods from obfuscation
-keepclassmembers class com.bf1.admin.tool.ui.login.EaLoginBridge {
    @android.webkit.JavascriptInterface <methods>;
}
