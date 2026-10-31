package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.llm.ChangeSet;
import com.chronos.navigator.llm.Crew;
import com.chronos.navigator.llm.Op;
import com.chronos.navigator.model.Graph;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.time.LocalDate;

/** The vocabulary the Navigator's crew answers in, checked against the graph and a snapshot of Chronos. */
public class ChangeSetTest {

    static final LocalDate TODAY = LocalDate.parse("2026-10-13");

    static JsonObject chronos() {
        return JsonParser.parseString("""
            {"goals":[{"name":"Course course","mode":"CLOSED","deadline":"2026-10-20","total_units":30,"done_units":20,"unit":"video","hours_per_unit":1.5},
                      {"name":"Language A","mode":"OPEN","weekly_floor_h":3}],
             "activities":[{"name":"watching course videos","kind":"goal","goal":"Course course","days":[0,1,2,3,4,5,6]},
                           {"name":"studying language a","kind":"goal","goal":"Language A","days":[0,2,4]},
                           {"name":"gym","kind":"upkeep","days":[1,3,5],"minutes":60}],
             "levers":{"slack_ratio":0.2,"f_safe":0.6,"f_safe_p":0.6,"bed":"23:30","wake":"07:30"},
             "rest_days":["2026-10-17"]}""").getAsJsonObject();
    }

    static String answer(String changes) {
        return "Thinking…\n```json\n{\"message\":\"ok\",\"summary\":\"s\",\"changes\":" + changes
            + ",\"questions\":[\"When is it due exactly?\"],\"consent_self_check\":{\"targets_other_person\":false}}\n```";
    }

    @Test
    public void anAdvisorsTaskBecomesANodeAGoalAndAnActivity() {
        Graph g = ForecastTest.answered();
        ChangeSet.Parsed p = ChangeSet.read(g, TODAY, chronos(), answer("""
            [{"title":"Literature review for the advisor","why":"assigned today","actions":[
               {"type":"add_task","key":"litrev","title":"Literature review","owner":"ADVISOR","p50_hours":12,"p90_hours":20,
                "deadline":"2026-11-01","hard":true,"priority":"critical","activity":"literature review","like":"watching course videos"}]},
             {"title":"Make room","actions":[{"type":"edit_activity","activity":"studying language a","days":[0,4]},
                                             {"type":"pressure","slack_ratio":0.15}]},
             {"title":"Too much at once","actions":[{"type":"pressure","slack_ratio":0.02}]},
             {"title":"Sleep earlier","actions":[{"type":"sleep","bed":"23:00","wake":"07:00"}]},
             {"title":"Jet lag","actions":[{"type":"sleep","bed":"21:00"}]},
             {"title":"Rest","actions":[{"type":"rest_day","date":"2026-10-24"}]}]"""));
        assertTrue(p.failure(), p.ok());
        assertEquals(4, p.groups().size());
        assertEquals("two changes were refused", 2, p.rejected().size());
        assertTrue(p.rejected().get(0).reason().contains("slack_ratio"));
        assertTrue(p.rejected().get(1).reason().contains("at most 30"));
        Op node = p.ops().get(p.groups().get(0).ops().get(0));
        assertEquals(Op.NODE_ADD, node.kind());
        assertEquals("T_LITREV", node.node().get("id").getAsString());
        assertEquals("critical", node.node().get("priority").getAsString());
        Op goal = p.ops().get(p.groups().get(0).ops().get(1));
        assertTrue(goal.isChronos());
        assertEquals("task_goal", goal.node().get("type").getAsString());
        assertEquals(12.0, goal.node().get("hours").getAsDouble(), 0);
        assertEquals(1, p.questions().size());
    }

    @Test
    public void namesAndBoundsAreChecked() {
        Graph g = ForecastTest.answered();
        ChangeSet.Parsed p = ChangeSet.read(g, TODAY, chronos(), answer("""
            [{"title":"a","actions":[{"type":"archive_activity","activity":"watching course videos"}]},
             {"title":"b","actions":[{"type":"edit_activity","activity":"chess"}]},
             {"title":"c","actions":[{"type":"archive_activity","activity":"gym"}]},
             {"title":"d","actions":[{"type":"rest_day","date":"2026-10-17"}]},
             {"title":"e","actions":[{"type":"add_task","key":"x","title":"X","p50_hours":3,"deadline":"2026-11-01","depends_on":["NOPE"]}]},
             {"title":"f","actions":[{"type":"add_activity","name":"walk","kind":"upkeep","days":[6],"minutes":40}]},
             {"title":"g","actions":[{"type":"edit_goal","goal":"Course course","deadline":"2026-10-25"}]}]"""));
        assertEquals(3, p.groups().size());
        assertTrue(p.rejected().get(0).reason().contains("only activity"));
        assertTrue(p.rejected().get(1).reason().contains("not in Chronos"));
        assertTrue(p.rejected().get(2).reason().contains("already"));
        assertTrue(p.rejected().get(3).reason().contains("NOPE"));
    }

    @Test
    public void anAnswerThatTargetsAPersonIsRefused() {
        Graph g = ForecastTest.answered();
        ChangeSet.Parsed p = ChangeSet.read(g, TODAY, chronos(),
            "{\"changes\":[],\"consent_self_check\":{\"targets_other_person\":true}}");
        assertFalse(p.ok());
    }

