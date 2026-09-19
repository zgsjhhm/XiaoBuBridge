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
        // v3.14：零回调有了更准的定性与更低的代价。真机数据显示零回调是
        //       **概率性**的（引擎吞掉首次注入），与前后台、与空闲时长都无
        //       单调关系——v3.13 的「进程刚起/引擎未预热」归因不成立。
        //       故首轮改用 15s 探测窗口快判「引擎有没有接单」：引擎一旦接单
        //       （本轮会话被登记）即延长到完整 request_timeout，绝不截断慢回答；
        //       窗口内毫无动静才降级/重试。
        //       代价按实测形态分列：探测被吞、唤醒一次即成功 16.9s（冷启动同形）；
        //       唤醒后的完整窗口重试也被吞 80.19s；关闭自动唤醒 62.58s 后放弃。
        //       与 v3.13 比要按同一形态：v3.13 发布版单次被吞 62.75–65.16s、连吞两次 126.42s；
        //       耗时只取响应已送达（RESP 正常收尾）的轮次——快照里另有轮次在被吞后
        //       重试成功但客户端已断开（Broken pipe），不以往返耗时论。
        //       （v3.13→v3.14 之间的中间构建测到 21.16s；更早的 v3.12 段已送达的
        //        只有单次被吞 60.17 / 68.77 / 80.61s 三轮，属「请求前无条件唤醒」
        //        旧形态，不作同形态对照。）
        //       （发生率只给定性：日志不打印版本号（只能按日志形态断代）且随工况波动；冻结快照上可直接
        //        grep 复现的两个计数是 450 个请求中 28 个、453 次注入中 47 次被吞，
        //        量级百分之几到一成；早期「44 轮 7 轮≈16%」样本过小且无留存证据，
        //        已不再引用。）
        //       另外把降级日志里的 AutoWake result=true 拆成 alreadyForeground /
        //       wakeLaunched / blocked / noTargetContext——旧写法会把「本来就在前台」
        //       记成「唤起了」（且把「拿不到 Context」也记成 true），已实际误导过一次归因。
        // v3.15：文生图取图。小布的图片不在正文里——正文只有「已生成图片」几个字，
        //       URL 在 AIChatViewBean.payload 的 MyAI.PictureCard.picUrl（真机 + dex 双证）。
        //       新增 /v1/images/generations（默认 b64_json），取图逻辑独立成纯逻辑类
        //       ImageResultCodec 并有 PC 侧离线回归（tools/run_image_codec_test.sh），
        //       版本差异与脏数据在那里收敛，Hook 侧只做「取字段→投递」。
        //       真机踩到一个只在 b64_json 下暴露的缺陷：图片直链先于对象可读被下发，
        //       载荷到达后立刻取会 404、约 4–9s 后才 200（url 格式不下载，故被掩盖）。
        //       下载侧因此把 404/5xx/超时当「尚未就绪」做限时重试（20s 预算、1s 间隔）。
        //       真机验收补齐 tools/verify_image_device.py（默认 b64_json 那条路，
        //       按魔数验图、验契约、验 400 边界）。两个上游限制据实记下、不假装能绕：
        //       ① 小布对文生图有每日配额，用尽后引擎回纯文本「已达到今日图片创作
        //          服务上限，请明日再试。」，端点按其原样报 502；密集压测会提前打满。
        //       ② 背靠背注入（n 串行约 20ms 间隔）第 2 轮起被引擎丢掉、由纯文本顶替，
        //          故 n>1 实际产出常低于请求值，n=2 稳定只拿 1 张且静默按 200 返回。
        //          独立请求间隔 ≥6s 才稳定成功。
        // ---- 版本通道（v3.12 起）----
        // 正式版（GitHub Release）：versionName "3.15"       / versionCode 31500
        // 本地测试版（-Pchannel=local）：versionName "3.15.99-local" / versionCode 31599
        //   patch 固定取 99：测试版号恒高于同一 patch 的正式版（可覆盖安装正式版做验证），
        //   又恒低于下一个次版本正式版（3.16 -> 31600），不会把后续正式升级挡在门外。
        val channel = (project.findProperty("channel") as String?) ?: "release"
        // 默认走 release：漏传参数时宁可产出正式号，也不能让测试号混进发布产物——
        // 测试号 31599 一旦发出，会把正式版 31500 反过来挡在安装门外。
        val officialCode = 31500
        val officialName = "3.15"
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
