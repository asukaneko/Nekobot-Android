# 恋叙模块第一批上游移植

目标分支为 `feat/lianxu-dual-ui`，起点为 `9cc6ce30`。按顺序移植 `bfdea5b`、`4550e66`、`cb5fb71`。对比上游这三个连续提交涉及的 32 个生产文件，26 个文件的内容或删除状态与 `cb5fb71` 一致，其余 6 个保留宿主差异。

## 宿主兼容核对

| 范围 | 核对结果 |
| --- | --- |
| Activity 获取 | 模块源码没有 `androidx.activity.compose.LocalActivity` 引用。模型配置页继续通过 `LocalContext.current.hostActivity()` 获取宿主。 |
| 请求和总结 | `ImageWire` 与该批上游最终版本一致。中转模型按名称匹配 token 字段，序列化请求只带一种上限字段。测试覆盖 o10 等易误匹配名称，非流式总结覆盖整份 JSON、逐行 JSON、失败、请求作废后迟到结果的丢弃及大小限制。 |
| 回顾模型和宿主 | 用途页通过 `hostPurposes` 识别宿主对话模型。即使向量镜像的名称没有体现类型或被传作当前选项，也不会出现在回顾选单里。空回顾用途沿用聊天模型。保留「来自 NekoBot」、宿主配置禁止编辑和删除、`HostModelSync.select/refresh`。 |
| 模型备份 | `ModelVault` 对所有用途使用同一导出规则，指向宿主的选择写为空，镜像模型及其密钥不进入备份。恢复拒绝镜像配置，读取镜像跳过 `liftLegacy`。JVM 覆盖用途清理；含密钥和不含密钥的实际 vault 导出另有设备测试源码。 |
| 后台任务 | `ReplyAccounting` 的 WorkManager tag 与 unique work 均保留 `lianxu_reply_accounting:`，执行前仍刷新宿主模型。回顾请求及迟到结果校验使用回顾模型。 |
| 角色备份 | `RoleDeletion.link` 默认可空，`ignored()` 过滤 `ROLE_MAP` 和 `FOLLOW_PREFIX`，模型校验排除宿主 ID。整库替换时读取旧 `localLinks`，按 `remapRestoredRole` 重建联动。 |
| 应用状态 | `AppState` 只调整三栏页面的返回来源。宿主角色 store、sync、duringRestore、前台同步、带 syncModels 参数的 reload、角色删除预览与联动删除均保留。 |
| 入口与导航 | 三个原入口各自打开对应页签。保留 `FragmentActivity`、宿主隐私锁、返回首页按钮与回调、全屏暖纸相遇页及联动删除文案。 |
| 界面文件 | 删除 `MemorySky.kt`，加入 `InnerWorldScreen.kt`、`LearningTemperature.kt`、`MemoryTimeline.kt`、`MoodHero.kt`，沿用已定稿界面。 |

## 本机验证

Windows PowerShell 使用 JDK 21 和本地 Android SDK。以测试 XML 合计，恋叙模块 12 份报告、55 项通过，宿主 367 份报告、3196 项通过，两组失败、错误、跳过均为 0。

```powershell
.\gradlew.bat :lianxu:compileDebugKotlin :lianxu:compileDebugAndroidTestKotlin :app:compileDebugKotlin :lianxu:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :lianxu:assembleDebugAndroidTest --offline --console=plain
.\gradlew.bat :lianxu:compileDebugKotlin :lianxu:compileDebugAndroidTestKotlin :app:compileDebugKotlin :lianxu:testDebugUnitTest :app:testDebugUnitTest
```

第一条强制执行两个 JVM 测试任务，并生成 instrumentation APK，耗时 1 分 8 秒，81 个任务中 13 个执行。第二条为指定命令原样复核，耗时 15 秒，55 个任务均为最新。两条均成功。测试 APK 清单包含 AndroidJUnitRunner、恋叙 Activity 和 Compose 测试 Activity。

ADB 设备列表为空。设备测试、三栏画面及手势的实际表现、真实供应商响应、lint 和 Release 混淆仍未验证。

## 变更文件

- `lianxu/` 下的 `build.gradle.kts`。
- `lianxu/src/androidTest/java/app/lianxu/independent/` 下的 `CompleteBackupAndroidTest.kt`、`HostModelBackupAndroidTest.kt`、`LearningRecordsAndroidTest.kt`、`LearningRecordsFixture.kt`、`LearningRecordsUiTest.kt`、`MemoryJsonDocumentAndroidTest.kt`、`MemoryUiSeedAndroidTest.kt`、`MemoryV2UiTest.kt`、`MockChatFixture.kt`、`ModelConfigUiTest.kt`、`MoodHeroUiTest.kt`、`RelationshipAndroidTest.kt`、`RelationshipUiAndroidTest.kt`、`ReplyAccountingAndroidTest.kt`、`RoleModelPayload.kt`。
- `lianxu/src/main/java/app/lianxu/independent/` 下的 `AdaptiveContext.kt`、`AppState.kt`、`BackupRepository.kt`、`ChatProtocol.kt`、`CompanionContracts.kt`、`CompanionRepository.kt`、`ImageWire.kt`、`InnerWorldScreen.kt`、`LearningRecords.kt`、`LearningRecordsScreen.kt`、`LearningTemperature.kt`、`LianxuActivity.kt`、`MemoryEngine.kt`、`MemoryExperience.kt`、`MemorySky.kt`、`MemoryTimeline.kt`、`ModelAccess.kt`、`ModelConfigScreen.kt`、`ModelVault.kt`、`MoodHero.kt`、`PageTabSwipe.kt`、`ProcessingScreen.kt`、`PurposeOptions.kt`、`PurposeSelection.kt`、`Relationship.kt`、`RelationshipPrompt.kt`、`RelationshipRepository.kt`、`RelationshipScreen.kt`、`ReplyAccounting.kt`、`ReviewBatchModel.kt`、`RolePlanner.kt`、`RoleRecordParser.kt`、`RoleStateProjection.kt`、`RoleUnderstanding.kt`。
- `lianxu/src/test/java/app/lianxu/independent/` 下的 `ExperiencePresentationTest.kt`、`HostModelPolicyTest.kt`、`ImageRequestBodyTest.kt`、`InnerWorldProjectionTest.kt`、`MemorySummaryResponseTest.kt`、`NetworkDiagnosticCorrelationTest.kt`、`RelationshipPromptTest.kt`、`ReplyAccountingTest.kt`、`RoleUnderstandingTest.kt`。
- `openspec/changes/lianxu-upstream-batch-one/` 下的 `tasks.md`。
