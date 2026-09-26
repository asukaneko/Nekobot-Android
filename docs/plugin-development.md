{% raw %}
# NekoBot Android 插件开发指南

NekoBot Android 支持在**本地模式**安装 ZIP 格式的 JavaScript 插件。插件通过 `plugin.json`
声明命令、权限和可选页面，由受限 WebView 运行时执行。

> 插件命令与插件页面只在本地模式运行。服务器模式下可以查看、安装、启停或卸载插件，但不会执行其命令。

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
  "api_version": 2,
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

在应用的「更多 -> 扩展功能 -> 插件」页面点击右上角加号，选择 ZIP 文件，阅读并确认第三方插件提示与权限清单后安装。进入本地模式的会话，输入 `/hello Neko` 即可测试。

## 2. 包结构

ZIP 根目录必须包含 `plugin.json`，入口脚本由 `entry` 指定。可以包含其他资源文件（页面、样式、脚本、图片等）：

```text
weather-plugin.zip
├── plugin.json
├── main.js
├── data/
│   └── cities.json
└── pages/
    ├── weather.html
    ├── weather.css
    └── weather.js
```

命令运行时只加载 `entry` 指定的 JavaScript 文件；插件页面运行时（第 9 章）会加载页面 HTML 及其相对引用的资源。插件不能读取本地文件系统；声明 `workspace` 权限后只能读写工作区中本插件的专属文件夹（见第 4 章「工作区文件」），声明 `files` 权限后只能访问本插件私有目录中用户通过文件选择器上传的文件（见第 4 章「插件私有文件」），静态数据应写入插件包内或在构建时合并。

## 3. plugin.json

```json
{
  "api_version": 2,
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
  ],
  "pages": [],
  "hooks": []
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `api_version` | 是 | 当前支持 `1` 与 `2`。旧版 App 会拒绝高于自身版本的插件。 |
| `id` | 是 | 2-64 位；必须以 ASCII 字母开头，后续只允许字母、数字、`.`、`_`、`-`。安装后不能与内置插件 ID 冲突。 |
| `name` | 是 | 插件显示名称，最多 80 个字符。 |
| `version` | 是 | 插件版本，最多 32 个字符。 |
| `author` | 否 | 开发者名称。 |
| `description` | 否 | 插件说明。 |
| `entry` | 是 | ZIP 内的相对 `.js` 路径，默认 `main.js`。不能使用绝对路径、`..` 或 Windows 驱动器路径。 |
| `permissions` | 否 | 需要使用的权限列表，见下一节。未声明或未授权的能力会被拒绝。 |
| `commands` | 否 | 最多 64 条命令；与 `hooks` 至少要有其一。 |
| `pages` | 否 | 插件页面声明，最多 8 个，见第 9 章。 |
| `hooks` | 否 | 事件钩子声明，最多 4 个：`message.beforeSend`、`app.lifecycle`，见第 5 章「事件钩子」。 |

### 命令字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 主命令名，不含 `/`。必须以 ASCII 字母开头，最多 32 位，仅允许字母、数字、`_`、`-`。运行时会转成小写。 |
| `aliases` | 否 | 最多 8 个别名；可带或不带 `/`，会被规范为小写的 `/命令`。 |
| `usage` | 否 | 帮助中显示的用法，最多 160 个字符。 |
| `description` | 否 | 帮助和插件页显示的说明，最多 500 个字符。 |
| `open_page` | 否 | 页面 id。设置后该命令**直接打开对应插件页面**（不执行 JS 处理器）；页面必须在 `pages[]` 中已声明。 |

主命令、别名、现有内置命令和所有已安装插件的命令都不能重名。停用插件的命令也会保留名称，避免重新启用后发生冲突。

## 4. 权限与 API

JavaScript 只能通过 `ctx.api` 调用宿主能力。每个方法都返回 `Promise`，应使用 `await`。

| 清单权限 | API | 返回值与限制 |
| --- | --- | --- |
| `chat.read` | `ctx.api.getSession()` | 当前会话记录。 |
| `chat.read` | `ctx.api.getMessages(limit)` | 当前会话最后的消息列表；`limit` 默认为 30，范围为 1-100。 |
| `chat.read` | `ctx.api.contextUsage(options?)` | 当前会话上下文占比与类型明细，见下文「会话洞察」。 |
| `chat.read` | `ctx.api.sessionConfig(options?)` | 当前会话的配置启用与配置情况，见下文「会话洞察」。 |
| `chat.read` | `ctx.api.promptStack(options?)` | 提示词注入栈（最近一轮快照），见下文「会话洞察」。 |
| `chat.read` | `ctx.api.toolCalls(options?)` | 工具调用记录，`limit` 默认 50、最大 200，见下文「会话洞察」。 |
| `storage` | `ctx.api.storage.get(key)` | 读取当前插件的 JSON 值；不存在时返回 `null`。 |
| `storage` | `ctx.api.storage.set(key, value)` | 写入当前插件的 JSON 值，返回 `true`。 |
| `storage` | `ctx.api.storage.remove(key)` | 删除当前插件的键，返回 `true`。 |
| `storage` | `ctx.api.storage.list()` | 返回当前插件全部键值组成的对象。 |
| `notify` | `ctx.api.notify(message)` | 显示短 Toast，消息最多 500 个字符，返回 `true`。 |
| `chat.progress` | `ctx.api.progress(options)` | 更新当前命令的进度卡片，见下文「进度卡片」。 |
| `network` | `ctx.api.httpGet(url)` | 仅允许 `https://` 的 GET 请求，返回 `{ status, body }`；响应正文最多 512 KiB。 |
| `ai.call` | `ctx.api.aiComplete(options)` | 走聊天故障转移队列的单次生成，返回 `{ content, model, usage }`；见下文「AI 调用」。 |
| `chat.write` | `ctx.api.appendMessage(options)` | 往会话追加一条消息（默认当前会话），返回 `{ id, sessionId, role, createdAt }`。 |
| `chat.write` | `ctx.api.sendMessage(options)` | 发送用户消息并触发一次完整回复（后台生成），返回 `{ messageId, sessionId, replyPending }`。 |
| `chat.write` | `ctx.api.createSession(options)` / `switchSession(id)` | 新建会话 / 切换到指定会话。 |
| `characters.write` | `ctx.api.createCharacter(options)` / `updateCharacter(options)` | 创建 / 修改角色卡，返回 `{ id, name, updatedAt }`；**没有删除 API**。 |
| `memory.write` | `ctx.api.memoryWrite/append/edit(...)` | 覆盖/追加/替换 Agent 长期记忆，返回 `{ charCount }`。 |
| `memory.read` | `ctx.api.memoryRead()` | 读取 Agent 长期记忆，返回 `{ content, charCount }`。 |
| `workspace` | `ctx.api.workspace.save/list/read/delete(...)` | 读写工作区中本插件的专属文件夹，见下文「工作区文件」。 |
| `files` | `ctx.api.files.list/read/delete(...)` | 访问本插件私有目录中用户上传的文件，见下文「插件私有文件」。 |
| 免 | `ctx.api.render(template, data)` | 内置模板渲染（Handlebars 子集），返回 HTML 字符串。 |

