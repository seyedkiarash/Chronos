package com.chronos.navigator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.chronos.navigator.llm.ChangeSet;
import com.chronos.navigator.llm.Op;
import com.chronos.navigator.model.Graph;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.time.LocalDate;

/**
 * The architect's answers from your first real run (14 Oct 2026), replayed. Then they were refused whole:
 * pause named Chronos activities instead of graph nodes, discuss named a Chronos goal, and one bad action
 * took the exam task down with it. Now each is read for what it meant.
 */
public class RealRunTest {

    static final LocalDate DAY = LocalDate.parse("2026-10-14");

    static JsonObject chronos() {
        return JsonParser.parseString("""
            {"goals":[{"name":"study convex optimisation ","mode":"CLOSED","deadline":"2026-10-13","total_units":30,"done_units":30},
                      {"name":"ta's job","mode":"CLOSED","deadline":"2026-10-20","total_units":10,"done_units":0},
                      {"name":"learning pytorch","mode":"OPEN","weekly_floor_h":7}],
             "activities":[{"name":"watching convex videos","kind":"goal","goal":"study convex optimisation "},
                           {"name":"learning pytorch","kind":"goal","goal":"learning pytorch"},
                           {"name":"reading gsp","kind":"goal","goal":"reading courses"},
                           {"name":"reading cs","kind":"goal","goal":"reading courses"},
                           {"name":"studying English ","kind":"goal","goal":"English fluency "},
                           {"name":"Studying Korean ","kind":"goal","goal":"Korean fluency"},
                           {"name":"planning","kind":"goal","goal":"planning"},
                           {"name":"Study","kind":"goal","goal":"ta's job"}],
             "levers":{"slack_ratio":0.01,"f_safe":0.8,"f_safe_p":0.8,"bed":"23:00","wake":"06:00"},"rest_days":[]}""").getAsJsonObject();
    }

    @Test
    public void theExamTaskAndThePausesOfChronosActivitiesAreKept() {
        Graph g = ForecastTest.answered();
        String first = """
            {"changes":[{"title":"Add convex-optimisation exam practice (advisor-mandated)","why":"…","actions":[
              {"type":"add_task","key":"CONVEX_EXAM","title":"Convex optimisation exam practice","owner":"ADVISOR","p50_hours":20,
               "p90_hours":25,"earliest":"2026-10-14","deadline":"2026-10-17","hard":true,"depends_on":[],"needed_by":[],
               "priority":"critical","activity":"exam practice","like":"Study"},
              {"type":"pause","node":"learning pytorch","from":"2026-10-14","until":"2026-10-17"},
              {"type":"pause","node":"reading gsp","from":"2026-10-14","until":"2026-10-17"},
              {"type":"pause","node":"studying English ","from":"2026-10-14","until":"2026-10-17"},
              {"type":"pause","node":"planning","from":"2026-10-14","until":"2026-10-17"}]}],
             "consent_self_check":{"targets_other_person":false}}""";
        ChangeSet.Parsed p = ChangeSet.read(g, DAY, chronos(), first);
        assertTrue(p.rejected().toString(), p.rejected().isEmpty());
        assertEquals(1, p.groups().size());
        long pauses = p.ops().stream().filter(op -> op.isChronos() && op.node().get("type").getAsString().equals("pause_activity")).count();
        assertEquals("each named activity is paused in Chronos", 4, pauses);
        assertTrue(p.ops().stream().anyMatch(op -> Op.NODE_ADD.equals(op.kind())));
    }

    @Test
    public void aDiscussNamingAChronosGoalAndANodePauseStand() {
        Graph g = ForecastTest.answered();
        String second = """
            {"changes":[{"title":"Convex exam sprint + schedule adjustment","actions":[
              {"type":"add_task","key":"EXAM_CONVEX_PRACTICE","title":"Convex optimization exam practice (advisor)","owner":"ADVISOR",
               "p50_hours":20,"p90_hours":25,"earliest":"2026-10-14","deadline":"2026-10-17","hard":true,"priority":"critical",
               "activity":"watching convex videos","like":"watching convex videos"},
              {"type":"pause","node":"SK_TORCH","from":"2026-10-14","until":"2026-10-17"},
              {"type":"discuss","node":"ta's job","topic":"Extend deadline by one week to 2026-10-27"},
              {"type":"pause","node":"no such thing","from":"2026-10-14","until":"2026-10-17"}]}],
             "consent_self_check":{"targets_other_person":false}}""";
        ChangeSet.Parsed p = ChangeSet.read(g, DAY, chronos(), second);
        assertEquals("the change stands without its one bad action", 1, p.groups().size());
        assertEquals(1, p.rejected().size());
        assertTrue(p.rejected().get(0).reason().contains("no such thing"));
        assertTrue(p.ops().stream().anyMatch(op -> op.kind().equals(Op.DISCUSS)));
        // The new task's planner activity does not take the name of one that exists.
        Op goal = p.ops().stream().filter(op -> op.isChronos() && op.node().get("type").getAsString().equals("task_goal")).findFirst().orElseThrow();
        assertEquals("watching convex videos 2", goal.node().get("activity").getAsString());
    }
}
