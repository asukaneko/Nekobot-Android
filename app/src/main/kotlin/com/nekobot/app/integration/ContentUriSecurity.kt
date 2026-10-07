package com.nekobot.app.integration

import android.content.Context
import android.net.Uri

/** 外部 URI 不得借助应用自身 UID 读取私有 Provider。 */
internal fun Context.isOwnedContentProvider(uri: Uri): Boolean {
    if (!uri.scheme.equals("content", ignoreCase = true)) return false
    val authority = uri.authority.orEmpty().substringAfterLast('@').lowercase()
    val packageAuthority = packageName.lowercase()
    if (authority == packageAuthority || authority.startsWith("$packageAuthority.")) return true
    val provider = runCatching { packageManager.resolveContentProvider(uri.authority.orEmpty(), 0) }
        .getOrNull()
        ?: return false
    return provider.applicationInfo?.uid == applicationInfo.uid
}
