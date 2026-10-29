package com.chronos.navigator.llm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.model.Kinds.Dist;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.List;
import java.util.Map;

/** The same change proposed again is one decision, not two (your report of 29 Oct 2026). */
public class RepeatsTest {

    private static Op pause(String activity, String note) {
        JsonObject what = new JsonObject();
        what.addProperty("type", "pause");
        what.addProperty("activity", activity);
        what.addProperty("from", "2026-10-30");
        what.addProperty("until", "2026-11-15");
        what.addProperty("note", note);
        return Op.chronos("pause_activity", activity, what, Map.of(), Map.of(), note);
    }

    private static Op effort(String node, double p50) {
        return new Op("node." + node + ".effort", Dist.LOGNORMAL, Map.of("p50", p50), Map.of(), "x", false);
    }

    @Test
    public void theSameChangeInOtherWordsIsTheSame() {
        List<Op> a = List.of(pause("Language B", "to fund the exam"), effort("SK_EN_TEST", 40.0));
        List<Op> b = List.of(effort("SK_EN_TEST", 40.001), pause("Language B", "frees hours for the exam"));
        assertTrue(Repeats.same(a, b));
        assertFalse("another number is another change", Repeats.same(a, List.of(pause("Language B", "x"), effort("SK_EN_TEST", 55.0))));
        assertFalse("nothing repeats nothing", Repeats.same(List.of(), List.of()));
    }

    @Test
    public void aNewerProposalThatChangesEverythingAnOlderOneDoesCoversIt() {
        List<Op> older = List.of(pause("Language B", "x"));
        List<Op> newer = List.of(pause("Language B", "y"), effort("SK_EN_TEST", 30.0));
        assertTrue(Repeats.covers(newer, older));
        assertFalse("the older one does not cover the newer", Repeats.covers(older, newer));
        assertFalse("different things", Repeats.covers(List.of(effort("T_EXT", 50.0)), older));
    }
}
