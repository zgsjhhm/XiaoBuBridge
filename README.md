# XiaoBu Bridge

把小布助手的 AI 对话能力桥接成**本地 OpenAI 兼容 HTTP 接口**的 LSPosed 模块。

任何支持自定义 Base URL 的客户端（Cherry Studio、NextChat、各类 OpenAI SDK、脚本）都能直接调用，
无需逆向、无需抓包、无需云端中转。数据全程在本机内部流转。

- **版本：** 3.8（versionCode 30800）
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

### Hook 侧能力

- **悬浮球与控制面板**：以应用内悬浮方式注入（Hook decorView），不需要 `SYSTEM_ALERT_WINDOW` 权限
- **自动唤醒**：请求到达时自动拉起小布
- **保活**：拦截小布的自动退出，维持会话可用

### 配置面板

模块自带 UI（首页 / 设置 / 关于），无需改配置文件：

- **服务设置**：启用服务、监听端口、SSE 流式、真流式（chunked）、日志级别、CORS 跨域
- **鉴权与提示词**：API Key 鉴权开关与密钥、系统提示词、并发上限、请求超时
- **功能开关**：应用内悬浮球、自动唤醒、保活、自动重试（含最大次数）、接口格式
- **首页状态自检**：AIDL 通道、网关状态、运行模式、目标 App 安装情况、作用域、API 地址

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

# 二级：真机闭环（打设备上的 9876，不经过任何 Python 旁路）
python3 tools/verify_toolcall_device.py [port] [e0|e1|e2|e3|e4|all]
```

离线回归覆盖 9 组确定性缺陷（裸 JSON 调用被丢弃、工具名被改写透传、arguments 非法被静默清空、
`tool_choice` 不生效、客户端错误原样回灌、预算溢出仍超限、体积与形态护栏、响应契约、注入文本组装）。

真机验收覆盖：完整闭环（请求 → tool_calls → 本地执行 → 回传 → `stop`）、
`tool_choice="none"` 不吐调用、对象点名生效、无 `tools` 时不得产生 `tool_calls`（防回归）、
流式下的 `tool_calls` 契约。

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
5. 结果按 OpenAI 兼容格式（可选 SSE chunked 流式）返回给客户端

## 已知限制

- 需已安装并启用 LSPosed 框架
- 仅针对小布助手生效，小布版本大更新可能导致 Hook 点失效
- 接口仅监听回环地址，跨设备调用需自行做端口转发
- 工具调用只在 OpenAI 格式（`/v1/chat/completions`）上生效；Anthropic 的 `tool_use`
  与 legacy 端点暂不解析，避免"半套支持"被误当成完整支持
- 小布输入有约 5000 字符硬上限，工具集过大时工具描述会被压缩；参数已压为
  `名称:类型=枚举` 形式，长描述不参与注入
- release 构建未开启混淆。若开启混淆，务必先验证 Hook 的目标类名与成员未被改写，
  否则模块会静默失效

## 许可证

[MIT](LICENSE)
