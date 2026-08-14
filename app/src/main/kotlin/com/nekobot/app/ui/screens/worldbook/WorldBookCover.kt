package com.nekobot.app.ui.screens.worldbook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.nekobot.app.ServiceContainer

internal fun resolveWorldBookCoverUrl(path: String): String = when {
    path.startsWith("file:") || path.startsWith("content://") ||
        path.startsWith("http://") || path.startsWith("https://") -> path
    else -> ServiceContainer.network.baseUrl().trimEnd('/') + "/" + path.trimStart('/')
}

@Composable
internal fun WorldBookCover(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val source = coverUrl?.takeIf { it.isNotBlank() }?.let {
        remember(it) { resolveWorldBookCoverUrl(it) }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (source == null) {
            Icon(
                Icons.Filled.Book,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                modifier = Modifier.fillMaxSize(0.42f)
            )
        } else {
            AsyncImage(
                model = source,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
