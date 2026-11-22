# JNI entry points are looked up by name.
-keep class com.armsx.flashpoint.core.FpCore { native <methods>; }
# JavascriptInterface bridges.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes *Annotation*
# Native code calls into these by name (fp-ruffle java.rs, set_rust_field) and binds natives.
-keep class com.armsx.flashpoint.game.RuffleActivity { *; }
-keep class com.armsx.flashpoint.data.FpDriver { *; }
-keepclasseswithmembernames class * { native <methods>; }
# GameActivity (androidx.games) is looked up from native code.
-keep class com.google.androidgamesdk.** { *; }
