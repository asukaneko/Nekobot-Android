package com.nekobot.app.data.local

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalApiKeyEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalCharacterMemoryEntity
import com.nekobot.app.data.local.db.LocalHookEntity
import com.nekobot.app.data.local.db.LocalMcpServerEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalSkillEntity
import com.nekobot.app.data.local.db.LocalTaskEntity
import com.nekobot.app.data.local.db.LocalToolEntity
import com.nekobot.app.data.local.db.LocalWorkflowEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntity
import com.nekobot.app.data.local.db.LocalWorldBookEntryEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * nbotcfg 配置导入器：从远程 URL 下载 .zip 格式的 nbotcfg 包，
 * 解密 config.nbotcfg，解析 bundle，并写入新的本地 db profile。
 *
 * 对应后端 `nbot/web/config_transfer.py` 的导出格式：
 * - ZIP 包含 config.nbotcfg（Fernet 加密的 JSON）+ manifest.json + portraits/
 * - 加密：PBKDF2HMAC-SHA256（390000 迭代）+ Fernet（AES-128-CBC + HMAC-SHA256）
 */
object NbotConfigImporter {

    private const val KDF_ITERATIONS = 390_000
    private val FERNET_VERSION: Byte = 0x80.toByte()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    data class ImportResult(
        val success: Boolean,
        val profileName: String? = null,
        val displayName: String? = null,
        val message: String,
        val imported: Int = 0
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 从远程 URL 下载 nbotcfg zip 并导入为新 db profile。
     *
     * @param url 远程服务器地址（如 https://server.com），将自动拼接 /api/config-transfer/export
     * @param token 服务器认证 token
     * @param password nbotcfg 加密密码
     * @param profileName 目标 db profile 名（不含扩展名），若已存在则覆盖
     * @param displayName profile 显示名
     */
    suspend fun importFromRemote(
        context: Context,
        url: String,
        token: String,
        password: String,
        profileName: String,
        displayName: String
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            // 1. 拼接导出端点
            val baseUrl = url.trimEnd('/')
            val exportUrl = "$baseUrl/api/config-transfer/export"
            val payloadJson = gson.toJson(JsonObject().apply { addProperty("password", password) })
            val req = Request.Builder().url(exportUrl).post(payloadJson.toRequestBody(JSON_TYPE))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .build()

            // 2. 下载 zip 字节
            val zipBytes: ByteArray?
            val dlErr: String?
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    zipBytes = null
                    dlErr = "HTTP ${resp.code}: ${resp.body?.string()?.take(200)}"
                } else {
                    zipBytes = resp.body?.bytes() ?: byteArrayOf()
                    dlErr = null
                }
            }
            if (dlErr != null || zipBytes == null || zipBytes.isEmpty()) {
                return@withContext ImportResult(false, message = dlErr ?: "下载失败：响应为空")
            }
            val bytes = zipBytes

            // 3. 解析 zip：提取 config.nbotcfg + portraits
            val extracted = extractZip(bytes)
            val cfgBytes = extracted["config.nbotcfg"]
                ?: return@withContext ImportResult(false, message = "ZIP 中未找到 config.nbotcfg")

            // 4. 解密 / 解析 bundle
            val bundleJson = parseBundle(cfgBytes, password)
                ?: return@withContext ImportResult(false, message = "解密失败：密码错误或格式不支持")

            // 5. 关闭目标 profile 已有连接（若存在），清空 db 文件以便重建
            NekobotDatabase.deleteProfileFile(context, profileName)
            Thread.sleep(100)
            // 同步清空目标 profile 的 token 用量记录（SharedPreferences 与 db 文件独立存储）
            val tokenPrefs = context.getSharedPreferences("token_usage_${profileName}.db", android.content.Context.MODE_PRIVATE)
            tokenPrefs.edit().clear().apply()
            // 创建空 db 并写入数据
            val db = NekobotDatabase.get(context, profileName)
            val importedCount = applyBundleToDb(db, bundleJson)
            // 把导入消息中的 token 用量同步写入 token_usage，让统计页能看到历史用量
            syncTokenUsageFromMessages(db, tokenPrefs)

