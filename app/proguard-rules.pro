# jump3r: the javax.sound based wrapper and CLI entry point are never used on Android.
-dontwarn javax.sound.**
-dontwarn java.beans.**
-dontwarn java.awt.**

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
