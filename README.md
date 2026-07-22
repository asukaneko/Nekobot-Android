<div align="center">

<img src="docs/assets/neko-full.png" alt="NekoBot" width="280" />

# NekoBot Android

**和你的 AI 伙伴，开始一场温柔的对话**

原生 Android 客户端 · 服务器 / 本地双模式 · 深色玻璃拟态 UI

[![Release](https://img.shields.io/github/v/release/asukaneko/Nekobot-Android?style=flat-square&color=f78fb3&labelColor=2b2b3a)](https://github.com/asukaneko/Nekobot-Android/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/asukaneko/Nekobot-Android/total?style=flat-square&color=f78fb3&labelColor=2b2b3a&label=downloads)](https://github.com/asukaneko/Nekobot-Android/releases)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-f78fb3?style=flat-square&labelColor=2b2b3a&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-f78fb3?style=flat-square&labelColor=2b2b3a&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-GPL--3.0-f78fb3?style=flat-square&labelColor=2b2b3a)](LICENSE)
[![Stars](https://img.shields.io/github/stars/asukaneko/Nekobot-Android?style=flat-square&color=f78fb3&labelColor=2b2b3a&logo=github)](https://github.com/asukaneko/Nekobot-Android/stargazers)

[功能特性](#-功能特性) · [下载安装](#-下载安装) · [双模式](#-双模式架构) · [从源码构建](#-从源码构建) · [更新日志](changelog.md) · [官方网站](https://asukaneko.github.io/Nekobot-Android/)

</div>

---

## ✨ 功能特性

### 🐾 核心体验

- **💬 沉浸式对话** — Socket.IO 流式回复、乐观更新、重新生成 / 停止生成、消息分叉与多选操作
- **🎭 角色卡系统** — 完整字段编辑（描述 / 人格 / 首条消息 / 场景 / 规则 / 立绘），支持导入 SillyTavern 酒馆卡（PNG 嵌入式、v2 / v3 JSON）
- **💞 角色运行时** — 六维关系系统、状态评估、记忆抽取、世界书注入、PromptStack 合成
- **🌍 世界书** — 条目 CRUD（关键词 / 常驻 / 选择 / 位置 / 优先级）、书信息编辑、多角色绑定
- **🌳 故事图** — Canvas 树状布局呈现剧情分支，支持分支选择、回滚与重生，本地持久化
- **📈 状态历程** — 角色状态随时间变化的可视化时间线
- **🧠 记忆管理** — 角色记忆的查看与编辑

### 🛠 个性化与工具

- **🤖 AI 配置中心** — 模型 / 温度 / max_tokens / top_p / 惩罚参数编辑、故障转移队列、一键测试连接
- **🧩 AI 模型管理** — 模型 CRUD、应用 / 启用 / 克隆、拉取可用模型列表、本地 AI 模型配置
- **📊 Token 用量** — 今日 / 本月 / 累计 / 费用统计、日期分组、会话 / 模型 / 用户排行、性能指标
- **🎤 语音与 TTS** — 录音转写为文字（服务器模式）、TTS 试听
- **📝 Markdown 渲染** — 内独白折叠、全角括号斜体、带语言标签与复制按钮的代码块、横滑表格
- **🗂 工作区** — 文件引用、预览与下载
- **🧰 12+ 扩展功能** — API Keys、频道、钩子、知识库、登录令牌、MCP 服务器、消息过滤、技能、任务中心、工具、工作流
- **⚙️ 系统设置** — 服务器地址切换、设置 JSON 编辑器、功能开关、数据维护、配置迁移、WebDAV 备份

### 🎨 设计

- **🌙 深色玻璃拟态** — 液态玻璃底部导航栏、玻璃卡片、自定义弹窗与状态芯片
- **🌈 自定义主题色** — 配合流式占位骨架动画，等待也优雅

## 🔄 双模式架构

|          | 🌐 服务器模式                | 📱 本地模式               |
| -------- | ----------------------- | --------------------- |
| **后端**   | 连接 NekoBot Web 后端       | 无需后端，直连 OpenAI 兼容 API |
| **通信**   | REST + Socket.IO 实时流式推送 | 本地直接请求 AI API         |
| **数据存储** | 服务器 + 本地缓存              | 全部存储于本地 Room 数据库      |
| **适用场景** | 完整功能生态、多端同步             | 隐私优先、离线可用、自有 API Key  |

## 📦 下载安装

<div align="center">

[![下载最新版](https://img.shields.io/github/v/release/asukaneko/Nekobot-Android?style=for-the-badge&logo=android&logoColor=white&label=%E4%B8%8B%E8%BD%BD%E6%9C%80%E6%96%B0%E7%89%88&color=f78fb3&labelColor=2b2b3a)](https://github.com/asukaneko/Nekobot-Android/releases/latest)

**要求 Android 8.0（API 26）及以上** · 应用内支持检查更新与下载安装

</div>

## 🚀 快速上手

**🌐 服务器模式**

1. 启动应用，在登录页输入服务器地址、用户名、密码
2. 登录成功进入会话页，底部导航切换功能
3. 聊天页发送消息后，AI 回复通过 Socket.IO 实时流式推送
4. 设置页可修改服务器地址（写入后自动重建网络与 Socket 客户端）

**📱 本地模式**

1. 登录页切换至本地模式
2. 在「本地 AI 模型」中配置 OpenAI 兼容 API 地址与密钥
3. 数据全部存储于本地，支持会话 / 角色 / 世界书 / 记忆等完整功能
4. 设置页可查看本地运行日志

## 🛠 技术栈

| 类别   | 选型                                         |
| ---- | ------------------------------------------ |
| 语言   | Kotlin 2.0.21                              |
| UI   | Jetpack Compose（BOM 2024.09.02）+ Material3 |
| 架构   | MVVM（BaseViewModel + StateFlow）            |
| 异步   | Kotlin Coroutines 1.9.0                    |
| 网络   | Retrofit 2.11 + OkHttp 4.12 + Gson         |
| 实时通信 | socket.io-client-java 2.1.0                |
| 数据库  | Room 2.6.1（KSP 注解处理）                       |
| 图片加载 | Coil 2.7.0（crossfade + 256MB 磁盘缓存）         |
| 导航   | Navigation Compose 2.8.2                   |
| 构建   | Gradle 8.11.1 + AGP 8.9.1 + KSP            |

## 🔧 从源码构建

<details>
<summary><b>展开构建指南</b>（JDK 17+ / Android SDK 35）</summary>

<br>

**1. 配置 SDK 路径**

在项目根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

**2. 编译**

```bash
# Windows
gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

**3. Release 构建**

```bash
gradlew.bat assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> Release 构建复用 debug 签名配置，生成的 APK 可直接安装。

**4. 安装到设备**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

</details>

<details>
<summary><b>项目结构</b></summary>

<br>

```
app/src/main/kotlin/com/nekobot/app/
├── MainActivity.kt                  # Activity 入口
├── NekobotApp.kt                    # Application + ServiceContainer + Coil ImageLoader
├── data/
│   ├── local/
│   │   ├── LocalLogger.kt           # 本地日志（SharedPreferences 持久化，上限 2000 条）
│   │   ├── LocalRepository.kt       # 本地模式数据仓库（Room）
│   │   └── PrefsManager.kt          # SharedPreferences 封装
│   ├── model/Models.kt              # 数据模型
│   ├── remote/
│   │   ├── ApiService.kt            # Retrofit 接口
│   │   ├── AuthInterceptor.kt       # Token 注入
│   │   ├── NetworkClient.kt         # Retrofit 工厂
│   │   └── SocketManager.kt         # Socket.IO 客户端
│   └── repository/
│       ├── NekobotRepository.kt     # 服务器模式仓库
│       └── UnifiedRepository.kt     # 模式统一入口（按 appMode 分发）
└── ui/
    ├── BaseViewModel.kt             # 统一 loading/error 处理
    ├── components/
    │   ├── CommonComponents.kt      # GlassCard、NekoDialog、ToggleChip 等
    │   └── MarkdownText.kt          # Markdown 渲染组件
    ├── navigation/
    │   ├── NavGraph.kt              # 路由图
    │   ├── Routes.kt                # 路由定义
    │   └── LiquidGlassBottomBar.kt  # 液态玻璃底部导航栏
    ├── screens/
    │   ├── login/                   # 登录
    │   ├── sessions/                # 会话列表 + 会话详情
    │   ├── chat/                    # 对话 + 工作区 + 多媒体内容
    │   ├── characters/              # 角色卡列表 + 详情
    │   ├── worldbook/               # 世界书列表 + 详情
    │   ├── memory/                  # 角色记忆
    │   ├── plot/                    # 故事图
    │   ├── statehistory/            # 状态历程
    │   ├── tokens/                  # Token 用量
    │   ├── aiconfig/                # AI 配置中心 + 模型 + 故障转移 + 本地模型
    │   ├── extensions/              # 扩展功能（12 个高级配置页面）
    │   ├── settings/                # 系统设置 + 功能开关 + 数据维护 + 配置迁移 + WebDAV + 样式
    │   └── more/                    # 更多
    └── theme/                       # 颜色 / 排版 / 主题
```

</details>

## 🔐 权限说明

| 权限                     | 用途          |
| ---------------------- | ----------- |
| `INTERNET`             | 网络通信        |
| `ACCESS_NETWORK_STATE` | 检测网络状态      |
| `RECORD_AUDIO`         | 语音输入（服务器模式） |
| `POST_NOTIFICATIONS`   | 会话通知提醒      |

## 🐞 调试

实时通信日志标签为 `NekoSocket`：

```bash
adb logcat -s NekoSocket:V
```

## 🤝 参与贡献

欢迎提交 Issue 与 Pull Request！版本变更记录见 [changelog.md](changelog.md)。

---

<div align="center">

基于 [GPL v3](LICENSE) 协议开源 · 衍生作品须同样以 GPL v3 发布

**[官方网站](https://asukaneko.github.io/Nekobot-Android/)** · **[问题反馈](https://github.com/asukaneko/Nekobot-Android/issues)** · **[最新版本](https://github.com/asukaneko/Nekobot-Android/releases/latest)**

🐾 Made with 💗 by [Asukaneko](https://github.com/asukaneko)

</div>
