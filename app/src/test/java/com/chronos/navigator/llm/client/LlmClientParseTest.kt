package com.chronos.navigator.llm.client

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reply is read as plain JSON: text, search result pages, usage and the cost they come to. */
class LlmClientParseTest {
    @Test
    fun aReplyWithSearchIsReadAndPriced() {
        val json = JsonParser.parseString("""
            {"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","stop_reason":"end_turn",
             "content":[
               {"type":"thinking","thinking":"","signature":"x"},
               {"type":"server_tool_use","id":"s1","name":"web_search","input":{"query":"term dates"}},
               {"type":"web_search_tool_result","tool_use_id":"s1","content":[
                  {"type":"web_search_result","url":"https://a.example/cal","title":"Cal"},
                  {"type":"web_search_result","url":"https://b.example/x","title":"X"}]},
               {"type":"text","text":"Here: {\"p50_hours\":10}"}],
             "usage":{"input_tokens":3000,"output_tokens":1500,"server_tool_use":{"web_search_requests":1}}}
        """.trimIndent()).asJsonObject
        val r = LlmClient.parse(json, "claude-opus-5-5")
        assertEquals(listOf("https://a.example/cal", "https://b.example/x"), r.urls)
        assertTrue(r.text.endsWith("{\"p50_hours\":10}"))
        assertEquals(1L, r.searches)
        // 3000 × $4/M + 1500 × $20/M + 1 search × $0.01
        assertEquals(0.012 + 0.03 + 0.01, r.costUsd, 1e-9)
    }

    @Test
    fun aModelOutsideThePriceListIsPricedAtTheDearest() {
        assertEquals(20.0, LlmClient.costUsd("claude-unknown", 0, 1_000_000, 0), 1e-9)
    }

    @Test
    fun anOpenAiCompatibleReplyIsReadAndPricedByTheProfile() {
        val json = JsonParser.parseString("""
            {"id":"c","model":"meta-llama/llama-3.3-70b-instruct:free",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok {\"a\":1}"}}],
             "usage":{"prompt_tokens":2000,"completion_tokens":500}}
        """.trimIndent()).asJsonObject
        val r = LlmClient.parseCompat(json, "x")
        assertEquals("ok {\"a\":1}", r.text)
        assertEquals(2000L, r.tokensIn)
        val free = Profile("p", "openrouter", "OpenRouter", ProviderKind.OPENAI_COMPATIBLE,
            "https://openrouter.ai/api/v1", r.model, 0.0, 0.0)
        assertEquals(0.0, LlmClient.costUsd(free, r), 0.0)
        val paid = free.copy(priceIn = 1.0, priceOut = 2.0)
        assertEquals(0.002 + 0.001, LlmClient.costUsd(paid, r), 1e-12)
    }

    @Test
    fun aFilteredReplyCountsAsARefusal() {
        val json = JsonParser.parseString("""{"choices":[{"finish_reason":"content_filter","message":{"content":null}}]}""").asJsonObject
        assertEquals("refusal", LlmClient.parseCompat(json, "x").stopReason)
    }
}
