# 恋叙模块第二批移植验收

从 `88dbc3a8` 开始按顺序移植 `3a6244a`、`bdab8bf`、`384e5e0`、`f262af0`。上游共涉及 42 个生产文件，按 LF 归一化后，32 个与 `f262af0` 逐字节相同，其余 10 个为保留宿主行为或命名所需的差异。第三批起点 `1f65675` 留待后续。

## 桥接核对

| 范围 | 结果 |
| --- | --- |
| Activity 与库清单 | 模块源码没有 LocalActivity 或独立版 MainActivity 引用。模型设置继续用 LocalContext.current.hostActivity()。库内三个组件均为非导出。新增 RECEIVE_BOOT_COMPLETED 权限与 ContactWakeReceiver 的 BOOT_COMPLETED 过滤器；宿主合并清单唯一 launcher 是 com.nekobot.app.MainActivity。 |
| 日程与角色删除 | Agenda 与 CompanionWorker 共用角色标签 lianxu_companion:<角色>。普通及闹钟日程名称为 lianxu_agenda:<角色>:<版本>:<到期>:<上次用户消息>:<是否闹钟>；重排任务名 lianxu_contact_wake_reconcile。角色删除仍等待 cancelAllWorkByTag 返回的 Operation.result.get()。新增 JVM 命名测试和调用真实 WorkManager 的双角色设备回归源码。 |
| 后台凭证 | DailyAgendaWorker 在角色互斥锁内创建绑定 store 的 ModelVault，先 HostModelSync.refresh，再把同一 vault 的 ModelAccess 传入 planner.run。CompanionWorker 和 ReplyAccounting 的刷新保留。 |
| 通知与后台工作 | 主动联系 uniqueWork 保留 lianxu_companion: 前缀和即时唤醒的 :alarm 后缀。通知仍使用 lianxu_companion_messages，并由 LianxuActivity.createIntent 打开宿主模块。后台引导和系统通知文案均称「本应用」。 |
| 聊天与页面 | 聊天页随生命周期及角色变化配对调用 foreground/leave。SoftPage 包裹页面、底部 Tab 和模型配置面板；InnerWorldScreen 接收当前过渡路由。保留 FragmentActivity、HostPrivacyGate、returnToHost、ReturnHomeButton、全屏暖纸 FirstEncounterDoor 与角色联动删除提示。 |
| 状态与备份 | 保留 LianxuModule.roles.store、sync、duringRestore、前台同步及角色预览/删除；加入 quietHours、saveQuietHours、goingBack。QuietHours.KEY 纳入全库备份校验；daily: 机会恢复允许空 source_id 并按新 ID 重映射。RoleDeletion.link、ROLE_MAP/FOLLOW_PREFIX 过滤、拒绝宿主模型配置、localLinks 与 remapRestoredRole 均保留。 |
| 模型与轻回顾 | 保留 hostPurposes、只读的「来自 NekoBot」标识、宿主模型选择与备份隔离。ReplyAccounting 保留 lianxu_reply_accounting: 前缀，并移除重复经历向量索引调用。 |
| 学习与心情 | 使用上游学习列表、详情、恢复逻辑及柔化心情头图。补充淡去的印象重新参与聊天与冷却计时和心情呈现测试，设备用例改用宿主 LianxuActivity 和项目现有 Compose 测试入口。 |
| 依赖目录 | android-json 版本及坐标迁入 libs.versions.toml。Lint 报告中 UseTomlInstead 为 0。 |

## 构建与测试

使用 PowerShell 5.1、D 盘 JDK 21、Android SDK 及 Gradle 缓存执行以下命令。

```powershell
.\gradlew.bat :lianxu:compileDebugKotlin :lianxu:compileDebugAndroidTestKotlin :app:compileDebugKotlin :lianxu:testDebugUnitTest --rerun :app:testDebugUnitTest :app:assembleDebug :lianxu:lintDebug
```

最终结果为 BUILD SUCCESSFUL in 1m 54s；100 actionable tasks: 22 executed, 78 up-to-date。

