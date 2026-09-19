# XiaoBu Bridge

把小布助手的 AI 对话能力桥接成**本地 OpenAI 兼容 HTTP 接口**的 LSPosed 模块。

任何支持自定义 Base URL 的客户端（Cherry Studio、NextChat、各类 OpenAI SDK、脚本）都能直接调用，
无需逆向、无需抓包、无需云端中转。数据全程在本机内部流转。

- **版本：** 3.15（versionCode 31500）
- **包名：** `com.wanxiang.xiaobubridge`
- **作用域：** 小布助手（`com.heytap.speechassist`）
- **平台：** Android 7.0+（minSdk 24），targetSdk 34
- **能力：** OpenAI 兼容接口（含 function calling） + Anthropic Messages + legacy completions，单 APK 自包含，不需要外部代理进程

## 功能

### 本地 OpenAI 兼容服务

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v1/models` | GET | 返回可用模型列表 |
| `/v1/chat/completions` | POST | 对话补全，支持多轮上下文与 function calling |
| `/v1/messages` | POST | Anthropic Messages 格式（供 Claude SDK / Cherry Studio 之类调用） |
| `/v1/completions` | POST | legacy 纯文本补全 |
| `/v1/images/generations` | POST | 文生图，返回 `b64_json`（默认）或上游直链 `url` |
| `/health` | GET | 运行统计快照（请求数 / 失败数 / 延迟 / 最近错误） |
| `/status` | GET | 精简健康状态 |

- 默认监听 `http://127.0.0.1:9876`，Base URL 填 `http://127.0.0.1:9876/v1`
- 仅绑定回环地址，局域网其他设备无法访问
- 支持 SSE 流式响应，支持 `Transfer-Encoding: chunked` 真流式分片下发（边收边发）
- 支持可选的 API Key 鉴权、CORS 跨域、并发上限控制
- 请求超时可配；「注入成功但零回调」会自动重试（可配次数），其余失败不重试

```bash
curl http://127.0.0.1:9876/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"xiaobu","messages":[{"role":"user","content":"你好"}]}'
```

### 工具调用（function calling）

客户端请求里带 `tools` 时，网关会自行完成 function calling 的闭环，**不需要外部 Python 代理**。
实现分三层，均为纯逻辑类，可在 PC 上离线回归：

| 类 | 职责 |
|---|---|
| `ToolCallPrompt` | 正向：折叠整段 `messages` + 注入工具协议 + 5000 字符预算裁剪 + Hermes 记忆块转运 |
| `ToolCallCodec` | 解析：调用块抽取、工具名四级校正、arguments 规整、工具错误中性化 |
| `ToolCallBridge` | 接缝：串起注入与还原，产出 OpenAI / Anthropic 契约的响应体 |

设计要点：

- **协议用紧凑 XML**（`<function_calls>` + CDATA），不是 JSON fence。实测两套协议模型遵循率相同，
  但同 14 个工具下 XML 1225 字符、JSON fence 3601 字符——小布约 5000 字符硬上限下这是决定性的。
- **预算是硬约束**：格式说明 → 工具清单 → 记忆 → 对话上下文，逐级降级，最后有硬钳位。
  超限不是「降级」而是「挂死」：实测超上限时上游会一路挂到超时。
- **工具名不直接透传**：模型常把 `read_file` 写成 `Read_File` 或截断成 `read`。按
  精确 → 忽略大小写 → 去 `mcp__` 前缀 → 子串包含 四级校正，**歧义时丢弃而不是猜**。
- **`tool_choice` 真正生效**：`none` 既不注入工具也不回吐调用，`required` 与对象点名写进指令。
- **历史调用按原协议形态回灌**（`<function_call>` 块），让模型对「发起调用长什么样」有格式记忆；
  `role=tool` 的错误结果做中性化，避免模型被客户端的错误措辞带偏。
- **带工具的流式不边收边发**：整段回答收完前无法判断这轮是「调用工具」还是「普通回答」，
  两者 `finish_reason` 与 delta 形状完全不同。缓冲后重放为 SSE，帧内带 `index`、
  收尾 `finish_reason=tool_calls`。无工具请求的真流式路径未动。

### 文生图（`/v1/images/generations`）

小布本身能生图，但结果**不在正文里**——正文只有「已生成图片」几个字，图片 URL 在回答
bean 的 `AIChatViewBean.payload` 里，形如（真机抓取，小布 12.9.9）：

