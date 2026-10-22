# youtubedl-android parses yt-dlp output with Jackson into its own model classes.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keepclassmembers class com.yausername.youtubedl_android.mapper.** { *; }
-dontwarn com.fasterxml.jackson.**

# Compile-time-only annotations that some libraries (incl. androidx.test) reference.
-dontwarn com.google.errorprone.annotations.**
