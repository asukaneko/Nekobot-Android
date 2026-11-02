package com.nekobot.app.data.local

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.model.Session

private val sessionMapperGson = Gson()

internal fun LocalSessionEntity.toSession(): Session = Session(
    id = id,
    name = name,
    listTitleOverride = listTitleOverride,
    type = "local",
    systemPrompt = systemPrompt,
    characterId = characterId,
    firstMessage = firstMessage,
    scenario = scenario,
    senderName = senderName,
    senderAvatar = senderAvatar,
    characterName = characterName,
    characterAvatar = characterAvatar,
    portrait = portrait,
    tags = tags?.split(",")?.filter { it.isNotEmpty() },
    favorite = favorite,
    pinned = pinned,
    archived = archived,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessage = lastMessage,
    messageCount = messageCount,
    plotMode = plotMode,
    plotRealTimeSync = plotRealTimeSync,
    plotChoiceStyle = plotChoiceStyle,
    plotOutline = plotOutline,
    userPersona = userPersona,
    autoStateInterval = autoStateInterval,
    autoNameInterval = autoNameInterval,
    disabledPromptKeys = disabledPromptKeys?.split(",")?.filter { it.isNotEmpty() },
    customPrompts = customPrompts?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
    promptStackDebug = promptStackDebug?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
    composedSystemPrompt = composedSystemPrompt,
    isPublic = isPublic,
    proactiveChat = proactiveChat?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
    ttsConfig = ttsConfig?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
    shareConfig = shareConfig?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
    archiveSessionId = archiveSessionId,
    sessionMode = sessionMode,
    groupId = groupId,
    characterIds = characterIds?.let {
        runCatching {
            val type = object : TypeToken<List<String>>() {}.type
            sessionMapperGson.fromJson<List<String>>(it, type)
        }.getOrNull()
    },
    groupConfig = groupConfig?.let { runCatching { JsonParser.parseString(it) }.getOrNull() },
    agentTodos = agentTodos,
    agentGoal = agentGoal,
    agentSpec = agentSpec,
    inheritCharacter = inheritCharacter,
    inheritCharacterGreeting = inheritCharacterGreeting,
    longConversationEnabled = longConversationEnabled,
    companionMode = companionMode,
    delayReplyEnabled = delayReplyEnabled,
    delayReplyDelaySeconds = delayReplyDelaySeconds
)
