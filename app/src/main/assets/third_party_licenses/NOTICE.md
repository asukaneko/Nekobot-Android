# Linux Sandbox Third-Party Notice

The Agent-mode Linux sandbox contains:

- OpenMinis PRoot fork at revision
  `8cf13e997cdc9472997aae19df8050c073c9a86c`, licensed GPL-2.0-only.
- talloc code linked into PRoot, licensed LGPL-3.0-or-later.
- Alpine Linux 3.21.3 aarch64 minirootfs, containing packages under
  GPL-2.0-only, GPL-2.0-or-later, MIT, BSD-2-Clause, Apache-2.0, MPL-2.0,
  and Zlib licenses.

Source and provenance:

- https://github.com/OpenMinis/OpenMinis/tree/9cf3a855fecd27bb5735b84cacbd56852a3ab8dd
- https://github.com/OpenMinis/proot/tree/8cf13e997cdc9472997aae19df8050c073c9a86c
- https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/

The complete license texts are stored beside this notice.

## 单会话记忆离线时间词库

Kotlin/java.time 引擎按本项目的自然周、说话当年和模糊日期不猜测约定独立实现，
未引入外部运行时、模型或联网组件。日期表达与回归用例参考/改编自 Microsoft
Recognizers-Text 的 DateParser 与 DateTimeModel Specs：

- https://github.com/microsoft/Recognizers-Text/tree/da7edcff59f669b2a460ab9d400e36298f0d658e/Specs/DateTime
- Copyright (c) Microsoft Corporation. All rights reserved.
- MIT；完整原版权与许可见 Recognizers-Text-MIT.txt。
- 筛选按支持语法和单日类型进行，不按运行答案挑题；缺年按说话当年，显式周词按自然周。
  入选数量、跳过原因、原文件SHA256见测试资源 timewords/selection.json。

BSD-3-Clause.txt 是保留的许可证标准文本（SPDX license-list-data）；当前引擎未改编
BSD规则代码。没有使用 Time-NLP、xk-time、HeidelTime、Stanford CoreNLP 或 Zoho Hawking。
