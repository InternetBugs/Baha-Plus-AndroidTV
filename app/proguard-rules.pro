# The official page uses scoped WebMessageListener. JavascriptInterface exists
# only in the isolated local compiler, whose network requests are all blocked.
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
