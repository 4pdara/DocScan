# Third-party components

This independent implementation uses the public AndroidX CameraX, Compose, Lifecycle, Room and ExifInterface APIs, Kotlin and kotlinx.coroutines, and the official OpenCV Android AAR 4.12.0 from Maven Central. AndroidX, Kotlin, coroutines and OpenCV are provided under Apache-2.0 with their applicable notices. Gradle's generated wrapper is also Apache-2.0. Transitive components retain their own licenses.

OpenCV source and license: https://github.com/opencv/opencv/tree/4.12.0
Official Android distribution: https://opencv.org/opencv4android-usage-models/
AndroidX source: https://android.googlesource.com/platform/frameworks/support/
Kotlin: https://github.com/JetBrains/kotlin
Coroutines: https://github.com/Kotlin/kotlinx.coroutines

The bundled assets/licenses directory includes OpenCV's license and notices for its embedded third-party code. The dependency inventory must be reviewed before a store release.

The standard build contains no CamScanner native libraries or models. The optional local native research profile bundles six externally supplied binaries from the user's reference package after hash verification: libopencv.so, libncnn.so, libMNN.so, libCVNN.so, libmagicenhancer.so and libimageprocessor.so. These binaries and the models embedded in them are not stored in Git and are not covered by the repository's public dependency notices. Their respective rights remain with their owners. The profile invokes measured Bitmap entry points for shadow removal, Magic Color, inversion and local SilverBullet processing; it is not a complete transfer of CamScanner's engine or cloud services. No activation keys or modified verification results are used. See docs/JNI_ENGINE_RESEARCH_HE.md and docs/FILTER_CATALOG_HE.md for provenance, scope and validation limits. No implementation, resources, database schema or build infrastructure were copied from the earlier DocScan project.
