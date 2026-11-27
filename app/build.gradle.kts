plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.chengyan.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chengyan.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "1.9.21"
        // 构建指纹：终端/自检里直接可见，杜绝"装的到底是不是新包"的版本错位问题。
        // 优先取构建命令注入的 -PBUILD_COMMIT=<远端HEAD>（发布包必须带，与 GitHub main 一一对应）；
        // 没注入时退回本地 git（开发期）。
        val _gitShort = (project.findProperty("BUILD_COMMIT") as String?)
            ?.takeIf { it.isNotBlank() }
            ?: try {
                val p = Runtime.getRuntime().exec(arrayOf("git", "-C", rootDir.absolutePath, "rev-parse", "--short", "HEAD"))
                p.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
            } catch (_: Exception) { "unknown" }
        val _ts = System.currentTimeMillis()
        buildConfigField("String", "BUILD_STAMP", "\"build-$_ts commit-$_gitShort\"")
        // 真 PTY（forkpty）：静态 busybox 直接挂在 PTY 上，不需要 proot。
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=none"
            }
        }
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            storeFile = file("chengyan-release.keystore")
            storePassword = "chengyan2026"
            keyAlias = "chengyan"
            keyPassword = "chengyan2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // 与 manifest 的 extractNativeLibs=true 配套：so 压缩进 APK、安装时解到 lib 目录。
        // 换来的是「任何设备/任何安装器都能 dlopen」——体积大 ~1MB，值得。
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Shizuku：真实集成（授权后用 shell 级 pm grant 授予被系统限制的权限）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

android {
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