            // 6. 立绘还原：将 portraits/ 下的图片复制到 cacheDir/portraits/<profile>/
            //    并把 bundle 中 /static/uploads/portraits/<filename> 形式的 URL 改写为本地 file:// URI，
            //    使 CharacterScreen / SessionScreen 等能直接加载本地立绘。
            val portraitDir = File(context.cacheDir, "portraits/$profileName")
            if (portraitDir.exists()) portraitDir.deleteRecursively()
            portraitDir.mkdirs()
            val portraitUrlMap = mutableMapOf<String, String>()  // 旧 filename → 新本地路径
            var portraitCount = 0
            extracted.entries.filter { it.key.startsWith("portraits/") }.forEach { (path, data) ->
                val name = path.removePrefix("portraits/")
                if (name.isNotEmpty()) {
                    val destFile = File(portraitDir, name)
                    destFile.writeBytes(data)
                    // 项目约定：portrait URI 用 Uri.fromFile() 生成标准 file:/// URI，Coil 才能正确加载
                    portraitUrlMap[name] = android.net.Uri.fromFile(destFile).toString()
                    portraitCount++
                }
            }
            // 用本地路径覆盖 db 中所有引用了 /static/uploads/portraits/ 的 portrait / sender_portrait / avatar 字段
            if (portraitUrlMap.isNotEmpty()) {
                rewritePortraitUrls(db, portraitUrlMap)
            }

