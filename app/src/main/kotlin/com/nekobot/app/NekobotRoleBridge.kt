package com.nekobot.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.room.withTransaction
import app.lianxu.independent.HostRoleBridge
import app.lianxu.independent.HostRoleScope
import app.lianxu.independent.RoleTextEdits
import app.lianxu.independent.RoleBasics
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal inline fun syncCompanionRoles(sync: () -> Unit) {
    runCatching(sync).onFailure { e ->
        if (e is CancellationException) throw e
        LocalLogger.w("HostRoleSync", "日常陪伴角色同步失败", e)
    }
}

internal class NekobotRoleBridge(private val context: Context) : HostRoleBridge {
    override fun profileExists(scope: String): Boolean {
        val profile = scope.removeSuffix(".db")
        return profile == com.nekobot.app.data.local.PrefsManager.DEFAULT_DB_NAME ||
            ServiceContainer.prefs.listDbProfiles().any { it.name.removeSuffix(".db") == profile } ||
            context.getDatabasePath(scope).isFile
    }

    override fun current(): HostRoleScope? {
        val prefs = ServiceContainer.prefs
        if (!prefs.isLocalMode) return null
        val profile = prefs.activeDbName
        val db = NekobotDatabase.get(context, profile)
        return NekobotRoleScope(db, context, requireNotNull(db.openHelper.databaseName)) {
            prefs.isLocalMode && prefs.activeDbName == profile
        }
    }
}

internal class NekobotRoleScope(
    private val db: NekobotDatabase,
    context: Context,
    override val name: String,
    private val active: () -> Boolean
) : HostRoleScope {
    companion object {
        private val revision = MutableStateFlow(0L)
        val roleRevision = revision.asStateFlow()
    }

    private val portraits = File(context.filesDir, "portraits").canonicalFile
    private val importedPortraits = File(context.cacheDir, "portraits/" + name.removeSuffix(".db")).canonicalFile
    private val dao = db.characterDao()

    private fun verify() = check(active()) { "资料库已切换，请重新打开角色" }

    private fun avatarBytes(path: String?): ByteArray? {
        val uri = path?.let(Uri::parse) ?: return null
        if (uri.scheme != null && uri.scheme != "file") return null
        val file = uri.path?.let(::File)?.canonicalFile ?: return null
        val privatePortrait = file.toPath().startsWith(portraits.toPath()) ||
            file.toPath().startsWith(importedPortraits.toPath())
        return if (privatePortrait && file.isFile) file.readBytes() else null
    }

    private fun basics(role: LocalCharacterEntity): RoleBasics {
        val description = role.description.orEmpty()
        val definition = role.systemPrompt.orEmpty()
        val fallback = listOf(role.personality, role.scenario, role.name).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        return RoleBasics(
            role.name, description.ifBlank { definition.ifBlank { fallback } },
            definition.ifBlank { description.ifBlank { fallback } },
            role.firstMessage.orEmpty().ifBlank { role.greeting.orEmpty() },
            avatarBytes(role.avatar?.takeIf { it.isNotBlank() } ?: role.portrait)
        )
    }

    override suspend fun ids(): List<String> {
        verify()
        return dao.listAll().map { it.id }
    }

    override suspend fun get(id: String): RoleBasics? {
        verify()
        return dao.getById(id)?.let(::basics)
    }

    private fun saveAvatar(data: RoleBasics): String? {
        val bytes = data.avatar ?: return null
        check(portraits.isDirectory || portraits.mkdirs())
        val file = File(portraits, "single-" + data.avatarDigest + ".jpg")
        if (!file.exists()) {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                throw e
            }
        }
        return Uri.fromFile(file).toString()
    }

    override suspend fun write(id: String, data: RoleBasics, expected: RoleBasics?, avatarChanged: Boolean, edits: RoleTextEdits): RoleBasics =
        db.withTransaction {
            verify()
            val old = dao.getById(id)
            val current = old?.let(::basics)
            check(current?.copy(avatar = null) == expected?.copy(avatar = null) && current?.avatarDigest == expected?.avatarDigest) {
                "角色资料已变化，请重试"
            }
            val avatar = if (avatarChanged && current?.avatarDigest != data.avatarDigest) saveAvatar(data) else old?.avatar
            val now = LocalRepository.nowIsoStatic()
            if (old == null) {
                dao.upsert(LocalCharacterEntity(id = id, name = data.name, description = data.bio,
                    systemPrompt = data.definition, firstMessage = data.opening, avatar = avatar, createdAt = now, updatedAt = now))
            } else {
                dao.updateRoleBasics(id, if (edits.name) data.name else old.name,
                    if (edits.bio) data.bio else old.description,
                    if (edits.definition) data.definition else old.systemPrompt,
                    if (edits.opening) data.opening else old.firstMessage, avatar, now)
            }
            if (avatar != old?.avatar) db.sessionDao().updatePortraitsByCharacterId(id, old?.portrait, avatar)
            basics(requireNotNull(dao.getById(id)))
        }.also { revision.update { value -> value + 1 } }

    override suspend fun delete(id: String) {
        val removed = db.withTransaction {
            verify()
            if (dao.getById(id) == null) return@withTransaction false
            dao.deleteById(id)
            true
        }
        if (removed) revision.update { it + 1 }
    }
}
