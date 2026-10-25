package com.chronos.data;

import com.chronos.model.Activity;
import com.chronos.model.Block;
import com.chronos.model.Commitment;
import com.chronos.model.Enums.LegMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compact text encodings for the nested pieces of the model, so they can live
 * in a single SQLite column.
 *
 * No JSON library on purpose. These shapes are small and fixed, the encodings
 * stay readable in a database browser, and skipping the dependency means the
 * whole data layer can be round-trip tested on a desktop JVM.
 *
 * Free text (activity names, block labels, notes) is separated by the ASCII
 * unit and record separators rather than by punctuation, so a comma or a pipe
 * in a name can never corrupt a row.
 */
public final class Codec {
    private Codec() {}

    private static final String FIELD = "\u001F";   // unit separator
    private static final String RECORD = "\u001E";  // record separator

    private static String nz(String s) { return s == null ? "" : s; }
    private static Double d(String s) { return s.isEmpty() ? null : Double.valueOf(s); }
    private static Integer i(String s) { return s.isEmpty() ? null : Integer.valueOf(s); }

    /** Splits without dropping trailing empties, which String.split does by default. */
    private static String[] split(String s, String sep) {
        return s.split(java.util.regex.Pattern.quote(sep), -1);
    }

    // ---- weekday sets: "0,1,4" -------------------------------------------
    public static String days(TreeSet<Integer> days) {
        StringBuilder sb = new StringBuilder();
        for (int d : days) { if (sb.length() > 0) sb.append(','); sb.append(d); }
        return sb.toString();
    }

