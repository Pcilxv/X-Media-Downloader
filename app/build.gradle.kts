plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.xinyu.xmedia"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "cn.xinyu.xmedia"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    signingConfigs {
        create("localRelease") {
            val keyDir = rootProject.file("签名密钥")
            val passwordFile = keyDir.resolve("密码.txt")
            if (passwordFile.exists()) {
                storeFile = keyDir.resolve("xmedia-release.jks")
                storePassword = passwordFile.readText().trim()
                keyAlias = "xmedia"
                keyPassword = storePassword
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("localRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("io.coil-kt.coil3:coil-gif:3.6.3")
    implementation("io.coil-kt.coil3:coil-video:3.6.3")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-inspector:1.11.1")
    implementation("androidx.media3:media3-inspector-frame:1.11.1")
    implementation("com.github.bumptech.glide:gifencoder-integration:5.0.9") {
        exclude(group = "com.github.bumptech.glide", module = "glide")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
