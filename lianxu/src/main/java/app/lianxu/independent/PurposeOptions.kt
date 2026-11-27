package app.lianxu.independent

internal data class PurposeOptions(val visible:List<ModelConfig>,val hasAdditionalUnknown:Boolean)

/** Presentation only: keep the catalog's capability policy and the current selection. */
internal fun purposeOptions(configs:List<ModelConfig>,purpose:ModelPurpose,current:String,includeUnknown:Boolean,hostPurposes:Map<String,String>):PurposeOptions {
    val (mirrors, local) = configs.partition { it.id.isHostModel() }
    val hostKind = if (purpose == ModelPurpose.EMBEDDING) ModelPurpose.EMBEDDING.key else ModelPurpose.CHAT.key
    val visible = ProviderCatalog.configurations(local, purpose, current, includeUnknown) +
        mirrors.filter { hostPurposes[it.id] == hostKind }
    val shown=visible.map{it.id}.toSet()
    val additional=purpose==ModelPurpose.EMBEDDING&&!includeUnknown&&local.any{
        it.id !in shown&&ProviderCatalog.modelKind(it)==ProviderModelKind.UNKNOWN
    }
    return PurposeOptions(visible,additional)
}
