import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.freddyai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freddyai.app"
        minSdk = 26
        targetSdk = 35
        // Версия задаётся в одном месте; versionCode выводится из неё: 2.2.0 → 20200.
        val ver = providers.gradleProperty("freddy.version").orNull ?: "2.2.0"
        val nums = ver.split('.').map { it.toInt() } + listOf(0, 0)
        versionCode = nums[0] * 10000 + nums[1] * 100 + nums[2]
        versionName = ver
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // Только для проверки на эмуляторе: -Pfreddy.x86 добавляет библиотеки x86_64.
            if (providers.gradleProperty("freddy.x86").isPresent) abiFilters += "x86_64"
        }
    }

    // Ключ подписи релизов хранится вне репозитория. Путь к файлу настроек: -Pfreddy.signing=... или переменная FREDDY_SIGNING.
    val signingFile = (providers.gradleProperty("freddy.signing").orNull ?: System.getenv("FREDDY_SIGNING"))?.let { file(it) }
    signingConfigs {
        if (signingFile != null && signingFile.exists()) create("freddy") {
            val p = Properties().apply { signingFile.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Подпись отладочным ключом, чтобы APK ставился сразу. Для публикации замени на свой ключ.
            signingConfig = signingConfigs.findByName("freddy") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    androidResources { noCompress += "tflite" }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
