package com.nekobot.app.ui.screens.characters

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.ui.components.NekoDialog
import app.lianxu.independent.LianxuModule
import com.nekobot.app.data.repository.CharacterDeleteApproval

@Composable
internal fun CharacterDeletion(id:String,onCancel:()->Unit,onDelete:(CharacterDeleteApproval)->Unit) {
    val prefs=ServiceContainer.prefs
    val profile=remember(id) { if(prefs.isLocalMode) prefs.activeDbName.removeSuffix(".db")+".db" else null }
    var approval by remember(id){mutableStateOf<CharacterDeleteApproval?>(null)}
    var failed by remember(id){mutableStateOf(false)}
    LaunchedEffect(id) {
        try {
            approval=CharacterDeleteApproval(id,profile,profile?.let { LianxuModule.roles.hostLink(it,id) })
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){failed=true}
    }
    val shown=approval
    if(shown!=null) NekoDialog(
        onDismiss=onCancel,title=stringResource(R.string.character_delete_title),
        message=stringResource(R.string.character_delete_confirm)+(if(shown.link!=null) "\n\n"+stringResource(R.string.character_delete_single_warning) else ""),
        confirmText=stringResource(R.string.common_delete),cancelText=stringResource(R.string.common_cancel),
        destructive=true,onConfirm={ onDelete(shown) },onCancel=onCancel
    ) else if(failed) NekoDialog(onDismiss=onCancel,title=stringResource(R.string.character_delete_title),
        message=stringResource(R.string.character_link_check_failed),confirmText=stringResource(R.string.common_cancel),onConfirm=onCancel)
}