| 模块 | JUnit XML 报告 | 通过 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| lianxu | 22 | 125 | 0 | 0 | 0 |
| app | 367 | 3196 | 0 | 0 | 0 |

Lint 共 0 错误、26 告警。按检查项计数为 ConfigurationScreenWidthHeight 1、ExifInterface 2、LocalContextResourcesRead 2、ModifierParameter 1、StaticFieldLeak 2、UsableSpace 4、UseKtx 14。原先基线报告为 27 条告警，其中含 1 条 UseTomlInstead。已构建宿主 Debug APK。

首轮 Android 测试编译发现上游 junit4.v2 API 与宿主依赖不兼容，已改为仓库现有的 junit4 API。第二次构建遇到 Windows classes.jar 占用；用 Restart Manager 定位到本项目缓存中的空闲 Gradle daemon，执行 `.\gradlew.bat --status` 和 `.\gradlew.bat --stop` 后验证锁已释放，再重跑。随后将新电池引导中的 URI 构造改用等价的 Kotlin 扩展，重新执行完整命令。

`adb devices` 未列出设备。Android 测试已编译，未上机运行。开机广播、系统省电设置入口、实际后台唤醒、角色取消的 WorkManager 设备回归、页面动效及触控体验尚未在设备验证。真实模型与 Release 混淆未测。

## 变更文件清单

- `gradle/` 下的 `libs.versions.toml`。
- `lianxu/` 下的 `build.gradle.kts`。
- `lianxu/src/androidTest/java/app/lianxu/independent/` 下的 `CompleteBackupAndroidTest.kt`、`ContactWakeAndroidTest.kt`、`LearningRecordsStatesUiTest.kt`、`LearningRecordsUiTest.kt`、`MoodHeroUiTest.kt`、`RelationshipUiAndroidTest.kt`、`ToolboxUiTest.kt`。
- `lianxu/src/main/` 下的 `AndroidManifest.xml`。
- `lianxu/src/main/java/app/lianxu/independent/` 下的 `AppState.kt`、`BackupRepository.kt`、`ChatBubbleSegments.kt`、`ChatLedger.kt`、`CompanionBackground.kt`、`CompanionChatUi.kt`、`CompanionModel.kt`、`CompanionRecord.kt`、`CompanionRepository.kt`、`ContactBatteryGuide.kt`、`ContactFailures.kt`、`ContactWake.kt`、`ConversationRunner.kt`、`DailyContact.kt`、`DailyPlanner.kt`、`ImmediateContact.kt`、`InnerWorldScreen.kt`、`LearningRecordsScreen.kt`、`LearningTemperature.kt`、`LianxuActivity.kt`、`MemoryEngine.kt`、`MemoryExperience.kt`、`MemoryTools.kt`、`ModelConfigScreen.kt`、`MoodHero.kt`、`ProactiveMaterial.kt`、`QuietHoursSettings.kt`、`RelationshipDecay.kt`、`RelationshipParameters.kt`、`RelationshipProactive.kt`、`RelationshipRepository.kt`、`RelationshipScreen.kt`、`ReplyAccounting.kt`、`ReplyDelivery.kt`、`ReviewBatchModel.kt`、`RolePlanner.kt`、`RoleRecordParser.kt`、`SoftMotion.kt`、`Store.kt`、`ToolArgumentDiagnosis.kt`、`UiComponents.kt`。
- `lianxu/src/test/java/app/lianxu/independent/` 下的 `ChatBubbleSegmentsTest.kt`、`CompanionTimeReferenceTest.kt`、`ContactContinuationTest.kt`、`ContactExposureTest.kt`、`ContactToolContractTest.kt`、`DailyContactTest.kt`、`InnerWorldProjectionTest.kt`、`OrderedChatReplyTest.kt`、`ReplyContractWordingTest.kt`、`ReplyDeliveryTest.kt`、`ReplyPacingTest.kt`。
- `openspec/changes/lianxu-upstream-batch-two/` 下的 `tasks.md`。
