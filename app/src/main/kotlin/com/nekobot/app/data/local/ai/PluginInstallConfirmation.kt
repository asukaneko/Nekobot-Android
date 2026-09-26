package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.plugin.PluginManifest
import com.nekobot.app.data.local.plugin.PluginManifestValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 推送到会话界面的第三方插件安装确认请求。
 *
 * Agent 通过 plugin_use install_zip 安装工作区内的插件 ZIP 时，
 * 必须在界面上弹出现有的「第三方插件同意」弹窗（协议勾选 + 权限勾选）后
 * 才能继续安装。此请求与命令授权不同：不可记忆、不受 YOLO 影响。
 */
data class PluginInstallConfirmationRequest(
    val requestId: String,
    val sessionId: String,
    /** 插件包来源（会话工作区内的相对路径），用于弹窗展示。 */
    val sourceLabel: String,
    val pluginId: String,
    val pluginName: String,
    val version: String,
    val author: String,
    val description: String,
    /** 清单声明的（受支持的）权限，弹窗据此渲染勾选项。 */
    val declaredPermissions: List<String>,
    /** 默认勾选集合（非危险权限）；用户可在弹窗中自行增减。 */
    val defaultGrantedPermissions: Set<String>
)

/** 用户对插件安装确认的结果；approved=false 表示拒绝安装。 */
data class PluginInstallDecision(
    val approved: Boolean,
    val grantedPermissions: Set<String> = emptySet()
)

/**
 * 第三方插件安装确认等待管理器。
 *
 * Agent 的 plugin_use install_zip 动作在此挂起，直到用户在第三方插件同意弹窗中
 * 确认（返回勾选的权限集合）或拒绝/超时。与 [LocalExecAuthorizationManager] 不同：
 * 没有「始终允许」记忆，也不检查 YOLO——安装第三方代码必须每次由用户显式确认。
 */
class LocalPluginInstallConfirmationManager(
    private val timeoutMs: Long = 10 * 60 * 1000L
) {
    private data class Pending(
        val sessionId: String,
        val decision: CompletableDeferred<PluginInstallDecision>
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /** 发起安装确认并挂起等待；[onRequest] 负责把请求转发到会话界面弹窗。 */
    suspend fun requestConfirmation(
        sessionId: String,
        sourceLabel: String,
        manifest: PluginManifest,
        onRequest: (PluginInstallConfirmationRequest) -> Unit
    ): PluginInstallDecision {
        val requestId = UUID.randomUUID().toString()
        val supported = manifest.permissions
            .filter { it in PluginManifestValidator.supportedPermissions }
            .distinct()
        val request = PluginInstallConfirmationRequest(
            requestId = requestId,
            sessionId = sessionId,
            sourceLabel = sourceLabel,
            pluginId = manifest.id,
            pluginName = manifest.name,
            version = manifest.version,
            author = manifest.author,
            description = manifest.description,
            declaredPermissions = supported,
            defaultGrantedPermissions = PluginManifestValidator.defaultGrantedPermissions(supported)
        )
        val decision = CompletableDeferred<PluginInstallDecision>()
        pending[requestId] = Pending(sessionId, decision)
        onRequest(request)
        val outcome = try {
            withTimeoutOrNull(timeoutMs) { decision.await() }
        } finally {
            pending.remove(requestId)
        }
        return outcome ?: PluginInstallDecision(approved = false)
    }

    /** 提交用户选择；requestId 不存在或会话不匹配时返回 false。 */
    fun resolve(requestId: String, sessionId: String, decision: PluginInstallDecision): Boolean {
        val request = pending[requestId] ?: return false
        if (request.sessionId != sessionId) return false
        return request.decision.complete(decision)
    }

    /** 停止生成时拒绝该会话全部待确认安装，立即解除挂起。 */
    fun cancelSession(sessionId: String) {
        pending.entries
            .filter { (_, request) -> request.sessionId == sessionId }
            .forEach { (requestId, request) ->
                if (pending.remove(requestId, request)) {
                    request.decision.complete(PluginInstallDecision(approved = false))
                }
            }
    }
}
