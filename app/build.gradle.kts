import java.security.MessageDigest
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

// Public phone release: original code and licensed components only, under its own package and signing identity.
val phoneRelease = providers.gradleProperty("phoneRelease").orNull?.toBooleanStrict() ?: false
// The APK published for phones carries only ARM libraries (32 and 64 bit: every Android 8+ phone). The Intel copies
// are needed only by the x86 emulators that test each release, which install a separate universal build.
val armOnly = providers.gradleProperty("armOnly").orNull?.toBooleanStrict() ?: false

android {
    namespace = "com.docscan.rebuild"
    compileSdk = 36
    defaultConfig {
        applicationId = if (phoneRelease) "com.docscan.rebuild.scanner" else "com.docscan.rebuild"
        manifestPlaceholders["docscanAppLabel"] = if (phoneRelease) "@string/release_app_name" else "@string/app_name"
        minSdk = 26
        targetSdk = 36
        versionCode = 41
        versionName = if (phoneRelease) "1.3.9" else "1.3.9-preview"
        testInstrumentationRunner = "com.docscan.rebuild.LocaleTestRunner"
        ndk {
            abiFilters += if (armOnly) listOf("arm64-v8a", "armeabi-v7a") else listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compressed libraries extracted at install time, the packaging the owner's phone accepted in 0.1.7.1.
        if (phoneRelease) jniLibs.useLegacyPackaging = true
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

// DocScan's own page network (tools/pagenet): trained in this project and kept in the assets; the build checks that
// the file is the one measured (docs/RELEASE_1.3.6_HE.md).
val checkPageNet by tasks.registering {
    val model = file("src/main/assets/pagenet/pagenet-v1.onnx")
    val expected = "68c625917d7c95af6aa8e0306570436a690644b70d6d0eb225d8916faa591951"
    inputs.file(model); inputs.property("digest", expected)
    doLast {
        val actual = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == expected) { "Page network digest mismatch: $actual" }
    }
}
tasks.named("preBuild").configure { dependsOn(checkPageNet) }