### 权限分级与用户授权

| 分组 | 权限 | 默认 |
| --- | --- | --- |
| 基础 | `storage`、`notify`、`chat.progress`、`files` | 安装时默认勾选 |
| 读取 | `chat.read`、`characters.read`、`worldbooks.read`、`memory.read` | 安装时默认勾选 |
| 网络 | `network` | **需用户手动授权** |
| 写入 | `chat.write`、`memory.write`、`characters.write`、`workspace` | **需用户手动授权** |
| AI | `ai.call` | **需用户手动授权** |

调用宿主 API 必须同时满足「清单已声明」与「用户已授权」。危险权限（网络/写入/AI）在 AI 创建或安装插件时**不会自动授予**，用户需要在插件卡片的「权限」入口勾选；未授权时调用会失败并返回 `permission_denied`（错误对象含 `code` 字段）。

写入类 API 会直接改动用户数据（会话消息、Agent 记忆），因此授权前请确认插件来源可信。

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

### AI 调用（`ai.call`）

插件可以在**用户授权后**调用聊天模型，请求走「聊天功能模型」的故障转移队列（与自动记忆、剧情选项等后台任务同一条队列）：队列顺序、模型冷却、token 限额与超时策略都由 App 统一执行，插件不能自选模型，队列本身就是模型白名单。

```js
NekoPlugin.registerCommand("polish", async (ctx) => {
  if (!ctx.argsText) return "用法：/polish <文本>";
  const result = await ctx.api.aiComplete({
    messages: [
      { role: "system", content: "你是文字润色助手，只输出润色后的文本。" },
      { role: "user", content: ctx.argsText }
    ],
    maxTokens: 512
  });
  return result.content;
});
```

`options` 字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `messages` | 是 | 对话数组，最多 16 条；每项 `{role, content}`，`role` 只能是 `system`/`user`/`assistant`。单条 ≤ 8000 字符，总长度 ≤ 24000 字符。 |
| `maxTokens` | 否 | 最大生成 token 数，默认 512，上限 2048。 |
| `temperature` | 否 | `0`-`2`，省略时用模型默认值。 |

返回 `{ content, model, usage: { input, output } }`。

限额与失败原因（错误对象含 `code`）：

| 情况 | `code` | 说明 |
| --- | --- | --- |
| 未授权 `ai.call` | `permission_denied` | 用户在插件「权限」入口勾选后即可用 |
| 设置里关闭了「Agent 网络访问」 | `network_disabled` | 与 `httpGet` 共用同一总开关 |
| 超过频率上限 | `ai_rate_limited` | 每个插件每分钟最多 10 次 |
| 超过用量上限 | `ai_budget_exceeded` | 每个插件每小时最多 20 万 token（输入+输出） |
| 队列全部失败/未配置模型 | `ai_failed` | 请用户检查故障转移队列 |

命令运行时调用 AI 时，单次命令总超时放宽到 120 秒（普通命令仍为 20 秒）；页面侧 `host.ai.complete` 单次调用超时 120 秒。

### 会话写入（`chat.write`）

`ctx.api.appendMessage({ sessionId?, role?, content })`（页面侧 `host.chat.messages.append(...)`）往会话追加一条消息：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `sessionId` | 否 | 默认当前会话；命令运行时为发起会话，页面为打开页面时的会话 |
| `role` | 否 | `user`（默认）或 `assistant`，其它值报 `invalid_argument` |
| `content` | 是 | 消息正文，最多 8000 字符 |

返回 `{ id, sessionId, role, createdAt }`。写入与正常消息同一条路径：会话预览、消息数与更新时间都会同步刷新，消息来源标记为 `plugin:<插件 id>`。

只提供「追加」，没有删除或修改消息的 API。需要「发消息并让模型回复」时，用 `appendMessage` 写入用户消息，再 `aiComplete` 生成回复，最后 `appendMessage({ role: "assistant" })` 写回——生成完全由插件显式控制，App 不会隐式触发对话。

失败原因：`permission_denied`（未授权 `chat.write`）、`not_found`（会话不存在）、`chat_write_failed`（落库失败）。

### 记忆写入（`memory.write`）

`ctx.api.memoryRead()` / `memoryWrite(content)` / `memoryAppend(content)` / `memoryEdit(oldText, newText)`（页面侧 `host.memory.read/write/append/edit`）读写「全局 Agent 记忆」——与设置里的 Agent 记忆是同一份内容。

| 调用 | 说明 |
| --- | --- |
| `memoryWrite(content)` | 整体覆盖记忆 |
| `memoryAppend(content)` | 追加到记忆末尾（自动空行分隔） |
| `memoryEdit(oldText, newText)` | 替换记忆中的一段文本；`oldText` 必须唯一命中 |

全部返回 `{ charCount }`（写入后的总字符数）；读取返回 `{ content, charCount }`（`content` 最多 32000 字符）。整体上限 32000 字符，超出报 `invalid_argument`；`memoryEdit` 未命中或命中多处同样报 `invalid_argument`。

维护记忆的推荐写法是先读、再局部替换，避免整篇重写覆盖掉用户的其它内容：

```js
NekoPlugin.registerCommand("remember", async (ctx) => {
  const line = ctx.argsText.trim();
  if (!line) return "用法：/remember <要记住的事>";
  await ctx.api.memoryAppend(line);
  return "已写入 Agent 记忆。";
});
```

### 会话发送与会话管理（`chat.write`）

- `ctx.api.sendMessage({sessionId?, content})`（页面侧 `host.chat.send`）：写入用户消息并**触发一次完整回复生成**（含 Agent 工具流程）。生成在后台进行，插件 API 不等它结束，返回 `{messageId, sessionId, replyPending: true}`；回复会异步出现在会话里。没有可用模型时返回 `chat_send_failed`。
- `ctx.api.createSession({name?, sessionMode?, characterId?, characterIds?, systemPrompt?, firstMessage?, scenario?, senderName?, tags?})`（页面侧 `host.chat.sessions.create`）：新建会话，`sessionMode` 支持 `character`（默认）/`agent`/`group`，群聊用 `characterIds`。返回 `{id, name, sessionMode, characterId}`。
- `ctx.api.switchSession(id)`（页面侧 `host.chat.sessions.switch`）：请求 App 跳转到该会话（页面/命令不会立即消失，返回后才会切换）。返回 `{id, name, switched: true}`。

