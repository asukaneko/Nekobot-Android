package com.nekobot.app.data.local.automation

data class AutomationExecutionResult(
    val title: String,
    val content: String = "",
    val sessionId: String? = null,
    val notify: Boolean = true
)
