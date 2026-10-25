package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.llm.Discuss;
import com.chronos.navigator.llm.Op;
import com.chronos.navigator.llm.Staleness;
import com.chronos.navigator.model.Graph;
import com.chronos.navigator.model.Kinds.Dist;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The inbox re-check: what the app can tell by itself is no longer worth deciding. */
public class StalenessTest {

    static final LocalDate TODAY = ForecastTest.TODAY;
    static final Set<String> ACTS = Set.of("watching convex videos", "reading gsp");
    static final Set<String> GOALS = Set.of("convex optimisation");

    private static Op extraHours(String node, LocalDate from, LocalDate until) {
        return new Op("node." + node + ".min_weekly", Dist.POINT,
            Map.of("value", 4.0, "from", (double) from.toEpochDay(), "until", (double) until.toEpochDay()), Map.of(),
            "4 h a week", false);
    }

    private static Op chronos(String type, String... kv) {
        JsonObject what = new JsonObject();
        what.addProperty("type", type);
        for (int i = 0; i < kv.length; i += 2) what.addProperty(kv[i], kv[i + 1]);
        return Op.chronos(type, "x", what, Map.of(), Map.of(), type);
    }

    @Test
    public void aCurrentProposalStays() {
        Graph g = ForecastTest.answered();
        Staleness.Verdict v = Staleness.check(g, TODAY,
            List.of(extraHours("T_SURVEY", TODAY, TODAY.plusDays(30))), "T_SURVEY", ACTS, GOALS);
        assertNull(v.outdated());
        assertEquals(1, v.live());
        assertFalse(v.partly());
    }

    @Test
    public void aPeriodThatIsOverIsOutdated() {
        Graph g = ForecastTest.answered();
        Staleness.Verdict v = Staleness.check(g, TODAY,
            List.of(extraHours("T_SURVEY", TODAY.minusDays(40), TODAY.minusDays(2))), "T_SURVEY", ACTS, GOALS);
        assertNotNull(v.outdated());
        assertTrue(v.outdated(), v.outdated().contains("over"));
    }

    @Test
    public void aNodeNoLongerInTheGraphIsOutdated() {
        Graph g = ForecastTest.answered();
        Staleness.Verdict v = Staleness.check(g, TODAY,
            List.of(extraHours("T_GONE_NODE", TODAY, TODAY.plusDays(30))), "T_GONE_NODE", ACTS, GOALS);
        assertNotNull(v.outdated());
    }

    @Test
    public void aFinishedSubjectIsOutdated() {
        Graph g = ForecastTest.answered();
        g.node("T_SURVEY").status = "done";
        Staleness.Verdict v = Staleness.check(g, TODAY,
            List.of(extraHours("T_SURVEY", TODAY, TODAY.plusDays(30))), "T_SURVEY", ACTS, GOALS);
        assertEquals("What it was about is finished", v.outdated());
    }

    @Test
    public void onlyPartOfACrewPackageGoneIsWorthAskingAgain() {
        Graph g = ForecastTest.answered();
        Staleness.Verdict v = Staleness.check(g, TODAY, List.of(
            chronos("pause_activity", "activity", "Reading GSP", "from", TODAY.toString(), "until", TODAY.plusDays(5).toString()),
            chronos("rest_day", "date", TODAY.minusDays(1).toString()),
            chronos("edit_activity", "activity", "studying korean")), null, ACTS, GOALS);
        assertNull(v.outdated());
        assertEquals(1, v.live());
        assertTrue(v.partly());
        assertEquals(2, v.gone().size());
    }

    @Test
    public void aCrewPackageWithNothingLeftIsOutdated() {
        Graph g = ForecastTest.answered();
        Staleness.Verdict v = Staleness.check(g, TODAY, List.of(
            chronos("edit_goal", "goal", "a goal that was removed", "deadline", TODAY.plusDays(9).toString())), null, ACTS, GOALS);
        assertNotNull(v.outdated());
        assertTrue(v.outdated(), v.outdated().contains("no longer has"));
    }

    @Test
    public void theModelCanSayAProposalNoLongerHelps() {
        Discuss.Answer a = Discuss.read("{\"reply\":\"The exam is over, so this no longer helps.\",\"revise\":false,"
            + "\"obsolete\":true,\"revised\":null,\"consent_self_check\":{\"involves_other_person\":false}}");
        assertNotNull(a);
        assertTrue(a.obsolete());
        assertFalse(Discuss.read("{\"reply\":\"Still fine.\",\"revise\":false,\"revised\":null}").obsolete());
    }
}
