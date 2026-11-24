package app.lianxu.independent

internal data class PurposeOptions(val visible:List<ModelConfig>,val hasAdditionalUnknown:Boolean)

/** Presentation only: keep the catalog's capability policy and the current selection. */
internal fun purposeOptions(configs:List<ModelConfig>,purpose:ModelPurpose,current:String,includeUnknown:Boolean):PurposeOptions {
    val visible=ProviderCatalog.configurations(configs,purpose,current,includeUnknown).distinctBy{it.id}
    val shown=visible.map{it.id}.toSet()
    val additional=purpose==ModelPurpose.EMBEDDING&&!includeUnknown&&configs.any{
        it.id !in shown&&ProviderCatalog.modelKind(it)==ProviderModelKind.UNKNOWN
    }
    return PurposeOptions(visible,additional)
}
