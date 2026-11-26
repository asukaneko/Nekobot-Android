package app.lianxu.independent

internal data class PurposeOptions(val visible:List<ModelConfig>,val hasAdditionalUnknown:Boolean)

/** Presentation only: keep the catalog's capability policy and the current selection. */
internal fun purposeOptions(configs:List<ModelConfig>,purpose:ModelPurpose,current:String,includeUnknown:Boolean,hostPurposes:Map<String,String>):PurposeOptions {
    if (purpose == ModelPurpose.REVIEW) {
        val local = configs.filterNot { it.id.isHostModel() }
        val chatMirrors = configs.filter { it.id.isHostModel() && hostPurposes[it.id] == ModelPurpose.CHAT.key }
        return PurposeOptions(ProviderCatalog.configurations(local, purpose, current) + chatMirrors, false)
    }
    val hostOptions = if (purpose == ModelPurpose.MEMORY || purpose == ModelPurpose.AUX_VISION) configs.filter { it.id.isHostModel() } else emptyList()
    val visible=(ProviderCatalog.configurations(configs,purpose,current,includeUnknown) + hostOptions).distinctBy{it.id}
    val shown=visible.map{it.id}.toSet()
    val additional=purpose==ModelPurpose.EMBEDDING&&!includeUnknown&&configs.any{
        it.id !in shown&&ProviderCatalog.modelKind(it)==ProviderModelKind.UNKNOWN
    }
    return PurposeOptions(visible,additional)
}
