package com.nekobot.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nekobot.app.ui.components.GlassCard

/**
 * RAG 检索设置页面：语义权重、返回数量、MMR 多样性、重排、得分阈值、引用标注。
 * 每个设置项使用 GlassCard 包裹，遵循 Material3 风格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagSettingsScreen(onBack: () -> Unit) {
    val vm: RagSettingsViewModel = viewModel()
    val config by vm.config.collectAsState()

    // 文本输入框的本地状态（避免输入过程中被 StateFlow 重置）
    var topKText by remember { mutableStateOf(config.topK.toString()) }
    var thresholdText by remember { mutableStateOf(config.scoreThreshold.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "RAG 检索设置",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ==================== 语义检索权重 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "语义检索权重",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${(config.semanticWeight * 100).toInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = config.semanticWeight,
                    onValueChange = { vm.updateSemanticWeight(it) },
                    valueRange = 0f..1f
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "控制语义检索与词法检索的权重比例，值越大越依赖语义理解",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ==================== 返回结果数 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "返回结果数",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "检索后返回给 AI 的知识片段数量（1~20）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = topKText,
                    onValueChange = { input ->
                        // 仅允许数字
                        val filtered = input.filter { it.isDigit() }
                        topKText = filtered
                        filtered.toIntOrNull()?.let { num ->
                            if (num in 1..20) {
                                vm.updateTopK(num)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ==================== MMR 多样性系数 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "MMR 多样性系数",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "%.2f".format(config.mmrLambda),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = config.mmrLambda,
                    onValueChange = { vm.updateMmrLambda(it) },
                    valueRange = 0f..1f
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "0=最大多样性，1=最大相关性",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ==================== 重排开关 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "重排",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "启用后对检索结果进行二次排序，提升相关性但增加耗时",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.rerankEnabled,
                        onCheckedChange = { vm.updateRerankEnabled(it) }
                    )
                }
            }

            // ==================== 得分阈值 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "得分阈值",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "低于此分数的知识片段将被过滤（0.0~1.0）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = thresholdText,
                    onValueChange = { input ->
                        // 允许数字和小数点
                        val filtered = input.filter { it.isDigit() || it == '.' }
                        thresholdText = filtered
                        filtered.toFloatOrNull()?.let { num ->
                            if (num in 0f..1f) {
                                vm.updateScoreThreshold(num)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ==================== 引用标注开关 ====================
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "引用标注",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "在 AI 回答中显示引用来源角标，点击可查看来源详情",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.citationEnabled,
                        onCheckedChange = { vm.updateCitationEnabled(it) }
                    )
                }
            }
        }
    }
}
