# Nekobot Android

基于 Jetpack Compose + Material3 实现的 NekoBot 原生 Android 客户端，支持服务器模式与本地模式双形态。

## 功能特性

### 双模式架构

- **服务器模式**：连接 NekoBot Web 后端，通过 REST + Socket.IO 实现完整功能
- **本地模式**：直连 OpenAI 兼容 AI API，数据全部存储于本地 Room 数据库，无需后端

### 核心功能

- **对话**：消息气泡、流式接收、乐观更新、重新生成、停止生成、消息分叉、消息多选
- **会话管理**：列表搜索/筛选/置顶、归档、会话详情、TTS / 主动聊天 / 公开分享配置
- **角色卡**：完整字段编辑（描述/人格/首条消息/场景/对话示例/规则/立绘/标签）、新建/删除/导入
- **角色运行时**：状态评估、关系六维编辑、记忆抽取、世界书注入、PromptStack 合成
- **故事图**：Canvas 树状布局展示剧情分支，支持分支选择、回滚与重生，本地持久化
- **状态历程**：可视化角色状态随时间变化的时间线视图
- **世界书**：书信息编辑、条目 CRUD（关键词/常驻/选择/位置/优先级）、多角色绑定
- **记忆管理**：角色记忆查看与编辑
- **Token 用量**：今日/本月/累计/费用统计、日期分组、会话/模型/用户排行、性能指标
- **AI 配置中心**：模型/温度/max_tokens/top_p/惩罚参数编辑、故障转移队列、测试连接
- **AI 模型**：模型 CRUD、应用/启用/克隆、拉取可用模型列表、本地 AI 模型配置
- **语音输入**：录音转写为文字（服务器模式）
- **工作区**：文件引用、预览与下载
- **Markdown 渲染**：内独白折叠、全角括号斜体、代码块带语言标签与复制、横滑表格、内联样式
- **系统设置**：服务器地址切换、系统设置 JSON 编辑器、功能开关、数据维护、配置迁移、WebDAV 备份
- **扩展功能**：API Keys、频道、钩子、知识库、登录令牌、MCP 服务器、消息过滤、技能、任务中心、工具、TTS 试听、工作流

### 设计

- 深色玻璃拟态主题，液态玻璃底部导航栏
- 自定义弹窗、玻璃卡片、状态芯片组件
- 自定义主题色
- 流式占位骨架动画

## 技术栈

| 类别 | 选型 |
|------|------|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.09.02）+ Material3 |
| 架构 | MVVM（BaseViewModel + StateFlow） |
| 异步 | Kotlin Coroutines 1.9.0 |
| 网络 | Retrofit 2.11 + OkHttp 4.12 + Gson |
| 实时 | socket.io-client-java 2.1.0 |
| 数据库 | Room 2.6.1（KSP 注解处理） |
| 图片 | Coil 2.7.0（crossfade + 256MB 磁盘缓存） |
| 导航 | Navigation Compose 2.8.2 |
| 构建 | Gradle 8.11.1 + AGP 8.9.1 + KSP |
| minSdk / targetSdk | 26 / 35 |

## 项目结构

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

## 构建

### 环境要求

- JDK 17+（构建兼容 JDK 24）
- Android SDK 35
- Gradle Wrapper 已包含，无需全局安装 Gradle

### 配置

创建 `local.properties` 指向 Android SDK：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 编译

```bash
# Windows
gradlew.bat assembleDebug

# Linux/macOS
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### Release 构建

```bash
gradlew.bat assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> Release 构建复用 debug 签名配置，生成的 APK 可直接安装。

### 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

### 服务器模式

1. 启动应用，登录页输入服务器地址、用户名、密码
2. 登录成功后进入会话页，底部导航切换功能
3. 聊天页发消息后，AI 回复通过 Socket.IO 实时流式推送
4. 设置页可修改服务器地址（写入本地后会重建网络与 Socket 客户端）

### 本地模式

1. 登录页切换至本地模式
2. 在「本地 AI 模型」中配置 OpenAI 兼容 API 地址与密钥
3. 数据全部存储于本地，支持会话/角色/世界书/记忆等完整功能
4. 设置页可查看本地运行日志

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 网络通信 |
| `ACCESS_NETWORK_STATE` | 检测网络状态 |
| `RECORD_AUDIO` | 语音输入（服务器模式） |
| `POST_NOTIFICATIONS` | 会话通知提醒 |

## 调试

实时通信日志标签：`NekoSocket`（可 `adb logcat -s NekoSocket` 观察连接/重连/错误）

```bash
adb logcat -s NekoSocket:V
```

## 版本记录

详见 [changelog.md](changelog.md)。

## 协议

MIT