```json
{"uiDirectives":[{"header":{"name":"PictureCard","namespace":"MyAI"},
  "payload":{"picUrl":"https://bot-pubstatic-cn.heytapdownload.com/text2image/online/…jpg",
             "aspectRatio":"2048:2048","roomId":"…","stateCode":0}}]}
```

载荷类是 `com.heytap.speech.engine.protocol.directive.myai.PictureCard`
（`@DirectivePayloadKey("MyAI.PictureCard")`，dex 已核实）。取图链路：

| 类 | 职责 |
|---|---|
| `BeanExtractor` | 反射取 `payload` / `markdownCardInfos`（后者是部分版本的兜底通道） |
| `ImageResultCodec` | 纯逻辑：递归抽 `MyAI.PictureCard` 的 picUrl，按 URL 保序去重 |
| `ConversationSession` | 图片槽位（`offerImages` / `drainImages`），与文本队列同生命周期 |
| `MainHook` | 从 bean 取字段 → 投递进会话 |
| `OpenAIServer` | 注入生图意图 → 等图 → 转 OpenAI 契约 |

参数支持边界（据实，不假装支持）：

- `prompt` 必需；`n` 支持但**串行**（小布一轮只出一张，上限 4 轮）
- `response_format`：`b64_json`（默认）或 `url`
- `size` / `quality` / `style`：**只记日志、不生效**——小布侧不接受这些参数，
  出图尺寸由它自己按 `aspectRatio` 决定
- 与对话端点**共用同一把锁**：小布的对话框与生图任务都只有一份，并发注入会让
  两个请求认领同一份结果，也会撞上小布自己的「当前已有生图任务正在进行」

两个上游限制（真机实测，**不是网关能绕过的**）：

1. **每日图片创作配额**。小布对文生图调用有日配额，用尽后引擎**不再出图**，
   而是回一句纯文本 `已达到今日图片创作服务上限，请明日再试。`。此时端点按
   「接单但无图片载荷」返回 `502 upstream_error`。**密集压测会提前打满配额**，
   一旦打满，当日内连单发 `n=1` 也会稳定失败——排查此类失败前先确认配额是否已被
   测试耗尽（用 `/v1/chat/completions` 发同一句生图意图即可看到那句上限提示，
   而普通问答仍正常，可据此区分「配额用尽」与「技能没路由」）。
2. **注入需要冷却**。对同一对话框在极短间隔内背靠背注入（`n` 串行时约 20ms 间隔），
   第 2 次起会被引擎丢掉、由纯文本回答顶替，表现为 `n=2` 只拿到 1 张。实测间隔
   ≥6s 的独立请求稳定成功。因此 `n>1` 的**实际产出常低于请求值**，且
   `n=2` 这类请求若第 2 轮无图会**静默按 200 返回已拿到的张数**（不报错、不补齐）。

为什么默认 `b64_json`：小布 CDN 直链是**回源地址**，既可能过期、也不属于网关自己的
托管地址。默认内联 base64 让响应自包含；显式要 `url` 时才把上游直链原样给出。

一个只在 `b64_json` 下暴露的上游时序缺陷：图片**直链先于对象可读被下发**——载荷到达后
立刻取会拿到 `404`，约 4–9s 后才 `200`（同一 URL，无重定向、无鉴权）。首轮真机验证用的
是 `response_format=url`（不下载），因此没踩到；默认 `b64_json` 必然踩到，表现为
「刚生成的图必然 502」。下载侧因此把 `404` / `5xx` / 超时当**尚未就绪**做限时重试
（20s 预算、1s 间隔），`401`/`403` 等确定性失败立即抛出、不做无谓等待。
实测单张 JPEG 约 60–120KB；载荷里的 `aspectRatio` 是**比例**不是分辨率，
出图分辨率由小布侧决定（实测 1024×1024，与 `2048:2048` 的比例一致）。

```bash
curl http://127.0.0.1:9876/v1/images/generations \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"一只戴帽子的橘猫，扁平插画风","n":1}'
```

### Hook 侧能力

- **系统悬浮球**（v3.9）：模块进程的常驻前台服务用 `TYPE_APPLICATION_OVERLAY` 挂系统窗口，
  **任何界面都能点开**，承载网关开关 / API Key 管理 / 心跳保活。需用户在系统设置里授予
  「显示在其他应用上层」权限，**默认关闭**，不做自动申请与自动拉起
  （v3.10 起这是**唯一**的悬浮球；v3.0–v3.9 的「应用内悬浮球」已移除，
  它只在小布前台时存在，与系统悬浮球并存只会让人分不清该开哪个）