    public static TreeSet<Integer> parseDays(String s) {
        TreeSet<Integer> out = new TreeSet<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) if (!part.isBlank()) out.add(Integer.parseInt(part.trim()));
        return out;
    }

    // ---- windows: "08:00-12:30;14:00-19:30" ------------------------------
    public static String windows(List<Activity.Window> windows) {
        StringBuilder sb = new StringBuilder();
        for (Activity.Window w : windows) {
            if (sb.length() > 0) sb.append(';');
            sb.append(w.from).append('-').append(w.to);
        }
        return sb.toString();
    }

    public static List<Activity.Window> parseWindows(String s) {
        List<Activity.Window> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            String[] ab = part.split("-");
            if (ab.length == 2) out.add(new Activity.Window(ab[0].trim(), ab[1].trim()));
        }
        return out;
    }

    // ---- pins: "0@07:05;2@07:05" -----------------------------------------
    public static String pins(List<Activity.Pin> pins) {
        StringBuilder sb = new StringBuilder();
        for (Activity.Pin p : pins) {
            if (sb.length() > 0) sb.append(';');
            sb.append(p.day).append('@').append(p.start);
        }
        return sb.toString();
    }

    public static List<Activity.Pin> parsePins(String s) {
        List<Activity.Pin> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            String[] ab = part.split("@");
            if (ab.length == 2) out.add(new Activity.Pin(Integer.parseInt(ab[0].trim()), ab[1].trim()));
        }
        return out;
    }

    // ---- commitment sessions --------------------------------------------
    // "0|09:00|13:00|0.8|0.1|0.2|0;1|10:00|13:00||||" — after the times, the
    // session's own mental load and recovery, then its physical load and
    // recovery, each empty when it inherits the commitment's. Rows written
    // before they existed have three or five fields and still parse, which is
    // why the length is checked rather than assumed.
    public static String sessions(List<Commitment.Session> sessions) {
        StringBuilder sb = new StringBuilder();
        for (Commitment.Session s : sessions) {
            if (sb.length() > 0) sb.append(';');
            sb.append(s.day).append('|').append(s.start).append('|').append(s.end)
              .append('|').append(s.load == null ? "" : s.load)
              .append('|').append(s.recovery == null ? "" : s.recovery)
              .append('|').append(s.physLoad == null ? "" : s.physLoad)
              .append('|').append(s.physRecovery == null ? "" : s.physRecovery);
        }
        return sb.toString();
    }

    public static List<Commitment.Session> parseSessions(String s) {
        List<Commitment.Session> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            String[] f = part.split("\\|", -1);
            if (f.length < 3) continue;
            Commitment.Session session = new Commitment.Session(
                Integer.parseInt(f[0].trim()), f[1].trim(), f[2].trim());
            if (f.length >= 5) {
                session.load = f[3].isBlank() ? null : Double.parseDouble(f[3].trim());
                session.recovery = f[4].isBlank() ? null : Double.parseDouble(f[4].trim());
            }
            if (f.length >= 7) {
                session.physLoad = f[5].isBlank() ? null : Double.parseDouble(f[5].trim());
                session.physRecovery = f[6].isBlank() ? null : Double.parseDouble(f[6].trim());
            }
            out.add(session);
        }
        return out;
    }

    // ---- trip legs -------------------------------------------------------
    public static String leg(Commitment.Leg leg) {
        if (leg == null) return LegMode.PINNED.name() + "||";
        return leg.mode.name() + "|" + nz(leg.from) + "|" + nz(leg.to);
    }

    public static Commitment.Leg parseLeg(String s) {
        Commitment.Leg leg = new Commitment.Leg();
        if (s == null || s.isBlank()) return leg;
        String[] f = split(s, "|");
        leg.mode = LegMode.valueOf(f[0]);
        if (f.length > 2) {
            leg.from = f[1].isEmpty() ? null : f[1];
            leg.to = f[2].isEmpty() ? null : f[2];
        }
        return leg;
    }

    // ---- tags: unit-separated, because tags are free text ----------------
    public static String tags(List<String> tags) {
        return tags == null ? "" : String.join(FIELD, tags);
    }

    public static List<String> parseTags(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String t : split(s, FIELD)) if (!t.isEmpty()) out.add(t);
        return out;
    }

    // ---- one day's goal allocation: "lit=1.5,courses=0.75" ---------------
    public static String allocation(Map<String, Double> alloc) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : alloc.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static Map<String, Double> parseAllocation(String s) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) {
            int eq = part.lastIndexOf('=');
            if (eq > 0) out.put(part.substring(0, eq), Double.parseDouble(part.substring(eq + 1)));
        }
        return out;
    }

    /** A whole horizon: one allocation per day, in day order. */
    public static String horizon(List<Map<String, Double>> perDay) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Double> m : perDay) parts.add(allocation(m));
        return String.join(RECORD, parts);
    }

    public static List<Map<String, Double>> parseHorizon(String s) {
        List<Map<String, Double>> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : split(s, RECORD)) out.add(parseAllocation(part));
        return out;
    }

    // ---- a sequenced day, enough to redraw it without re-solving ---------
    public static String blocks(List<Block> blocks) {
        List<String> recs = new ArrayList<>();
        for (Block b : blocks) {
            recs.add(String.join(FIELD,
                String.valueOf(b.start), String.valueOf(b.dur), nz(b.name), nz(b.goalId),
                b.fixed ? "1" : "0", b.away ? "1" : "0", b.gap ? "1" : "0", b.forced ? "1" : "0",
                String.valueOf(b.meanEta), String.valueOf(b.yieldMin),
                String.valueOf(b.load), String.valueOf(b.recovery),
                nz(b.why), nz(b.reason),
                String.valueOf(b.physLoad), String.valueOf(b.physRecovery),
                b.leisure ? "1" : "0", String.valueOf(b.dopamine)));
        }
        return String.join(RECORD, recs);
    }

    public static List<Block> parseBlocks(String s) {
        List<Block> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String rec : split(s, RECORD)) {
            if (rec.isEmpty()) continue;
            String[] f = split(rec, FIELD);
            Block b = new Block();
            b.start = Integer.parseInt(f[0]);
            b.dur = Integer.parseInt(f[1]);
            b.name = f[2];
            b.goalId = f[3].isEmpty() ? null : f[3];
            b.fixed = "1".equals(f[4]);
            b.away = "1".equals(f[5]);
            b.gap = "1".equals(f[6]);
            b.forced = "1".equals(f[7]);
            b.meanEta = Double.parseDouble(f[8]);
            b.yieldMin = Double.parseDouble(f[9]);
            b.load = Double.parseDouble(f[10]);
            b.recovery = Double.parseDouble(f[11]);
            b.why = f[12].isEmpty() ? null : f[12];
            b.reason = f[13].isEmpty() ? null : f[13];
            // Days stored before fatigue had two kinds carry no physical figures.
            if (f.length >= 16) {
                b.physLoad = Double.parseDouble(f[14]);
                b.physRecovery = Double.parseDouble(f[15]);
            }
            // Leisure (v15); days stored before it have none.
            if (f.length >= 18) {
                b.leisure = "1".equals(f[16]);
                b.dopamine = Double.parseDouble(f[17]);
            }
            out.add(b);
        }
        return out;
    }

    // ---- plain string lists (warnings) -----------------------------------
    public static String lines(List<String> lines) {
        return lines == null ? "" : String.join(RECORD, lines);
    }

    public static List<String> parseLines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String l : split(s, RECORD)) if (!l.isEmpty()) out.add(l);
        return out;
    }
}
