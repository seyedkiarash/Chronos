package com.chronos.navigator.llm.client

import com.chronos.navigator.llm.LlmRequest
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message reaches a model and its whole answer comes back (your request of 27 Oct 2026). */
class SureDeliveryTest {

    @Test
    fun aCutOffAnswerIsAskedForTheRestAndJoined() {
        val req = LlmRequest("DISCUSS", JsonObject(), "sys", "Why this?", true)
        val more = LlmClient.continuation(req, "The first half {\"reply\":\"be")
        assertTrue(more.user().startsWith("Why this?"))
        assertTrue(more.user().contains("The first half {\"reply\":\"be"))
        assertFalse("no web search the second time", more.webSearch())
        // A model that repeats the end of what it wrote: the overlap is joined once.
        assertEquals("{\"reply\":\"because the week is full\"}",
            LlmClient.joinAt("{\"reply\":\"because the week", "because the week is full\"}"))
        assertEquals("abc def", LlmClient.joinAt("abc ", "def"))
        assertTrue(LlmClient.cutOff(LlmClient.Reply("x", emptyList(), "m", 1, 1, 0, "length")))
        assertTrue(LlmClient.cutOff(LlmClient.Reply("x", emptyList(), "m", 1, 1, 0, "max_tokens")))
        assertFalse(LlmClient.cutOff(LlmClient.Reply("x", emptyList(), "m", 1, 1, 0, "stop")))
    }

    @Test
    fun whatPassesIsTriedAgainAndWhatDoesNotIsKept() {
        assertTrue(NavAsk.passing("Software caused connection abort"))
        assertTrue(NavAsk.passing("Connection reset"))
        assertTrue(NavAsk.passing("HTTP 503 (the provider is busy; try again in a while)"))
        assertTrue(NavAsk.passing("Google Gemini: HTTP 429 (the provider's own limit is reached)"))
        assertTrue(NavAsk.passing("No network: a conversation needs it."))
        assertFalse(NavAsk.passing("That proposal is gone."))
        assertFalse(NavAsk.passing("This would pass your monthly LLM budget"))
    }

    @Test
    fun aKeyAtItsLimitRestsAndTheNextOneGoes() {
        val keys = listOf("sk-or-aaaaaaaaaa1111", "sk-or-bbbbbbbbbb2222")
        val first = KeyRing.take("p-test", keys)!!
        val second = KeyRing.take("p-test", keys)!!
        assertNotEquals("two at once go out on different keys", first, second)
        KeyRing.give("p-test", first, null)
        KeyRing.give("p-test", second, "HTTP 429 (the provider's own limit is reached): Rate limit exceeded: free-models-per-min")
        assertEquals(1, KeyRing.ready("p-test", keys))
        assertEquals("the resting key is passed over", first, KeyRing.take("p-test", keys))
        KeyRing.give("p-test", first, "HTTP 429: free-models-per-day")
        assertNull("both resting", KeyRing.take("p-test", keys))
        assertTrue(KeyRing.perKey("HTTP 429: quota exceeded"))
        assertFalse("a network block is not the key's", KeyRing.perKey("HTTP 403 (…): <!DOCTYPE html> <html lang=en>"))
        assertFalse(KeyRing.perKey("Software caused connection abort"))
    }

    @Test
    fun anAnswerCutOffInItsJsonIsShownAsWords() {
        val cut = "{\"message\": \"Your advisor's suggestion fits: \\\"T_EXT\\\" is where it lives.\",\"summary\": \"x\", \"questions\": [\"Is it"
        assertEquals("Your advisor's suggestion fits: \"T_EXT\" is where it lives.\n\n(The rest of this answer was cut off.)",
            CrewRun.readable(cut))
        assertEquals("plain words stay", "Plain words.", CrewRun.readable("Plain words."))
        val whole = "{\"message\":\"ok\"}"
        assertEquals("a whole JSON answer is left to the usual reading", whole, CrewRun.readable(whole))
    }
}
