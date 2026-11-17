plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.learnswood.focusindicator.demo"
    compileSdk = 33

    defaultConfig {
        applicationId = "io.github.learnswood.focusindicator.demo"
        // 与功能库保持一致：Demo 不引入任何要求更高 minSdk 的组件
        minSdk = 17
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":focusindicator"))
    // 仅用于演示列表/网格场景；1.2.1 的 minSdk 为 14，不与 minSdk 17 冲突
    implementation("androidx.recyclerview:recyclerview:1.2.1")
}
