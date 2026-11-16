# R8 rules for the shrinking experiment (branch experiment/smaller-apk-r8).
#
# Of the app's 31,045 classes, 11,400 are Compose's full icon set (material-icons-extended); the app draws a few
# dozen of those icons. Only that set is trimmed: every other class (DocScan, Kotlin, Compose, CameraX, OpenCV,
# Tesseract, ZXing...) stays whole, unchanged and under its own name. The first attempt kept less and the test runner
# could not start: it needs Kotlin classes the app itself never calls.
-keep class !androidx.compose.material.icons.**, ** { *; }
-dontoptimize
-dontobfuscate
-dontwarn **
-keepattributes *
