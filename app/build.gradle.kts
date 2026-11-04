plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.maynkudu.yoinkd"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maynkudu.yoinkd"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
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
    implementation(libs.extractor.library)
    implementation(libs.extractor.ffmpeg)
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
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
