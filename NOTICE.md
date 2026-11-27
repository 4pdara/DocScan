# Originality

All DocScan Scanner code was written from scratch in this project and belongs to the project owner. No CamScanner code, decompiled code, resources, filters, models, outputs or data were used. This covers:
- page boundary detection;
- the page network (pagenet-v1): its architecture, training code and weights;
- the filters and page clean-up;
- cropping, perspective correction and flattening of curved pages.

CamScanner research (screenshots, screen recordings and analysis) was used only to understand behaviour and to measure DocScan against it.

The third-party components listed below remain their authors' work under their own licenses. Text recognition uses Tesseract and the tessdata_fast models; only the code that integrates them is DocScan's.

The native research mode is not part of the application. It is a separate research tool, used only to compare DocScan with CamScanner; the application does not use it, and the build refuses to include it in the phone release. Its only files in the repository are two small JNI declaration files in com/intsig/nativelib, which connect the research tool to CamScanner binaries. Those binaries are not stored in Git.

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

The APK bundles the original OCR component license texts under assets/licenses (Tesseract4Android, Tesseract, tessdata_fast, Leptonica, libpng and the IJG libjpeg README). This software is based in part on the work of the Independent JPEG Group.

## Page network (1.3.6)
DocScan's page network (pagenet-v1) is DocScan's own: its architecture, training code and weights were made for this project. It was trained on synthetic scenes from DocScan's own generator and on "A New Image Dataset for Document Corner Localization" by Shima Dizaj (Mendeley Data, version 3, https://doi.org/10.17632/x3nm4cxr83.3), under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/); the dataset's authors ask that https://ieeexplore.ieee.org/abstract/document/9116896/ be cited. The photos were reduced to 256 x 256 and their marked corners drawn as page masks; 60% were used for training and 40% kept for measurement only. No dataset photo is in the repository or the app. No CamScanner model, output or data was used. The network file (535,968 bytes) is kept in the repository at app/src/main/assets/pagenet/pagenet-v1.onnx, with the owner's approval, and the build checks its SHA-256. It runs on the phone through OpenCV's DNN module. The attribution text is bundled as assets/licenses/PageNet-training-data-CC-BY-4.0.txt. The training code is in tools/pagenet.