    @Test
    public void changesTheCoordinatorKeepsByNameComeFromTheProposal() {
        Graph g = ForecastTest.answered();
        ChangeSet.Parsed proposed = ChangeSet.read(g, TODAY, chronos(), answer("""
            [{"title":"Report","actions":[{"type":"add_task","key":"REP","title":"Report","p50_hours":4,"p90_hours":6,
               "deadline":"2026-11-01","like":"watching course videos"}]},
             {"title":"Earlier night","actions":[{"type":"sleep","bed":"23:00","wake":"07:00"}]}]"""));
        assertEquals(2, proposed.groups().size());
        // A model that names what it keeps instead of copying it: one in `keep`, one in `changes` without actions.
        String text = "{\"message\":\"Keep both.\",\"keep\":[\"report\"],\"changes\":[{\"title\":\"Earlier night\"}],"
            + "\"consent_self_check\":{\"targets_other_person\":false}}";
        Crew.Decision d = Crew.decision(g, TODAY, chronos(), text, false);
        assertTrue("on its own the decision holds nothing", d.set().empty());
        ChangeSet.Parsed whole = Crew.reconcile(proposed, d.set(), text);
        assertEquals(2, whole.groups().size());
        assertEquals(proposed.ops().size(), whole.ops().size());
        assertTrue("the kept change is not reported as refused", whole.rejected().isEmpty());
        assertEquals("Keep both.", whole.message());
        // An answer with no JSON at all leaves the proposal as it was.
        assertEquals(2, Crew.reconcile(proposed, ChangeSet.read(g, TODAY, chronos(), "no json"), "no json").groups().size());
    }

    @Test
    public void numbersAsTextPersianDigitsSlashDatesAndDayNamesAreRead() {
        Graph g = ForecastTest.answered();
        ChangeSet.Parsed p = ChangeSet.read(g, TODAY, chronos(), answer("""
            [{"title":"Report","actions":[{"type":"add_task","key":"REP","title":"Report","p50_hours":"۴","p90_hours":"6.5",
               "deadline":"2026/11/01","hard":"true","like":"watching course videos"}]},
             {"title":"Walk","actions":[{"type":"add_activity","name":"walk","kind":"upkeep","days":["Monday","3"],"minutes":"40"}]}]"""));
        assertTrue(p.rejected().toString(), p.rejected().isEmpty());
        assertEquals(2, p.groups().size());
        Op task = p.ops().get(p.groups().get(0).ops().get(0));
        assertEquals(4.0, task.node().getAsJsonObject("effort").get("total_h_p50").getAsDouble(), 0);
        assertEquals("2026-11-01", task.node().getAsJsonObject("window").get("deadline").getAsString());
        Op walk = p.ops().get(p.groups().get(1).ops().get(0));
        assertEquals("[2,3]", walk.node().get("days").toString());
    }

    @Test
    public void theCoordinatorMayAskOnceForARevision() {
        Graph g = ForecastTest.answered();
        String text = "{\"message\":\"m\",\"changes\":[],\"responses\":[{\"objection\":\"too big\",\"accepted\":true,\"why\":\"yes\"}],"
            + "\"revise\":{\"role\":\"architect\",\"instructions\":\"smaller tasks\"},\"consent_self_check\":{\"targets_other_person\":false}}";
        Crew.Decision d = Crew.decision(g, TODAY, chronos(), text, true);
        assertNotNull(d.revise());
        assertEquals(Crew.Role.ARCHITECT, d.revise().role());
        assertEquals(1, d.responses().size());
        assertNull("no second revision", Crew.decision(g, TODAY, chronos(), text, false).revise());

        Crew.Review r = Crew.review("{\"objections\":[{\"severity\":\"BLOCKER\",\"change\":\"a\",\"text\":\"t\"},{\"severity\":\"x\",\"text\":\"u\"}],"
            + "\"verdict\":\"revise\",\"consent_self_check\":{\"targets_other_person\":false}}");
        assertTrue(r.objections().get(0).blocker());
        assertEquals("minor", r.objections().get(1).severity());
    }

    @Test
    public void theGraphItselfCanBeCorrected() {
        Graph g = ForecastTest.answered();
        ChangeSet.Parsed p = ChangeSet.read(g, TODAY, chronos(), answer("""
            [{"title":"The survey needs the course first","actions":[{"type":"set_links","node":"T_READING","depends_on":["T_VIDEOS"]}]},
             {"title":"It is smaller than thought","actions":[{"type":"set_effort","node":"T_READING","p50_hours":15,"p90_hours":25}]},
             {"title":"Your GPA is safer","actions":[{"type":"set_success","node":"MS_MARKS","probability":0.9,"confidence":"medium"}]},
             {"title":"A loop","actions":[{"type":"set_links","node":"STR_CLASSES","depends_on":["MS_BSC"]}]},
             {"title":"Not a gate","actions":[{"type":"set_success","node":"T_READING","probability":0.5}]}]"""));
        assertTrue(p.failure(), p.ok());
        assertEquals(3, p.groups().size());
        assertEquals(2, p.rejected().size());
        Op links = p.ops().get(p.groups().get(0).ops().get(0));
        assertEquals(Op.NODE_EDIT, links.kind());
        assertEquals("T_VIDEOS", links.node().getAsJsonObject("set").getAsJsonArray("depends_on").get(0).getAsString());
        Op effort = p.ops().get(p.groups().get(1).ops().get(0));
        assertEquals("node.T_READING.effort", effort.key());
        assertEquals(15.0, effort.values().get("p50"), 1e-9);
        assertEquals(1.0, effort.values().get("override"), 1e-9);
        assertEquals(25.0, effort.before().get("p50"), 1e-9);
        Op success = p.ops().get(p.groups().get(2).ops().get(0));
        assertEquals("node.MS_MARKS.success", success.key());
        assertEquals(9.0, success.values().get("a"), 1e-9);
        assertEquals(1.0, success.values().get("b"), 1e-9);
    }
}
