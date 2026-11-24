package app.lianxu.independent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

data class RoleBasics(val name: String, val bio: String, val definition: String, val opening: String, val avatar: ByteArray?) {
    val avatarDigest: String by lazy { avatar?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }.orEmpty() }
    internal val digest by lazy { stableDigest(listOf(name,bio,definition,opening,avatarDigest).joinToString("") { "${it.length}:$it" }) }
    internal fun local(): RoleBasics {
        fun String.limit(n: Int) = trim().take(n).dropLastWhile { it.isHighSurrogate() }
        val label = name.limit(30).ifBlank { "角色" }
        return copy(name=label,bio=bio.ifBlank { definition }.limit(500).ifBlank { label },
            definition=definition.ifBlank { bio }.limit(8000).ifBlank { label },opening=opening.limit(8000))
    }
}

data class RoleTextEdits(val name: Boolean, val bio: Boolean, val definition: Boolean, val opening: Boolean)

interface HostRoleScope {
    val name: String
    suspend fun ids(): List<String>
    suspend fun get(id: String): RoleBasics?
    suspend fun write(id: String, data: RoleBasics, expected: RoleBasics?, avatarChanged: Boolean, edits: RoleTextEdits): RoleBasics
    suspend fun delete(id: String)
}

fun interface HostRoleBridge {
    fun current(): HostRoleScope?
    fun profileExists(scope: String): Boolean = true
}
@androidx.annotation.Keep
data class RoleLink(val scope: String, val host: String, val friend: String)
@androidx.annotation.Keep
private data class RoleBaseline(val link: RoleLink, val hostHash:String="", val localHash:String="",
    val hostAvatar:String="", val localAvatar:String="", val deleting:Boolean=false,
    val localNameHash: String? = "", val localBioHash: String? = "",
    val localDefinitionHash: String? = "", val localOpeningHash: String? = "",
    val restoredFrom: Set<String>? = null, val detached: Boolean = false) {
    val key: String get() = if (detached) ROLE_MAP + "detached:" + link.friend
        else ROLE_MAP + stableDigest(link.scope) + ":" + link.host

    fun edits(friend: RoleBasics, host: RoleBasics?): RoleTextEdits {
        val projection = host?.local()
        fun changed(value: String, hash: String?, legacy: String?) =
            host == null || if (hash.isNullOrEmpty()) value != legacy else stableDigest(value) != hash
        return RoleTextEdits(changed(friend.name, localNameHash, projection?.name),
            changed(friend.bio, localBioHash, projection?.bio),
            changed(friend.definition, localDefinitionHash, projection?.definition),
            changed(friend.opening, localOpeningHash, projection?.opening))
    }
}

internal fun remapRestoredRole(value: String, restored: Map<String, String>): Pair<String, String>? {
    val gson = Gson()
    val baseline = gson.fromJson(value, RoleBaseline::class.java)
    if (baseline.deleting) return baseline.key to value
    val ids = baseline.restoredFrom.orEmpty() + baseline.link.friend
    val target = restored[baseline.link.friend] ?: ids.firstNotNullOfOrNull { restored[it] } ?: return null
    val remapped = baseline.copy(link = baseline.link.copy(friend = target), restoredFrom = ids)
    return remapped.key to gson.toJson(remapped)
}
internal class ForeignRoleProfile : IllegalStateException("请切回角色所属的本地资料库再删除")

internal const val ROLE_MAP = "host-char-map:"
internal enum class RoleSource { HOST, LOCAL, NONE }
internal fun roleSource(hostChanged:Boolean, localChanged:Boolean, hostAction:Boolean) = when {
    hostChanged && (!localChanged || hostAction) -> RoleSource.HOST
    localChanged -> RoleSource.LOCAL
    else -> RoleSource.NONE
}

