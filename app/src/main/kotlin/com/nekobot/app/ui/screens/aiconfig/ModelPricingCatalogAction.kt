package com.nekobot.app.ui.screens.aiconfig

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.R

@Composable
fun ModelPricingCatalogRefreshAction() {
    val viewModel: ModelPricingCatalogViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.snapshot.updatedAt, state.error) {
        val text = state.error ?: state.message
        if (!text.isNullOrBlank()) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    IconButton(
        enabled = !state.refreshing,
        onClick = viewModel::refresh
    ) {
        if (state.refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.model_catalog_update_action)
            )
        }
    }
}
