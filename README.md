# Nekobot Android

基于 Jetpack Compose + Material3 实现的 NekoBot Web API 原生 Android 客户端。

## 功能特性

- **登录**：应用内可配置服务器地址 + Token 持久化
- **对话**：消息气泡、流式接收 AI 回复、乐观更新、重新生成、停止生成
- **会话管理**：列表、新建、重命名、删除、收藏/置顶切换
- **角色卡**（完整数据源）：列表、查看/编辑全字段（描述/人格/首条消息/场景/对话示例/规则/立绘/标签等）、新建/删除
- **世界书**：列表、书信息编辑、条目 CRUD（关键词/常驻/选择/位置/优先级）
- **Token 用量**：今日/本月/累计/费用统计 + 日期范围筛选 + 会话/模型/用户排行
- **AI 配置**：模型/温度/max_tokens/top_p/惩罚参数编辑 + 测试连接
- **AI 模型**：模型 CRUD + 应用/启用/克隆/测试 + 拉取可用模型列表
- **系统设置**：服务器地址切换、系统设置 JSON 编辑器、重载配置、日志查看、登出
- **实时通信**：基于 Socket.IO 接收 AI 流式分片与消息推送
- **设计**：深色玻璃拟态主题，自定义弹窗、玻璃卡片、状态芯片组件

## 技术栈

| 类别 | 选型 |
|------|------|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.09.02）+ Material3 |
| 架构 | MVVM（BaseViewModel + StateFlow） |
| 异步 | Kotlin Coroutines |
| 网络 | Retrofit 2.11 + OkHttp 4.12 + Gson |
| 实时 | socket.io-client-java 2.1.0 |
| 图片 | Coil 2.7.0 |
| 导航 | Navigation Compose 2.8.2 |
| 构建 | Gradle 8.11.1 + AGP 8.9.1 |
| minSdk / targetSdk | 26 / 35 |

## 项目结构

```
app/src/main/kotlin/com/nekobot/app/
├── MainActivity.kt              # Activity 入口
├── NekobotApp.kt                # Application + ServiceContainer
├── data/
│   ├── local/PrefsManager.kt    # SharedPreferences 封装
│   ├── model/Models.kt          # 数据模型
│   ├── remote/
│   │   ├── ApiService.kt        # Retrofit 接口
│   │   ├── AuthInterceptor.kt   # Token 注入
│   │   ├── NetworkClient.kt     # Retrofit 工厂
│   │   └── SocketManager.kt     # Socket.IO 客户端
│   └── repository/NekobotRepository.kt
└── ui/
    ├── BaseViewModel.kt         # 统一 loading/error 处理
    ├── components/              # 通用组件（GlassCard、NekoDialog 等）
    ├── navigation/              # 路由 + NavGraph
    ├── screens/                 # 业务屏幕
    │   ├── login/               # 登录
    │   ├── sessions/            # 会话列表
    │   ├── chat/                # 对话
    │   ├── characters/          # 角色卡
    │   ├── worldbook/           # 世界书
    │   ├── tokens/              # Token 用量
    │   ├── aiconfig/            # AI 配置/模型
    │   └── settings/            # 系统设置
    └── theme/                   # 颜色/排版/主题
```

## 数据源约定

本客户端的角色卡数据走 `/api/personality/custom-presets` 完整数据源（`data/web/custom_personality_presets.json`），不使用 `/api/characters` 运行时快照。

创建角色卡请求体**不传** `id` / `systemPrompt` / `greeting`（后端自动管理），其他字段（name / description / portrait / tags / basicInfo / personality / scenario / firstMessage / exampleDialogues / responseFormat / rules 等）按需提交。

## 构建

### 环境要求

- JDK 17+（推荐 Android Studio 自带 JBR）
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

### 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

1. 启动应用，登录页输入服务器地址（默认 `http://localhost:5000`）、用户名、密码
2. 登录成功后自动进入会话页，底部导航可切换：会话 / 角色 / 世界书 / 用量 / 设置
3. 聊天页发消息后，AI 回复通过 Socket.IO 实时流式推送
4. 设置页可修改服务器地址（写入本地后会重建网络与 Socket 客户端）

## 调试

实时通信日志标签：`NekoSocket`（可 `adb logcat -s NekoSocket` 观察连接/重连/错误）

```bash
adb logcat -s NekoSocket:V
```

## 协议

MIT
