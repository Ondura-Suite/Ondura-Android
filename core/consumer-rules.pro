# UniFFI bindings are called through JNA reflection.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class de.mirranet.ondura.core.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