### 角色卡写入（`characters.write`）

`ctx.api.createCharacter(options)` 与 `ctx.api.updateCharacter(options)`（页面侧 `host.characters.create/update`）创建或修改角色卡：

| 字段 | 说明 |
| --- | --- |
| `id` | `update` 必填；`create` 省略（由 App 生成） |
| `name` | 角色名，最多 128 字符；`create` 必填 |
| `description`、`personality`、`scenario`、`firstMessage`、`exampleDialogues`、`systemPrompt`、`greeting`、`basicInfo`、`responseFormat`、`avatar`、`portrait` | 文本字段，每个最多 32000 字符 |
| `tags`、`rules` | 字符串数组（`tags` ≤32 项、`rules` ≤32 条） |
| `alternateGreetings` | 备用开场白数组，≤16 条 |
| `state` | 初始六维状态对象 |

`update` 是补丁语义：只覆盖传入的字段，其余保持原值。返回 `{id, name, updatedAt}`；**没有删除角色卡的 API**，需要删除时请用户在角色页操作。

### 工作区文件（`workspace`）

插件生成的内容可以保存到工作区，与「工作区」页面看到的是同一份文件，聊天里用 `[File: ...]` 标记即可渲染文件卡片：

- 有会话上下文（命令、从会话打开的页面、消息钩子）时保存到**当前会话工作区** `plugins/<插件id>/`；
- 没有会话上下文（扩展功能页、桌面小组件等入口打开的页面）时保存到**共享工作区** `plugins/<插件id>/`；
- 插件只能访问自己的专属文件夹，无法读取或修改用户文件与其他插件的文件。

| API | 说明 |
| --- | --- |
| `ctx.api.workspace.save(path, content)`（页面侧 `host.workspace.save({path, content})`） | 保存 UTF-8 文本，`path` 是专属文件夹内的相对路径（可含子目录）。返回 `{scope, path, name, size, mime_type, file_reference}`。 |
| `ctx.api.workspace.list(path?)`（页面侧 `host.workspace.list({path?})`） | 列出子目录（默认根）内容；每项含 `name`/`type`/`size`/`path`/`mime_type`/`file_reference`。 |
| `ctx.api.workspace.read(path)`（页面侧 `host.workspace.read({path})`） | 读取文本（最多 128 KiB，超出截断并置 `truncated`）；不存在报 `not_found`。 |
| `ctx.api.workspace.delete(path)`（页面侧 `host.workspace.delete({path})`） | 删除文件或文件夹（递归）；不存在报 `not_found`。 |

限制：单个文件 ≤ 2 MiB、每个插件最多 500 个文件、专属文件夹总大小 ≤ 32 MiB；超出分别报 `too_large`、`too_many_files`、`quota_exceeded`。卸载插件不会删除已保存到工作区的文件（避免误删用户数据），需要清理时请用户在工作区页面操作。

`file_reference` 可直接拼进命令返回值或消息正文，让聊天渲染文件卡片：

```js
NekoPlugin.registerCommand("export", async (ctx) => {
  const result = await ctx.api.workspace.save(
    "exports/notes.md",
    "# 我的笔记\n\n由插件生成。"
  );
  return `已导出。\n\n[File: ${result.file_reference}]`;
});
```

`scope` 为 `session` 时 `file_reference` 形如 `plugins/<插件id>/exports/notes.md`；为 `shared` 时形如 `shared://plugins/<插件id>/exports/notes.md`。页面里也可以按相同规则拼 `[File: ...]` 后交给 `host.chat.messages.append` 或命令返回值。

### 插件私有文件（`files`）

插件页面可以用标准文件输入框让用户选择文件；文件会复制到插件私有目录（与插件包分离，更新插件不会丢失），命令运行时也可以读取：

```html
<input type="file" accept="image/*" multiple id="uploader">
<script>
  document.getElementById("uploader").onchange = async (event) => {
    const files = Array.from(event.target.files);
    await host.ui.toast("已上传 " + files.length + " 个文件");
    const { files: saved } = await host.files.list();
    if (saved[0]) document.getElementById("preview").src = saved[0].url;
  };
</script>
```

- 上传需要清单声明 `files` 权限（基础组，安装默认勾选）；未声明或未授权时宿主会拒绝并提示。
- `accept` 与 `multiple` 会传给系统选择器；选择的文件立即可以按标准 `File` API 使用（`FileReader`、`URL.createObjectURL`）。
- 持久化访问用下表 API；`list`/`read` 返回的 `url` 指向 `/plugin/<插件id>/@files/<文件名>`，可直接放进 `<img>`、`<audio>`、`<video>` 等标签。

| API | 说明 |
| --- | --- |
| `host.files.list()`（命令侧 `ctx.api.files.list()`） | 返回 `{count, total_bytes, files: [{name, size, mime_type, updated_at, url}]}`。 |
| `host.files.read(name, encoding?)`（命令侧 `ctx.api.files.read(name, encoding?)`） | 读取内容；`encoding` 为 `text`（默认）或 `base64`；最多 128 KiB，超出截断并置 `truncated`。 |
| `host.files.delete(name)`（命令侧 `ctx.api.files.delete(name)`） | 删除文件；不存在报 `not_found`。 |

限制：单个文件 ≤ 16 MiB、每个插件最多 100 个文件、总量 ≤ 64 MiB；超出分别报 `too_large`、`too_many_files`、`quota_exceeded`。文件名取选择器显示的原始名（去掉路径与危险字符），同名自动追加序号；卸载插件会删除私有文件，工作区文件不受影响。

### 模板渲染（免权限）

`ctx.api.render(template, data)`（页面侧 `host.ui.render`）用内置模板引擎渲染 HTML 字符串，适合把移植过来的模板片段变成实际界面：

```js
const characters = await host.characters.list();
const html = await host.ui.render(
  "{{#each this}}<li>{{name}}{{#if (contains tags \"vip\")}} ★{{/if}}</li>{{else}}<li>暂无角色</li>{{/each}}",
  characters
);
document.getElementById("character-list").innerHTML = html;
```

支持 `{{path}}`（HTML 转义）、`{{{path}}}` / `{{&path}}`（原样）、`{{#if}}`/`{{#unless}}`/`{{#each}}`/`{{#with}}`（含 `{{else}}`）、`{{! 注释 }}`、`../` 上级上下文，以及 `eq`/`ne`/`gt`/`gte`/`lt`/`lte`/`and`/`or`/`not`/`contains`/`length` 子表达式。不支持自定义 helper、partials 与 `{{~` 空白控制；模板 ≤64000 字符，渲染结果 ≤256000 字符。渲染是纯字符串处理，不执行模板里的任何代码。

