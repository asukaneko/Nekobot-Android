# Android 15 设备测试

## 复现与修复

- CI 运行 `45919949267` 使用 API 35、x86_64、320×640、160 dpi，三项系统动画关闭。Gradle 同时执行宿主编译和模块设备测试。报告收到 42 个完整结果，第 43 项关系页用例只有 FAILED 标记，随后 ADB 报设备消失。日志没有该用例的断言栈，也没有内核 OOM 记录。
- 本机 `lianxu-test35` 首轮使用 SwiftShader，收到 28 个完整结果后 QEMU 退出。Windows 事件记录异常码 `0xc0000005`。后续改用本机 GPU，关闭 Vulkan，在同一台 AVD 跑完全套。
- 模块测试 APK 原先默认沿用 minSdk 26 作为 targetSdk。Android 15 的旧版应用警告窗占据输入焦点，ClipboardService 拒绝测试 Activity 读取剪贴板，真实返回键也到不了目标页面。保留全部剪贴板、跳转和系统返回断言，单独将测试 APK 的 targetSdk 对齐宿主 35。原分辨率对照从 58/65 提升到 65/65。额外暴露的五项系统返回失败位于 SettingsHierarchyTest（三项）和 ToolboxUiTest（两项）。
- 对齐 CI 的屏幕尺寸后，试听台词弹层在输入法关闭后还可停靠在半高位置。测试按可见状态处理这三层返回，并检查草稿仍被丢弃。连接表单在提交前使用键盘的“完成”动作让出焦点，模拟服务按提交的合成密钥返回验证结果。暗色图标像素测试使用与宿主页面一致的安全区，保留七个图标和原有对比度阈值。
- CI 在启动模拟器前编译 Debug、两个仪器测试 APK 和临时签名的 Release。设备阶段顺序运行两个模块，最终保留五次 Release 冷启动检查。Gradle 堆上限从 6 GiB 降到 4 GiB，关闭项目并行，两个模块分别保留设备报告。两个模块的八处截图在写入结束或抛错时释放位图。

## 本机验证

- 使用 JDK 21 及 Android SDK 的 API 35 x86_64 AVD `lianxu-test35`，检查三项动画比例均为 0。AVD 原尺寸为 1080×2400、420 dpi，并额外按 CI 的 320×640、160 dpi 重测。
- `.\gradlew.bat :lianxu:connectedDebugAndroidTest` 在两种尺寸均完成全量 65 项。CI 尺寸的关系页手势用例另用旧测试 APK 配置（targetSdk 26）做单项对照，1/1 通过；没有复现独立的手势断言失败。
- 本地 AVD 已装有宿主应用，本次只在该设备安装隔离的模块测试 APK。宿主设备套件及临时签名 Release 的五次启动由 CI 脚本保留，未在此 AVD 实跑。未重新触发 GitHub Actions。
- 最终 CI 尺寸连续两轮全量通过，Gradle 分别用时 4 分 46 秒和 4 分 44 秒。最终设备 XML 有 65 个用例，0 失败、0 错误、0 跳过。
- `.\gradlew.bat :lianxu:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :app:assembleDebug :app:compileDebugAndroidTestKotlin :lianxu:lintDebug` 构建成功，用时 1 分 32 秒。模块 JVM 163/163，宿主 JVM 3196/3196，均为 0 失败、0 错误、0 跳过。模块 Lint 为 0 错误、26 告警，与移植基线分类及数量一致。
- `bash -n scripts/ci/android-smoke.sh` 通过。用替身命令分别模拟设备测试成功和失败，验证两模块顺序、五次启动调用、临时签名清理及非零退出码传播。已恢复 AVD 原始分辨率与密度并关闭模拟器。
