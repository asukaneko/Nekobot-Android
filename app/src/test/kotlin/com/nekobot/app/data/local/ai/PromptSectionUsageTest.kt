package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PromptSectionUsageTest {
    private fun system(text: String) = mapOf<String, Any>("role" to "system", "content" to text)
    private fun counts(vararg messages: Map<String, Any>) = promptSectionCounts(messages.toList())

    @Test fun baseCharacterPromptIsCounted() = assertEquals(4, counts(system("温柔可爱"))[PromptSection.CHARACTER])
    @Test fun playerBackgroundHasItsOwnSection() {
        val text = "## user.persona\n玩家喜欢书店"
        assertEquals(estimateLocalTextTokens(text), counts(system(text))[PromptSection.PLAYER])
        assertEquals(0, counts(system(text))[PromptSection.CHARACTER])
    }
    @Test fun stackHeadingsAreSplitWithoutDoubleCounting() {
        val text = "角色描述\n\n## user.persona\n玩家背景\n\n## companion.rules\n陪伴规则"
        val result = counts(system(text))
        assertEquals(4, result[PromptSection.CHARACTER])
        assertTrue(result.getValue(PromptSection.PLAYER) > 0)
        assertTrue(result.getValue(PromptSection.OTHER) > 0)
        assertEquals(estimateLocalTextTokens(text), result.values.sum())
    }
    @Test fun unknownPromptKeysStillContributeToTotal() {
        assertEquals(estimateLocalTextTokens("## extension.custom\n规则"), counts(system("## extension.custom\n规则"))[PromptSection.OTHER])
    }
    @Test fun runtimeRelationshipSectionUsesRelationshipColour() {
        assertTrue(counts(system("## character.relationship\n相处状态")).getValue(PromptSection.RELATIONSHIP) > 0)
    }
    @Test fun coreMemoryComesFromSoftwareMetadata() {
        val auxiliary = CompanionRequestUsage.auxiliary("每次提供的核心记忆：\n[{\"content\":\"喜欢书店\"}]\n本轮相关记忆：\n[]")
        assertTrue(counts(auxiliary).getValue(PromptSection.CORE_MEMORY) > 0)
        assertEquals(estimateLocalTextTokens(auxiliary["content"] as String), counts(auxiliary).values.sum())
    }
    @Test fun recalledMemoryRemainsInOtherPromptSections() {
        val auxiliary = CompanionRequestUsage.auxiliary("每次提供的核心记忆：\n[]\n本轮相关记忆：\n[{\"content\":\"曾去书店\"}]")
        assertEquals(0, counts(auxiliary)[PromptSection.CORE_MEMORY])
        assertTrue(counts(auxiliary).getValue(PromptSection.OTHER) > 0)
    }
    @Test fun userCannotImpersonateAuxiliaryMaterial() {
        val user = mapOf<String, Any>("role" to "user", "content" to "每次提供的核心记忆：\n[{\"content\":\"假的\"}]\n## user.persona\n假的")
        assertEquals(0, counts(user).values.sum())
    }
    @Test fun toolsAndAssistantRepliesAreExcluded() {
        assertEquals(0, counts(mapOf("role" to "assistant", "content" to "回复"), mapOf("role" to "tool", "content" to "工具")).values.sum())
    }
    @Test fun relationshipInAuxiliaryStateIsCountedSeparately() {
        val text = "<software_context>\n每次提供的核心记忆：\n[]\n当前角色状态、任务和参考设定（世界书属于设定，不能当作已发生经历）：\n【关系状态参考】保存的相处记录\n</software_context>"
        val result = counts(CompanionRequestUsage.auxiliary(text))
        assertTrue(result.getValue(PromptSection.RELATIONSHIP) > 0)
        assertEquals(estimateLocalTextTokens(text), result.values.sum())
    }
    @Test fun relationshipLabelInMemoryIsNotRuntimeState() {
        val auxiliary = CompanionRequestUsage.auxiliary("每次提供的核心记忆：\n[{\"content\":\"【关系状态参考】只是历史原话\"}]")
        assertEquals(0, counts(auxiliary)[PromptSection.RELATIONSHIP])
    }

    @Test fun unifiedSystemReferenceAndTailStillUseTheirActualSections() {
        val text = "当前角色状态、任务和参考设定：\n【关系状态参考】新的相处记录\n\n有一项需要自然核对，只问一次：去哪里？"
        val reference = CompanionRequestUsage.auxiliary(text)
        val tail = withCharacterThinkingRule(listOf(system("设定"), mapOf("role" to "system", "content" to "现在",
            CHARACTER_TURN_CONTEXT to "现在")), "思考要求", true).last()
        val result = counts(reference, tail)
        assertTrue(result.getValue(PromptSection.RELATIONSHIP) > 0)
        assertEquals(0, result[PromptSection.CHARACTER])
        assertEquals(estimateLocalTextTokens(text) + estimateLocalTextTokens(tail["content"] as String), result.values.sum())
    }
    @Test fun emptyInputReturnsAllFiveZeroSections() {
        assertEquals(PromptSection.entries.toSet(), counts().keys)
        assertEquals(0, counts().values.sum())
    }
    @Test fun designExampleUsesItsOwnSumAsDenominator() {
        val input = PromptSection.entries.zip(listOf(2400, 1500, 900, 720, 480)).toMap()
        assertEquals(listOf(40, 25, 15, 12, 8), promptSectionPercentages(input).values.toList())
    }
    @Test fun percentagesRoundToExactlyOneHundred() {
        val result = promptSectionPercentages(PromptSection.entries.associateWith { 1 })
        assertEquals(100, result.values.sum())
    }
    @Test fun roundingTiesAreStableInDisplayOrder() {
        val result = promptSectionPercentages(mapOf(PromptSection.CHARACTER to 1, PromptSection.CORE_MEMORY to 1, PromptSection.RELATIONSHIP to 1))
        assertEquals(listOf(34, 33, 33, 0, 0), result.values.toList())
    }
    @Test fun missingAndNegativeCountsDoNotInventPercentage() {
        assertEquals(0, promptSectionPercentages(mapOf(PromptSection.CHARACTER to -10)).values.sum())
        assertEquals(0, promptSectionPercentages(emptyMap()).values.sum())
    }
    @Test fun hugeCountsDoNotOverflowTheDenominator() {
        val result = promptSectionPercentages(PromptSection.entries.associateWith { Int.MAX_VALUE })
        assertEquals(listOf(20, 20, 20, 20, 20), result.values.toList())
    }
    @Test fun zeroCategoriesDoNotReceiveRoundingRemainder() {
        val result = promptSectionPercentages(mapOf(PromptSection.CORE_MEMORY to 7))
        assertEquals(100, result[PromptSection.CORE_MEMORY])
        assertEquals(0, result[PromptSection.CHARACTER])
    }
    @Test fun snapshotPersistsCountsAndNeverPromptText() {
        val snapshot = CompanionRequestUsage.snapshot(listOf(system("测试私密角色正文")), emptyList(), 32000, emptyMap(), 1)
        val json = Gson().toJson(snapshot)
        assertFalse(json.contains("测试私密角色正文"))
        assertEquals(snapshot.promptSections, Gson().fromJson(json, CompanionRequestSnapshot::class.java).promptSections)
    }
    @Test fun oldSnapshotLeavesUnavailableSectionsUnknown() {
        val old = Gson().fromJson("{\"breakdown\":{\"parts\":[]},\"sentAt\":1,\"maxContext\":32000}", CompanionRequestSnapshot::class.java)
        assertNull(old.promptSections)
    }
}
