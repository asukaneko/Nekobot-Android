package app.lianxu.independent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface HostModelBridge {
    suspend fun listLocalModels(): List<HostModelSnapshot>
}

// Keep credentials out of generated toString output.
class HostModelSnapshot(
    val hostId: String, val name: String, val baseUrl: String, val model: String,
    val apiKey: String, val purpose: String, val active: Boolean,
    val contextTokens: Int?, val outputTokens: Int?
) {
    internal fun config(): ModelConfig {
        val provider = ProviderPresets.all.firstOrNull { it.id != "custom" && it.base == baseUrl }?.id ?: "custom"
        val limits = ModelTokenPolicy.capability(provider, baseUrl, model)
        val defaults = ModelTokenPolicy.defaults(provider, baseUrl, model)
        val context = (contextTokens?.takeIf { it > 0 } ?: defaults.context).coerceIn(4096, limits?.context ?: 2000000)
        val output = (outputTokens?.takeIf { it > 0 } ?: defaults.chat)
            .coerceIn(1, minOf(context - 1, limits?.maxOutput ?: ModelTokenPolicy.MAX_OUTPUT))
        return ModelConfig("host-" + stableDigest(hostId).take(24), name, provider, baseUrl, model, context, output)
    }
}

internal fun String.isHostModel() = startsWith("host-")
internal const val FOLLOW_PREFIX = "host-model-follow:"

internal fun followedModel(purpose: ModelPurpose, models: List<HostModelSnapshot>, current: String, follow: Boolean): String {
    if (!follow || purpose !in listOf(ModelPurpose.CHAT, ModelPurpose.EMBEDDING)) return current
    val eligible = models.filter { it.purpose == purpose.key }
    val preferred = eligible.firstOrNull { it.active } ?: eligible.firstOrNull { purpose == ModelPurpose.CHAT }
    return preferred?.config()?.id ?: current.takeUnless { it.isHostModel() }.orEmpty()
}

internal class HostModelChanges(models: List<HostModelSnapshot>, existing: List<ModelConfig>, readKey: (String) -> String) {
    val writes = mutableMapOf<ModelConfig, String>()
    val purposes = mutableMapOf<String, String>()
    val removed: Set<String>

    init {
        val mirrors = existing.filter { it.id.isHostModel() }.associateBy { it.id }
        for (model in models) {
            val config = model.config()
            val old = mirrors[config.id]
            if (ModelKeyDraft.needsWrite(old, config, old?.let { readKey(it.id) }.orEmpty(), model.apiKey)) {
                writes[config] = model.apiKey
            }
            purposes[config.id] = model.purpose
        }
        removed = mirrors.keys - purposes.keys
    }
}

internal object HostModelSync {
    private val mutex = Mutex()

    suspend fun refresh(store: Store, vault: ModelVault, bridge: HostModelBridge = LianxuModule.hostModels) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val models = bridge.listLocalModels()
            AppMutationGate.read {
                val changes = HostModelChanges(models, vault.list(), vault::secret)
                changes.writes.forEach { (config, key) -> vault.save(config, key) }
                for (purpose in listOf(ModelPurpose.CHAT, ModelPurpose.EMBEDDING)) {
                    val follow = store.readableDatabase.rawQuery("SELECT value FROM preferences WHERE key=?",
                            arrayOf(FOLLOW_PREFIX + purpose.key)).use { !it.moveToFirst() || it.getString(0) == "1" }
                    val current = vault.purposeId(purpose)
                    val next = followedModel(purpose, models, current, follow)
                    if (next != current) vault.setPurpose(purpose, next)
                }
                changes.removed.forEach(vault::delete)
                changes.purposes.toMap()
            }
        }
    }

    fun select(store: Store, vault: ModelVault, purpose: ModelPurpose, id: String) = AppMutationGate.read {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO preferences(key,value) VALUES(?, '0')",
                arrayOf(FOLLOW_PREFIX + purpose.key))
            vault.setPurpose(purpose, id)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
