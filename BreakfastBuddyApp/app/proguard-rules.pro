# Keep WebView JS interface methods if minify is enabled
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
