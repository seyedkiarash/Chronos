package com.chronos.engine;

import java.time.LocalDate;

/**
 * Gregorian to Jalali conversion, so a date can be shown in both calendars.
 * Algorithm follows the standard 33-year cycle arithmetic.
 */
public final class Jalali {
    private Jalali() {}

    public static final String[] MONTHS = {
        "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
        "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand"
    };

    public record Date(int year, int month, int day) {
        @Override public String toString() { return day + " " + MONTHS[month - 1] + " " + year; }
    }

    private static int div(int a, int b) { return Math.floorDiv(a, b); }

    public static Date of(LocalDate g) {
        int gy = g.getYear(), gm = g.getMonthValue(), gd = g.getDayOfMonth();
        int[] gdm = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
        int gy2 = (gm > 2) ? gy + 1 : gy;
        int days = 355666 + 365 * gy + div(gy2 + 3, 4) - div(gy2 + 99, 100)
                 + div(gy2 + 399, 400) + gd + gdm[gm - 1];
        int jy = -1595 + 33 * div(days, 12053);
        days %= 12053;
        jy += 4 * div(days, 1461);
        days %= 1461;
        if (days > 365) {
            jy += div(days - 1, 365);
            days = (days - 1) % 365;
        }
        int jm, jd;
        if (days < 186) { jm = 1 + div(days, 31); jd = 1 + days % 31; }
        else { jm = 7 + div(days - 186, 30); jd = 1 + (days - 186) % 30; }
        return new Date(jy, jm, jd);
    }

    /** "Sat 3 Mehr 1405 / 26 Sep 2026" */
    public static String both(LocalDate g) {
        Date j = of(g);
        return Slots.DOW[Slots.dow(g)] + " " + j.day() + " " + MONTHS[j.month() - 1] + " " + j.year()
             + " / " + g.getDayOfMonth() + " " + g.getMonth().toString().substring(0, 1)
             + g.getMonth().toString().substring(1, 3).toLowerCase() + " " + g.getYear();
    }
}
