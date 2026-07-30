package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPricingCatalogTest {
    @Test
    fun `openrouter response converts per-token prices to per-million prices`() {
        val snapshot = ModelPricingCatalog.parseOpenRouterResponse(
            json = """
                {
                  "data": [
                    {
                      "id": "openai/gpt-test",
                      "canonical_slug": "openai/gpt-test",
                      "name": "OpenAI: GPT Test",
                      "context_length": 128000,
                      "pricing": {
                        "prompt": "0.0000025",
                        "completion": "0.00001"
                      },
                      "supported_parameters": ["tools", "reasoning"]
                    }
                  ]
                }
            """.trimIndent(),
            updatedAt = "2026-07-30T00:00:00Z"
        )

        val entry = snapshot.entries.single()
        assertEquals(2.5, entry.inputPricePerMillion ?: -1.0, 0.000001)
        assertEquals(10.0, entry.outputPricePerMillion ?: -1.0, 0.000001)
        assertEquals(128_000, entry.contextLength)
        assertTrue(entry.supportsTools)
        assertTrue(entry.supportsReasoning)
    }

    @Test
    fun `matcher accepts provider prefix short id and dated provider aliases`() {
        val entry = ModelPricingEntry(
            id = "openai/gpt-4.1",
            name = "OpenAI: GPT-4.1",
            provider = "openai",
            inputPricePerMillion = 2.0,
            outputPricePerMillion = 8.0,
            aliases = listOf("gpt-4.1")
        )
        val snapshot = ModelPricingSnapshot(
            entries = listOf(entry),
            updatedAt = "2026-07-30T00:00:00Z",
            source = ModelPricingCatalog.SOURCE_OPENROUTER
        )

        assertEquals(entry, ModelPricingCatalog.find("gpt-4.1", catalog = snapshot))
        assertEquals(entry, ModelPricingCatalog.find("openai/gpt-4.1", catalog = snapshot))
        assertEquals(
            entry,
            ModelPricingCatalog.find("openai/gpt-4.1-2025-04-14", catalog = snapshot)
        )
    }

    @Test
    fun `manual price wins while missing side is filled from catalog`() {
        val prices = ModelPricingCatalog.resolvePrices(
            modelName = "gpt-4o",
            provider = "openai",
            inputPrice = 99.0,
            outputPrice = null
        )

        assertEquals(99.0, prices.first ?: -1.0, 0.000001)
        assertEquals(10.0, prices.second ?: -1.0, 0.000001)
        assertNotNull(ModelPricingCatalog.find("openai/gpt-4o"))
    }
}
