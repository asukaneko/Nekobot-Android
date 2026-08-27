# NekoBot Android 插件开发指南

NekoBot Android 支持在**本地模式**安装 ZIP 格式的 JavaScript 插件。插件通过 `plugin.json`
声明命令和权限，由受限 WebView 运行时执行。

> 插件命令只在本地模式运行。服务器模式下可以查看、安装、启停或卸载插件，但不会执行其命令。

## 1. 快速开始

创建以下两个文件：

```text
hello-plugin/
├── plugin.json
└── main.js
```

`plugin.json`：

```json
{
  "api_version": 1,
  "id": "hello-plugin",
  "name": "Hello Plugin",
  "version": "1.0.0",
  "author": "Your Name",
  "description": "一个最小的 NekoBot Android 插件示例。",
  "entry": "main.js",
  "permissions": ["storage", "notify"],
  "commands": [
    {
      "name": "hello",
      "aliases": ["hi"],
      "usage": "/hello [名字]",
      "description": "打招呼并记录调用次数"
    }
  ]
}
```

`main.js`：

```js
NekoPlugin.registerCommand("hello", async (ctx) => {
  const name = ctx.args[0] || "朋友";
  const previous = (await ctx.api.storage.get("visits")) || 0;
  const visits = Number(previous) + 1;

  await ctx.api.storage.set("visits", visits);
  await ctx.api.notify("Hello Plugin 已执行");
  return `你好，${name}。这是第 ${visits} 次调用。`;
});
```

将**文件内容**打包到 ZIP 根目录。不要把外层目录一并打入压缩包：

```powershell
Compress-Archive -Path plugin.json, main.js -DestinationPath hello-plugin.zip
```

在应用的「更多 -> 扩展功能 -> 插件」页面点击右上角加号，选择 ZIP 文件，阅读并确认第三方插件提示后安装。进入本地模式的会话，输入 `/hello Neko` 即可测试。

## 2. 包结构

ZIP 根目录必须包含 `plugin.json`，入口脚本由 `entry` 指定。可以包含其他资源文件：

```text
weather-plugin.zip
├── plugin.json
├── main.js
└── data/
    └── cities.json
```

当前运行时只加载 `entry` 指定的 JavaScript 文件；其他文件不会自动加载。插件不能读取本地文件系统，因此需要的静态数据应直接写入入口脚本，或在构建时合并进入口脚本。

## 3. plugin.json

```json
{
  "api_version": 1,
  "id": "example.plugin",
  "name": "示例插件",
  "version": "1.0.0",
  "author": "开发者名称",
  "description": "插件简介",
  "entry": "main.js",
  "permissions": ["storage", "chat.read", "notify", "network"],
  "commands": [
    {
      "name": "example",
      "aliases": ["ex"],
      "usage": "/example <参数>",
      "description": "命令说明"
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `api_version` | 是 | 当前仅支持 `1`。 |
| `id` | 是 | 2-64 位；必须以 ASCII 字母开头，后续只允许字母、数字、`.`、`_`、`-`。安装后不能与内置插件 ID 冲突。 |
| `name` | 是 | 插件显示名称，最多 80 个字符。 |
| `version` | 是 | 插件版本，最多 32 个字符。 |
| `author` | 否 | 开发者名称。 |
| `description` | 否 | 插件说明。 |
| `entry` | 是 | ZIP 内的相对 `.js` 路径，默认 `main.js`。不能使用绝对路径、`..` 或 Windows 驱动器路径。 |
| `permissions` | 否 | 需要使用的权限列表，见下一节。未声明的能力会被拒绝。 |
| `commands` | 是 | 至少一条，最多 64 条命令。 |

### 命令字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 主命令名，不含 `/`。必须以 ASCII 字母开头，最多 32 位，仅允许字母、数字、`_`、`-`。运行时会转成小写。 |
| `aliases` | 否 | 最多 8 个别名；可带或不带 `/`，会被规范为小写的 `/命令`。 |
| `usage` | 否 | 帮助中显示的用法，最多 160 个字符。 |
| `description` | 否 | 帮助和插件页显示的说明，最多 500 个字符。 |

主命令、别名、现有内置命令和所有已安装插件的命令都不能重名。停用插件的命令也会保留名称，避免重新启用后发生冲突。

## 4. 权限与 API

JavaScript 只能通过 `ctx.api` 调用宿主能力。每个方法都返回 `Promise`，应使用 `await`。

| 清单权限 | API | 返回值与限制 |
| --- | --- | --- |
| `chat.read` | `ctx.api.getSession()` | 当前会话记录。 |
| `chat.read` | `ctx.api.getMessages(limit)` | 当前会话最后的消息列表；`limit` 默认为 30，范围为 1-100。 |
| `storage` | `ctx.api.storage.get(key)` | 读取当前插件的 JSON 值；不存在时返回 `null`。 |
| `storage` | `ctx.api.storage.set(key, value)` | 写入当前插件的 JSON 值，返回 `true`。 |
| `storage` | `ctx.api.storage.remove(key)` | 删除当前插件的键，返回 `true`。 |
| `storage` | `ctx.api.storage.list()` | 返回当前插件全部键值组成的对象。 |
| `notify` | `ctx.api.notify(message)` | 显示短 Toast，消息最多 500 个字符，返回 `true`。 |
| `network` | `ctx.api.httpGet(url)` | 仅允许 `https://` 的 GET 请求，返回 `{ status, body }`；响应正文最多 512 KiB。 |