### 进度卡片（`chat.progress`）

耗时命令可以用 `ctx.api.progress()` 在聊天界面显示一张进度卡片，效果与内置 `/jm` 命令一致：卡片展示在用户气泡下方，带百分比进度条与步骤列表。

```js
NekoPlugin.registerCommand("download", async (ctx) => {
  await ctx.api.progress({
    content: "准备下载",
    progress: 0,
    steps: [{ type: "file", name: "解析链接", status: "running" }]
  });

  for (let i = 1; i <= 10; i++) {
    // ... 处理第 i 项 ...
    await ctx.api.progress({
      content: `下载中 (${i}/10)`,
      progress: i * 10,
      steps: [
        { type: "file", name: "解析链接", status: "done", detail: "已完成" },
        { type: "file", name: "下载分片", status: i === 10 ? "done" : "running", detail: `${i}/10` }
      ],
      complete: i === 10
    });
  }
  return "下载完成。";
});
```

`options` 字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `content` | 否 | 卡片头部文本，最多 200 个字符。 |
| `progress` | 否 | `0`-`100` 的确定进度；省略或非数字时为 `0`。进度只前进不后退，宿主要求上必须单调递增。 |
| `steps` | 否 | 步骤数组，最多 32 项。每项字段见下。 |
| `complete` | 否 | 为 `true` 时把整张卡片标记为完成，之后的上报会被忽略。 |
| `force` | 否 | 为 `true` 时跳过宿主节流，立即写入（默认节流约 250ms）。 |

每个步骤对象的字段：

| 字段 | 说明 |
| --- | --- |
| `type` | 图标类型：`thinking`、`tool`、`image`、`file`、`knowledge`、`done` 等；缺省为 `tool`。 |
| `name` | 步骤名称，最多 60 个字符。 |
| `status` | `running`（进行中）、`done`（完成）、`error`（失败）；缺省为 `running`。 |
| `detail` | 步骤摘要，最多 200 个字符。 |

注意事项：

- 进度卡片只在本地模式的聊天会话中可用。在 `plugin_use` 工具的测试环境中，因为没有关联的用户消息，`progress()` 会被静默忽略（返回 `false`），命令本身仍会正常执行完成。插件页面只有由聊天命令（`open_page`）打开时才有可更新的卡片，从扩展页入口打开的页面返回 `false`。
- 每次调用都会**整体替换**卡片内容，步骤列表不会自动累加。需要保留前面的步骤时，请在每次上报中重新传入完整列表。
- 卡片不会随命令结束自动收尾，请在最后一次上报中传 `complete: true`；否则卡片会停留在未完成状态。

### 会话洞察（`chat.read`）

四个只读 API 让插件读取当前会话的运行态：上下文占比、会话配置、提示词注入栈与工具调用记录。
都支持 `sessionId` 参数（省略时用当前会话），返回内容受长度上限约束。

**上下文占比** `ctx.api.contextUsage()` / `host.chat.context(options)`：

```json
{
  "sessionId": "…",
  "usedTokens": 21500,
  "maxTokens": 100000,
  "usagePercent": 21.5,
  "parts": [
    { "part": "system_prompt", "tokens": 3200, "count": 1, "percent": 14.9 },
    { "part": "tool_definitions", "tokens": 15000, "count": 118, "percent": 69.8 },
    { "part": "assistant_messages", "tokens": 3300, "count": 6, "percent": 15.3 }
  ],
  "run": { "active": true, "stage": "tool", "lastTool": "workspace_read_file", "completedToolCalls": 3 }
}
```

`parts[].part` 取值：`system_prompt`、`tool_definitions`、`summary`、`user_messages`、
`assistant_messages`、`tool_trajectory`、`other_messages`；与聊天页圆环、上下文分析页同一口径。
`usagePercent` 为占模型上下文窗口的比例，`parts[].percent` 为占合计用量的比例。

**会话配置** `ctx.api.sessionConfig()` / `host.chat.sessionConfig(options)`：

```json
{
  "sessionId": "…",
  "sessionMode": "agent",
  "characterId": "…",
  "features": {
    "plotMode": false, "plotRealTimeSync": false,
    "inheritCharacter": true, "inheritCharacterGreeting": false,
    "proactiveChat": true, "tts": false,
    "favorite": false, "pinned": false, "archived": false, "publicShare": false
  },
  "intervals": { "autoState": 2, "autoName": 10 },
  "prompt": {
    "hasSystemPrompt": true, "systemPromptChars": 120, "composedSystemPromptChars": 8600,
    "customPromptCount": 2,
    "customPrompts": [{ "order": 1, "title": "写作风格", "chars": 88 }],
    "disabledPromptKeys": ["character.memories"]
  },
  "plot": { "choiceStyle": "balanced", "outlineChars": 0 },
  "userPersonaChars": 0,
  "agent": {
    "goal": "…", "hasSpec": false, "specChars": 0,
    "todos": [{ "content": "…", "status": "in_progress", "priority": "high" }]
  }
}
```

**提示词注入栈** `ctx.api.promptStack()` / `host.chat.promptStack(options)`：返回最近一轮对话实际使用的注入项。

| 字段 | 说明 |
| --- | --- |
| `available` / `itemCount` | 是否已有注入记录 / 注入项数量 |
| `items[].key`、`priority`、`role`、`scope`、`enabled` | 注入项标识、优先级、角色、作用域与启用状态 |
| `items[].content` | 注入内容；传 `includeContent=false` 时为 `null`（只取结构） |
| `items[].chars` / `tokens` | 内容长度与 token 估算 |
| `disabledKeys` | 用户在会话中关闭的注入项 key |
| `composedSystemPrompt` | 传 `includeComposedPrompt=true` 时返回截断后的完整系统提示词 |

注入栈在每轮对话后写入会话记录，因此这是**最近一轮的快照**，不是实时重算结果。

**工具调用记录** `ctx.api.toolCalls({limit})` / `host.chat.toolCalls({limit})`：

```json
{ "total": 12, "returned": 12, "records": [
  { "callId": "call_1", "name": "workspace_read_file", "arguments": "{\"path\":\"a.txt\"}",
    "result": "…", "status": "done", "messageId": "…", "source": "history" }
] }
```

`status` 为 `done` / `error`（工具结果 `success=false`）/ `pending`（尚无结果，常见于中断轮次）；
`source` 为 `history`（已完成轮次）或 `trajectory`（进行中轮次的逐条落库轨迹）；
`messageId` 是记录归属的助手消息（进行中轮次为 `null`）。参数与结果会被截断，响应总量也有上限，超出时优先保留最新记录。

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

