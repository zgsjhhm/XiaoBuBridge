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
        // v3.7：复刻参照物 UI（顶栏 + 底部导航 + 卡片体系）并补齐
        //       Anthropic / legacy 端点、CORS 开关、自动重试、运行统计。
        // v3.8：工具调用层内嵌（XML 协议注入 + 解析校正 + 预算裁剪 + tool_calls 契约），
        //       由 ToolCallPrompt / ToolCallCodec / ToolCallBridge 承担。
        // v3.9：系统悬浮球（SYSTEM_ALERT_WINDOW 前台服务，承载网关开关 / API Key /
        //       心跳保活）、心跳保活看门狗（GatewayWatchdog）、运行状态新增工具调用指标、
        //       并发默认 3、System Prompt 支持文本导入。
        // v3.10：移除应用内悬浮球（FloatingBall / FloatingPanel / UiInjector 及其配置项），
        //       只保留系统悬浮球；补齐 FOREGROUND_SERVICE_SPECIAL_USE 权限并兜住
        //       startForeground 异常 —— 原先缺该权限导致服务启动即崩、悬浮球永不出现。
        // v3.11：悬浮球面板改固定半屏高 + 内部滚动 + 可拖动（拖标题栏，位置会记住）；
        //       新增悬浮球消失后的自愈路径（服务内 5s 巡检、配置变化真正提交布局、
        //       BOOT_COMPLETED / MY_PACKAGE_REPLACED 复活、自检闹钟、MainActivity 打开即同步）。
        // v3.12：修复「从最近任务划掉模块卡片后悬浮球消失且不再回来」。ColorOS 划掉
        //       等于对整包 force-stop（am_kill ... o-stop(40)），会连带停服务、撤通知
        //       并清空该包全部闹钟——v3.10 靠闹钟复活的路径因此天然失效。改为挂在
        //       「小布进程按需拉起模块进程」这条稳定路径上自愈：ConfigProvider 在
        //       process 启动与每次 query 时按配置补一次 OverlayBallService。
        // v3.13：API 调用改为后台静默——小布进程存活时直接后台注入并拿回调，
        //       不再每次请求前把 SpeechAssistMainActivity 拉到前台；仅当后台整轮
        //       零回调且开启「自动唤醒」时，才拉前台兜底重试一次（失败返回 503）。
        //       真机取证推翻了 v3.6 的假设「后台注入零回调」（锁屏/流式/多轮/tools 均可）。
        // ---- 版本通道（v3.12 起）----
        // 正式版（GitHub Release）：versionName "3.13"       / versionCode 31300
        // 本地测试版（-Pchannel=local）：versionName "3.13.99-local" / versionCode 31399
        //   patch 固定取 99：测试版号恒高于同一 patch 的正式版（可覆盖安装正式版做验证），
        //   又恒低于下一个次版本正式版（3.14 -> 31400），不会把后续正式升级挡在门外。
        // 默认走 release：漏传参数时宁可产出正式号，也不能让测试号混进发布产物——
        // 测试号 31399 一旦发出，会把正式版 31300 反过来挡在安装门外。
        val channel = (project.findProperty("channel") as String?) ?: "release"
        val officialCode = 31300
        val officialName = "3.13"
        if (channel == "local") {
            versionCode = officialCode + 99
            versionName = "$officialName.99-local"
        } else {
            versionCode = officialCode
            versionName = officialName
        }
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
