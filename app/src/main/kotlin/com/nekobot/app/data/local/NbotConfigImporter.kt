package com.nekobot.app.data.local

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.local.db.LocalApiKeyEntity
import com.nekobot.app.data.local.db.LocalCharacterEntity
import com.nekobot.app.data.local.db.LocalHookEntity
import com.nekobot.app.data.local.db.LocalMcpServerEntity
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
            // 创建空 db 并写入数据
            val db = NekobotDatabase.get(context, profileName)
            val importedCount = applyBundleToDb(db, bundleJson)

            // 6. 立绘还原：将 portraits/ 下的图片复制到 cacheDir/portraits/<profile>/
            val portraitDir = File(context.cacheDir, "portraits/$profileName")
            if (portraitDir.exists()) portraitDir.deleteRecursively()
            portraitDir.mkdirs()
            extracted.entries.filter { it.key.startsWith("portraits/") }.forEach { (path, data) ->
                val name = path.removePrefix("portraits/")
                if (name.isNotEmpty()) {
                    File(portraitDir, name).writeBytes(data)
                }
            }

            ImportResult(
                success = true,
                profileName = profileName,
                displayName = displayName,
                message = "导入成功，共迁移 $importedCount 项配置",
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

    /** 将 bundle 中的 18 个 CONFIG_KEYS 转换为本地 entity 并写入 db。返回写入条数。 */
    private suspend fun applyBundleToDb(db: NekobotDatabase, bundle: JsonObject): Int {
        var count = 0
        val now = nowIso()

        // AI 模型
        bundle.getAsJsonArray("ai_models")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val id = obj.str("id") ?: UUID.randomUUID().toString()
            val purpose = obj.str("purpose") ?: "chat"
            val activeModelsByPurpose = bundle.getAsJsonObject("active_models_by_purpose")
            val active = obj.get("active")?.takeIf { it.isJsonPrimitive }?.asBoolean
                ?: activeModelsByPurpose?.get(purpose)?.asString?.let { it == id }
                ?: (bundle.str("active_model_id") == id)
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
                    active = active,
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

        // API Keys
        bundle.getAsJsonArray("api_keys")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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

        // 角色卡（personality + custom_personality_presets）
        val personalities = mutableListOf<JsonObject>()
        bundle.getAsJsonArray("personality")?.forEach { if (it.isJsonObject) personalities.add(it.asJsonObject) }
        bundle.getAsJsonArray("custom_personality_presets")?.forEach { if (it.isJsonObject) personalities.add(it.asJsonObject) }
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

        // 世界书
        bundle.getAsJsonArray("world_books")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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
            obj.getAsJsonArray("entries")?.forEach { entryEle ->
                val entry = entryEle.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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

        // 会话
        bundle.getAsJsonArray("sessions")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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
                    portrait = obj.str("portrait"),
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
        }

        // Skills
        bundle.getAsJsonArray("skills")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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
        bundle.getAsJsonArray("tools")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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
        bundle.getAsJsonArray("hooks")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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
        bundle.getAsJsonArray("scheduled_tasks")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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
        bundle.getAsJsonArray("workflows")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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
        bundle.getAsJsonArray("mcp_servers")?.forEach { ele ->
            val obj = ele.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
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
                    builtin = obj.bool("builtin", false),
                    createdAt = obj.str("created_at") ?: now,
                    lastConnectedAt = obj.str("last_connected_at")
                )
            )
            count++
        }

        return count
    }

    private fun nowIso(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }
}
