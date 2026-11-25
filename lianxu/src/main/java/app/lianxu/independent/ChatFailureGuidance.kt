package app.lianxu.independent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

internal enum class FailureGuidanceAction {
    OPEN_APP_BACKGROUND_SETTINGS,
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_MODEL_CONFIG,
}

internal data class FailureGuidance(
    val title:String,
    val summary:String,
    val steps:List<String>,
    val action:FailureGuidanceAction? = null,
    val actionLabel:String = "",
)

/**
 * Turns low-level chat failures and OS power/background status into plain-language guidance
 * and an optional one-tap settings shortcut so users know what failed and how to fix it.
 */
internal object ChatFailureGuidance {
    fun shouldAutoPrompt(failure:ChatFailure?):Boolean = failure in setOf(
        ChatFailure.CONNECTION_INTERRUPTED,
        ChatFailure.RESPONSE_BROKEN,
        ChatFailure.FOREGROUND_UNAVAILABLE,
        ChatFailure.TRUNCATED,
    )

    fun build(failure:ChatFailure?,detail:String,background:BackgroundAccessState=BackgroundAccessState(),meta:ChatFailureMeta?=null):FailureGuidance {
        val isAuxVision=meta?.route=="aux_vision"||(meta==null&&detail.contains("阶段：辅助看图"))
        val sawBackground=meta?.sawBackground==true||(meta==null&&detail.contains("切到过后台"))
        return when(failure){
            ChatFailure.CONNECTION_INTERRUPTED,ChatFailure.RESPONSE_BROKEN -> {
                val stageCode=meta?.stage.orEmpty().ifBlank{
                    when{
                        failure==ChatFailure.RESPONSE_BROKEN -> "read_response_body"
                        detail.contains("发送请求时") -> "connect_or_write"
                        else -> "await_response_headers"
                    }
                }
                val stage=when(stageCode){
                    "connect_or_write" -> if(isAuxVision)"辅助看图发送请求时" else "发送请求时"
                    "read_response_body" -> if(isAuxVision)"辅助看图读取结果中途" else "读取回复中途"
                    else -> if(isAuxVision)"辅助看图等待响应时" else "等待服务商响应时"
                }
                val title=when(stageCode){
                    "connect_or_write" -> if(isAuxVision)"辅助看图发送请求时连接中断" else "发送请求时连接中断"
                    "read_response_body" -> if(isAuxVision)"辅助看图读到一半断了" else "回复读到一半连接断了"
                    else -> if(isAuxVision)"辅助看图时连接中断" else "等待回复时连接中断"
                }
                if(sawBackground){
                    val needsPower=background.needsBackgroundSettings
                    val actionLabel=if(needsPower)"去开启后台权限" else "去检查后台权限"
                    val summary=if(needsPower){
                        "在${stage}连接被中途掐断，且本次请求期间应用曾切到后台。系统的「自动管理 / 电池优化」可能限制后台网络或冻结应用并切断连接；原消息已保留，没有自动重发。"
                    } else {
                        "在${stage}连接中断，且本次请求期间应用曾切到后台。系统后台策略或当前网络波动可能导致连接断开；原消息已保留，没有自动重发。"
                    }
                    val steps=buildList {
                        if(needsPower){
                            add("1. 点下方「$actionLabel」，打开本应用的系统应用设置页。")
                            add("2. 在「应用启动管理 / 后台运行」中关闭自动管理，打开「允许后台活动」。")
                            add("3. 在「电池优化 / 耗电管理」中设为「不允许优化」或「无限制」。")
                            if(background.needsNotificationSettings){
                                add("4. 在「通知管理」中开启通知，便于系统保持后台等待服务。")
                            }
                            add("设置完成后回到聊天页点「重试」。")
                        } else {
                            add("1. 检查当前 Wi‑Fi、移动网络或代理连接是否稳定。")
                            add("2. 如需核对厂商后台策略，可点下方「$actionLabel」，确认已关闭「自动管理」并允许后台活动。")
                            add("3. 确认后回到聊天页点「重试」。")
                        }
                    }
                    FailureGuidance(
                        title=title,
                        summary=summary,
                        steps=steps,
                        action=FailureGuidanceAction.OPEN_APP_BACKGROUND_SETTINGS,
                        actionLabel=actionLabel,
                    )
                } else {
                    FailureGuidance(
                        title=title,
                        summary="在${stage}连接中断了（请求期间应用保持在前台），可能是当前网络波动、代理断开或服务商提前关闭了连接；原消息已保留，没有自动重发。",
                        steps=listOf(
                            "1. 检查当前 Wi‑Fi、移动网络或代理连接是否稳定，确认服务商接口可正常访问。",
                            "2. 确认网络正常后，回到聊天页点「重试」。",
                        ),
                    )
                }
            }
            ChatFailure.FOREGROUND_UNAVAILABLE -> {
                val notifyFirst=background.needsNotificationSettings
                val appNotifyOff=background.notificationsEnabled==false
                FailureGuidance(
                    title="后台等待服务未能启动",
                    summary="系统阻止了这次聊天的后台等待服务，请求尚未发出。通常是关闭了通知或系统限制了后台运行。",
                    steps=listOf(
                        "1. 点下方按钮打开本应用的系统设置。",
                        if(notifyFirst && !appNotifyOff)"2. 开启「聊天回复进度」通知渠道，并在应用启动/耗电管理中允许后台活动、设为「不允许优化」。"
                        else "2. 开启应用通知权限，并在应用启动/耗电管理中允许后台活动、设为「不允许优化」。",
                        "3. 完成后回到聊天页点「重试」。",
                    ),
                    action=if(notifyFirst)FailureGuidanceAction.OPEN_NOTIFICATION_SETTINGS else FailureGuidanceAction.OPEN_APP_BACKGROUND_SETTINGS,
                    actionLabel=if(notifyFirst)(if(appNotifyOff)"去开启应用通知" else "去开启聊天通知") else "去开启后台权限",
                )
            }
            ChatFailure.TRUNCATED -> {
                val target=if(isAuxVision)"辅助看图" else "聊天"
                val streamCutShort=detail.contains("done_before_finish")||detail.contains("incomplete")
                if(streamCutShort){
                    if(sawBackground){
                        val actionLabel=if(background.needsBackgroundSettings)"去开启后台权限" else "去检查后台权限"
                        FailureGuidance(
                            title=if(isAuxVision)"辅助看图回复流未完整结束" else "回复流未完整结束",
                            summary="${target}服务返回的数据流中途结束了，且本次请求期间应用曾切到后台，可能是系统后台限制或网络波动导致连接提前结束；原消息已保留。",
                            steps=listOf(
                                "1. 点下方「$actionLabel」，确认已开启「允许后台活动」和「不允许优化」。",
                                "2. 同时检查当前网络或代理是否稳定，完成后回到聊天页点「重试」。",
                            ),
                            action=FailureGuidanceAction.OPEN_APP_BACKGROUND_SETTINGS,
                            actionLabel=actionLabel,
                        )
                    } else FailureGuidance(
                        title=if(isAuxVision)"辅助看图回复流未完整结束" else "回复流未完整结束",
                        summary="${target}服务返回的数据流中途结束了，没有收到完整的结束标记，可能是当前网络/代理波动或服务商提前断流；原消息已保留。",
                        steps=listOf(
                            "1. 检查当前网络或代理是否稳定，确认服务商响应正常。",
                            "2. 回到聊天页点「重试」。",
                        ),
                    )
                } else FailureGuidance(
                    title=if(isAuxVision)"辅助看图达到输出上限" else "回复达到输出上限",
                    summary="${target}模型达到了当前 API 配置的输出上限，内容还没生成完就被截断了（开启思考时，思考过程也会占用输出额度）。原消息已保留。",
                    steps=listOf(
                        "1. 点下方「去调大模型配置」，直接打开当前${target}使用的模型。",
                        "2. 调高「输出上限」，或按需调整思考设置并保存。",
                        "3. 返回聊天页点「重试」。",
                    ),
                    action=FailureGuidanceAction.OPEN_MODEL_CONFIG,
                    actionLabel="去调大模型配置",
                )
            }
            ChatFailure.MISSING_CONFIG,ChatFailure.MISSING_VISION_MODEL,ChatFailure.INVALID_CONFIG,
            ChatFailure.UNAUTHORIZED,ChatFailure.FORBIDDEN,ChatFailure.PAYMENT,
            ChatFailure.CONTEXT_CAPACITY,ChatFailure.REJECTED,ChatFailure.UNSUPPORTED_TOOLS -> FailureGuidance(
                title="需要检查大模型配置",
                summary=failure.display,
                steps=listOf(
                    "1. 点下方「去大模型配置」核对对应模型、密钥、余额或上下文/输出设置。",
                    "2. 调整并保存后，回到聊天页点「重试」。",
                ),
                action=FailureGuidanceAction.OPEN_MODEL_CONFIG,
                actionLabel="去大模型配置",
            )
            else -> FailureGuidance(
                title="没发出去的原因",
                summary=failure?.display?:"回复未完成。",
                steps=emptyList(),
            )
        }
    }
}

/**
 * Opens this app's system settings page where users on Honor/Huawei/Xiaomi/OPPO/vivo and stock Android
 * can enable "Allow background activity" and disable battery optimization in one place.
 */
internal fun openBackgroundAccessSettings(context:Context):Boolean {
    fun launch(intent:Intent):Boolean = try {
        if(context !is Activity)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch(_:ActivityNotFoundException){false} catch(_:SecurityException){false}

    val appDetails=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",context.packageName,null))
    if(launch(appDetails))return true
    if(launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))return true
    return launch(Intent(Settings.ACTION_SETTINGS))
}
