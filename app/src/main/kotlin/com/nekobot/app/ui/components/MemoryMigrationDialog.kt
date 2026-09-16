package com.nekobot.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer

/**
 * 「旧版全局记忆是否迁移到当前数据库」询问弹窗。
 *
 * 记忆改为按数据库 Profile 隔离后，旧版全局记忆只在用户明确同意时才拷贝到当前 Profile。
 * 用户做出选择即记录「已询问」，此后不再打扰——包括选择留空的情况，
 * 这样刻意清空的 Profile 不会被反复塞回旧内容。
 *
 * 挂在 [com.nekobot.app.MainActivity] 上，因为记忆绑定发生在数据库 Profile 切换时，
 * 那时可能还没有任何业务页面在场。
 */
@Composable
fun MemoryMigrationDialog() {
    val pending by ServiceContainer.pendingMemoryMigration.collectAsStateWithLifecycle()
    val request = pending ?: return

    NekoDialog(
        onDismiss = { ServiceContainer.resolveMemoryMigration(migrate = false) },
        title = stringResource(R.string.memory_migration_title),
        message = stringResource(R.string.memory_migration_message, request.legacyCharCount),
        confirmText = stringResource(R.string.memory_migration_migrate),
        onConfirm = { ServiceContainer.resolveMemoryMigration(migrate = true) },
        cancelText = stringResource(R.string.memory_migration_keep_empty),
        onCancel = { ServiceContainer.resolveMemoryMigration(migrate = false) }
    ) {
        Text(
            text = stringResource(R.string.memory_migration_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
