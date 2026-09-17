# XiaoBu Bridge

把小布助手的 AI 对话能力桥接成**本地 OpenAI 兼容 HTTP 接口**的 LSPosed 模块。

任何支持自定义 Base URL 的客户端（Cherry Studio、NextChat、各类 OpenAI SDK、脚本）都能直接调用，
无需逆向、无需抓包、无需云端中转。数据全程在本机内部流转。

- **版本：** 3.0（versionCode 30000）
- **包名：** `com.wanxiang.xiaobubridge`
- **作用域：** 小布助手（`com.heytap.speechassist`）
- **平台：** Android 7.0+（minSdk 24），targetSdk 34

## 功能

### 本地 OpenAI 兼容服务

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v1/models` | GET | 返回可用模型列表 |
| `/v1/chat/completions` | POST | 对话补全，支持多轮上下文 |

- 默认监听 `http://127.0.0.1:9876`，Base URL 填 `http://127.0.0.1:9876/v1`
- 仅绑定回环地址，局域网其他设备无法访问
- 支持 SSE 流式响应，支持 `Transfer-Encoding: chunked` 真流式分片下发（边收边发）
- 支持可选的 API Key 鉴权与并发上限控制

```bash
curl http://127.0.0.1:9876/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"xiaobu","messages":[{"role":"user","content":"你好"}]}'
```

### Hook 侧能力

- **悬浮球与控制面板**：以应用内悬浮方式注入（Hook decorView），不需要 `SYSTEM_ALERT_WINDOW` 权限
- **自动唤醒**：请求到达时自动拉起小布
- **保活**：拦截小布的自动退出，维持会话可用

### 配置面板

模块自带 UI（首页 / 设置 / 关于），无需改配置文件：

- **服务设置**：启用服务、监听端口、SSE 流式、真流式（chunked）、日志级别
- **鉴权与提示词**：API Key 鉴权开关与密钥、系统提示词、并发上限
- **功能开关**：应用内悬浮球、自动唤醒、保活
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
4. 结果按 OpenAI 兼容格式（可选 SSE chunked 流式）返回给客户端

## 已知限制

- 需已安装并启用 LSPosed 框架
- 仅针对小布助手生效，小布版本大更新可能导致 Hook 点失效
- 接口仅监听回环地址，跨设备调用需自行做端口转发
- release 构建未开启混淆。若开启混淆，务必先验证 Hook 的目标类名与成员未被改写，
  否则模块会静默失效

## 许可证

[MIT](LICENSE)