声明了 `open_page` 的命令不会进入 JS 运行时：用户在输入框输入该命令（或其别名）时，宿主直接打开对应页面，并在聊天里留一条「已打开插件页面」的回复。这类命令不需要在 `main.js` 里注册处理器。

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

### 事件钩子

在 `plugin.json` 的 `hooks` 里声明后，用 `NekoPlugin.on(name, handler)` 注册处理器：

```json
{ "hooks": ["message.beforeSend"] }
```

```js
NekoPlugin.on("message.beforeSend", async (ctx) => {
  const text = ctx.payload.content;
  if (!text.startsWith("喵")) return null;          // 返回 null 表示不改写
  return { content: "喵～" + text };
});
```

| 钩子 | 触发时机 | 处理器返回 |
| --- | --- | --- |
| `message.beforeSend` | 用户发送消息、且消息尚未落库/进入模型之前 | 返回 `{content}` 改写本条消息；返回 `null`/`undefined` 或抛错则保持原文 |
| `app.lifecycle` | `app.start`（App 启动）、`chat.open`（进入会话）、`chat.close`（离开会话） | 返回值忽略 |

`ctx` 在钩子里额外带 `hook`（钩子名）与 `payload`（`message.beforeSend` 为 `{sessionId, content, role}`；`app.lifecycle` 为 `{event, sessionId}`），其余字段与命令 `ctx` 相同，同样可以用 `ctx.api.*`（受权限约束）。

执行方式与命令运行时相同（无界面 WebView + 同一套权限桥），区别是：

- 单插件单次钩子超时 8 秒；`message.beforeSend` 失败/超时一律保持原文，**永远不会因为插件而发不出消息**。
- 多个插件声明同一钩子时按安装顺序串联，后一个插件看到的是前一个改写后的文本。
- 钩子在 App 进程内执行，不保证 `app.start` 一定先于其它钩子；`app.lifecycle` 的返回值不会被使用，适合做初始化、清理或统计。
- 页面运行时不提供 `NekoPlugin`（页面只有 `host.*`）；钩子必须写在入口脚本 `main.js` 里。

## 6. 安全边界

第三方插件代码被视为不可信代码。运行时具有以下边界：

- 禁止直接文件访问、内容提供器访问、DOM Storage、多窗口和页面导航；文件上传只能由用户通过系统文件选择器发起（`files` 权限），副本存入插件私有目录。
- 禁止 WebView 自行联网；网络只能通过声明了 `network` 权限的受控 HTTPS GET API。
- 写入类 API（`chat.write`、`memory.write`、`characters.write`）只能追加消息、读写 Agent 记忆、创建/修改角色卡，**没有删除能力**；`workspace` 的删除只作用于本插件专属文件夹，没有任意命令执行、相机、麦克风或 Android Intent 等 API。
- 事件钩子（`hooks`）由清单声明后即可运行，不需要额外权限；`message.beforeSend` 只能改写用户正在发送的那条消息，插件卡片会展示已声明钩子，供用户确认。
- 不能访问其他插件的存储数据；`workspace` 权限只能读写工作区中本插件的专属文件夹 `plugins/<插件id>/`，`files` 权限只能访问本插件私有目录中用户上传的文件，无法访问用户与其他插件的文件。
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
| 单个 JavaScript 文件 | 1 MiB |
| 入口脚本文本 | 1,048,576 个字符 |
| 单个其他资源 | 4 MiB |
| 页面数量 | 每个插件最多 8 个 |
| 页面 styles / scripts | 各最多 8 项 |

如果安装失败，应用会显示具体的清单、命令冲突、路径或大小错误。命令运行错误会直接作为本地会话中的命令结果显示。

## 8. 内置插件与兼容性

`builtin.jm`（JM 漫画）和 `builtin.light-novel`（轻小说）是应用随附的内置插件。它们可以停用，但不能卸载、覆盖或作为第三方 ZIP 的 `id` 使用。

第三方插件 API 当前为 v2（App 同时接受 v1 清单）。新增能力优先通过 `host.system.info().capabilities` 做能力协商，而不是抬高版本号；会话洞察能力对应 `chat.context`、`chat.session.config`、`chat.prompt.stack`、`chat.tool.calls`（需 `chat.read` 权限）。升级应用后，请重新验证插件的清单、权限、命令与页面是否仍符合本指南；不受支持的 `api_version` 会在安装时被拒绝。

## 9. 插件页面

插件可以声明独立页面：安装启用后出现在「更多 -> 扩展功能 -> 插件页面」区块，桌面小组件「插件页面」也会以网格显示这些入口；点击进入一个由插件自行设计的界面。页面是插件包内的 HTML + CSS + JS，运行在受限 WebView 中，只能通过 `host.*` 桥访问宿主能力。单个页面可以在插件目录内的多个 HTML 之间切换（相对链接或 `host.ui.openPage`），并使用原生弹窗（`alert`/`confirm`/`prompt`/`select`）。

### 9.1 `pages[]` 字段

| 字段 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | 是 | 1-32 位小写字母开头，可含数字、`_`、`-`；插件内唯一 | 页面标识 |
| `title` | 是 | ≤ 40 字符 | 入口显示名 |
| `title_i18n` | 否 | 键为 `zh`/`en`/`ja`/`ko` | 命中系统语言时覆盖 `title` |
| `icon` | 否 | 安全相对路径 | 入口图标（图片文件）；缺省用内置图标 |
| `order` | 否 | 默认 100 | 同插件内排序 |
| `entry` | 是 | 安全相对路径，必须以 `.html` 结尾 | 页面入口，安装时必须真实存在 |
| `styles` / `scripts` | 否 | 各 ≤ 8 项、安全相对路径 | 仅用于清单自描述；页面内相对引用同样可用 |

### 9.2 页面加载与资源

页面运行在虚拟源 `https://appassets.androidplatform.net/plugin/<插件id>/<entry>`，`entry` 同目录的 CSS/JS/图片等相对引用会自动解析到插件目录内的对应文件。

- 页面内 `fetch`、XHR、WebSocket 与图片外链被禁止；网络只能走 `host.http.get`。插件目录与 `@files/` 私有文件中的图片/音频可直接用相对路径或 `url` 引用展示（由宿主直接提供，不经过网络）。
- 页面跳转仅允许插件目录内的资源（相对链接、`location.href`、`host.ui.openPage`），外部地址、`target=_blank` 与自定义 scheme 一律拦截；用法见 9.4。
- 不使用 `localStorage`：页面存储必须走 `host.storage.*`，这样卸载插件时数据能一次清干净。
- 页面主题：宿主会在 HTML 的 `<head>` 起始处注入 CSS 变量与桥接脚本，可用变量（浅色/深色自动跟随 App）：
  `--neko-bg`、`--neko-surface`、`--neko-surface-variant`、`--neko-on-surface`、`--neko-on-surface-variant`、`--neko-primary`、`--neko-on-primary`、`--neko-secondary`、`--neko-outline`、`--neko-error`、`--neko-radius`、`--neko-font`。
  同时注入 `window.__NEKO_THEME__ = { mode: "dark"|"light", locale, appVersion, apiVersion }`。

