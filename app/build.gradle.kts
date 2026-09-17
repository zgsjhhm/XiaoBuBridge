plugins {
    id("com.android.application")
}

android {
    namespace = "com.wanxiang.xiaobubridge"
    compileSdk = 34
    // 显式指定 build-tools 35.0.0：其 AIDL 编译器为 aarch64 原生二进制，
    // 可兼容 x86_64 与 aarch64 宿主（34.0.0 的 aidl 仅 x86_64，
    // 在 aarch64/PRoot 下无 qemu 兼容层会直接失败）。
    // AGP 允许 buildTools 版本高于 compileSdk。
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.wanxiang.xiaobubridge"
        minSdk = 24
        targetSdk = 34
        // 首个正式版本：v3.0
        // versionCode 采用 major*10000 + minor*100 + patch 编码，保证严格递增，
        // 且与 versionName 一一对应（3.0 -> 30000、3.0.1 -> 30001、3.1 -> 30100）。
        versionCode = 30000
        versionName = "3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // kotlinOptions removed: no Kotlin plugin applied

    buildFeatures {
        // libxposed-service / 自定义 IXposedService AIDL
        aidl = true
        // MainActivity 布局绑定（纯 Java，无需 Kotlin）
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    // NanoHTTPD removed; using built-in com.sun.net.httpserver.HttpServer

    // ---- v2.0 Material3 View UI (纯 Java，不引入 Kotlin/Compose 插件) ----
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // ---- v2.0 IPC 方案 ----
    // UI ↔ Hook 的配置通道由自定义 AIDL (IConfigService) + 跨应用只读
    // ContentProvider (ConfigProvider) 承担，运行期零额外依赖。
    // 注：io.github.libxposed:service / :api 在 Maven Central 实测 404
    //（该坐标不存在），故不引入，避免依赖解析直接失败。
}