- **悬浮球消失自愈**（v3.11/v3.12）：面板改固定半屏高 + 内部滚动，标题栏可拖动且记住位置；
  服务内 5s 巡检补窗口、配置变化真正提交布局、`BOOT_COMPLETED` /
  `MY_PACKAGE_REPLACED` 复活、MainActivity 打开即同步。
  **ColorOS 划掉任务卡片 = 对整包 force-stop**（`am_kill ... o-stop(40)`），
  会连带停服务、撤通知并清空该包全部闹钟——靠闹钟复活的路径天然无效。
  v3.12 改为挂在「小布进程按需拉起模块进程」这条稳定路径上自愈：
  `ConfigProvider.onCreate()` 与每次 `query()` 按配置补一次 `OverlayBallService`，
  实测划掉后约 0.7s 进程被拉起、1s 内服务与悬浮窗恢复
- **网关看门狗**（v3.9）：1 秒粒度跟随配置实时启停监听，使「悬浮球上关网关」无需重启小布即可生效
- **心跳保活**（v3.9）：周期性 `GET /health` 探活（默认 60s），连续 3 次失败自动重启监听；
  心跳开启期间同时启用自杀拦截
- **后台静默调用**（v3.13）：API 调用默认**不再把小布界面拉到前台**——小布在后台
  （含锁屏）时直接注入并回收回答，实测流式 / 多轮 / tools / 锁屏均可用。
  v3.6 的原假设「小布在后台则注入零回调，必须先拉前台」经真机取证被推翻
- **首轮探测降级**（v3.14）：注入零回调经真机数据定性为**概率性**事件
  （引擎吞掉首次注入），与前后台、与空闲时长都**无**单调关系
  ——v3.13 的「进程刚起 / 引擎未预热」归因不成立，冷启动只是其中一种情形。
  （发生率只给定性：日志不打印版本号，只能按**日志形态**断代、且发生率随工况
  波动，给不出稳定的百分比常数。
  冻结快照上有两个可直接 grep 复现的计数：450 个进入对话处理的请求中，28 个
  至少被吞一次（6.2%）；453 次注入中 47 次被吞（10.4%）。量级为百分之几到一成。
  早期小结的「44 轮 7 轮 ≈ 16%」样本过小、无留存证据，已不再引用。）
  因此 v3.14 把「等满 `request_timeout` 才发现失败」改为**首轮探针**（15s）：
  探针窗口内既无内容、本轮会话也未被登记 → 判定引擎没接单，立即降级/重试；
  一旦引擎接单（本轮会话被登记）就延长到完整超时，**不截断首字慢的长回答**。
  被吞轮的代价按实测形态分列（非单一区间；均为冻结快照上的 REQ→RESP 实测）：
  冷启动（force-stop 小布后重开）探测被吞、唤醒一次即成功 **16.9s**；
  唤醒后的完整窗口重试**也被吞** → **80.19s**；
  关闭自动唤醒则 4 次探针+退避 **62.58s** 后放弃。正常轮 2.3–4.0s（76 轮 sweep 实测）。
  与 v3.13 对照要按**同一形态**比（否则会拿「两次连吞」去比「单次被吞」）：
  v3.13 发布版单次被吞 **62.75–65.16s**（6 轮）、连吞两次 126.42s。
  v3.14 单次被吞的探测代价是固定的 15s + 唤醒 + 重注入（实测 16.9s / 21.16s），
  与 v3.13 的「等满 60s 才降级」不同量级；但 80.19s 这一形态（探测被吞 +
  唤醒后完整窗口重试又被吞）与 v3.13 的单次被吞轮**同量级、未获改善**——因为
  降级重试刻意给足完整 60s 窗口，绝不截断慢回答。
  （口径说明：以上耗时都只取**响应已送达**客户端（`RESP` 正常收尾）的轮次。
  快照里还有若干轮在被吞后重试成功、但客户端已在完成瞬间断开
  （`java.net.SocketException: Broken pipe`，响应未送达，例如 v3.13 的
  3d6e448b 在连吞两次后 124.33s 完成时断开），这类轮次不以「往返耗时」论，
  以免把「未送达」与「已送达」混在一个区间里。
  另有 1 轮（v3.13 的 0a93bc21，吞 3 次）既无 `RESP` 也无 `Broken pipe`，
  原因是其等待期间我在测试中用悬浮球开关重启过一次网关
  （`OpenAIServer.setEnabled` 会 `executor.shutdownNow()` 中断在途请求），
  属测试操作打断，不是 v3.13 自身形态，同样不计入耗时统计。
  v3.13→v3.14 之间的中间构建上还测到过 21.16s；更早的 v3.12 段已送达的
  只有单次被吞 3 轮 60.17 / 68.77 / 80.61s，其余 12 轮（1 次或 2 次被吞）
  均因 Broken pipe 未送达，属「请求前无条件唤醒」+多轮退避的旧形态，
  不作同形态对照。）