### 9.3 `host.*` API

全部返回 `Promise`；单次调用超时 10 秒、并发上限 4、响应上限 256 KiB；失败时 Promise 拒绝，错误对象带 `message` 与 `code`。原生弹窗等待用户回答，最长 5 分钟，超时按取消处理；`host.system.info().capabilities` 含 `ui.openPage`、`ui.alert`、`ui.confirm`、`ui.prompt`、`ui.select` 时对应能力可用。

| API | 权限 | 说明 |
| --- | --- | --- |
| `host.system.info()` | 免 | `{appVersion, apiVersion, capabilities[], theme, locale}`；能力协商入口 |
| `host.log(level, msg)` | 免 | 仅写 logcat，≤ 500 字符 |
| `host.ui.close()` | 免 | 请求宿主关闭当前页面 |
| `host.storage.get/set/remove/list` | `storage` | 与命令侧 `ctx.api.storage` 共用同一存储 |
| `host.ui.toast(msg)` | `notify` | 短提示 |
| `host.chat.current()` | `chat.read` | 从会话上下文打开时返回当前会话（字段裁剪），否则 `null` |
| `host.chat.sessions.list()` | `chat.read` | 会话摘要列表（≤ 100 条） |
| `host.chat.sessions.get(id)` | `chat.read` | 单个会话摘要 |
| `host.chat.messages.list(sessionId, limit)` | `chat.read` | 最近消息（含 `reasoningContent`、`model`、`inputTokens`/`outputTokens` 等字段）；`limit` 默认 50、最大 200；`sessionId` 省略时用当前会话 |
| `host.chat.context(options)` | `chat.read` | 上下文占比与类型明细（第 4 章「会话洞察」） |
| `host.chat.sessionConfig(options)` | `chat.read` | 会话配置启用与配置情况（第 4 章「会话洞察」） |
| `host.chat.promptStack(options)` | `chat.read` | 提示词注入栈（第 4 章「会话洞察」） |
| `host.chat.toolCalls(options)` | `chat.read` | 工具调用记录（第 4 章「会话洞察」） |
| `host.characters.list()` / `get(id)` | `characters.read` | 角色卡只读数据（不含提示词运行态） |
| `host.worldbooks.list()` / `get(id)` | `worldbooks.read` | 世界书与条目只读数据 |
| `host.ui.render(template, data)` | 免 | 模板渲染，返回 HTML 字符串（语法见第 4 章） |
| `host.ui.openPage(pageId, args?)` | 免 | 在同一 WebView 内切换到声明过的页面（多 HTML 页面互跳），用法见 9.4 |
| `host.ui.alert(message, options?)` | 免 | 原生提示弹窗，返回 `true`；`options.title` 覆盖标题 |
| `host.ui.confirm(message, options?)` | 免 | 原生确认弹窗，返回 `boolean` |
| `host.ui.prompt(options)` | 免 | 原生输入弹窗，返回输入文本；取消返回 `null` |
| `host.ui.select(options)` | 免 | 原生单选弹窗，返回 `{index, value, label}`；取消返回 `null` |
| `host.chat.messages.append(options)` | `chat.write` | 追加消息，`options = {sessionId?, role?, content}`；返回 `{id, sessionId, role, createdAt}` |
| `host.chat.send(options)` | `chat.write` | 发送消息并触发后台回复生成，返回 `{messageId, sessionId, replyPending}` |
| `host.chat.sessions.create(options)` / `switch(id)` | `chat.write` | 新建会话 / 请求跳转到会话 |
| `host.characters.create(options)` / `update(options)` | `characters.write` | 创建 / 修改角色卡（无删除） |
| `host.memory.read()` | `memory.read` | `{content, charCount}`；Agent 长期记忆 |
| `host.memory.write(content)` / `append(content)` / `edit(oldText, newText)` | `memory.write` | 覆盖 / 追加 / 替换记忆，返回 `{charCount}` |
| `host.workspace.save({path, content})` / `list({path?})` / `read({path})` / `delete({path})` | `workspace` | 读写插件专属工作区文件夹（有会话时在会话工作区，否则共享工作区），见第 4 章「工作区文件」 |
| `host.files.list()` / `read(name, encoding?)` / `delete(name)` | `files` | 插件私有文件：列出 / 读取（`text`/`base64`，≤128 KiB）/ 删除；上传与 URL 用法见 9.9 |
| `host.ai.complete(options)` | `ai.call` | 走聊天故障转移队列的单次生成，返回 `{content, model, usage}`；超时 120 秒，限额见第 4 章 |
| `host.http.get(url)` | `network` | 仅 HTTPS 公网地址，返回 `{status, body}`（≤ 512 KiB） |
| `host.progress.update(options)` | `chat.progress` | 由聊天命令（`open_page`）打开时更新该命令消息上的进度卡片；其他入口返回 `false` |

### 9.4 多页面与页内切换

页面可以在多个 HTML 之间切换，不需要退回应用列表：

- **相对链接**：`<a href="detail.html">`、`location.href = "views/detail.html"`、`location.replace(...)`、`history.back()` 都在插件目录内解析；路径越界（`..`、绝对路径）会被拒绝。
- **声明页面互跳**：`await host.ui.openPage("detail", "id=3")` 切换到 `pages[]` 里声明过的页面；第二个参数是可选的参数文本（≤ 1000 字符），页面通过 `window.__NEKO_LAUNCH__.argsText` / `args` 读取，`sessionId` 保持不变。
- 顶栏标题跟随当前页面：命中声明页面用其本地化标题，其他 HTML 用文档 `<title>`。
- 返回键优先在页面历史中回退，只有没有上一页时才关闭页面。

```js
// 打开声明过的 detail 页面，并把当前记录的 id 传过去
await host.ui.openPage("detail", "id=" + note.id);
```

### 9.5 原生弹窗

页面里的 `alert()`、`confirm()`、`prompt()` 会显示为应用原生弹窗（不再被忽略）。HTML 的 `<select>` 下拉也会自动替换为应用弹窗（Nekobot 样式），选中后照常触发 `input` / `change` 事件，插件代码无需改动：

- 需要弹窗标题时给 select 加 `data-neko-title="选择一项"`（否则用 `title` 属性）。
- `multiple`、`size>1`、`disabled` 或带 `data-neko-native` 的 select 保留系统原生行为；`type=date` / `type=time` 等仍使用系统选择器。
- 需要自己控制流程（异步取值、动态选项）时用下面的 `host.*` 弹窗 API。

