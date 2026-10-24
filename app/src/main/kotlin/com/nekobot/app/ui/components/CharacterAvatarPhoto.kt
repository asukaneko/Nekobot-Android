package com.nekobot.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.repeatCount
import com.nekobot.app.data.model.characterImagePath

/** A missing/undecodable old avatar falls back to the portrait, then to the name. */
@Composable
internal fun CharacterAvatarPhoto(avatar: String?, portrait: String?, name: String, modifier: Modifier = Modifier) {
    val candidates = remember(avatar, portrait) { listOfNotNull(characterImagePath(avatar), characterImagePath(portrait)).distinct() }
    var failed by remember(candidates) { mutableStateOf(emptySet<String>()) }
    val current = candidates.firstOrNull { it !in failed }
    val context = LocalContext.current
    val request = remember(context, current) {
        current?.let { ImageRequest.Builder(context).data(resolveAvatarUrl(it)).crossfade(false).repeatCount(0).build() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (current == null) Text(name.trim().takeIf(String::isNotEmpty)?.let { it.substring(0, it.offsetByCodePoints(0, 1)) }.orEmpty(),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        else AsyncImage(request, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onError = { failed = failed + current })
    }
}
