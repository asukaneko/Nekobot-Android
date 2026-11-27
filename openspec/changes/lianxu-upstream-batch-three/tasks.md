# 恋叙模块第三批移植验收

父提交为 `924d5a9a`。依次移植 `1f65675`、`feb43a6`、`306c7d2`、`4e247c7`、`bb98cf1`、`50ea9db`。上游 `50ea9db` 与 PR #35 合并点 `35b7ae0` 的 `app/src/main` 树无差异。

## 生产代码与宿主核对

本批上游修改 40 个生产文件。按 LF 归一化并将 MainActivity 映射到 LianxuActivity 后，30 个与上游最终内容逐字节一致。10 个有宿主差异，见下表。另外调整了宿主自己的 PurposeOptions，所有用途均以宿主声明的模型类别筛选镜像。上游 250 个 Kotlin 生产文件中，独立版 LianxuApplication 由宿主初始化代替，其余 249 个均有对应文件。

| 范围 | 检查结果 |
| --- | --- |
| Activity 获取 | 全模块没有 androidx.activity.compose.LocalActivity 引用。需要 Activity 的模型配置入口沿用 LocalContext.current.hostActivity()。媒体保存通过 Context 和系统权限请求回调完成。 |
| ModelConfigScreen | 已接入 startAtPurposes、SlidingPageTabs、聊天「使用中／使用」按钮和 ModelRowInfo。保留 edit 和直接进入编辑时的宿主 ID 检查。聊天、向量页按 hostPurposes 分类；宿主聊天模型可切换，编辑区禁用并隐藏箭头。宿主向量条目点击后选择 EMBEDDING，隐藏编辑箭头。两处均保留「来自 NekoBot」。 |
| PurposeSelection | 新版底部弹层将 hostPurposes 传给 purposeOptions。宿主分类优先于模型名称推断，向量用途只纳入向量镜像，聊天、记忆、轻回顾和辅助看图只纳入对话镜像。列表及当前选择都显示宿主来源。JVM 测试遍历全部用途，并验证镜像与自建模型的标签。 |
| LianxuActivity | models-purposes 一级路由直接打开模型用途；诊断页面跟踪排除该路由，详情页使用 DirectoryGroup 和 DirectoryDivider。保留 FragmentActivity、宿主隐私门、返回宿主按钮与回调、暖纸初遇扉页、SoftPage、聊天前后台通知及角色联动删除文案。 |
| SettingsDirectory | 新设置目录包含模型用途、记忆规则入口，已移除原记忆列表入口。新版设置项及说明页标题均为「关于日常陪伴」。 |
| BreezeStudioScreen | 语音连接、音色库和试听状态已同步；试听临时文件前缀仍为 lianxu-voice-preview-。设备测试使用上游合成音频，已核对该资源仅打包入测试 APK。 |
| LocalUpdates、UpdatesPresentation | 按正式发行版本和测试编号匹配当前与历史记录，保留版本 65 的宿主文案。JVM 用例验证测试编号与正式发行编号相同也不会串用记录，未知发行版本不借用旧记录。 |
| AppState、BackupRepository | 草稿和发图接受引用，记忆设置的默认返回页为 home。引用作为角色数据校验并重映射，导出全局偏好时过滤引用键。保留角色 store/sync/duringRestore、HostModelSync、删除联动、RoleDeletion.link、角色映射和模型跟随前缀过滤、排除宿主模型配置以及整库恢复的 localLinks/remapRestoredRole。 |
| AndroidManifest | 库与宿主均声明 WRITE_EXTERNAL_STORAGE 上限为 API 28。最终 APK 的二进制清单只有一条该权限，maxSdkVersion 为 28；RECEIVE_BOOT_COMPLETED 存在，ContactWakeReceiver 非导出并监听 BOOT_COMPLETED。唯一 MAIN/LAUNCHER 入口为 com.nekobot.app.MainActivity。 |
| 视频与聊天 | 同步视频均匀抽帧、最多 24 帧及邻近帧重试。新增 ChatMessageMenu、ChatReplyQuote 和 ChatMediaSave。按气泡复制、语音文字切换、媒体保存、引用草稿、引用跳转、原话 sourcePrefix 及 daily 消息撤回处理均对应最终上游生产代码。 |

## 验证

本机使用 JDK 21、Android SDK、D 盘 Gradle 缓存。先执行完整构建，Android 测试编译暴露独立版私有根界面和 TLS 测试工具的依赖。改为通过宿主 Activity 测试真实聊天组件，并为视频抽帧保留可独立执行的设备回归。调整后完整命令成功，用时 1 分 56 秒，两个模块的 JVM 任务都实际执行。补充向量模型选择的落盘断言后，再次执行同一命令。

