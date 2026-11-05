# Third-party components

This independent implementation uses the public AndroidX CameraX, Compose, Lifecycle, Room and ExifInterface APIs, Kotlin and kotlinx.coroutines, and the official OpenCV Android AAR 4.12.0 from Maven Central. AndroidX, Kotlin, coroutines and OpenCV are provided under Apache-2.0 with their applicable notices. Gradle's generated wrapper is also Apache-2.0. Transitive components retain their own licenses.

OpenCV source and license: https://github.com/opencv/opencv/tree/4.12.0
Official Android distribution: https://opencv.org/opencv4android-usage-models/
AndroidX source: https://android.googlesource.com/platform/frameworks/support/
Kotlin: https://github.com/JetBrains/kotlin
Coroutines: https://github.com/Kotlin/kotlinx.coroutines

The bundled assets/licenses directory includes OpenCV's license and notices for its embedded third-party code. The dependency inventory must be reviewed before a store release.

No code, assets, models or native libraries extracted from CamScanner are included. Its supplied package was inspected as a reference for capabilities and architecture. This app is neither CamScanner nor a redistribution of its engine. No implementation, resources, database schema or build infrastructure were copied from the earlier DocScan project.