存储按插件 ID 隔离。键不能为空、最多 128 个字符，且不能包含换行。卸载插件会删除它自己的存储数据。

网络能力必须通过 `ctx.api.httpGet()` 使用。运行时禁止直接使用 `fetch`、XHR、WebSocket、图片加载或页面导航访问网络，因此这些方式不是可用的网络接口。

下面是一个需要 `network` 权限的示例：

```js
NekoPlugin.registerCommand("status", async () => {
  const response = await NekoPlugin.api.httpGet("https://example.com/status.json");
  if (response.status !== 200) {
    return `请求失败：HTTP ${response.status}`;
  }
  return response.body;
});
```

## 5. JavaScript 运行模型

入口脚本会在每次命令调用时重新执行。脚本应注册与 `plugin.json` 中主命令同名的处理器：

```js
NekoPlugin.registerCommand("command-name", async (ctx) => {
  return "要发送到聊天中的结果";
});
```

也可一次注册多个处理器：

```js
NekoPlugin.register({
  commands: {
    first: async () => "第一个命令",
    second: async () => "第二个命令"
  }
});
```

命令名会忽略开头的 `/` 并转成小写。别名最终仍会调用对应主命令的处理器。

处理器接收的 `ctx`：

| 字段 | 说明 |
| --- | --- |
| `pluginId` / `pluginName` | 当前插件信息。 |
| `command` | 用户输入的命令或别名，带 `/`。 |
| `handler` | 清单中注册的主命令名。 |
| `args` | 按空白字符拆分后的参数数组。 |
| `argsText` | 未拆分的原始参数文本。 |
| `raw` | 完整原始命令文本。 |
| `sessionId` | 当前本地会话 ID。 |
| `appMode` | 固定为 `LOCAL`。 |
| `api` | 与 `NekoPlugin.api` 相同的受控 API。 |

处理器可返回字符串，也可返回可 JSON 序列化的对象；对象会作为 JSON 文本发送到聊天。返回 `null` 或 `undefined` 时发送空结果。抛出异常或 Promise 拒绝会显示为命令执行失败。

单次执行最长 20 秒，最终回复最多 20,000 个字符。不要在模块顶层启动长期循环，也不要依赖上一次执行留下的 JavaScript 内存状态；需要持久化的数据应使用 `storage`。

## 6. 安全边界

第三方插件代码被视为不可信代码。运行时具有以下边界：

- 禁止文件访问、内容提供器访问、DOM Storage、多窗口和页面导航。
- 禁止 WebView 自行联网；网络只能通过声明了 `network` 权限的受控 HTTPS GET API。
- 没有 `chat.write`、任意命令执行、相机、麦克风或 Android Intent 等 API。
- 不能访问其他插件的存储数据。
- 用户安装 ZIP 前必须明确接受第三方插件风险提示。

开发时只申请实际需要的权限，并在 `description` 中解释读取会话或联网的原因。不要直接调用 `NekoAndroid` 等运行时内部对象；它们不是稳定的插件 API。

## 7. 安装校验与大小限制

安装器会拒绝不安全路径、重复文件、重复命令和超出限制的包：

| 项目 | 限制 |
| --- | --- |
| ZIP 压缩包 | 16 MiB |
| 解压后总大小 | 32 MiB |
| ZIP 条目数 | 128 |
| `plugin.json` | 128 KiB |
| 单个 JavaScript 文件 | 512 KiB |
| 入口脚本文本 | 262,144 个字符 |
| 单个其他资源 | 4 MiB |

如果安装失败，应用会显示具体的清单、命令冲突、路径或大小错误。命令运行错误会直接作为本地会话中的命令结果显示。

## 8. 内置插件与兼容性

`builtin.jm`（JM 漫画）和 `builtin.light-novel`（轻小说）是应用随附的内置插件。它们可以停用，但不能卸载、覆盖或作为第三方 ZIP 的 `id` 使用。

第三方插件 API 当前为 v1。升级应用后，请重新验证插件的清单、权限和命令是否仍符合本指南；未兼容的 `api_version` 会在安装时被拒绝。
