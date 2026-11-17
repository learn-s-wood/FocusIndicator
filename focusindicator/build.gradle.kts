import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// JitPack 按 com.github.<用户名>:<artifactId>:<tag> 解析依赖。
// 打 tag 时请用与 libVersion 完全一致的版本号（如 1.0.0），否则 JitPack 找不到对应产物。
val libGroup = "com.github.learn-s-wood"
val libArtifact = "focusindicator"
val libVersion = "1.0.0"

group = libGroup
version = libVersion

android {
    namespace = "io.github.learnswood.focusindicator"
    compileSdk = 33

    defaultConfig {
        // 库本体只依赖 Framework API，最低支持 Android 4.2
        minSdk = 17
        consumerProguardFiles("consumer-rules.pro")
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // 刻意不引入任何第三方 / AndroidX 依赖：
    // 使用方升级支撑库或使用 Compose 都不会与本库产生版本冲突
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = libGroup
                artifactId = libArtifact
                version = libVersion

                from(components["release"])

                pom {
                    name.set("FocusIndicator")
                    description.set("Android TV focus indicator: a flying frame that follows focus across views and containers.")
                    url.set("https://github.com/learn-s-wood/FocusIndicator")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("learn-s-wood")
                            name.set("learn-s-wood")
                        }
                    }
                    scm {
                        url.set("https://github.com/learn-s-wood/FocusIndicator")
                        connection.set("scm:git:https://github.com/learn-s-wood/FocusIndicator.git")
                        developerConnection.set("scm:git:ssh://git@github.com/learn-s-wood/FocusIndicator.git")
                    }
                }
            }
        }
    }
}
