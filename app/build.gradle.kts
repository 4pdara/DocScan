import java.security.MessageDigest
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val camScannerNativeDir = providers.gradleProperty("camScannerNativeDir").orNull
val camScannerNative = camScannerNativeDir != null
val universalPreview = providers.gradleProperty("universalPreview").orNull?.toBooleanStrict() ?: false
require(!universalPreview || camScannerNative) { "Universal preview requires the optional ARM64 research input" }
val standaloneNativePreview = providers.gradleProperty("standaloneNativePreview").orNull?.toBooleanStrict() ?: false
require(!standaloneNativePreview || camScannerNative) { "Standalone Native preview requires camScannerNativeDir" }
// Public phone release: original code and licensed components only, under its own package and signing identity.
val phoneRelease = providers.gradleProperty("phoneRelease").orNull?.toBooleanStrict() ?: false
require(!phoneRelease || !camScannerNative) { "The public phone release never bundles the optional research engine" }
if (camScannerNative) {
    val expected = mapOf(
        "libopencv.so" to "9df94cd8f94f0f6818ce700104b6337259c7c95eb95cd98552d27105d5d8320a",
        "libncnn.so" to "b1841e5a3ea32d7120bbd0cd53e9609c2181441414a961d55fd0c422c11e1dc0",
        "libMNN.so" to "e03bbcbc0f26b7f2753a2284580b0feab1ac92a629f138fd214c8a5a12f049e9",
        "libCVNN.so" to "81634fe554a8622eb238dca6a200c18c3e95730fe24474302f809ade12277ddd",
        "libmagicenhancer.so" to "4fecd50628352cc4d88fba220ee1059e8104857166014f8b5d6e8a13e9c92599",
        "libimageprocessor.so" to "0ccf18b6323ff23a076dc860dcd76e84097d031427ab056b52a4c7d8ffa28353"
    )
    expected.forEach { (name, digest) ->
        val library = file("$camScannerNativeDir/arm64-v8a/$name")
        require(library.isFile) { "Missing research engine library: $name" }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(library.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == digest) { "Unexpected research engine library: $name" }
    }
}

android {
    namespace = "com.docscan.rebuild"
    compileSdk = 36
    defaultConfig {
        applicationId = when {
            phoneRelease -> "com.docscan.rebuild.scanner"
            standaloneNativePreview -> "com.docscan.rebuild.csnative.preview"
            camScannerNative -> "com.docscan.rebuild.csnative"
            else -> "com.docscan.rebuild"
        }
        buildConfigField("boolean", "CAMSCANNER_NATIVE", camScannerNative.toString())
        buildConfigField("boolean", "UNIVERSAL_PREVIEW", universalPreview.toString())
        manifestPlaceholders["docscanAppLabel"] = when {
            phoneRelease -> "@string/release_app_name"
            standaloneNativePreview -> "@string/native_preview_app_name"
            camScannerNative -> "@string/native_app_name"
            else -> "@string/app_name"
        }
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = when {
            phoneRelease -> "0.4.0"
            universalPreview -> "0.4.0-universal-preview"
            camScannerNative -> "0.4.0-native-preview"
            else -> "0.4.0-preview"
        }
        testInstrumentationRunner = "com.docscan.rebuild.LocaleTestRunner"
        ndk { abiFilters += if (camScannerNative && !universalPreview) listOf("arm64-v8a") else listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
    }
    // Phone releases from 0.2.1 on are signed with one persistent key so each version installs over the previous one.
    // CI supplies it; local builds keep the standard debug key.
    System.getenv("SCANNER_KEYSTORE")?.takeIf { phoneRelease && it.isNotBlank() }?.let { keystore ->
        signingConfigs.getByName("debug") {
            storeFile = file(keystore); storeType = "pkcs12"
            storePassword = requireNotNull(System.getenv("SCANNER_KEYSTORE_PASSWORD")); keyAlias = "docscan-scanner"
            keyPassword = storePassword
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    if (camScannerNative) sourceSets.getByName("main").jniLibs.srcDir(requireNotNull(camScannerNativeDir))
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compressed libraries extracted at install time, the packaging the owner's phone accepted in 0.1.7.1.
        if (universalPreview || phoneRelease) jniLibs.useLegacyPackaging = true
    }
    testOptions { animationsDisabled = true }
    lint { abortOnError = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.runtime:runtime:1.9.5")
    implementation("androidx.compose.runtime:runtime-saveable:1.9.5")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    kapt("androidx.room:room-compiler:2.8.3")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("org.opencv:opencv:4.12.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // QR codes and barcodes (Apache-2.0, pure Java).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}



// Public Apache-2.0 OCR language data are verified and generated outside Git.
val ocrAssets = layout.buildDirectory.dir("generated/ocr-assets")
val prepareOcrAssets by tasks.registering {
    val revision = "87416418657359cb625c412a48b6e1d6d41c29bd"
    val models = mapOf("heb" to "11f9e43ab227f786352a50f75c94c2e9906f1baba86d93276da19da7ce0904db",
        "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")
    inputs.property("revision", revision); inputs.property("models", models)
    outputs.dir(ocrAssets)
    doLast {
        val target = ocrAssets.get().dir("tessdata").asFile.apply { mkdirs() }
        models.forEach { (language, expected) ->
            val file = target.resolve("$language.traineddata")
            fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!file.isFile || digest(file.readBytes()) != expected) {
                val connection = URI("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$revision/$language.traineddata").toURL().openConnection()
                connection.connectTimeout = 30000; connection.readTimeout = 60000
                val bytes = connection.getInputStream().use { it.readBytes() }
                check(digest(bytes) == expected) { "OCR model digest mismatch: $language" }
                file.writeBytes(bytes)
            }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(ocrAssets)
tasks.named("preBuild").configure { dependsOn(prepareOcrAssets) }
