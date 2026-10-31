package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.llm.Discuss;
import com.chronos.navigator.llm.EstimatePrior;
import com.chronos.navigator.llm.LlmRequest;
import com.chronos.navigator.llm.ProposalValidator;
import com.chronos.navigator.llm.Replan;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Overrides;
import com.chronos.navigator.trigger.Triggers;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Talking a proposal over with the model, and asking it to refine a value. */
public class DiscussTest {

    static final LocalDate TODAY = ForecastTest.TODAY;
    static final String SELF = "\"consent_self_check\":{\"involves_other_person\":false}";

    @Test
    public void theDiscussionCarriesTheQuestionTheProposalAndTheThread() {
        Graph g = ForecastTest.answered();
        LlmRequest original = Replan.replan(g, TODAY, Triggers.PROB_DROP, List.of("T_READING"), "test", Map.of(), null);
        LlmRequest d = Discuss.request(original, "Title: More survey hours",
            List.of(new Discuss.Turn("you", "why 6?"), new Discuss.Turn("model", "because…")), "I have classes on Sunday");
        assertEquals(original.task(), d.task());
        assertTrue(d.target().get("discussion").getAsBoolean());
        assertTrue(d.user().startsWith(original.user()));
        assertTrue(d.user().contains("Student: why 6?"));
        assertTrue(d.user().endsWith("Student: I have classes on Sunday"));
        assertTrue(d.system().contains("revise"));
    }

    @Test
    public void aRevisionIsReadAndPassesTheOriginalChecks() {
        Graph g = ForecastTest.answered();
        LlmRequest original = Replan.replan(g, TODAY, Triggers.PROB_DROP, List.of("T_READING"), "test", Map.of(), null);
        String answer = "Fair point.\n{\"reply\":\"Four hours then, away from your class days.\",\"revise\":true,"
            + "\"revised\":{\"options\":[{\"title\":\"Fewer hours\",\"actions\":[{\"type\":\"extra_hours\",\"node\":\"T_READING\","
            + "\"hours_per_week\":4,\"from\":\"2026-10-14\",\"until\":\"2026-11-10\"}]}]}," + SELF + "}";
        Discuss.Answer a = Discuss.read(answer);
        assertNotNull(a);
        assertEquals("Four hours then, away from your class days.", a.reply());
        assertNotNull(a.revised());
        List<ProposalValidator.Result> r = Replan.validate(g, TODAY, original, a.revised().toString());
        assertTrue(r.get(0).reason(), r.get(0).ok());
        assertEquals(4.0, r.get(0).ops().get(0).values().get("value"), 1e-9);
    }

    @Test
    public void aReplyWithoutRevisionOrInProseIsStillAReply() {
        Discuss.Answer kept = Discuss.read("{\"reply\":\"I would keep six: …\",\"revise\":false,\"revised\":null," + SELF + "}");
        assertNull(kept.revised());
        assertEquals("I would keep six: …", kept.reply());
        Discuss.Answer prose = Discuss.read("Because the survey has fifteen papers.");
        assertNull(prose.revised());
        assertFalse(prose.reply().isEmpty());
        assertNull(Discuss.read("  "));
    }

    @Test
    public void refiningAValueReplacesWhatTheFileStates() {
        Graph g = ForecastTest.answered();
        LlmRequest req = EstimatePrior.refine(g, TODAY, Overrides.nodeKey("T_REPORT", "effort"), "it moves the odds a lot");
        assertTrue(req.target().get("update").getAsBoolean());
        assertTrue(req.user().contains("refine"));
        String answer = "{\"p50_hours\":30,\"p90_hours\":60,\"rationale\":\"r\"," + SELF + ",\"summary\":\"s\"}";
        ProposalValidator.Result r = ProposalValidator.validate(g, TODAY, req, answer, List.of());
        assertTrue(r.reason(), r.ok());
        Map<String, Double> v = r.ops().get(0).values();
        assertEquals(1.0, v.get("override"), 0);
        assertEquals(g.node("T_REPORT").effortP50, v.get("was_p50"), 1e-9);

        LlmRequest mix = EstimatePrior.refine(g, TODAY, Overrides.regimeKey("LATER", "mix"), "");
        String m = "{\"ideal\":0.3,\"busy\":0.5,\"stress\":0.2,\"strength_days\":2,\"rationale\":\"r\"," + SELF + "}";
        ProposalValidator.Result rm = ProposalValidator.validate(g, TODAY, mix, m, List.of());
        assertTrue(rm.reason(), rm.ok());
        assertEquals(1.0, rm.ops().get(0).values().get("override"), 0);
    }
}