```powershell
.\gradlew.bat :lianxu:compileDebugKotlin :lianxu:compileDebugAndroidTestKotlin :app:compileDebugKotlin :lianxu:testDebugUnitTest --rerun :app:testDebugUnitTest :app:assembleDebug :lianxu:lintDebug
.\gradlew.bat :lianxu:assembleDebugAndroidTest
```

最终完整命令用时 8 秒，100 个任务中 5 个执行。恋叙 JVM 任务强制重跑，宿主 JVM 任务为最新。第二条命令用时 24 秒，51 个任务中 21 个执行，生成 instrumentation APK。

| 模块 | JUnit XML 报告 | 通过 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| lianxu | 29 | 163 | 0 | 0 | 0 |
| app | 367 | 3196 | 0 | 0 | 0 |

Lint 为 0 错误、26 告警，与第二批的数量及分类一致。UseKtx 14 条，UsableSpace 4 条，ExifInterface、LocalContextResourcesRead、StaticFieldLeak 各 2 条，ConfigurationScreenWidthHeight、ModifierParameter 各 1 条。

使用 Android SDK Build Tools 36.0.0 的 `aapt dump xmltree` 读取宿主 APK 内的 AndroidManifest.xml，核对权限属性、接收器及启动入口。宿主 Debug APK 为 79,851,517 字节。`aapt list` 确认测试 APK 含所需合成音频。`adb devices` 未发现已连接设备，Android 测试已编译打包。未在设备操作模型切换、长按手势、真实相册保存或试听，未调用真实供应商，未验证 Release 混淆。

## 变更文件

- `lianxu/src/androidTest/assets/voice/` 下的 `modulated-tone.mp3`。
- `lianxu/src/androidTest/java/app/lianxu/independent/` 下的 `BreezeVoiceEntryTest.kt`、`ChatImagesUiTest.kt`、`ChatMessageMenuUiTest.kt`、`ChatVideosAndroidTest.kt`、`CompleteBackupAndroidTest.kt`、`LearningRecordsUiTest.kt`、`MockChatFixture.kt`、`ModelConfigUiTest.kt`、`SettingsHierarchyTest.kt`、`ToolboxUiTest.kt`、`UpdatesMetadataUiTest.kt`。
- `lianxu/src/main/` 下的 `AndroidManifest.xml`。
- `lianxu/src/main/java/app/lianxu/independent/` 下的 `AppState.kt`、`BackupPageComponents.kt`、`BackupRepository.kt`、`BasicDiagnosticFields.kt`、`BreezeStudioScreen.kt`、`BreezeVoiceConnectSheet.kt`、`BreezeVoiceProviderList.kt`、`ChatBubbleSegments.kt`、`ChatImageRoute.kt`、`ChatImageUi.kt`、`ChatLedger.kt`、`ChatMediaSave.kt`、`ChatMessageMenu.kt`、`ChatReplyQuote.kt`、`ChatScreen.kt`、`ChatVideos.kt`、`ChatVoiceBubble.kt`、`CompanionPrompt.kt`、`CompanionRepository.kt`、`ConversationRunner.kt`、`DiagnosticExportDialog.kt`、`DiagnosticJournal.kt`、`LianxuActivity.kt`、`LocalUpdates.kt`、`ModelConfigScreen.kt`、`OrderedAssistantBubbles.kt`、`PencilNavigation.kt`、`ProcessEvidence.kt`、`ProcessingScreen.kt`、`PurposeOptions.kt`、`PurposeSelection.kt`、`RoleArchiveRepository.kt`、`RoleArchiveScreen.kt`、`SettingsDirectory.kt`、`ToolboxScreen.kt`、`UpdatesContent.kt`、`UpdatesPresentation.kt`、`VideoDelivery.kt`、`VideoPolicy.kt`、`VoiceLibrary.kt`。
- `lianxu/src/test/java/app/lianxu/independent/` 下的 `ChatMediaSaveTest.kt`、`ChatReplyQuoteTest.kt`、`DiagnosticJournalTest.kt`、`HostModelPolicyTest.kt`、`IncidentDiagnosticsTest.kt`、`UpdateVersionMatchTest.kt`、`VideoPolicyTest.kt`、`VideoWireTest.kt`。
- `openspec/changes/lianxu-upstream-batch-three/` 下的 `tasks.md`。
