# UniFFI's generated bindings use JNA reflection to bind to libnexus_core.so;
# keep everything under the generated package and JNA itself untouched.
-keep class com.nexusterm.android.uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }
