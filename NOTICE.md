# Third-party components

This independent implementation uses the public AndroidX CameraX, Compose, Lifecycle, Room and ExifInterface APIs, Kotlin and kotlinx.coroutines, and the official OpenCV Android AAR 4.12.0 from Maven Central. AndroidX, Kotlin, coroutines and OpenCV are provided under Apache-2.0 with their applicable notices. Gradle's generated wrapper is also Apache-2.0. Transitive components retain their own licenses.

OpenCV source and license: https://github.com/opencv/opencv/tree/4.12.0
Official Android distribution: https://opencv.org/opencv4android-usage-models/
AndroidX source: https://android.googlesource.com/platform/frameworks/support/
Kotlin: https://github.com/JetBrains/kotlin
Coroutines: https://github.com/Kotlin/kotlinx.coroutines

The bundled assets/licenses directory includes OpenCV's license and notices for its embedded third-party code. The dependency inventory must be reviewed before a store release.

The standard build contains no CamScanner native libraries or models. The optional local native research profile bundles six externally supplied binaries from the user's reference package after hash verification: libopencv.so, libncnn.so, libMNN.so, libCVNN.so, libmagicenhancer.so and libimageprocessor.so. These binaries and the models embedded in them are not stored in Git and are not covered by the repository's public dependency notices. Their respective rights remain with their owners. The profile invokes measured Bitmap entry points for shadow removal, Magic Color, inversion and local SilverBullet processing; it is not a complete transfer of CamScanner's engine or cloud services. No activation keys or modified verification results are used. See docs/JNI_ENGINE_RESEARCH_HE.md and docs/FILTER_CATALOG_HE.md for provenance, scope and validation limits. No implementation, resources, database schema or build infrastructure were copied from the earlier DocScan project.


## Offline OCR (0.1.8)
Tesseract4Android 4.9.0 is an Apache-2.0 wrapper around Tesseract 5.5.1 and its native dependencies (including Leptonica and image codecs with their respective licenses): https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0 . Standard, non-OpenMP distribution is used, with all four supported ABIs. Review its bundled transitive notices before a store release.
The Hebrew and English tessdata_fast language data are Apache-2.0: https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd . These verified models are downloaded during the build into generated assets; model binaries are not stored in Git. Recognition runs locally without adding an Internet permission. This dependency is separate from the optional reference processing engine.
