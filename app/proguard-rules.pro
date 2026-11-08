# JNI entry points are looked up by name from native code.
-keep class app.breakpiano.audio.NativeEngine { *; }
-keepclasseswithmembernames class * { native <methods>; }
