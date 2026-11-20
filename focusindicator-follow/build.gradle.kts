import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// 可选伴生库：与核心库同仓库同 tag 发布。
// JitPack 多模块坐标形如 com.github.learn-s-wood.FocusIndicator:focusindicator-follow:<tag>
val libGroup = "com.github.learn-s-wood"
val libArtifact = "focusindicator-follow"
val libVersion = "1.2.0"

group = libGroup
version = libVersion

android {
    namespace = "io.github.learnswood.focusindicator.follow"
    compileSdk = 33

    defaultConfig {
        // 与核心库一致，最低支持 Android 4.2；recyclerview 1.2.1 的 minSdk 为 14，不冲突
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
    // 公开签名里出现 FocusIndicatorLayout 与 RecyclerView，用 api 传递暴露
    api(project(":focusindicator"))
    api("androidx.recyclerview:recyclerview:1.2.1")
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
                    name.set("FocusIndicator Follow")
                    description.set("Optional companion for FocusIndicator: focus-stationary list scrolling for RecyclerView.")
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
                        connection.set("scm:git:git://github.com/learn-s-wood/FocusIndicator.git")
                        developerConnection.set("scm:git:ssh://git@github.com/learn-s-wood/FocusIndicator.git")
                    }
                }
            }
        }
    }
}