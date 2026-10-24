# 实施与验证

- [x] 核实原贡献分支与干净基线393e5300，保留可恢复Git bundle；隔离目录修改。
- [x] 采用作者分组长列表组织更多和设置，暂停旧五分类首页，保留旧路由兼容及全部业务入口。
- [x] 保留返回位置、防重复跳转、整行开关无障碍和错误清理；余额及Kimi入口保留，合入上游230842fe的Kimi Code授权支持。
- [x] 盘点内置提示词及拼接、保存、结构化结果边界，只修改默认说话与默认思考文案；非空自定义全文保持。
- [x] 全量JVM 2946项通过，0失败、0错误、0跳过；编译及标准Debug构建通过。
- [x] Lint通过：0错误、911警告、36提示；未屏蔽检查。
- [x] API35/x86_64专用合成模拟器28项设备回归通过。首轮27/28，唯一失败为Agent设置标题和入口同名的测试定位歧义；改用可点击节点定位后相关5项全部通过，没有更改产品行为或删减断言。
- [x] 设备覆盖作者列表全部导航入口、本地/服务器可见性、退出、整行开关、保存状态与返回滚动位置，以及余额反复进入、角色记忆目录、提示词保存/重开/导入及编辑恢复。
- [x] 仓库外个人补丁git apply --check通过；不修改配置、签名、版本策略或个人正式APK。
- [ ] 真机、真实模型自然度、长期陪伴效果和真实Kimi/余额服务由后续独立验收；本轮未调用这些服务。

统一命令：`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest`，随后`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。首轮提示词测试有一条旧文案断言失效，更新为新默认要求后全量通过；失败记录保留。

设备测试通过仓库外init脚本排除ARM专用终端库，正式源码未删库，不能据此宣称ARM工具通过。28项由MoreHomeScreenTest、SettingsHomeScreenTest、AuthorLayoutNavigationTest、AiBalanceNavigationTest、AgentCharacterMemoryDirectoryTest、CompanionPromptStoreTest、CompanionPromptSettingsUiTest组成。旧分类组件测试继续覆盖兼容性，实际首页另由作者列表测试覆盖。

合成的六类对话只证明请求构造、默认单份注入、原话和气泡协议保留，不证明模型一定更自然。此前保存的旧默认全文继续视作非空自定义，不强制升级；空值跟随新版默认。原生思考流、内联独白、工具与记忆JSONL协议未改。

本地证据：工作任务目录`author-layout-20261024`中的编译、全量测试、Lint/构建、设备首轮与复跑日志。最终提交、PR正文、精确head CI与截图交付状态记录于PR31和工作区交接记录；不以旧head的CI代替本轮结果。
