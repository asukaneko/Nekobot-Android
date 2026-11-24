package app.lianxu.independent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder


internal data class SavedVoice(val id: String, val name: String)
internal data class VoiceTarget(val account: String, val voice: String, val generation: Long, val catalog: Long = 0, val provider: VoiceVendor = VoiceVendor.OTHER)
internal data class VoiceEligibility(val target: VoiceTarget? = null, val reason: VoiceReason? = null)
internal data class RequestedVoiceReply(val text: String, val target: VoiceTarget)

internal class BreezeVoiceAccount(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("breezeblue-roles", Context.MODE_PRIVATE)
    private val vault get() = ModelVault(app, "breezeblue-speech")
    private val account get() = prefs.getString("account", "").orEmpty()
    companion object {
        private val updates = Mutex()
        private val stateLock = Any()
        @Volatile private var switching = false
    }

    fun enabled(role: String) = prefs.getBoolean("allow:$role", false)
    fun enable(role: String, on: Boolean) = synchronized(stateLock) {
        prefs.edit().putBoolean("allow:$role", on).putLong("gen:$role", System.nanoTime()).apply()
    }
    fun voices(): List<SavedVoice> {
        val rows = JSONArray(prefs.getString("voices", "[]"))
        return List(rows.length()) { rows.getJSONObject(it).let { row -> SavedVoice(row.getString("id"),row.getString("name")) } }
    }
    fun selected(role: String) = prefs.getString("voice:$account:$role", null)
    fun bind(role: String, choice: SavedVoice, expectedAccount: String) = synchronized(stateLock) {
        require(account.isNotEmpty() && expectedAccount == account && choice in voices())
        prefs.edit().putString("voice:$expectedAccount:$role",choice.id).remove("blocked:$expectedAccount:$role").putLong("gen:$role", System.nanoTime()).apply()
    }
    fun auditionLine() = prefs.getString("audition-line", null) ?: BreezeVoiceStudio.DEFAULT_LINE
    fun saveAuditionLine(line: String) {
        prefs.edit().putString("audition-line", line.trim().ifBlank { BreezeVoiceStudio.DEFAULT_LINE }).apply()
    }
    fun accountId() = account
    // Preference publication is the commit point. Screens observe it even when the refresh caller is cancelled.
    val catalogChanges = callbackFlow {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
            if(key=="catalog" || key=="ready") trySend(Triple(account,prefs.getLong("catalog",0),prefs.getBoolean("ready",false)))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Triple(account,prefs.getLong("catalog",0),prefs.getBoolean("ready",false)))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    fun target(role: String) = eligibility(role).target

    fun eligibility(role: String): VoiceEligibility = synchronized(stateLock) {
        if (!enabled(role)) return VoiceEligibility(reason = VoiceReason.ROLE_DISABLED)
        if (switching) return VoiceEligibility(reason = VoiceReason.SERVICE_NOT_READY)
        val owner = account
        if (owner.isEmpty()) return VoiceEligibility(reason = VoiceReason.MISSING_CREDENTIAL)
        val record = java.io.File(app.noBackupFilesDir, "breezeblue-speech/tts-$owner.enc")
        if (!record.isFile && !java.io.File(record.path + ".bak").isFile)
            return VoiceEligibility(reason = VoiceReason.MISSING_CREDENTIAL)
        if (!prefs.getBoolean("ready", false)) return VoiceEligibility(reason = VoiceReason.SERVICE_NOT_READY)
        val voice = selected(role) ?: return VoiceEligibility(reason = VoiceReason.NO_VOICE)
        if (prefs.getString("blocked:$owner:$role", null) == voice)
            return VoiceEligibility(reason = VoiceReason.ROLE_VOICE_BLOCKED)
        if (voices().none { it.id == voice }) return VoiceEligibility(reason = VoiceReason.NO_VOICE)
        VoiceEligibility(VoiceTarget(owner, voice, prefs.getLong("gen:$role",0), prefs.getLong("catalog",0), VoiceVendor.BREEZEBLUE))
    }
    private fun currentKey() = account.takeIf { it.isNotEmpty() }?.let { ModelVault.existingSecret(app.noBackupFilesDir,"breezeblue-speech","tts-$it") }
    internal fun keyFor(owner:String):String? = currentKey()?.takeIf { owner.isNotEmpty() && account==owner && stableDigest(it)==owner }
    suspend fun synthesize(role: String, reply: RequestedVoiceReply, moreVoiceParts:Boolean=false): ByteArray {
        check(target(role) == reply.target)
        val provider = BreezeBlueSpeechProvider({
            currentKey()?.takeIf { target(role) == reply.target && stableDigest(it) == reply.target.account }
        }, AppServices.voiceConnections)
        return try { provider.synthesize(reply.text,reply.target.voice) }
        catch (e: BreezeBlueSpeechException) {
            val transient=e.code in 500..599 || e.reason in listOf(BreezeBlueSpeechException.Reason.NETWORK,BreezeBlueSpeechException.Reason.TIMEOUT)
            if (e.code in listOf(401,403,404,429) || e.reason==BreezeBlueSpeechException.Reason.MISSING_CREDENTIAL || transient && !moreVoiceParts)
                synchronized(stateLock) {
                    if (target(role)==reply.target) {
                        if(e.code==404) prefs.edit().putString("blocked:${reply.target.account}:$role",reply.target.voice).apply()
                        else prefs.edit().putBoolean("ready",false).apply()
                    }
                }
            throw e
        }
    }

    // Publish the new credential and catalog only after every requested page succeeds.
    suspend fun refresh(candidate: String? = null) = withContext(Dispatchers.IO) {
        updates.withLock {
            val key = candidate ?: currentKey() ?: error("请先配置 BreezeBlue")
            require(key.length in 1..8192 && key.all { it.code in 33..126 }) { "密钥格式无效" }
            val owner = stableDigest(key)
            val previous=account
            switching=candidate!=null && previous!=owner
            try {
                val models=JSONArray(readCatalog(key,"models"))
                require((0 until models.length()).any { models.getJSONObject(it).optString("model_id")=="breeze-tts-2" })
                val list = listPersonal(key)
                val json = JSONArray()
                list.forEach { json.put(JSONObject().put("id",it.id).put("name",it.name)) }
                currentCoroutineContext().ensureActive()
                if (candidate != null) vault.save(ModelConfig("tts-$owner","BreezeBlue","custom","https://api.breeze.blue","breeze-tts-2"),key)
                synchronized(stateLock) {
                    val edit=prefs.edit().putString("account",owner).putString("voices",json.toString())
                        .putLong("catalog",System.nanoTime()).putBoolean("ready",true)
                    prefs.all.keys.filter{it.startsWith("blocked:$owner:")}.forEach(edit::remove)
                    check(edit.commit())
                }
                if(previous.isNotEmpty() && previous!=owner) vault.delete("tts-$previous")
                list
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (owner == account) prefs.edit().putBoolean("ready",false).apply()
                throw IOException("无法更新 BreezeBlue 音色，请检查密钥、权限或网络")
            } finally { switching=false }
        }
    }
    private fun readCatalog(key:String,path:String):String {
        val c = AppServices.voiceConnections.open(URL("https://api.breeze.blue/v1/$path"))
        return try {
            c.instanceFollowRedirects=false; c.useCaches=false; c.connectTimeout=8000; c.readTimeout=15000
            c.setRequestProperty("xi-api-key",key)
            if(c.responseCode != 200) throw IOException("音色列表不可用")
            val bytes=java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf=ByteArray(8192)
                while(true){ val n=input.read(buf); if(n<0) break; require(bytes.size()+n<=2*1024*1024); bytes.write(buf,0,n) }
            }
            bytes.toString("UTF-8")
        } finally { c.disconnect() }
    }

    private fun listPersonal(key: String): List<SavedVoice> {
        val result = linkedMapOf<String,SavedVoice>()
        val seen = mutableSetOf<String>()
        var token = ""
        var page = 1
        do {
            require(page <= 200)
            val cursor = if (token.isEmpty()) "page=$page" else "next_page_token="+URLEncoder.encode(token,"UTF-8")
            val json = JSONObject(readCatalog(key,"voices?voice_type=personal&page_size=50&$cursor"))
            val rows=json.getJSONArray("voices")
            for (i in 0 until rows.length()) {
                val row=rows.getJSONObject(i);val id=row.getString("voice_id")
                require(id.isNotBlank());result[id]=SavedVoice(id,row.optString("name",id))
            }
            if(!json.getBoolean("has_more")) break
            token=if(json.isNull("next_page_token")) "" else json.getString("next_page_token")
            require(rows.length()>0 && (token.isEmpty() || seen.add(token)))
            page++
        } while(true)
        return result.values.toList()
    }
}
