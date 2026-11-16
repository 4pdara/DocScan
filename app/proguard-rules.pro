# R8 rules for the shrinking experiment (branch experiment/smaller-apk-r8).

# DocScan's own code is small; the device tests call its internal functions, so it is kept whole.
-keep class com.docscan.rebuild.** { *; }

# OpenCV's Java bindings: the native library creates and reads these classes and fields by name.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Tesseract4Android and Leptonica: JNI.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn com.googlecode.**

# Compose UI is what the device tests drive (semantics, test hooks); kept whole so a test never meets a class the
# app itself does not need. The large icon set and the rest of the libraries are trimmed.
-keep class androidx.compose.ui.** { *; }

# Names stay as they are: only unused code is removed. The test APK then links to the app by the same names.
-dontobfuscate

# Line numbers in crash reports.
-keepattributes SourceFile,LineNumberTable