- **自动唤醒（降级兜底）**：即上条的兜底开关。关闭后首轮探测失败不再拉前台，
  直接返回错误，适合「宁可失败也不希望界面被弹出」的场景
- **保活**：拦截小布的自动退出，维持会话可用

### 配置面板

模块自带 UI（首页 / 设置 / 关于），无需改配置文件：

- **服务设置**：启用服务、监听端口、SSE 流式、真流式（chunked）、日志级别、CORS 跨域
- **鉴权与提示词**：API Key 鉴权开关与密钥（可一键生成/复制）、系统提示词
  （支持从文本文件或剪贴板导入）、并发上限（默认 3）、请求超时
- **保活与悬浮球**：心跳保活开关与间隔、系统悬浮球开关、悬浮窗权限申请
- **功能开关**：自动唤醒、保活、自动重试（含最大次数）、接口格式
- **首页状态自检**：AIDL 通道、网关状态、运行模式、目标 App 安装情况、作用域、API 地址；
  运行状态含**工具调用请求数 / 回吐调用数 / 最近调用名**与心跳运行情况。
  网关不通时统计行一律显示「—」，不保留上一轮的陈旧数字

### 跨进程配置通道

UI 进程与小布进程通过自定义 AIDL 服务（`IConfigService`）+ 跨应用只读 ContentProvider
（`com.wanxiang.xiaobubridge.config`）通信。

- Provider 导出访问带**调用方白名单校验**，非小布进程读取会被拒绝
- 两端数据目录互相隔离，配置经只读通道获取，无需共享存储

## 安装

1. 安装 APK（见 [Releases](../../releases)）
2. 打开模块应用，确认首页状态自检各项正常
3. 在 LSPosed 管理器中启用本模块，**作用域勾选「小布助手」**
4. 强制停止小布助手后重新打开，使 Hook 生效
5. 回到模块首页，确认「网关：运行中」

## 从源码构建

需要 JDK 17、Android SDK（compileSdk 34）、build-tools 35.0.0。

```bash
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release-unsigned.apk`

> **签名**：构建脚本不配置签名，release 产物为 unsigned。
> 自行签名示例（含内存对齐 + v2/v3 签名）：
> ```bash
> zipalign -p -f 4 app-release-unsigned.apk aligned.apk
> apksigner sign --ks your.keystore --v2-signing-enabled true --v3-signing-enabled true \
>   --out XiaoBuBridge-signed.apk aligned.apk
> ```
> 安装到已装过其他签名版本的设备前，需先卸载旧版。

### 版本通道：正式版号 / 测试版号

同一份源码产出两种版号，靠 Gradle 属性 `-Pchannel` 切换（`app/build.gradle.kts`）：

| 通道 | 参数 | versionName | versionCode | 用途 |
|---|---|---|---|---|
| 正式 | `-Pchannel=release`（默认） | `3.15` | `31500` | GitHub Release 分发 |
| 测试 | `-Pchannel=local` | `3.15.99-local` | `31599` | 本机构建、真机自测 |

规则与理由：

- **测试版号必须恒大于同一 patch 的正式版**，否则装过测试版就回不去正式版——
  测试版取 `.99-local`（`31599 > 31500`），保证可原地覆盖安装正式版做对比验证；
- **同时必须恒小于下一个次版本正式版**（3.16 → `31600`），否则测试版会把后续
  正式升级挡在门外。patch 位留 99 即两者兼顾；若某版本 patch 逼近 99，需先升 minor；
