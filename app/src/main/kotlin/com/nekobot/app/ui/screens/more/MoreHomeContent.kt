package com.nekobot.app.ui.screens.more

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nekobot.app.R
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.navigation.rememberLiquidGlassBottomBarClearance

/** Lock navigation during a transition, then allow a fresh entry when this page resumes. */
@Composable
internal fun rememberMoreNavigateOnce(
    onNavigate: (String) -> Unit,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
): (String) -> Unit {
    var navigationInFlight by remember(lifecycleOwner) { mutableStateOf(false) }
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) navigationInFlight = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(lifecycleOwner) {
        { route ->
            if (!navigationInFlight && lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
                navigationInFlight = true
                currentOnNavigate(route)
            }
        }
    }
}

/** Retained for compatibility with the paused category layout; not the active More entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreHomeContent(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState()
) {
    val bottomBarClearance = rememberLiquidGlassBottomBarClearance()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.more_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize()
                    .testTag("more-home-scroll")
                    .verticalScroll(scrollState)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomBarClearance + 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                MoreHomeGroup {
                    MoreHomeRow(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.global_search_title),
                        route = Routes.GLOBAL_SEARCH,
                        onNavigate = onNavigate
                    )
                }
                MoreHomeGroup {
                    MoreCategory.entries.forEachIndexed { index, category ->
                        if (index > 0) MoreHomeDivider()
                        MoreHomeRow(
                            icon = category.icon,
                            title = stringResource(category.title),
                            subtitle = stringResource(category.summary),
                            route = category.route,
                            onNavigate = onNavigate
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MoreHomeGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(
            text = title,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ) {
            Column(content = content)
        }
    }
}

@Composable
internal fun MoreHomeDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 48.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    )
}

@Composable
internal fun MoreHomeRow(
    icon: ImageVector,
    title: String,
    route: String,
    onNavigate: (String) -> Unit,
    subtitle: String? = null
) {
    MoreHomeActionRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        tag = "more-home-$route",
        onClick = { onNavigate(route) }
    )
}

@Composable
private fun MoreHomeActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