class HostRoleSync internal constructor(private val context: Context, private val bridge: HostRoleBridge,
    internal val store: Store = Store(context)) {
    private val mutex = Mutex()
    internal suspend fun <T> duringRestore(restore: suspend () -> T): T = mutex.withLock { restore() }
    private val gson = Gson()

    private fun maps() = store.readableDatabase.rawQuery("SELECT value FROM preferences WHERE key GLOB ?",arrayOf(ROLE_MAP+"*")).use { c ->
        buildList { while(c.moveToNext()) add(gson.fromJson(c.getString(0), RoleBaseline::class.java)) }
    }
    private fun persist(m:RoleBaseline) = store.writableDatabase.execSQL(
        "INSERT OR REPLACE INTO preferences(key,value) VALUES(?,?)",arrayOf(m.key,gson.toJson(m)))
    private fun forget(m:RoleBaseline) = store.writableDatabase.delete("preferences","key=?",arrayOf(m.key))
    private fun local(db:SQLiteDatabase,id:String): RoleBasics? {
        val role=db.rawQuery("SELECT name,bio FROM friends WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: return null
        val prompts=prompts(db,id)
        val avatar=db.rawQuery("SELECT path,length(data) FROM role_assets WHERE friend_id=? AND kind='avatar' ORDER BY path LIMIT 1",arrayOf(id)).use {
            if(it.moveToFirst()) RoleAssets.read(db,id,it.getString(0),it.getInt(1)) else null
        }
        return RoleBasics(role.first,role.second,prompts.characterDefinition,prompts.opening,avatar)
    }
    private fun prompts(db:SQLiteDatabase,id:String) = RolePromptStore.decode(db.rawQuery(
        "SELECT value FROM preferences WHERE key=?",arrayOf(RolePromptStore.key(id))).use { if(it.moveToFirst()) it.getString(0) else "" })
    private fun prepareBridgeAvatar(bytes: ByteArray) = runCatching { prepareRoleAvatar(bytes) }.getOrNull()

    // Publish the new local baseline in the same transaction as the role and its (possibly
    // recompressed) avatar. An interrupted copy must never send truncated text back to the host.
    private fun writeLocal(baseline: RoleBaseline, host: RoleBasics, expected: RoleBasics?, avatarChanged: Boolean) {
        val id = baseline.link.friend
        val data = host.local()
        val avatar = if (avatarChanged) data.avatar?.let(::prepareBridgeAvatar) else null
        AppMutationGate.read {
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                check(local(db, id)?.digest == expected?.digest) { "角色资料已变化，请重试" }
                val fields = ContentValues().apply {
                    put("name", data.name)
                    put("bio", data.bio)
                }
                if (expected == null) {
                    fields.put("id", id)
                    fields.put("prompt", "")
                    fields.put("tone", 0)
                    db.insertOrThrow("friends", null, fields)
                } else {
                    db.update("friends", fields, "id=?", arrayOf(id))
                }
                val updated = prompts(db, id).copy(characterDefinition = data.definition, opening = data.opening)
                db.execSQL("INSERT OR REPLACE INTO preferences(key,value) VALUES(?,?)",
                    arrayOf(RolePromptStore.key(id), RolePromptStore.encode(updated)))
                if (expected == null) store.seedOpening(db, id, data.opening)
                if (avatarChanged) {
                    db.delete("role_assets", "friend_id=? AND kind='avatar'", arrayOf(id))
                    if (avatar != null) db.execSQL(
                        "INSERT INTO role_assets(friend_id,path,kind,mime,data) VALUES(?,'avatar.jpg','avatar',?,?)",
                        arrayOf(id, avatar.first, avatar.second))
                }
                saveBaseline(baseline, host, requireNotNull(local(db, id)))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun saveBaseline(baseline: RoleBaseline, host: RoleBasics, friend: RoleBasics) =
        persist(baseline.copy(hostHash = host.digest, localHash = friend.digest,
            hostAvatar = host.avatarDigest, localAvatar = friend.avatarDigest,
            localNameHash = stableDigest(friend.name), localBioHash = stableDigest(friend.bio),
            localDefinitionHash = stableDigest(friend.definition), localOpeningHash = stableDigest(friend.opening)))

    suspend fun sync(hostActionId: String? = null, expectedProfile: String? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = bridge.current() ?: return@withLock
            if (expectedProfile != null && expectedProfile != scope.name) return@withLock
            val unlinkedHosts = scope.ids().toMutableSet()
            val unlinkedFriends = store.friends().mapTo(mutableSetOf()) { it.id }
            val baselines = maps()
            val foreign = baselines.filter { !it.detached && it.link.scope != scope.name }.groupBy { it.link.scope }
            for ((name, links) in foreign) {
                if (!bridge.profileExists(name)) detach(links)
            }
            for (baseline in baselines) {
                val link = baseline.link
                unlinkedFriends.remove(link.friend)
                if (baseline.detached || link.scope != scope.name) continue
                unlinkedHosts.remove(link.host)
                if (baseline.deleting) completeDelete(scope, baseline, null)
                else reconcile(scope, baseline, hostActionId == link.host)
            }
            for (host in unlinkedHosts) {
                val link = RoleLink(scope.name, host, UUID.randomUUID().toString())
                scope.get(host)?.let { writeLocal(RoleBaseline(link), it, null, it.avatar != null) }
            }
            for (friend in unlinkedFriends) {
                val link = RoleLink(scope.name, UUID.randomUUID().toString(), friend)
                val baseline = RoleBaseline(link)
                // Reserve the host ID first so a retry after a Room commit reuses the same row.
                persist(baseline)
                reconcile(scope, baseline, false)
            }
        }
    }

    private suspend fun reconcile(scope: HostRoleScope, baseline: RoleBaseline, hostAction: Boolean) {
        val link = baseline.link
        val host = scope.get(link.host)
        val friend = AppMutationGate.read { local(store.readableDatabase, link.friend) }
        val direction = when {
            host == null && friend == null -> return
            host == null -> RoleSource.LOCAL
            friend == null -> RoleSource.HOST
            else -> roleSource(host.digest != baseline.hostHash, friend.digest != baseline.localHash, hostAction)
        }
        when (direction) {
            RoleSource.NONE -> return
            RoleSource.HOST -> {
                requireNotNull(host)
                val avatarChanged = friend == null || host.avatarDigest != baseline.hostAvatar ||
                    friend.avatarDigest != baseline.localAvatar
                writeLocal(baseline, host, friend, avatarChanged)
            }
            RoleSource.LOCAL -> {
                requireNotNull(friend)
                val avatarChanged = host == null || friend.avatarDigest != baseline.localAvatar ||
                    host.avatarDigest != baseline.hostAvatar
                val edits = baseline.edits(friend, host)
                val outgoing = friend.copy(
                    name = if (host == null || edits.name) friend.name else host.name,
                    bio = if (host == null || edits.bio) friend.bio else host.bio,
                    definition = if (host == null || edits.definition) friend.definition else host.definition,
                    opening = if (host == null || edits.opening) friend.opening else host.opening,
                    avatar = if (avatarChanged && friend.avatar != null && host?.avatarDigest != friend.avatarDigest) {
                        prepareBridgeAvatar(friend.avatar)?.second ?: host?.avatar
                    } else friend.avatar
                )
                val saved = scope.write(link.host, outgoing, host, avatarChanged, edits)
                saveBaseline(baseline, saved, friend)
            }
        }
    }
    suspend fun hostLink(profile:String,id:String):RoleLink? = withContext(Dispatchers.IO) {
        mutex.withLock { maps().firstOrNull { !it.detached && it.link.scope==profile && it.link.host==id }?.link }
    }
    suspend fun detachHost(scopeName: String, hostIds: Collection<String>) =
        applyHostTombstones(hostIds) { detach -> detach(scopeName) }

    /** Keep sync out until the host transaction commits and its links are detached. */
    suspend fun applyHostTombstones(
        hostIds: Collection<String>,
        commitThenDetach: suspend (detach: (scopeName: String) -> Unit) -> Unit
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            commitThenDetach { scopeName ->
                val ids = hostIds.toSet()
                detach(maps().filter { !it.detached && it.link.scope == scopeName && it.link.host in ids })
            }
        }
    }
    suspend fun detachProfile(scopeName: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock { detach(maps().filter { it.link.scope == scopeName && !it.detached }) }
        }
    }

    private fun detach(links: List<RoleBaseline>) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            for (baseline in links) {
                forget(baseline)
                if (!baseline.deleting) persist(baseline.copy(detached = true))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    internal suspend fun friendLink(id:String):RoleLink? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val baseline = maps().firstOrNull { it.link.friend == id } ?: return@withLock null
            if (baseline.detached) return@withLock null
            val link = baseline.link
            if (bridge.current()?.name != link.scope) {
                if (!bridge.profileExists(link.scope)) return@withLock null
                throw ForeignRoleProfile()
            }
            link
        }
    }
    suspend fun deleteHost(profile:String,id:String,ack:RoleLink?,unlinked:suspend ()->Unit) = withContext(Dispatchers.IO+NonCancellable) {
        mutex.withLock {
            val m=maps().firstOrNull { !it.detached && it.link.scope==profile && it.link.host==id }
            check(m?.link == ack) {
                if (ack == null) "该角色已关联日常陪伴，请前往内容库手动确认删除聊天与记忆"
                else "角色关联已变化，请前往内容库重新确认删除"
            }
            if(m==null) unlinked() else {
                val scope=requireNotNull(bridge.current()).also { check(it.name==profile) }
                completeDelete(scope,m,null)
            }
        }
    }
    internal suspend fun deleteFriend(plan:BackupRepository.RoleDeletion) = withContext(Dispatchers.IO+NonCancellable) {
        mutex.withLock {
            val m=maps().firstOrNull { it.link.friend==plan.friendId }
            if (m != null && (m.detached || (bridge.current()?.name != m.link.scope && !bridge.profileExists(m.link.scope)))) {
                check(plan.link == null || plan.link == m.link) { "角色关联已变化，请重新确认删除" }
                clearLocal(plan)
                forget(m)
                return@withLock
            }
            check(m?.link==plan.link) { "角色关联已变化，请重新确认删除" }
            if(m==null) clearLocal(plan) else {
                val scope=bridge.current()
                if (scope == null || scope.name != m.link.scope) throw ForeignRoleProfile()
                completeDelete(scope,m,plan)
            }
        }
    }
    private fun backup()=BackupRepository(store,ModelVault(context,AppServices.vaultNamespace,store))
    private suspend fun clearLocal(plan:BackupRepository.RoleDeletion) {
        withContext(Dispatchers.Main.immediate) {
            AppMutationGate.read {
                store.memorySummary.requireEditable(plan.friendId)
                ChatRunnerBinding.stopRole(store.databaseName, plan.friendId)
            }
        }
        CompanionRuntime.foreground(plan.friendId)
        try {
            CompanionScheduler.cancel(context, plan.friendId).result.get()
            androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("lianxu_reply_accounting:"+plan.friendId).result.get()
            CompanionRuntime.mutex(plan.friendId).withLock { backup().deleteRole(plan, true) }
        } finally {
            CompanionRuntime.leave(plan.friendId)
        }
    }
    private suspend fun completeDelete(scope:HostRoleScope,m:RoleBaseline,plan:BackupRepository.RoleDeletion?) {
        if(!m.deleting) persist(m.copy(deleting=true))
        try {
            if(local(store.readableDatabase,m.link.friend)!=null) clearLocal(plan ?: backup().previewRoleDeletion(m.link.friend))
        } catch(e:Exception) {
            if(local(store.readableDatabase,m.link.friend)!=null) persist(m.copy(deleting=false))
            throw e
        }
        scope.delete(m.link.host)
        forget(m)
    }
}
