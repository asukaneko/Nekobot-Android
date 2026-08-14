# Qwen Omni Realtime 适配与故障处理

本文记录 Nekobot Android 对阿里云 DashScope Qwen Omni Realtime 的适配方式，以及联调
`qwen3.5-omni-flash-realtime` 时实际遇到的问题和处理方案。

当前实现位于：

- `app/src/main/kotlin/com/nekobot/app/data/local/ai/RealtimeVoiceClient.kt`
- `app/src/main/kotlin/com/nekobot/app/ui/screens/chat/ChatViewModel.kt`
- `app/src/main/kotlin/com/nekobot/app/data/local/LocalRepository.kt`

## 适用范围

以下条件任一成立时，客户端走 Qwen Realtime 分支：

- AI 配置的 `provider` 为 `qwen` 或 `dashscope`。
- 模型名同时包含 `qwen` 和 `realtime`。

当前默认值：

| 配置项 | 值 |
| --- | --- |
| 模型 | `qwen3.5-omni-flash-realtime` |
| WebSocket Base URL | `https://dashscope.aliyuncs.com/api-ws/v1` |
| 输入音频 | PCM16、16 kHz、单声道 |
| 输出音频 | PCM16、24 kHz、单声道 |
| 默认音色 | `Tina` |
| 输入转写模型 | `paraformer-realtime-v2` |

Qwen 端点必须使用 `api-ws/v1`。若配置仍是文本兼容模式常用的
`compatible-mode/v1`，客户端会自动改写为 `api-ws/v1`，并在 URL 查询参数中显式传入
`model`。

## 会话时序

客户端使用手动提交音频，而不是服务端 VAD 自动切轮：

```text
WebSocket 连接
  -> session.update
  -> input_audio_buffer.append (每包 100 ms)
  -> input_audio_buffer.commit
  -> 等待 input_audio_buffer.committed
  -> response.create
  -> response.created / 音频与字幕增量
  -> response.done
  -> input_audio_transcription.completed
  -> WebSocket.close(1000) 并等待 onClosed
```

`response.create` 只能在收到 `input_audio_buffer.committed` 后发送，且一轮只发送一次。

## 已遇到的问题与处理

| 现象 | 根因 | 当前处理 |
| --- | --- | --- |
| `buffer too small, or have no audio` | 手动 `commit` 前的 PCM 数据不足，或者大包发送使服务端输入缓冲处理滞后。 | Qwen 至少要求 16000 bytes PCM16，约为 16 kHz 下 0.5 秒音频；音频按 3200 bytes/100 ms 分包发送。 |
| 语音识别只有前半段、或字幕重复/跳变 | Qwen 转写增量使用 `text` 加 `stash`，不是可直接累加的 OpenAI 风格 `delta`。 | 每次以 `text + stash` 替换当前预览；收到 `input_audio_transcription.completed` 后再作为最终用户文本保存。 |
| `conversation already has an active response` | 已有响应在服务端自动或并发启动，客户端又重复发送 `response.create`。 | 仅在 `input_audio_buffer.committed` 后创建响应；用 `responseCreateSent` 和 `responseActive` 去重。若仍收到该错误，保留连接并等待现有响应的 `response.done`。 |
| 服务端记录 `400 Client disconnected before task finished`，但已产生文字或音频 | 客户端在 `response.done` 后立即取消 `callbackFlow`，导致 WebSocket 关闭握手被中断；最终转写事件也可能还未到达。 | 完成时先发起 `close(1000)`，只在 `onClosed` 后结束 Flow；保留 5 秒超时兜底。Qwen 在 `response.done` 后最多再等待 5 秒获取最终转写。 |
| 已有语音输出，随后报 `invalid value: session.finish` | `session.finish` 在部分 ASR/LiveTranslate 文档和 SDK 场景中存在，但当前 `qwen3.5-omni-flash-realtime` Omni 端点实测拒绝该事件。 | Omni 对话链路不发送 `session.finish`，以 `response.done` 加 WebSocket 正常关闭结束本轮。不要因为其他模式的文档示例把它重新加回 Omni 链路。 |
| 角色上下文/历史消息没有生效 | Qwen 当前的 `conversation.item.create` 不支持普通 `message` 类型上下文项，仅允许 `function_call_output`。 | 不发送 OpenAI 分支的历史 message 事件，改为将受限长度的历史拼接到 `session.instructions`。 |
| 使用旧音色后报不支持 | Qwen3.5 Omni Realtime 已不支持部分旧模型音色，如 `Cherry`、`Serena`、`Chelsie`。 | 客户端检测到这些音色时自动回退为 `Tina`。 |
| 未传模型或使用旧模型快照时 `ModelNotFound` | DashScope WebSocket 端点需要查询参数 `model`；历史模型快照可能已下线。 | URL 始终附带 `?model=...`，默认模型使用 `qwen3.5-omni-flash-realtime`。 |

