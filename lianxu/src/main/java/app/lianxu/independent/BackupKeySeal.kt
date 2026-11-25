package app.lianxu.independent

import com.google.gson.JsonObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * API keys inside a full backup are locked with a password the user sets on export:
 * PBKDF2-HMAC-SHA256 with a random salt derives an AES-256 key, and AES-GCM seals every key at once.
 * The rest of the backup stays readable; only the keys need the password.
 */
internal object BackupKeySeal {
    const val FIELD="sealedKeys"
    const val MIN_PASSWORD=6
    private const val ITERATIONS=210_000
    private const val AAD="lianxu-backup-keys-v1"
    class WrongPassword:IllegalArgumentException("备份密码不对，请输入导出这份备份时设的密码。")
    class NeedsPassword:IllegalArgumentException("这份备份里的密钥加了密码，请先输入备份密码。")

    fun sealed(root:JsonObject)=root.has(FIELD)
    /** Why a new password can't be used, or null when it can. */
    fun passwordProblem(first:CharArray,second:CharArray):String?=when{
        first.size<MIN_PASSWORD->"密码至少 $MIN_PASSWORD 位"
        !first.contentEquals(second)->"两次输入的密码不一样"
        else->null
    }

    private fun derive(password:CharArray,salt:ByteArray,iterations:Int):SecretKeySpec {
        val spec=PBEKeySpec(password,salt,iterations,256)
        try{return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")}finally{spec.clearPassword()}
    }
    private fun b64(bytes:ByteArray)=Base64.getEncoder().encodeToString(bytes)
    private fun unb64(o:JsonObject,name:String):ByteArray{
        val v=o[name];require(v!=null&&v.isJsonPrimitive&&v.asJsonPrimitive.isString){"备份密钥锁缺少字段：$name"}
        return Base64.getDecoder().decode(v.asString)
    }

    /** Moves every model's key out of the plain JSON into one sealed block. */
    fun seal(root:JsonObject,password:CharArray,iterations:Int=ITERATIONS,random:SecureRandom=SecureRandom()):JsonObject {
        require(password.size>=MIN_PASSWORD){"备份密码至少 $MIN_PASSWORD 位"}
        val copy=root.deepCopy();val keys=JsonObject()
        copy["models"].asJsonArray.forEach{m->val j=m.asJsonObject;val key=j.remove("key");if(key!=null&&!key.isJsonNull)keys.add(j["config"].asJsonObject["id"].asString,key)}
        val salt=ByteArray(16).also(random::nextBytes);val iv=ByteArray(12).also(random::nextBytes)
        val plain=keys.toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,derive(password,salt,iterations),GCMParameterSpec(128,iv));updateAAD(AAD.toByteArray())}
        val data=try{cipher.doFinal(plain)}finally{plain.fill(0)}
        copy.add(FIELD,JsonObject().apply{addProperty("v",1);addProperty("kdf","PBKDF2WithHmacSHA256");addProperty("iterations",iterations);addProperty("salt",b64(salt));addProperty("iv",b64(iv));addProperty("data",b64(data))})
        return copy
    }

    /** Puts the keys back into the models; a wrong password is reported as such, never as a broken file. */
    fun open(root:JsonObject,password:CharArray?):JsonObject {
        if(!sealed(root))return root
        return LocalStep.UNSEAL.observe {
            val supplied = password ?: throw NeedsPassword()
            val lock=root[FIELD].asJsonObject
            require(lock["v"]?.asInt==1&&lock["kdf"]?.asString=="PBKDF2WithHmacSHA256"){"不支持的备份密钥锁"}
            val iterations=lock["iterations"].asInt;require(iterations in 100_000..5_000_000){"备份密钥锁参数无效"}
            val salt=unb64(lock,"salt");val iv=unb64(lock,"iv");val data=unb64(lock,"data")
            require(salt.size==16&&iv.size==12&&data.size in 16..1_000_000){"备份密钥锁参数无效"}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,derive(supplied,salt,iterations),GCMParameterSpec(128,iv));updateAAD(AAD.toByteArray())}
            val plain=try{cipher.doFinal(data)}catch(_:AEADBadTagException){throw WrongPassword()}
            val keys=try{com.google.gson.JsonParser.parseString(String(plain,Charsets.UTF_8)).asJsonObject}finally{plain.fill(0)}
            val copy=root.deepCopy();copy.remove(FIELD)
            copy["models"].asJsonArray.forEach{m->val j=m.asJsonObject;val id=j["config"].asJsonObject["id"].asString;keys[id]?.let{j.add("key",it)}}
            copy
        }
    }
    /** Just checks the password, for the import dialog. */
    fun check(root:JsonObject,password:CharArray){open(root,password)}
}
