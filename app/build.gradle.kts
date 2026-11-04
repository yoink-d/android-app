plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val appVersionName = providers.gradleProperty("yoinkdVersionName").orElse("0.2.0-alpha.1")
val appVersionCode = providers.gradleProperty("yoinkdVersionCode").orElse("2")
val runtimeReleaseTag = providers.gradleProperty("runtimeReleaseTag").orElse(appVersionName.map { "v$it" })
val nativeMediaRuntime by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val runtimeOutput = layout.buildDirectory.dir("media-runtime")
val prepareMediaRuntime by tasks.registering(Exec::class) {
    inputs.files(nativeMediaRuntime)
    inputs.file(rootProject.file("scripts/prepare-runtime.py"))
    inputs.file(rootProject.file("scripts/media-runtime.json"))
    inputs.property("releaseTag", runtimeReleaseTag)
    outputs.dir(runtimeOutput)
    doFirst {
        commandLine("python3", rootProject.file("scripts/prepare-runtime.py"),
            "--library-aar", nativeMediaRuntime.files.single { it.name.startsWith("library-") },
            "--ffmpeg-aar", nativeMediaRuntime.files.single { it.name.startsWith("ffmpeg-") },
            "--specification", rootProject.file("scripts/media-runtime.json"),
            "--release-tag", runtimeReleaseTag.get(), "--output", runtimeOutput.get().asFile)
    }
}

android {
    namespace = "com.maynkudu.yoinkd"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maynkudu.yoinkd"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode.get().toInt()
        versionName = appVersionName.get()
        buildConfigField("String", "UPDATE_REPOSITORY", "\"yoink-d/android-app\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits.abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        isUniversalApk = false
    }
    val signingValues = listOf("YOINKD_KEYSTORE", "YOINKD_STORE_PASSWORD", "YOINKD_KEY_ALIAS", "YOINKD_KEY_PASSWORD")
        .associateWith { providers.environmentVariable(it).orNull }
    if (signingValues.values.any { it != null }) {
        require(signingValues.values.all { !it.isNullOrBlank() }) { "Set all four YOINKD release signing variables." }
        signingConfigs.create("release") {
            storeFile = file(signingValues.getValue("YOINKD_KEYSTORE")!!)
            storePassword = signingValues.getValue("YOINKD_STORE_PASSWORD")
            keyAlias = signingValues.getValue("YOINKD_KEY_ALIAS")
            keyPassword = signingValues.getValue("YOINKD_KEY_PASSWORD")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets.getByName("main") {
        jniLibs.srcDir(runtimeOutput.map { it.dir("generated/jniLibs") })
        assets.srcDir(runtimeOutput.map { it.dir("generated/assets") })
    }
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
}

tasks.named("preBuild") { dependsOn(prepareMediaRuntime) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    add(nativeMediaRuntime.name, libs.extractor.library)
    add(nativeMediaRuntime.name, libs.extractor.ffmpeg)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.core)
    implementation(libs.documentfile)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.core)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