- **默认走 `release`**：漏传参数时宁可产出正式号，也不能让测试号混进发布产物；
- 测试版号带 `-local` 后缀，安装后关于页、`/health` 的 `version` 字段一眼可辨；
- 共享签名（见下），因此正式/测试版之间可自由覆盖安装，不必卸载。

构建脚本（本仓库外，含沙箱专用 aapt2 覆盖与签名校验）：
`build-xbb.sh <项目目录> <Gradle task>`，通道按任务自动判定
（`assembleDebug` → local，`assembleRelease` → release），也可用 `CHANNEL=` 显式覆盖。
脚本以 **APK 清单里的 versionName** 命名产物并自校验「通道 ↔ 版号」是否相符，
正式通道一旦产出含 `local` 的版号会直接失败退出。

### 特殊平台

资源受限环境（如 aarch64/PRoot 容器）下，AGP 从 Maven 获取的 aapt2 是 x86_64 ELF，会因
`AAPT2 ... Daemon startup failed` 失败。此时需改用宿主原生 aapt2，在**本机**
（不要提交）`gradle.properties` 或命令行传入：

```bash
./gradlew :app:assembleRelease \
  -Pandroid.aapt2FromMavenOverride=/path/to/build-tools/35.0.0/aapt2
```

同理，无网络环境可加 `-Pandroid.builder.sdkDownload=false` 禁止自动下载 SDK 组件。

## 验收脚本

工具调用层刻意做成不依赖 Android / Xposed 的纯逻辑（日志走可替换的 `Logger`），
因此有两级验证——离线证「逻辑对」，真机证「链路通」：

```bash
# 一级：PC 侧离线回归（编译三个纯逻辑类直接跑，秒级）
bash tools/run_toolcall_test.sh

# 图片抽取层的离线回归（同样秒级，不需要设备）
bash tools/run_image_codec_test.sh

# 二级：真机闭环（打设备上的 9876，不经过任何 Python 旁路）
python3 tools/verify_toolcall_device.py [port] [e0|e1|e2|e3|e4|all]
XBB_API_KEY=<key> python3 tools/verify_image_device.py [port] [--n-clamp]
```

离线回归覆盖 9 组确定性缺陷（裸 JSON 调用被丢弃、工具名被改写透传、arguments 非法被静默清空、
`tool_choice` 不生效、客户端错误原样回灌、预算溢出仍超限、体积与形态护栏、响应契约、注入文本组装）。

图片抽取回归覆盖 8 组（真机 payload 原样抽取、header 改名/缺失时按 `picUrl` 字段兜住、
`markdownCardInfos` 通道与 `info` 为字符串的形态、异常输入一律返回空列表不抛异常、
普通卡片 / `iconUrl` 不得误判为图片、一轮多图保序、深嵌套与字符串型数值。

真机验收覆盖：完整闭环（请求 → tool_calls → 本地执行 → 回传 → `stop`）、
`tool_choice="none"` 不吐调用、对象点名生效、无 `tools` 时不得产生 `tool_calls`（防回归）、
流式下的 `tool_calls` 契约。

`tools/verify_image_device.py` 是文生图端点的真机闭环（默认 `b64_json` 那条路，
即「载荷到达后立刻取 CDN 会 404」的现场）：

```bash
XBB_API_KEY=<key> python3 tools/verify_image_device.py [port] [--n-clamp]
```

检查项包括：默认 `b64_json` 能被**解码成真实图片**（按魔数判，不信 Content-Type——
实测上游会拿 `.jpg` 直链回 `image/png`）、`b64_json` 形态不回吐 `url`、
`revised_prompt` 原样回带、`response_format=url` 只给直链且不回吐 `b64_json`、
缺 `prompt` / 非法 `response_format` 一律 `400` 且错误体为 OpenAI 契约
（拼写错误**不静默降级**成默认值）、`n` 超上限只钳制不报错。

`tools/verify_stream.py` 用于验证真流式（chunked）输出是否符合 OpenAI 流式契约，
逐字节复刻 SDK 的解析行为，不依赖 `openai` 包：

```bash
python3 tools/verify_stream.py [port] [prompt]
```

检查项包括：响应头必须为 `Transfer-Encoding: chunked` 且无 `Content-Length`、必须按 HTTP
chunked 分帧解码、每帧须为可解析的 `data: <json>\n\n`、**首字须早于整段结束到达**
（区分"格式流式"与"时序流式"）、须以 `data: [DONE]` 与零长度终止块收尾、
UTF-8 多字节字符不得跨 chunk 被截断。

