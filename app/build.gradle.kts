import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val camScannerNativeDir = providers.gradleProperty("camScannerNativeDir").orNull
val camScannerNative = camScannerNativeDir != null
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
        applicationId = if (camScannerNative) "com.docscan.rebuild.csnative" else "com.docscan.rebuild"
        buildConfigField("boolean", "CAMSCANNER_NATIVE", camScannerNative.toString())
        manifestPlaceholders["docscanAppLabel"] = if (camScannerNative) "@string/native_app_name" else "@string/app_name"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = if (camScannerNative) "0.1.4-native-preview" else "0.1.4-preview"
        testInstrumentationRunner = "com.docscan.rebuild.LocaleTestRunner"
        ndk { abiFilters += if (camScannerNative) listOf("arm64-v8a") else listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    if (camScannerNative) sourceSets.getByName("main").jniLibs.srcDir(requireNotNull(camScannerNativeDir))
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { animationsDisabled = true }
    lint { abortOnError = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

