package com.nekobot.app.data.local

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Metadata first, then bounded reads of registered attachments only; never drain unknown entries. */
internal object PortableArchiveValidator {
    fun version(value: JsonElement?, supported: IntRange): Int {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
            value.asString.matches(Regex("[0-9]+"))) { "归档版本必须是整数" }
        val number = value.asString.toIntOrNull()
        require(number != null && number in supported) { "不支持的数据归档版本，请核对文件或升级应用" }
        return number
    }
    fun storySessions(graphJson: String, choiceSessions: Set<String>): Set<String> {
        val graph = com.google.gson.JsonParser.parseString(graphJson).asJsonObject
        val sessions = choiceSessions.toMutableSet()
        listOf("nodes", "choices", "edges").forEach { key ->
            graph[key]?.asJsonArray?.forEach { value ->
                val item = value.asJsonObject
                require(item["id"]?.takeUnless(JsonElement::isJsonNull)?.asString?.isNotBlank() == true) { "故事地图缺少稳定ID" }
                if (key == "nodes") sessions += item["conversation_id"]?.takeUnless(JsonElement::isJsonNull)?.asString
                    ?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("故事地图缺少会话ID")
            }
        }
        require(sessions.none(String::isBlank)) { "故事地图包含空会话ID" }
        return sessions
    }
    /** Return identity metadata only. Never expose decrypted values in previews, errors or logs. */
    fun credentialTargets(raw: ByteArray): Map<String, Set<String>> {
        PortableImportContentInspector.validateObject(raw)
        val root = com.google.gson.JsonParser.parseString(String(raw, Charsets.UTF_8)).asJsonObject
        version(root["version"], 1..1)
        val tables = mapOf("ai_models" to "local_ai_models", "api_keys" to "local_api_keys",
            "mcp_servers" to "local_mcp_servers", "oauth_accounts" to "local_oauth_accounts")
        require(root.keySet().all { it == "version" || it in tables }) { "凭据清单包含不支持的类别" }
        return tables.mapValues { (kind, _) ->
            val seen = hashSetOf<String>()
            root[kind]?.asJsonArray?.mapNotNullTo(linkedSetOf()) { value ->
                val item = value.asJsonObject
                val id = item["id"]?.takeUnless(JsonElement::isJsonNull)?.asString
                require(!id.isNullOrBlank() && seen.add(id)) { "凭据清单包含空ID或重复ID" }
                item.entrySet().forEach { (_, field) -> require(field.isJsonNull || field.isJsonPrimitive && field.asJsonPrimitive.isString) { "凭据清单字段格式无效" } }
                if (kind == "oauth_accounts" && item["credentials"]?.takeUnless(JsonElement::isJsonNull)?.asString.isNullOrEmpty()) null else id
            }.orEmpty()
        }.mapKeys { tables.getValue(it.key) }
    }
    fun entryName(raw: String): String {
        require(raw.isNotBlank() && !raw.startsWith('/') && !raw.startsWith('\\') &&
            !Regex("^[A-Za-z]:").containsMatchIn(raw) && !raw.contains('\u0000')) { "归档路径无效" }
        return raw.replace('\\', '/').also {
            require(it.split('/').none { part -> part.isBlank() || part == "." || part == ".." }) { "归档路径无效" }
        }
    }
    fun validateStructure(file: File) {
        ZipFile(file).use { zip ->
            val names = hashSetOf<String>()
            var total = 0L
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                val name = entryName(entry.name.removeSuffix("/"))
                require(names.add(name) && names.size <= 50000) { "归档路径重复或条目过多" }
                require(entry.size in 0..(128L * 1024 * 1024)) { "归档条目大小无效" }
                total += entry.size
                require(total <= 1024L * 1024 * 1024) { "归档解压后过大" }
            }
        }
    }
    fun validateFiles(file: File, inventory: JsonArray) {
        require(inventory.size() <= 50000) { "归档文件清单过大" }
        val expected = linkedMapOf<String, Pair<Long, String>>()
        inventory.forEach { raw ->
            val item = raw.asJsonObject
            val path = entryName(item["path"].asString)
            val size = item["size"].asLong
            val hash = item["sha256"].asString
            require(path.startsWith("files/") && size in 0..(64L * 1024 * 1024) && hash.matches(Regex("[0-9a-f]{64}"))) { "文件清单的路径、大小或哈希无效" }
            require(expected.put(path, size to hash) == null) { "文件清单路径重复" }
        }
        ZipFile(file).use { zip ->
            val actual = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("files/") }.map { it.name }.toSet()
            require(actual == expected.keys) { "归档附件与校验清单不一致" }
            var total = 0L
            expected.forEach { (path, descriptor) ->
                val entry = zip.getEntry(path) ?: error("归档缺少附件")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        size += count; total += count
                        require(size <= descriptor.first && total <= 1024L * 1024 * 1024) { "归档附件超过声明大小" }
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                require(size == descriptor.first && hash == descriptor.second) { "归档附件校验失败" }
            }
        }
    }
}
