package com.chronos.harness;

import com.chronos.data.Codec;
import com.chronos.data.Mappers;
import com.chronos.engine.DaySequencer;
import com.chronos.engine.HorizonPlanner;
import com.chronos.engine.Slots;
import com.chronos.model.*;
import com.chronos.model.Enums.LogKind;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The data layer's risky part is the encoding, not the Room boilerplate. This
 * pushes a full configuration, a solved day and a log entry through the codec
 * and back, and fails loudly on any difference.
 */
public class DataRoundTrip {

    static int failures = 0;

    static void check(String what, Object expected, Object actual) {
        boolean ok = String.valueOf(expected).equals(String.valueOf(actual));
        if (!ok) { failures++; System.out.println("  FAIL " + what + ": " + expected + " != " + actual); }
    }

    public static void main(String[] args) {
        if (run() > 0) System.exit(1);
    }

    /** Runs every check and returns the failure count; the JUnit wrapper asserts on it. */
    public static int run() {
        failures = 0;
        LocalDate today = LocalDate.parse("2026-09-22");
        Config original = ParityHarness.build(today);

        System.out.println("=== configuration round trip ===");
        Config back = Mappers.toConfig(
            Mappers.toRow(original.params),
            Mappers.goalRows(original),
            Mappers.activityRows(original),
            Mappers.commitmentRows(original));

        check("goal count", original.goals.size(), back.goals.size());
        check("activity count", original.activities.size(), back.activities.size());
        check("commitment count", original.commitments.size(), back.commitments.size());
        check("sleep start", original.params.sleepStart, back.params.sleepStart);
        check("phi", original.params.phi, back.params.phi);
        check("pessimism", original.params.lcbBeta, back.params.lcbBeta);

        Activity ap = original.activity("papers"), bp = back.activity("papers");
        check("papers days", ap.days, bp.days);
        check("papers windows", Codec.windows(ap.windows), Codec.windows(bp.windows));
        check("papers load", ap.load, bp.load);
        check("papers blocks mode", ap.blocksMode, bp.blocksMode);

        Activity ab = original.activity("breakfast"), bb = back.activity("breakfast");
        check("breakfast pins", Codec.pins(ab.pins), Codec.pins(bb.pins));

        Commitment ac = original.commitments.get(1), bc = back.commitments.get(1);
        check("commute inbound mode", ac.inbound.mode, bc.inbound.mode);
        check("commute inbound window", ac.inbound.from + "-" + ac.inbound.to,
                                        bc.inbound.from + "-" + bc.inbound.to);
        check("classes sessions", Codec.sessions(original.commitments.get(0).sessions),
                                  Codec.sessions(back.commitments.get(0).sessions));

        System.out.println("=== fingerprint ===");
        check("hash is stable", Mappers.configHash(original), Mappers.configHash(back));
        Config edited = ParityHarness.build(today);
        edited.goal("lit").doneUnits = 4.0;
        boolean moved = !Mappers.configHash(original).equals(Mappers.configHash(edited));
        System.out.println("  progress change invalidates the snapshot: " + moved);
        if (!moved) failures++;

        System.out.println("=== solved day round trip ===");
        HorizonPlanner.Plan plan = HorizonPlanner.plan(original, today);
        DaySequencer.Result r = DaySequencer.run(original, Slots.dow(today),
                                                 plan.allocation().get(0), 42L);
        String encoded = Codec.blocks(r.blocks());
        List<Block> decoded = Codec.parseBlocks(encoded);
        check("block count", r.blocks().size(), decoded.size());
        for (int i = 0; i < r.blocks().size(); i++) {
            check("block " + i + " start", r.blocks().get(i).start, decoded.get(i).start);
            check("block " + i + " name", r.blocks().get(i).name, decoded.get(i).name);
            check("block " + i + " goal", r.blocks().get(i).goalId, decoded.get(i).goalId);
        }
        System.out.println("  encoded size: " + encoded.length() + " chars for "
                         + r.blocks().size() + " blocks");

        System.out.println("=== horizon round trip ===");
        String h = Codec.horizon(plan.allocation());
        List<Map<String, Double>> hb = Codec.parseHorizon(h);
        check("horizon days", plan.allocation().size(), hb.size());
        check("day 0 allocation", plan.allocation().get(0), hb.get(0));
        System.out.println("  encoded size: " + h.length() + " chars for "
                         + plan.allocation().size() + " days");

        System.out.println("=== awkward text ===");
        Activity tricky = new Activity();
        tricky.id = "x"; tricky.name = "Read, write | and; think @ 5-9";
        tricky.kind = Enums.ActivityKind.GOAL; tricky.goalId = "lit";
        tricky.days.add(0); tricky.minBlock = 30; tricky.maxBlock = 60;
        tricky.load = 0.5; tricky.recovery = 0.0;
        tricky.windows.add(new Activity.Window("08:00", "12:00"));
        Activity trickyBack = Mappers.toModel(Mappers.toRow(tricky, 0));
        check("name with separators survives", tricky.name, trickyBack.name);

        LogEntry log = new LogEntry();
        log.id = "l1"; log.kind = LogKind.SESSION; log.date = today; log.at = "09:15";
        log.actId = "papers"; log.planned = 90; log.actual = 105; log.attain = 70.0;
        log.focus = 4; log.fatigueAfter = 4; log.ts = 1_700_000_000_000L;
        log.tags = List.of("Poor sleep", "Noisy or interrupted");
        log.note = "Kept losing the thread; pipe | comma , semicolon ;";
        LogEntry logBack = Mappers.toModel(Mappers.toRow(log));
        check("log tags", log.tags, logBack.tags);
        check("log note", log.note, logBack.note);
        check("log attain", log.attain, logBack.attain);
        check("log date", log.date, logBack.date);

        System.out.println("=== empty and null tolerance ===");
        Config blank = new Config();
        Config blankBack = Mappers.toConfig(Mappers.toRow(blank.params),
            Mappers.goalRows(blank), Mappers.activityRows(blank), Mappers.commitmentRows(blank));
        check("blank params survive", null, blankBack.params.sleepStart);
        check("blank goals survive", 0, blankBack.goals.size());
        check("empty windows", 0, Codec.parseWindows("").size());
        check("empty pins", 0, Codec.parsePins(null).size());
        check("empty blocks", 0, Codec.parseBlocks("").size());

        System.out.println("=== seeding ===");
        long s1 = com.chronos.data.ChronosRepository.seedFor(today, "abc");
        long s2 = com.chronos.data.ChronosRepository.seedFor(today, "abc");
        long s3 = com.chronos.data.ChronosRepository.seedFor(today.plusDays(1), "abc");
        long s4 = com.chronos.data.ChronosRepository.seedFor(today, "abd");
        check("seed is stable", s1, s2);
        System.out.println("  different day gives a different seed: " + (s1 != s3));
        System.out.println("  different config gives a different seed: " + (s1 != s4));

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        return failures;
    }
}
