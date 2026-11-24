# Whisper JNI bridge: class and method names are looked up from native code.
-keep class com.whispercpp.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
