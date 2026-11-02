package com.chronos.navigator.llm.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which provider answers first, and the daily conversation's safety note. */
class QuickTest {
    private fun profile(id: String) = Profile(id, "custom", id, ProviderKind.OPENAI_COMPATIBLE, "https://x.test/v1", "m", 0.0, 0.0)

    @Test
    fun theFasterProviderComesFirstAndARefusedOneLast() {
        val fast = profile("fast-1"); val slow = profile("slow-1"); val dead = profile("dead-1"); val unknown = profile("new-1")
        LlmHealth.record(fast, "key-aaaa", 900, null, "ping")
        LlmHealth.record(slow, "key-bbbb", 9_000, null, "ping")
        LlmHealth.record(dead, "key-cccc", 400, "HTTP 401 (the key is refused)", "ping")
        val order = listOf(dead, unknown, slow, fast).sortedBy { LlmHealth.score(it) }.map { it.id }
        assertTrue(order.toString(), order == listOf("fast-1", "slow-1", "new-1", "dead-1"))
    }

    @Test
    fun aBusyMomentDoesNotMarkAProviderBroken() {
        val p = profile("busy-1")
        LlmHealth.record(p, "key-dddd", 1_000, null, "ping")
        LlmHealth.record(p, "key-dddd", 30_000, "HTTP 503 (the provider is busy; try again in a while)", "quick")
        assertTrue(LlmHealth.score(p) < 10_000_000L)
    }

    @Test
    fun distressIsNoticedInPersianAndEnglish() {
        assertTrue(Companion.distress("دیگه نمی‌خوام زنده باشم"))
        assertTrue(Companion.distress("گاهی به خودکشی فکر می‌کنم"))
        assertTrue(Companion.distress("I want to end my life"))
        assertFalse(Companion.distress("امروز خیلی خسته بودم ولی کارها پیش رفت"))
        assertFalse(Companion.distress("I killed it in the exam today"))
    }
}