需要锚定展示或获取返回值时用 `host.*` 弹窗（都返回 Promise，取消/关闭同样有明确返回值）：

| API | 返回 | 说明 |
| --- | --- | --- |
| `host.ui.alert(message, options?)` | `true` | 提示弹窗；`options.title` 覆盖标题（默认插件名） |
| `host.ui.confirm(message, options?)` | `boolean` | 确认弹窗 |
| `host.ui.prompt({message, value?, title?})` | `string \| null` | 输入弹窗；`value` 为初始文本 |
| `host.ui.select({message?, title?, options, selected?})` | `{index, value, label} \| null` | 单选弹窗 |

`options` 是字符串数组或 `{label, value}` 对象数组（≤ 32 项），`selected` 可传下标或 value 作为默认选中项。

```js
// 页面里的原生 select 已自动走应用弹窗；下面是等价的 API 用法
const choice = await host.ui.select({
  message: "选择要应用的风格",
  options: [{ label: "简洁", value: "simple" }, { label: "华丽", value: "rich" }]
});
if (choice) await host.storage.set("style", choice.value);
```

### 9.6 页面生命周期

- 同时只允许打开 1 个插件页面；打开新页面会先销毁旧的。
- 页面没有总超时；单次 API 调用 10 秒超时。
- 返回键优先页面内后退，其次关闭页面；关闭时 WebView 会被销毁。
- 页面内切换页面（相对链接 / `host.ui.openPage`）不销毁 WebView，也会进入返回栈。
- 展示原生弹窗期间共享该页面的 API 并发名额；离页会取消未回答的弹窗。
- 页面进程崩溃时展示错误卡片，可点「重载」恢复，不会影响 App 其他部分。
- 插件被停用或卸载后入口消失，页面无法再打开。

### 9.7 从聊天命令打开页面

除了「扩展功能 → 插件页面」入口，命令也可以直接打开页面：给命令加 `"open_page": "<页面id>"`。

```json
{
  "name": "note",
  "usage": "/note [关键词]",
  "description": "打开随手记页面",
  "open_page": "notes"
}
```

用户在会话输入 `/note`（或别名）后：

- 宿主直接打开该页面，不执行 JS 处理器，命令也不会被当作普通消息发给 AI；
- 页面从会话上下文打开，因此 `host.chat.current()`、`host.progress.update()` 可用，`host.workspace.*` 保存到该会话工作区（非命令入口打开时 `sessionId` 为 `null`，保存到共享工作区）；
- 页面入口同时会出现在桌面小组件「插件页面」的网格里（不带参数）；
- 命令后面的参数会传给页面，页面通过 `window.__NEKO_LAUNCH__` 读取：

| 字段 | 说明 |
| --- | --- |
| `sessionId` | 触发命令的会话 id；非命令入口打开时为 `null` |
| `argsText` | 命令后的原始参数文本（如 `/note 搜索 猫` 的 `搜索 猫`） |
| `args` | 按空白切分后的参数数组 |

```js
// 页面脚本：根据命令参数决定初始视图
const launch = window.__NEKO_LAUNCH__ || { args: [], argsText: "" };
if (launch.args[0] === "搜索") {
  document.getElementById("input").value = launch.args.slice(1).join(" ");
}
```

`open_page` 引用的页面必须已在 `pages[]` 中声明，否则安装时会被拒绝。

### 9.8 完整示例

`plugin.json`：

```json
{
  "api_version": 2,
  "id": "demo.notes",
  "name": "随手记",
  "version": "1.0.0",
  "author": "Agent",
  "description": "带独立管理页面的随手记插件",
  "entry": "main.js",
  "permissions": ["storage", "notify"],
  "commands": [
    { "name": "note", "usage": "/note <内容>", "description": "保存一条笔记" },
    { "name": "notes", "usage": "/notes", "description": "打开随手记页面", "open_page": "notes" }
  ],
  "pages": [
    {
      "id": "notes",
      "title": "随手记",
      "title_i18n": { "zh": "随手记", "en": "Notes", "ja": "メモ", "ko": "메모" },
      "order": 10,
      "entry": "pages/notes.html",
      "styles": ["pages/notes.css"],
      "scripts": ["pages/notes.js"]
    }
  ]
}
```

`pages/notes.html`：

```html
<!DOCTYPE html>
<html lang="zh">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>随手记</title>
  <link rel="stylesheet" href="notes.css">
</head>
<body>
  <h1>随手记</h1>
  <form id="form">
    <input id="input" placeholder="写点什么…" autocomplete="off">
    <button type="submit">保存</button>
  </form>
  <ul id="list"></ul>
  <script src="notes.js"></script>
</body>
</html>
```

`pages/notes.js`：

```js
const listEl = document.getElementById("list");

async function refresh() {
  const all = await host.storage.list();
  const keys = Object.keys(all).filter((k) => k.startsWith("note:")).sort();
  listEl.innerHTML = "";
  keys.forEach((key) => {
    const li = document.createElement("li");
    li.textContent = all[key];
    listEl.appendChild(li);
  });
}

document.getElementById("form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const input = document.getElementById("input");
  const text = input.value.trim();
  if (!text) return;
  await host.storage.set("note:" + Date.now(), text);
  input.value = "";
  await host.ui.toast("已保存");
  refresh();
});

refresh();
```

`main.js` 与页面共用同一份存储：

```js
NekoPlugin.registerCommand("note", async (ctx) => {
  if (!ctx.argsText) return "用法：/note <内容>";
  await ctx.api.storage.set("note:" + Date.now(), ctx.argsText);
  return "已保存。可在插件页面查看全部笔记。";
});
```

### 9.9 文件上传（`files`）

页面可以直接使用标准文件输入框；用户选择后文件被复制到插件私有目录，页面与命令运行时都能读取：

```html
<input type="file" id="uploader" accept="image/*,text/*" multiple>
<button id="pick">选择文件</button>
<pre id="result"></pre>
<script>
  document.getElementById("pick").onclick = () => document.getElementById("uploader").click();
  document.getElementById("uploader").onchange = async (event) => {
    const files = Array.from(event.target.files);
    const { files: saved } = await host.files.list();
    document.getElementById("result").textContent =
      "本次选择 " + files.length + " 个，私有目录共 " + saved.length + " 个";
    if (saved[0]) document.getElementById("preview").src = saved[0].url;
  };
</script>
```