## Qwen 与 OpenAI Realtime 的关键差异

| 项目 | Qwen / DashScope | OpenAI 兼容分支 |
| --- | --- | --- |
| URL | `/api-ws/v1?model=...` | `/v1/realtime?model=...` 或配置的等价路径 |
| `session.update` | 扁平字段：`model`、`voice`、`input_audio_format`、`output_audio_format` 等 | 嵌套的 `audio.input`、`audio.output` 配置 |
| 历史上下文 | 拼接到 `instructions` | `conversation.item.create` 发送 message 项 |
| `response.create` | 空事件体：`{"type":"response.create"}` | 携带 `response.output_modalities=["audio"]` |
| 转写预览 | `text + stash` 覆盖当前文本 | `delta` 追加文本 |
| 回合结束 | `response.done` 后正常关闭连接 | 同样基于 `response.done`，但最终转写等待窗口较短 |

## PromptStack 与新建 Live 会话

新建会话第一次直接进入 Live 时，数据库中尚不存在 `composed_system_prompt`。聊天页会先调用
`UnifiedRepository.prepareRealtimeLivePrompt()`：

1. 仅执行 `AIPipeline.prepareContext()`，不调用生成模型、不执行工具、不写入空用户消息。
2. 复用普通聊天的角色运行时、状态、关系、记忆、世界书、会话自定义提示词、用户人设和时间上下文。
3. 将得到的 `composed_system_prompt` 与 `prompt_stack_debug` 写回会话。
4. 把合成提示词传入 Qwen 的 `session.instructions`；Qwen 分支再追加裁剪后的既有聊天记录。

因此，新会话的首段 Live 音频也会带完整的静态角色 PromptStack。首段语音尚未完成转写时，
无法按该段口语的关键词补充动态世界书/RAG 命中；这些内容会在后续已保存的转写消息中参与下一轮
上下文准备。

## 排查清单

1. AI 配置的目的必须是 `live`，provider 设为 `qwen` 或 `dashscope`。
2. 确认模型为仍可用的 `qwen3.5-omni-flash-realtime`，Base URL 为 `api-ws/v1`。
3. 确认录音至少 0.5 秒；过短语音会在客户端直接提示，不会提交空缓冲区。
4. 检查日志中是否有 `input_audio_buffer.committed`，没有该事件时不能发送 `response.create`。
5. 若服务端仍出现 `ClientDisconnect`，检查客户端是否在最终转写完成前被用户主动挂断、切换会话或系统回收。
6. 若只有第一轮角色设定异常，检查会话详情中的 `composed_system_prompt` 和 `prompt_stack_debug` 是否已生成。

## 官方资料

- [Omni Realtime interaction process](https://help.aliyun.com/en/model-studio/omni-realtime-interaction-process)
- [Omni Realtime Python SDK](https://help.aliyun.com/zh/model-studio/omni-realtime-python-sdk)
- [Client events](https://help.aliyun.com/zh/model-studio/client-events)
- [DashScope Python SDK: omni_realtime.py](https://github.com/dashscope/dashscope-sdk-python/blob/8ebe713c/dashscope/audio/qwen_omni/omni_realtime.py)

文档与 SDK 中涉及 `session.finish` 的示例需要结合具体任务类型判断。对于本项目当前使用的
Qwen Omni Realtime 对话模型，应以实测协议行为为准。
