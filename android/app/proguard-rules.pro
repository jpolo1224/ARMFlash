# JNI entry points are looked up by name.
-keep class com.armsx.flashpoint.core.FpCore { native <methods>; }
# JavascriptInterface bridges.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes *Annotation*
