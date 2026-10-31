package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.llm.EstimatePrior;
import com.chronos.navigator.llm.LlmRequest;
import com.chronos.navigator.llm.Op;
import com.chronos.navigator.llm.ProposalValidator;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Issue;
import com.chronos.navigator.model.Issue.Fix;
import com.chronos.navigator.model.Issue.Severity;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

/** What the LLM is shown, and every way a bad answer is refused (spec: "LLM validation" test). */
public class LlmLayerTest {

    static final LocalDate TODAY = LocalDate.parse("2026-10-03");

    static LlmRequest ask(Graph g, Fix fix, String subject) {
        return EstimatePrior.forIssue(g, TODAY, new Issue(Severity.BLOCKING, fix, subject, ""));
    }

    @Test
    public void theSnapshotLeavesOutWhatInvolvesAnotherPerson() {
        Graph g = GraphImportTest.real();
        for (LlmRequest r : List.of(ask(g, Fix.REGIME_GAP, "2027-02-11..2032-09-30"),
                                    ask(g, Fix.NODE_EFFORT, "T_PRODUCT"),
                                    ask(g, Fix.NODE_PRIOR, "MS_MARKS"))) {
            for (String hidden : List.of("G_NETWORK", "A_PITCH", "G_PARTNER"))
                assertFalse(hidden + " must not be sent", r.user().contains(hidden));
            assertFalse("no log is sent", r.user().contains("SESSION"));
        }
        try {
            ask(g, Fix.NODE_PRIOR, "G_NETWORK");
            throw new AssertionError("a private node must not be asked about");
        } catch (IllegalArgumentException expected) { }
    }

    private static final String SELF = "\"consent_self_check\":{\"involves_other_person\":false,\"explanation\":\"no\"}";

    @Test
    public void aGoodRegimeAnswerBecomesOperations() {
        Graph g = GraphImportTest.real();
        LlmRequest r = ask(g, Fix.REGIME_GAP, "2027-02-11..2032-09-30");
        String answer = "Thinking done. Here it is:\n```json\n{\"regimes\":["
            + "{\"id\":\"TERM_S27\",\"from\":\"2027-02-11\",\"to\":\"2027-06-30\",\"ideal\":0.5,\"busy\":0.4,\"stress\":0.1,\"strength_days\":4,\"rationale\":\"spring term\"},"
            + "{\"id\":\"LATER\",\"from\":\"2027-07-01\",\"to\":\"2032-09-30\",\"ideal\":0.4,\"busy\":0.45,\"stress\":0.15,\"strength_days\":1.5,\"rationale\":\"far\"}],"
            + SELF + ",\"sources\":[{\"url\":\"https://example.edu/calendar\",\"used_for\":\"term dates\"}],\"summary\":\"two stretches\"}\n```";
        ProposalValidator.Result v = ProposalValidator.validate(g, TODAY, r, answer, List.of("https://example.edu/calendar"));
        assertTrue(v.reason(), v.ok());
        assertEquals(4, v.ops().size());
        assertEquals(1, v.sources().size());
        Op mix = v.ops().get(1);
        assertEquals("regime.TERM_S27.mix", mix.key());
        assertEquals(4.0, mix.values().get("strength"), 0);
    }

    private static String reasonFor(Graph g, LlmRequest r, String json) {
        ProposalValidator.Result v = ProposalValidator.validate(g, TODAY, r, json, List.of());
        assertFalse("must be rejected: " + json, v.ok());
        assertNotNull(v.reason());
        System.out.println("  rejected: " + v.reason());
        return v.reason();
    }

    @Test
    public void brokenAnswersAreRejectedWithTheRightReason() {
        Graph g = GraphImportTest.real();
        LlmRequest gap = ask(g, Fix.REGIME_GAP, "2027-02-11..2032-09-30");
        assertTrue(reasonFor(g, gap, "no json here").contains("no JSON"));
        assertTrue(reasonFor(g, gap, "{\"regimes\":[]}").contains("consent self-check"));
        assertTrue(reasonFor(g, gap, "{\"regimes\":[{\"id\":\"A\",\"from\":\"2027-03-01\",\"to\":\"2032-09-30\",\"ideal\":0.5,\"busy\":0.4,\"stress\":0.1,\"strength_days\":2}]," + SELF + "}")
            .contains("without a gap"));
        assertTrue(reasonFor(g, gap, "{\"regimes\":[{\"id\":\"A\",\"from\":\"2027-02-11\",\"to\":\"2030-01-01\",\"ideal\":0.5,\"busy\":0.4,\"stress\":0.1,\"strength_days\":2}]," + SELF + "}")
            .contains("stop at"));
        assertTrue(reasonFor(g, gap, "{\"regimes\":[{\"id\":\"EARLY\",\"from\":\"2027-02-11\",\"to\":\"2032-09-30\",\"ideal\":0.5,\"busy\":0.4,\"stress\":0.1,\"strength_days\":2}]," + SELF + "}")
            .contains("already taken"));
        assertTrue(reasonFor(g, gap, "{\"regimes\":[{\"id\":\"A\",\"from\":\"2027-02-11\",\"to\":\"2032-09-30\",\"ideal\":0.9,\"busy\":0.4,\"stress\":0.1,\"strength_days\":2}]," + SELF + "}")
            .contains("adding up to 1"));
        assertTrue(reasonFor(g, gap, "{\"regimes\":[{\"id\":\"A\",\"from\":\"2027-02-11\",\"to\":\"2032-09-30\",\"ideal\":0.5,\"busy\":0.4,\"stress\":0.1,\"strength_days\":2}],"
            + "\"consent_self_check\":{\"involves_other_person\":true,\"explanation\":\"x\"}}").contains("another person"));

        LlmRequest effort = ask(g, Fix.NODE_EFFORT, "T_PRODUCT");
        assertTrue(reasonFor(g, effort, "{\"p50_hours\":300,\"p90_hours\":100," + SELF + "}").contains("below"));
        assertTrue(reasonFor(g, effort, "{\"p50_hours\":-1,\"p90_hours\":100," + SELF + "}").contains("above zero"));

        LlmRequest prior = ask(g, Fix.NODE_PRIOR, "MS_MARKS");
        assertTrue(reasonFor(g, prior, "{\"probability\":1.4,\"strength_cases\":5," + SELF + "}").contains("between 0 and 1"));
    }

    @Test
    public void anAdvisorNodeIsOnlyEverDiscussed() {
        Graph g = GraphImportTest.real();
        LlmRequest r = ask(g, Fix.NODE_EFFORT, "T_VIDEOS");
        ProposalValidator.Result v = ProposalValidator.validate(g, TODAY, r,
            "{\"p50_hours\":40,\"p90_hours\":60," + SELF + "}", List.of());
        assertTrue(v.ok());
        assertTrue(v.ops().get(0).discussOnly());
    }
}