## 工作原理

1. `MainHook` 在小布进程加载时挂载 Hook，接管对话链路
2. `OpenAIServer` 在进程内启动 HTTP 服务（`com.sun.net.httpserver`），绑定 `127.0.0.1`
3. 收到请求后经 `AutoWaker` 确保小布可用，`ConversationSession` 维护多轮上下文，
   由 `BeanExtractor` 提取响应内容
4. 带 `tools` 的请求先经 `ToolCallBridge` 把整段上下文与工具协议折叠成一条注入文本，
   回答回来再由 `ToolCallCodec` 还原成标准 `tool_calls`（该路径在进程内完成，无外部依赖）
5. 生图请求注入生图意图，`ImageResultCodec` 从回答 bean 的 `payload` 抽 `picUrl`，
   下载（或直链）后按 OpenAI 图片契约返回
6. `GatewayWatchdog` 常驻线程每秒比对配置与监听状态，负责网关实时启停与心跳探活
7. 结果按 OpenAI 兼容格式（可选 SSE chunked 流式）返回给客户端

## 已知限制

- 需已安装并启用 LSPosed 框架
- 系统悬浮球需手动授予「显示在其他应用上层」权限；未授权时开关打开也不会显示。
  服务本身用 `foregroundServiceType="specialUse"`，因此 API 34+ 要求同时声明
  `FOREGROUND_SERVICE_SPECIAL_USE` 权限，否则服务启动即崩（已在 manifest 补齐）
- 心跳保活只能覆盖「进程还在、小布准备自己退出」与「监听 socket 已死但对象还在」
  两类场景；**进程被系统强杀时无法复活**——看门狗跑在小布进程内，进程没了线程也没了。
  进程级复活需要外部拉起，本模块不做，也不假装能做
- 心跳开启后会周期性拉起小布（可能短暂出现在前台），这是保活的代价，故默认关闭
- 仅针对小布助手生效，小布版本大更新可能导致 Hook 点失效
- 接口仅监听回环地址，跨设备调用需自行做端口转发
- 工具调用只在 OpenAI 格式（`/v1/chat/completions`）上生效；Anthropic 的 `tool_use`
  与 legacy 端点暂不解析，避免"半套支持"被误当成完整支持
- 小布输入有约 5000 字符硬上限，工具集过大时工具描述会被压缩；参数已压为
  `名称:类型=枚举` 形式，长描述不参与注入
- 文生图依赖小布把提示词路由到 `text2image` 技能。裸画面描述会被当普通问答回答，
  网关会在缺少生图动词时补前缀「画一张图：」；若补了前缀仍被当作普通问答，
  端点据实返回 `502 upstream_error`（`XiaoBu answered without an image payload`），
  不拿正文糊弄成图片。注意两种情况**同样的报错、不同的原因**：一是技能没路由，
  二是**小布每日图片创作配额已用尽**（引擎回「已达到今日图片创作服务上限，请明日再试。」）。
  前者可重试或改写提示词，后者当日内怎么试都不会成功。区分办法：用
  `/v1/chat/completions` 发同一句生图意图，看返回文本里有没有那句上限提示
  （配额用尽时普通问答仍正常）。
- 文生图的 `n>1` **实际产出常低于请求值**：小布一轮只出一张，网关按轮串行注入，
  但背靠背注入（约 20ms 间隔）第 2 轮起会被引擎丢掉、由纯文本回答顶替，
  实测 `n=2` 稳定只拿到 1 张，此时端点**静默按 200 返回已拿到的张数**。
  独立请求间隔 ≥6s 才稳定成功——对「一次要 n 张」的调用方，建议改成发 n 次
  间隔 ≥6s 的单张请求，而不是依赖 `n`。
- 文生图与对话**串行**：两者共用一把锁，生图期间对话请求排队。这是刻意的——
  小布只有一份对话框与一个生图任务
- 生图轮等待上限独立于 `request_timeout`（固定 180s）：那个默认 60s 是给文本轮定的，
  生图云端排队时会显著更长。客户端要更短请自行设 HTTP 超时，网关不会中途截断
- release 构建未开启混淆。若开启混淆，务必先验证 Hook 的目标类名与成员未被改写，
  否则模块会静默失效

## 许可证

[MIT](LICENSE)