            ImportResult(
                success = true,
                profileName = profileName,
                displayName = displayName,
                message = "导入成功，共迁移 $importedCount 项配置，$portraitCount 张立绘",
                imported = importedCount
            )
        } catch (e: Exception) {
            ImportResult(false, message = "导入异常：${e.message}")
        }
    }

    /** 解压 ZIP，返回文件名 → 字节 内容映射。 */
    private fun extractZip(zipBytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val buf = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zis.read(buffer)
                        if (n <= 0) break
                        buf.write(buffer, 0, n)
                    }
                    result[entry.name] = buf.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    /**
     * 解析 bundle：
     * - 加密 bundle：Fernet 解密 payload
     * - 明文 bundle：直接 JSON.parse
     */
    private fun parseBundle(cfgBytes: ByteArray, password: String): JsonObject? {
        val raw = String(cfgBytes, Charsets.UTF_8).trim()
        val bundle = JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val encrypted = bundle.get("encrypted")?.asBoolean ?: false
        if (!encrypted) {
            // 明文 bundle：configs 字段直接返回
            return bundle
        }
        val saltB64 = bundle.get("salt")?.asString ?: return null
        val payload = bundle.get("payload")?.asString ?: return null
        // Python Fernet 用 base64.urlsafe_b64encode 编码 salt 和 token，必须用 URL_SAFE 解码
        val salt = Base64.decode(saltB64, Base64.URL_SAFE or Base64.NO_WRAP) ?: return null
        val plaintext = decryptFernet(payload, password, salt) ?: return null
        return JsonParser.parseString(plaintext).takeIf { it.isJsonObject }?.asJsonObject
    }

    /**
     * 实现 Fernet token 解密：
     * 1. PBKDF2HMAC-SHA256 派生 32 字节密钥（前 16 字节 signing key，后 16 字节 encryption key）
     * 2. Base64 URL-safe 解码 payload
     * 3. 验证 HMAC-SHA256(signing_key, version||timestamp||iv||ciphertext)
     * 4. AES-128-CBC 解密 ciphertext，去除 PKCS7 padding
     */
    private fun decryptFernet(token: String, password: String, salt: ByteArray): String? {
        return try {
            val keySpec = PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, 256)
            val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val masterKey = keyFactory.generateSecret(keySpec).encoded
            val signingKey = masterKey.copyOfRange(0, 16)
            val encryptionKey = masterKey.copyOfRange(16, 32)

            // Fernet token 是 urlsafe base64 编码（带 padding），trim 防止首尾空白
            val tokenBytes = Base64.decode(token.trim(), Base64.URL_SAFE or Base64.NO_WRAP)
            // Fernet token 格式: version(1) + timestamp(8) + iv(16) + ciphertext(16n) + hmac(32)
            // 最小长度 = 1 + 8 + 16 + 16 + 32 = 73
            if (tokenBytes.size < 73) return null
            if (tokenBytes[0] != FERNET_VERSION) return null

            val iv = tokenBytes.copyOfRange(9, 25)
            val ciphertext = tokenBytes.copyOfRange(25, tokenBytes.size - 32)
            val hmac = tokenBytes.copyOfRange(tokenBytes.size - 32, tokenBytes.size)

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
            mac.update(tokenBytes, 0, tokenBytes.size - 32)
            val computedHmac = mac.doFinal()
            if (!computedHmac.contentEquals(hmac)) return null

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== JSON 安全取值 ====================

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString
    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asBoolean ?: default
    private fun JsonObject.int(key: String, default: Int = 0): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asInt ?: default
    private fun JsonObject.floatOrNull(key: String): Float? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asFloat
    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asInt
    private fun JsonObject.jsonStr(key: String): String? = get(key)?.let { if (it.isJsonNull) null else it.toString() }

    /**
     * 将 bundle 转换为本地 entity 并写入 db。返回写入条数。
     *
     * bundle 结构（与后端 build_plain_bundle 一致）：
     * {
     *   "version": 1, "type": "nbot_config_bundle", "exported_at": ...,
     *   "source": {...},
     *   "configs": {
     *     "ai_models": [...], "api_keys": [...], "personality": {...},
     *     "custom_personality_presets": [...], "sessions": {...}, "world_books": {...},
     *     "skills": [...], "tools": [...], "hooks": [...],
     *     "scheduled_tasks": [...], "workflows": [...], "mcp_servers": [...],
     *     "active_model_id": "...", "active_models_by_purpose": {...},
     *     "settings": {...}, "ai_config": {...}, "channels": [...],
     *     "heartbeat": {...}, "memories": [...]
     *   }
     * }
     */
    private suspend fun applyBundleToDb(db: NekobotDatabase, bundle: JsonObject): Int {
        var count = 0
        val now = nowIso()

        // 所有配置项在 bundle.configs 下
        val configs = bundle.getAsJsonObject("configs") ?: bundle

        // 将 dict 形式的对象统一转成 List<JsonObject>（sessions/world_books 是 dict 而非 list）
        fun asList(ele: JsonElement?): List<JsonObject> {
            if (ele == null || ele.isJsonNull) return emptyList()
            return when {
                ele.isJsonArray -> ele.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                ele.isJsonObject -> ele.asJsonObject.entrySet().mapNotNull { e ->
                    e.value.takeIf { it.isJsonObject }?.asJsonObject
                }
                else -> emptyList()
            }
        }

        // AI 模型
        // 导入阶段先全部置 active=false，后续按 purpose 选 p0（priority 最小且 enabled）为当前模型
        asList(configs.get("ai_models")).forEach { obj ->
            val id = obj.str("id") ?: UUID.randomUUID().toString()
            val purpose = obj.str("purpose") ?: "chat"
            db.aiModelDao().upsert(
                LocalAiModelEntity(
                    id = id,
                    name = obj.str("name") ?: "未命名",
                    protocol = obj.str("protocol") ?: "openai_chat",
                    provider = obj.str("provider"),
                    apiKey = obj.str("api_key") ?: "",
                    baseUrl = obj.str("base_url") ?: "",
                    model = obj.str("model") ?: "",
                    enabled = obj.bool("enabled", true),
                    purpose = purpose,
                    priority = obj.int("priority", 0),
                    active = false,
                    temperature = obj.floatOrNull("temperature"),
                    maxTokens = obj.intOrNull("max_tokens"),
                    topP = obj.floatOrNull("top_p"),
                    appendBaseUrlPath = obj.bool("append_base_url_path", true),
                    supportsStream = obj.bool("supports_stream", true),
                    createdAt = obj.str("created_at") ?: now
                )
            )
            count++
        }
        // 按 purpose 分组，每组选 priority 最小（p0）且 enabled=true 的模型设为 active
        run {
            val all = db.aiModelDao().listAll()
            all.groupBy { it.purpose.ifBlank { "chat" } }
                .forEach { (_, list) ->
                    val p0 = list
                        .filter { it.enabled }
                        .minByOrNull { it.priority }
                        ?: list.minByOrNull { it.priority }
                        ?: return@forEach
                    db.aiModelDao().setActiveForPurpose(p0.id, p0.purpose.ifBlank { "chat" })
                }
        }

        // API Keys
        asList(configs.get("api_keys")).forEach { obj ->
            val id = obj.str("id") ?: UUID.randomUUID().toString()
            db.apiKeyDao().upsert(
                LocalApiKeyEntity(
                    id = id,
                    name = obj.str("name") ?: "未命名",
                    key = obj.str("key") ?: obj.str("api_key") ?: "",
                    createdAt = obj.str("created_at") ?: now,
                    updatedAt = obj.str("updated_at") ?: now
                )
            )
            count++
        }

        // 角色卡：personality（dict 或 list）+ custom_personality_presets（list）
        // personality 是主角色，通常为单个对象或含 name/avatar 的 dict
        val personalityEle = configs.get("personality")
        val personalities = mutableListOf<JsonObject>()
        if (personalityEle?.isJsonObject == true) {
            // personality 是单个对象（主角色），直接加入
            personalities.add(personalityEle.asJsonObject)
        } else {
            asList(personalityEle).forEach { personalities.add(it) }
        }
        asList(configs.get("custom_personality_presets")).forEach { personalities.add(it) }
        personalities.forEach { obj ->
            val id = obj.str("id") ?: obj.str("preset_id") ?: UUID.randomUUID().toString()
            db.characterDao().upsert(
                LocalCharacterEntity(
                    id = id,
                    name = obj.str("name") ?: "未命名",
                    description = obj.str("description"),
                    avatar = obj.str("avatar"),
                    portrait = obj.str("portrait"),
                    tags = obj.jsonStr("tags"),
                    basicInfo = obj.str("basicInfo"),
                    personality = obj.str("personality"),
                    scenario = obj.str("scenario"),
                    firstMessage = obj.str("firstMessage") ?: obj.str("first_message"),
                    alternateGreetings = obj.jsonStr("alternateGreetings") ?: obj.jsonStr("alternate_greetings"),
                    exampleDialogues = obj.str("exampleDialogues") ?: obj.str("dialog_examples"),
                    responseFormat = obj.str("responseFormat"),
                    rules = obj.jsonStr("rules"),
                    state = obj.jsonStr("state"),
                    systemPrompt = obj.str("systemPrompt") ?: obj.str("system_prompt"),
                    greeting = obj.str("greeting"),
                    createdAt = obj.str("created_at") ?: now,
                    updatedAt = obj.str("updated_at") ?: now
                )
            )
            count++
        }

        // 世界书：configs.world_books 可能是 dict（{"world_books": {...}}）或 list
        val wbRoot = configs.get("world_books")
        val wbList: List<JsonObject> = when {
            wbRoot?.isJsonObject == true -> {
                // 内部可能是 {"world_books": [...]} 或直接是 {id: bookObj, ...}
                val inner = wbRoot.asJsonObject.get("world_books")
                if (inner != null) asList(inner) else asList(wbRoot)
            }
            else -> asList(wbRoot)
        }
        wbList.forEach { obj ->
            val id = obj.str("id") ?: UUID.randomUUID().toString()
            db.worldBookDao().upsert(
                LocalWorldBookEntity(
                    id = id,
                    name = obj.str("name") ?: "未命名世界书",
                    description = obj.str("description"),
                    characterId = obj.str("character_id"),
                    enabled = obj.bool("enabled", true),
                    createdAt = obj.str("created_at") ?: now,
                    updatedAt = obj.str("updated_at") ?: now
                )
            )
            // 世界书条目
            asList(obj.get("entries")).forEach { entry ->
                db.worldBookDao().upsertEntry(
                    LocalWorldBookEntryEntity(
                        id = entry.str("id") ?: UUID.randomUUID().toString(),
                        bookId = id,
                        keys = entry.jsonStr("keys") ?: entry.jsonStr("keywords") ?: "[]",
                        content = entry.str("content"),
                        comment = entry.str("comment"),
                        enabled = entry.bool("enabled", true),
                        constant = entry.bool("constant", false),
                        selective = entry.bool("selective", false),
                        insertionOrder = entry.int("insertion_order", 0),
                        priority = entry.int("priority", 0),
                        position = entry.str("position"),
                        caseSensitive = entry.bool("case_sensitive", false),
                        displayIndex = entry.int("display_index", 0)
                    )
                )
            }
            count++
        }

        // 会话：configs.sessions 是 dict（{session_id: sessionObj}）
        asList(configs.get("sessions")).forEach { obj ->
            val id = obj.str("id") ?: obj.str("session_id") ?: UUID.randomUUID().toString()
            db.sessionDao().upsert(
                LocalSessionEntity(
                    id = id,
                    name = obj.str("name") ?: "未命名会话",
                    characterId = obj.str("character_id"),
                    systemPrompt = obj.str("system_prompt"),
                    firstMessage = obj.str("first_message"),
                    scenario = obj.str("scenario"),
                    senderName = obj.str("sender_name"),
                    senderAvatar = obj.str("sender_avatar"),
                    characterName = obj.str("character_name"),
                    characterAvatar = obj.str("character_avatar"),
                    portrait = obj.str("portrait") ?: obj.str("sender_portrait"),
                    tags = obj.get("tags")?.let { if (it.isJsonArray) it.toString().trim('[', ']').replace("\"", "") else it.asString },
                    favorite = obj.bool("favorite"),
                    pinned = obj.bool("pinned"),
                    archived = obj.bool("archived"),
                    createdAt = obj.str("created_at") ?: now,
                    updatedAt = obj.str("updated_at") ?: now,
                    lastMessage = obj.str("last_message"),
                    messageCount = obj.int("message_count", 0),
                    plotMode = obj.bool("plot_mode"),
                    plotRealTimeSync = obj.bool("plot_realtime_sync") ?: obj.bool("plot_real_time_sync"),
                    plotChoiceStyle = obj.str("plot_choice_style"),
                    autoStateInterval = obj.int("auto_state_interval", 2),
                    disabledPromptKeys = obj.get("disabled_prompt_keys")?.let { if (it.isJsonArray) it.toString().trim('[', ']').replace("\"", "") else null },
                    customPrompts = obj.jsonStr("custom_prompts"),
                    promptStackDebug = obj.jsonStr("prompt_stack_debug"),
                    composedSystemPrompt = obj.str("composed_system_prompt"),
                    isPublic = obj.bool("is_public"),
                    proactiveChat = obj.jsonStr("proactive_chat"),
                    ttsConfig = obj.jsonStr("tts_config"),
                    shareConfig = obj.jsonStr("share_config"),
                    archiveSessionId = obj.str("archive_session_id")
                )
            )
            count++

            // 导入会话消息（session.messages 是 list）
            // 注意：跳过 role=system 的首条消息（已存入 session.system_prompt），避免重复
            asList(obj.get("messages")).forEachIndexed { idx, msg ->
                val msgId = msg.str("id") ?: "${id}_msg_$idx"
                val role = msg.str("role") ?: "user"
                // 跳过首条 system 消息（通常是 system_prompt 的副本，已存入 session）
                if (role == "system" && idx == 0) return@forEachIndexed
                val metadata = msg.getAsJsonObject("metadata")
                val inputTokens = metadata?.int("input_tokens", -1) ?: -1
                val outputTokens = metadata?.int("output_tokens", -1) ?: -1
                val model = metadata?.str("model") ?: msg.str("model")
                db.messageDao().upsert(
                    LocalMessageEntity(
                        id = msgId,
                        sessionId = id,
                        role = role,
                        content = msg.str("content") ?: "",
                        sender = msg.str("sender") ?: msg.str("sender_name"),
                        timestamp = msg.str("timestamp") ?: msg.str("created_at") ?: now,
                        model = model,
                        inputTokens = if (inputTokens >= 0) inputTokens else null,
                        outputTokens = if (outputTokens >= 0) outputTokens else null,
                        createdAt = msg.str("timestamp") ?: msg.str("created_at") ?: now
                    )
                )
                count++
            }
        }

        // Skills
        asList(configs.get("skills")).forEach { obj ->
            db.skillDao().upsert(
                LocalSkillEntity(
                    id = obj.str("id") ?: UUID.randomUUID().toString(),
                    name = obj.str("name") ?: "未命名",
                    description = obj.str("description"),
                    aliasesJson = obj.jsonStr("aliases") ?: "[]",
                    enabled = obj.bool("enabled", true),
                    parametersJson = obj.jsonStr("parameters"),
                    createdAt = obj.str("created_at") ?: now
                )
            )
            count++
        }

        // Tools（仅导入用户自定义工具，跳过与内置工具 ID 冲突的）
        asList(configs.get("tools")).forEach { obj ->
            val id = obj.str("id") ?: UUID.randomUUID().toString()
            if (com.nekobot.app.data.local.db.BuiltinTools.isBuiltin(id)) return@forEach
            db.toolDao().upsert(
                LocalToolEntity(
                    id = id,
                    name = obj.str("name") ?: "未命名",
                    description = obj.str("description"),
                    enabled = obj.bool("enabled", true),
                    parametersJson = obj.jsonStr("parameters"),
                    implementationJson = obj.jsonStr("implementation"),
                    builtin = false,
                    createdAt = obj.str("created_at") ?: now
                )
            )
            count++
        }

        // Hooks
        asList(configs.get("hooks")).forEach { obj ->
            db.hookDao().upsert(
                LocalHookEntity(
                    id = obj.str("id") ?: UUID.randomUUID().toString(),
                    name = obj.str("name") ?: "未命名",
                    event = obj.str("event") ?: "message_received",
                    description = obj.str("description"),
                    enabled = obj.bool("enabled", true),
                    scope = obj.str("scope") ?: "global",
                    priority = obj.int("priority", 100),
                    actionsJson = obj.jsonStr("actions") ?: "[]",
                    conditionsJson = obj.jsonStr("conditions"),
                    permissionsJson = obj.jsonStr("permissions"),
                    timeoutMs = obj.int("timeout_ms", 3000),
                    maxRetries = obj.int("max_retries", 0),
                    triggerMode = obj.str("trigger_mode") ?: "always",
                    conditionLogic = obj.str("condition_logic") ?: "and",
                    characterId = obj.str("character_id"),
                    conversationId = obj.str("conversation_id"),
                    userId = obj.str("user_id"),
                    createdAt = obj.str("created_at") ?: now,
                    updatedAt = obj.str("updated_at") ?: now
                )
            )
            count++
        }

        // Scheduled tasks（映射到 LocalTaskEntity）
        asList(configs.get("scheduled_tasks")).forEach { obj ->
            db.taskDao().upsert(
                LocalTaskEntity(
                    id = obj.str("id") ?: UUID.randomUUID().toString(),
                    kind = obj.str("kind") ?: "custom",
                    name = obj.str("name") ?: "未命名任务",
                    description = obj.str("description"),
                    enabled = obj.bool("enabled", true),
                    trigger = obj.str("trigger") ?: obj.str("schedule") ?: "manual",
                    configJson = obj.jsonStr("config") ?: obj.jsonStr("args"),
                    targetSessionId = obj.str("target_session_id"),
                    prompt = obj.str("prompt"),
                    createdAt = obj.str("created_at") ?: now,
                    lastRun = obj.str("last_run") ?: obj.str("last_run_at"),
                    nextRun = obj.str("next_run")
                )
            )
            count++
        }

        // Workflows
        asList(configs.get("workflows")).forEach { obj ->
            db.workflowDao().upsert(
                LocalWorkflowEntity(
                    id = obj.str("id") ?: UUID.randomUUID().toString(),
                    name = obj.str("name") ?: "未命名工作流",
                    description = obj.str("description"),
                    enabled = obj.bool("enabled", true),
                    trigger = obj.str("trigger") ?: "manual",
                    configJson = obj.jsonStr("config") ?: obj.jsonStr("steps"),
                    createdAt = obj.str("created_at") ?: now
                )
            )
            count++
        }

        // MCP servers
        asList(configs.get("mcp_servers")).forEach { obj ->
            db.mcpServerDao().upsert(
                LocalMcpServerEntity(
                    id = obj.str("id") ?: UUID.randomUUID().toString(),
                    name = obj.str("name") ?: "未命名 MCP",
                    transport = obj.str("transport") ?: "streamable-http",
                    description = obj.str("description"),
                    enabled = obj.bool("enabled", true),
                    autoConnect = obj.bool("auto_connect", false),
                    connected = false,
                    toolCount = obj.int("tool_count", 0),
                    url = obj.str("url"),
                    command = obj.str("command"),
                    argsJson = obj.jsonStr("args"),
                    envJson = obj.jsonStr("env"),
                    builtin = obj.bool("builtin", false) || obj.bool("_builtin", false),
                    createdAt = obj.str("created_at") ?: now,
                    lastConnectedAt = obj.str("last_connected_at")
                )
            )
            count++
        }

        // MemoryFS：configs.memory_fs 是 dict（{path: MemoryFile}）
        // 路径模板（与原仓库 nbot/memory/fs.py 对齐）：
        //   characters/{char_id}/users/{user_id}/user_persona.md
        //   characters/{char_id}/users/{user_id}/character_persona.md
        //   characters/{char_id}/events/{conversation_id}.md
        //   characters/{char_id}/timeline.md
        //   characters/{char_id}/life_sim/{conversation_id}.md
        //   characters/{char_id}/users/{user_id}/recent_digest.md
        // 安卓端 LocalCharacterMemoryEntity 用 category 字段代替 path，需做映射
        val memoryFsRoot = configs.get("memory_fs")
        if (memoryFsRoot?.isJsonObject == true) {
            val fsObj = memoryFsRoot.asJsonObject
            val entities = mutableListOf<LocalCharacterMemoryEntity>()
            fsObj.entrySet().forEach { (path, fileEle) ->
                val file = fileEle.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val parsed = parseMemoryFsPath(path) ?: return@forEach
                // importance 原为 0-1 float，安卓端为 Int（0-100）
                val importanceFloat = file.get("importance")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
                val importanceInt = (importanceFloat * 100f).toInt().coerceIn(0, 100)
                val memoryIds = file.getAsJsonArray("memory_ids")?.toString() ?: "[]"
                entities.add(
                    LocalCharacterMemoryEntity(
                        id = file.str("path") ?: path,  // 用 path 作为唯一 ID（同 path 覆盖）
                        characterId = parsed.characterId,
                        targetId = parsed.targetId,
                        type = parsed.type,
                        category = parsed.category,
                        title = file.str("title") ?: parsed.defaultTitle,
                        summary = file.str("summary") ?: "",
                        content = file.str("content") ?: "",
                        importance = importanceInt,
                        emotionImpact = null,
                        sourceTurnId = file.str("source_event_id"),
                        createdAt = file.str("updated_at") ?: now,
                        expiresAt = null
                    ).also {
                        // memory_ids 暂不入库（LocalCharacterMemoryEntity 无对应字段），日志记录
                        @Suppress("UNUSED_VARIABLE")
                        val _ids = memoryIds
                    }
                )
            }
            if (entities.isNotEmpty()) {
                db.memoryDao().upsertAll(entities)
                count += entities.size
            }
        }

        return count
    }

    /**
     * 解析 MemoryFS 路径，提取 character_id / target_id / category / type。
     *
     * 返回 null 表示路径格式不匹配（如 legacy 路径），跳过该条记忆。
     */
    private data class ParsedMemoryPath(
        val characterId: String,
        val targetId: String,
        val category: String,
        val type: String,           // long / short / flash
        val defaultTitle: String
    )

    private fun parseMemoryFsPath(path: String): ParsedMemoryPath? {
        // 标准化：去掉开头 / 和空白
        val p = path.trim().trimStart('/')
        val parts = p.split('/')
        if (parts.size < 3 || parts[0] != "characters") return null
        val charId = parts[1]
        // characters/{char_id}/users/{user_id}/user_persona.md
        if (parts.size >= 5 && parts[2] == "users" && parts[4].endsWith(".md")) {
            val userId = parts[3]
            val fileName = parts[4].removeSuffix(".md")
            return when (fileName) {
                "user_persona" -> ParsedMemoryPath(charId, userId, "user_persona", "long", "用户人格")
                "character_persona" -> ParsedMemoryPath(charId, userId, "character_persona", "long", "角色人格")
                "recent_digest" -> ParsedMemoryPath(charId, userId, "recent_digest", "short", "近期摘要")
                else -> ParsedMemoryPath(charId, userId, "legacy", "long", fileName)
            }
        }
        // characters/{char_id}/events/{conversation_id}.md
        if (parts.size >= 4 && parts[2] == "events" && parts[3].endsWith(".md")) {
            val convId = parts[3].removeSuffix(".md")
            return ParsedMemoryPath(charId, convId, "important_event", "long", "重要事件")
        }
        // characters/{char_id}/timeline.md
        if (parts.size == 3 && parts[2] == "timeline.md") {
            return ParsedMemoryPath(charId, "", "important_event", "long", "时间线")
        }
        // characters/{char_id}/life_sim/{conversation_id}.md
        if (parts.size >= 4 && parts[2] == "life_sim" && parts[3].endsWith(".md")) {
            val convId = parts[3].removeSuffix(".md")
            return ParsedMemoryPath(charId, convId, "important_event", "long", "生活片段")
        }
        // legacy 路径：users/{id}.md / diary/* / plot/* / world/* / general.md
        return ParsedMemoryPath(charId, "", "legacy", "long", path.substringAfterLast('/').removeSuffix(".md"))
    }

    private fun nowIso(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    /**
     * 把 db 中所有 assistant 消息的 token 用量同步写入 token_usage SharedPreferences，
     * 让 TokenStatsScreen 能看到导入的历史用量。
     *
     * 仅写入有 input/output token 的消息，避免重复无意义记录。
     */
    private suspend fun syncTokenUsageFromMessages(db: NekobotDatabase, prefs: android.content.SharedPreferences) {
        try {
            val messages = db.messageDao().listAllAssistant()
            if (messages.isEmpty()) return
            val arr = com.google.gson.JsonArray()
            for (msg in messages) {
                val input = msg.inputTokens ?: continue
                val output = msg.outputTokens ?: continue
                if (input <= 0 && output <= 0) continue
                val ts = msg.timestamp.ifBlank { msg.createdAt }
                com.google.gson.JsonObject().apply {
                    addProperty("session_id", msg.sessionId)
                    addProperty("model", msg.model ?: "")
                    addProperty("input_tokens", input)
                    addProperty("output_tokens", output)
                    addProperty("total_tokens", input + output)
                    addProperty("timestamp", ts)
                    addProperty("source", "chat")
                    addProperty("purpose", "chat")
                    addProperty("date", ts.substringBefore("T").substringBefore(" ").ifBlank { ts })
                }.also { arr.add(it) }
            }
            if (arr.size() > 0) {
                prefs.edit().putString("records", arr.toString()).apply()
            }
        } catch (_: Exception) { }
    }

    /**
     * 遍历 db 中已导入的 sessions/characters，将引用了 /static/uploads/portraits/xxx
     * 的 portrait / sender_portrait / avatar / sender_avatar / character_avatar 字段
     * 改写为本地文件路径，使 UI 能直接通过 Coil 加载本地立绘。
     *
     * @param urlMap key=旧文件名（如 portrait_xxx.png），value=新本地绝对路径
     */
    private suspend fun rewritePortraitUrls(db: NekobotDatabase, urlMap: Map<String, String>) {
        if (urlMap.isEmpty()) return
        // 提取旧 URL 中的文件名 → 映射到本地路径
        fun rewrite(url: String?): String? {
            if (url.isNullOrBlank()) return url
            // 形如 /static/uploads/portraits/portrait_xxx.png 或 http://host/static/uploads/portraits/xxx.png
            val filename = url.substringAfterLast('/')
            return urlMap[filename] ?: url
        }

        db.sessionDao().listAll().forEach { sess ->
            val newPortrait = rewrite(sess.portrait)
            val newSenderAvatar = rewrite(sess.senderAvatar)
            val newCharAvatar = rewrite(sess.characterAvatar)
            if (newPortrait != sess.portrait || newSenderAvatar != sess.senderAvatar || newCharAvatar != sess.characterAvatar) {
                db.sessionDao().updatePortraits(
                    sess.id,
                    newPortrait,
                    newSenderAvatar,
                    newCharAvatar
                )
            }
        }

        db.characterDao().listAll().forEach { ch ->
            val newPortrait = rewrite(ch.portrait)
            val newAvatar = rewrite(ch.avatar)
            if (newPortrait != ch.portrait || newAvatar != ch.avatar) {
                db.characterDao().updatePortraits(ch.id, newPortrait, newAvatar)
            }
        }
    }
}
