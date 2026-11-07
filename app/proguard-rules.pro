# ARCore is accessed through JNI; keep its classes intact.
-keep class com.google.ar.core.** { *; }
-dontwarn com.google.ar.**