- 需要清单声明 `files` 权限；未声明或未授权时宿主会拒绝选择并提示。
- `accept` 的 MIME/扩展名会传给系统选择器（无法识别的项按 `*/*` 处理）；`multiple` 决定是否允许多选。
- 选择的文件可立即按标准 `File` API 使用（`FileReader`、`URL.createObjectURL`）；持久化访问走 `host.files.*`，详见第 4 章「插件私有文件」。
- 私有文件也可作为页面资源直接引用：`list`/`read` 返回的 `url` 指向 `/plugin/<插件id>/@files/<文件名>`，可用于 `<img>`、`<audio>`、`<video>` 等标签。
- 命令运行时（`ctx.api.files.*`）只能列出、读取或删除已上传的文件，不能发起文件选择。

## 10. 从其他生态移植

用户可以把别家插件包发进会话，由 AI 改写为 Nekobot 插件后直接安装。本章是改写规范。

### 10.1 推荐流程

```text
用户发插件包 → 附件落到会话工作区
  → plugin_use action=inspect, path=/workspace/xxx.zip
      （安全解压到 /workspace/plugin-port/<名称>/，返回生态、清单、文件清单、权限建议）
  → 用 workspace_read / grep 阅读源码
  → 按本章映射表改写，plugin_use action=create 安装
      （页面文件放 extra_files_json；compat / compat_note 记录移植级别与差异）
  → plugin_use action=check 静态自检（清单 / 页面入口 / API 名称 / 大小）
  → plugin_use action=execute 测试命令
  → 交付并告知用户差异
```

`check` 的 `ready=true` 只代表静态检查通过，不代表行为与原插件一致；不要向用户声称「完全兼容」。

### 10.2 生态识别规则

| 命中条件 | 判定 | 移植策略 |
| --- | --- | --- |
| 清单含 `toolpkg_id` 或 `schema_version` | Operit ToolPkg | 仅 WebView UI 型可移植；Compose DSL 改为 HTML；`Tools.*` 改为 `host.*` 或删除该功能 |
| 清单含 `display_name`（或 `js` + `i18n`） | SillyTavern 扩展 | 命令/事件/存储/生成钩子 → `commands[]` + `ctx.api`；模板面板 → 独立页面 |
| 清单含 `api_version` + `id` + `commands` | Nekobot 原生 | 直接 `create` 或 `install_url` |
| 含 `package.json` 且出现 `cordis` / `deepseek-harness` / `dsh` | DeepSeek Harness | **终止移植**，引导用户走 MCP 接入 |
| 其他 | 未知 | 询问用户用途，必要时按功能重写为原生插件 |

### 10.3 SillyTavern → Nekobot 映射

| ST | Nekobot | 级别 |
| --- | --- | --- |
| `manifest.json` | `plugin.json`（`display_name`→`name`，`version`/`author` 直搬） | 直译 |
| `SlashCommandParser.addCommandObject` | `commands[]` + `NekoPlugin.registerCommand` | 直译 |
| `extensionSettings` + `saveSettingsDebounced()` | `ctx.api.storage.get/set` 或 `host.storage.*` | 直译 |
| `toastr.success/error` | `ctx.api.notify` / `host.ui.toast` | 直译 |
| `renderExtensionTemplateAsync` | 页面内直接写 HTML（`pages[]`） | 改写 |
| `$('#extensions_settings2').append(html)` | 页面自身的 DOM（`document.body`） | 改写 |
| `context.chat` / `context.characters` | `host.chat.messages.list` / `host.chat.sessions.*` / `host.characters.*` | 改写 |
| `context.worldInfo` | `host.worldbooks.list/get` | 改写 |
| `generateRaw` / `generateQuietPrompt` | `host.ai.complete` / `ctx.api.aiComplete`（需 `ai.call` 授权） | 改写 + 授权 |
| `context.chat.push(...)` / 直接改 `chat` 数组 | `host.chat.messages.append` / `ctx.api.appendMessage`（需 `chat.write` 授权）；需要模型回复时用 `host.chat.send` | 改写 + 授权 |
| `context.characters` 的增改 | `host.characters.create/update`（需 `characters.write` 授权） | 改写 + 授权 |
| `context.setExtensionPrompt` / 写世界书条目 | `host.memory.append` / `ctx.api.memoryAppend`（需 `memory.write` 授权） | 改写 + 授权 |
| `eventSource.on(event_types.MESSAGE_RECEIVED, …)` | 部分可移植：`hooks: ["message.beforeSend"]`（发送前改写）+ `app.lifecycle`；收到消息后的事件仍无对应 | **部分降级**，须告知用户 |
| `SillyTavern.libs.*` | 无 → 自带实现或改用浏览器原生 API | **降级** |
| 直接操作 ST 主界面 DOM | 无 | **不支持**，明确劝退 |

### 10.4 Operit ToolPkg → Nekobot 映射

| ToolPkg | Nekobot | 级别 |
| --- | --- | --- |
| `manifest.json`（`toolpkg_id`/`main`/`subpackages`） | `plugin.json` + `pages[]` | 转换 |
| `registerToolboxUiModule{runtime:"webview"}` | `pages[].entry`（HTML 可直接复用） | 直译 |
| `registerToolboxUiModule{runtime:"compose_dsl"}` | `pages[].entry`（HTML 重写） | 重写 |
| `registerMessageProcessingPlugin` | 暂无对应 → 改为命令/页面 | **降级** |
| `registerXmlRenderPlugin` / `registerInputMenuTogglePlugin` | 暂无对应 | **不支持** |
| `registerAppLifecycleHook` | 暂无对应 | **降级** |
| `Tools.Files.*` | 插件目录内相对资源（只读）；读写工作区文件用 `ctx.api.workspace.*` / `host.workspace.*`（需 `workspace` 授权），跨会话留存也可用 `ctx.api.storage.*` / `ctx.api.memoryAppend` | 改写 |
| `Tools.System.*` / `toolCall(...)` / Ubuntu 终端 | 无对应 | **不支持** |

### 10.5 兼容级别与交付检查清单

兼容级别（写入 `compat`，展示在插件卡片）：

| 级别 | 含义 |
| --- | --- |
| `native` | Nekobot 原生插件 |
| `ported-full` | 移植后功能完整 |
| `ported-partial` | 移植后部分功能不可用（必须在 `compat_note` 列出） |
| `unsupported` | 无法移植（DSH / 深度 DOM 类） |

交付前必须逐项确认：

1. 已通读本指南，并按第 9 章实现页面（若有）。
2. `plugin_use create` 成功；`plugin_use check` 的 `ready=true`。
3. 至少用 `plugin_use execute` 测通一条命令；有页面时确认页面文件已写入且无 `http(s)://` 外链引用。
4. 报告内容：插件 id、可用命令、页面入口位置、**权限申请清单**、**与原插件的行为差异 / 未实现项**。
5. 不得声称「完全兼容」，只描述实际实现的功能。

{% endraw %}
