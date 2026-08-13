plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nekobot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nekobot.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "0.4.10"
        ndk {
            // 内置 Linux 沙盒当前使用 arm64 PRoot。
            abiFilters += "arm64-v8a"
        }
        vectorDrawables { useSupportLibrary = true }
    }

    // Release 固定使用旧 Windows 构建证书，保证可覆盖安装已发布版本。
    // 密钥文件仅保存在本地 .signing/ 目录，不纳入 Git。
    signingConfigs {
        create("legacyRelease") {
            storeFile = rootProject.file(".signing/legacy-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("legacyRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // tom_roush pdfbox 的 afm 资源在 Windows 上压缩时会导致路径异常，跳过压缩
        noCompress += listOf("afm", "ttf", "otf", "pfb")
    }
    packaging {
        jniLibs {
            // PRoot 需要以真实可执行文件存在于 nativeLibraryDir。
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.socket.io.client) {
        // 排除冲突的 org.json（Android 系统自带）
        exclude(group = "org.json", module = "json")
    }
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.ui.tooling)
}
