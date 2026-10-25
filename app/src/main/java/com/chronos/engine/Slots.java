package com.chronos.engine;

import java.time.LocalDate;

/** Day-grid arithmetic. One slot is fifteen minutes; a day is ninety-six of them. */
public final class Slots {
    private Slots() {}

    public static final int SLOT_MIN = 15;
    public static final int PER_DAY = 96;
    public static final String[] DOW = {"Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri"};

    /** Saturday is 0, matching a week that starts on Saturday. */
    public static int dow(LocalDate d) {
        return (d.getDayOfWeek().getValue() + 1) % 7;   // Mon=1 -> 2, Sat=6 -> 0, Sun=7 -> 1
    }

    public static int parse(String hhmm) {
        if (hhmm == null) return 0;
        String[] parts = hhmm.split(":");
        int m = Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
        return Math.floorDiv(m + SLOT_MIN / 2, SLOT_MIN) % PER_DAY;
    }

    public static String format(int slot) {
        int m = Math.floorMod(slot, PER_DAY) * SLOT_MIN;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    public static String formatMinutes(int minutes) {
        int m = Math.floorMod(minutes, 1440);
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    /**
     * A window's span in slots, or null when it ends before it starts. An end
     * of 00:00 is midnight at the end of the day, so 22:00–00:00 is two hours;
     * anything else that ends at or before its start is a typing mistake, and
     * is left out of planning until it is corrected rather than read as
     * "until midnight", which is what it silently meant before.
     */
    public static int[] span(String from, String to) {
        if (from == null || to == null) return null;
        int s = parse(from), e = parse(to);
        if (e == 0) e = PER_DAY;
        return e > s ? new int[]{s, e} : null;
    }

    public static int wrap(int slot) { return Math.floorMod(slot, PER_DAY); }

    public static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
    public static int clamp(int x, int lo, int hi) { return Math.max(lo, Math.min(hi, x)); }

    public static double sum(double[] a) { double s = 0; for (double v : a) s += v; return s; }
}
