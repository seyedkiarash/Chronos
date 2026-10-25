package com.chronos.calendar;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import com.chronos.engine.Slots;
import com.chronos.model.Block;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a solved day into the device calendar provider.
 *
 * Two things are worth knowing before reading this.
 *
 * First, a calendar created with ACCOUNT_TYPE_LOCAL lives only on the phone.
 * It shows up in the calendar app but never reaches google.com, because only
 * Google's own sync adapter may push rows into a Google account. So the app
 * asks which existing calendar to write into: choose a Google one and the
 * blocks appear everywhere that account is signed in. The local calendar is
 * offered as a fallback for someone who wants the schedule on the phone only.
 *
 * Second, the app must be able to find its own events again to replace them.
 * Rather than remembering row ids, every event carries a marker in its
 * description, which survives a reinstall and is easy to verify by eye.
 */
public final class CalendarWriter {
    private CalendarWriter() {}

    /** Prefix that identifies an event this app wrote. */
    public static final String MARKER = "chronos:";

    public record CalendarChoice(long id, String displayName, String accountName,
                                 String accountType, boolean writable) {
        public boolean syncsToGoogle() { return "com.google".equals(accountType); }
    }

    public record SyncResult(int written, int removed, String message) {}

    /**
     * One colour per activity, so a week in Google Calendar reads at a glance.
     *
     * A Google calendar only accepts colours from the account's own palette
     * (the Colors table, keyed "1".."11"); an arbitrary ARGB is silently
     * dropped by the sync adapter. So for a Google calendar the choice is a
     * colour key, and for a phone-only calendar it is our own ARGB. Goals take
     * the first colours in order, matching the app's charts; upkeep and
     * commitments are hashed by name into what is left, so the same activity
     * keeps the same colour from week to week.
     */
    public static final class Colours {
        private final List<String> keys;      // Google colour keys, or empty
        private final List<Integer> argb;     // matching ARGB, or our palette
        private final List<String> goalIds;

        Colours(List<String> keys, List<Integer> argb, List<String> goalIds) {
            this.keys = keys; this.argb = argb; this.goalIds = goalIds;
        }

        boolean usesKeys() { return !keys.isEmpty(); }

        int indexFor(Block b) {
            int n = argb.size();
            if (n == 0) return -1;
            if (b.goalId != null) {
                int g = goalIds.indexOf(b.goalId);
                return Math.floorMod(g < 0 ? b.goalId.hashCode() : g, n);
            }
            int reserved = Math.min(goalIds.size(), n - 1);
            int free = n - reserved;
            // "commute (out)" and "commute (back)" share one colour.
            String base = b.name == null ? "" : b.name.replaceAll("\\s*\\(.*\\)$", "").trim();
            return reserved + Math.floorMod(base.toLowerCase().hashCode(), free);
        }

        void apply(ContentValues values, Block b) {
            int i = indexFor(b);
            if (i < 0) return;
            if (usesKeys()) values.put(CalendarContract.Events.EVENT_COLOR_KEY, keys.get(i));
            else values.put(CalendarContract.Events.EVENT_COLOR, argb.get(i));
        }
    }

    /** Our own palette, used when the calendar has no colour table of its own. */
    private static final int[] LOCAL_PALETTE = {
        0xFF4A4E8C, 0xFF2F5D7C, 0xFF5E8C6A, 0xFF8A6A9E, 0xFFB07A3C, 0xFF3F7F87, 0xFF9E5A6B,
        0xFFB08D2A, 0xFFA8452F, 0xFF7A8C99
    };

    public static Colours coloursFor(Context context, long calendarId, List<String> goalIds) {
        ContentResolver resolver = context.getContentResolver();
        String account = null, type = null;
        try (Cursor c = resolver.query(
                ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
                new String[]{CalendarContract.Calendars.ACCOUNT_NAME,
                             CalendarContract.Calendars.ACCOUNT_TYPE}, null, null, null)) {
            if (c != null && c.moveToFirst()) { account = c.getString(0); type = c.getString(1); }
        }

        List<String> keys = new ArrayList<>();
        List<Integer> argb = new ArrayList<>();
        if (account != null && type != null) {
            String selection = CalendarContract.Colors.ACCOUNT_NAME + " = ? AND "
                             + CalendarContract.Colors.ACCOUNT_TYPE + " = ? AND "
                             + CalendarContract.Colors.COLOR_TYPE + " = ?";
            String[] args = {account, type, String.valueOf(CalendarContract.Colors.TYPE_EVENT)};
            try (Cursor c = resolver.query(CalendarContract.Colors.CONTENT_URI,
                    new String[]{CalendarContract.Colors.COLOR_KEY, CalendarContract.Colors.COLOR},
                    selection, args, CalendarContract.Colors.COLOR_KEY)) {
                while (c != null && c.moveToNext()) {
                    keys.add(c.getString(0));
                    argb.add(c.getInt(1));
                }
            } catch (Exception ignored) {
                keys.clear(); argb.clear();
            }
        }
        if (keys.isEmpty()) {
            argb.clear();
            for (int v : LOCAL_PALETTE) argb.add(v);
        }
        return new Colours(keys, argb, goalIds);
    }

    private static final String[] CAL_COLUMNS = {
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.ACCOUNT_TYPE,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
    };

    /** Everything the user could write into, Google accounts first. */
    public static List<CalendarChoice> available(Context context) {
        List<CalendarChoice> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, CAL_COLUMNS, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                int access = c.getInt(4);
                boolean writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
                out.add(new CalendarChoice(c.getLong(0), c.getString(1),
                                           c.getString(2), c.getString(3), writable));
            }
        }
        out.sort((a, b) -> Boolean.compare(b.syncsToGoogle(), a.syncsToGoogle()));
        return out;
    }

    /**
     * A phone-only calendar named Chronos, for people who do not want their
     * schedule leaving the device. Created once and reused.
     */
    public static long ensureLocalCalendar(Context context, String displayName) {
        ContentResolver resolver = context.getContentResolver();
        String selection = CalendarContract.Calendars.ACCOUNT_TYPE + " = ? AND "
                         + CalendarContract.Calendars.ACCOUNT_NAME + " = ?";
        String[] args = {CalendarContract.ACCOUNT_TYPE_LOCAL, displayName};
        try (Cursor c = resolver.query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID}, selection, args, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }

        ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, displayName);
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        values.put(CalendarContract.Calendars.NAME, displayName);
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName);
        values.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF2F5D7C);
        values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                   CalendarContract.Calendars.CAL_ACCESS_OWNER);
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, displayName);
        values.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().getId());
        values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        values.put(CalendarContract.Calendars.VISIBLE, 1);

        Uri uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, displayName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,
                                  CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build();
        Uri created = resolver.insert(uri, values);
        return created == null ? -1 : ContentUris.parseId(created);
    }

    /**
     * Replace one day. Previous blocks written by this app for that date are
     * removed first, so pushing twice never leaves duplicates, and anything the
     * user put in that calendar themselves is left untouched.
     *
     * @param reminderMinutes minutes before the start, or a negative number for
     *                        no reminder. The calendar app fires these itself,
     *                        which is why the app needs no alarm permission.
     */
    public static SyncResult pushDay(Context context, long calendarId, LocalDate date,
                                     List<Block> blocks, Colours colours, boolean includeUpkeep,
                                     int reminderMinutes) {
        return pushDay(context, calendarId, date, blocks, null, java.util.Set.of(),
                       colours, includeUpkeep, reminderMinutes);
    }

    /**
     * @param sleep     the night's sleep as a block starting on this date, or
     *                  null to leave sleep out of the calendar
     * @param napNames  blocks that are the nap: written whenever sleep is, even
     *                  with upkeep left out, because a nap is sleep
     */
    public static SyncResult pushDay(Context context, long calendarId, LocalDate date,
                                     List<Block> blocks, Block sleep, java.util.Set<String> napNames,
                                     Colours colours, boolean includeUpkeep, int reminderMinutes) {
        ContentResolver resolver = context.getContentResolver();
        ZoneId zone = ZoneId.systemDefault();
        String marker = MARKER + date;

        int removed = clear(resolver, calendarId, date, zone);

        List<Block> all = new ArrayList<>(blocks);
        if (sleep != null) all.add(sleep);

        int written = 0;
        for (Block b : all) {
            if (b.gap || b.start < 0) continue;
            boolean isSleep = b == sleep || (sleep != null && b.name != null && napNames.contains(b.name));
            if (!includeUpkeep && b.goalId == null && !isSleep) continue;

            ZonedDateTime start = date.atStartOfDay(zone)
                .plusMinutes((long) b.start * Slots.SLOT_MIN);
            ZonedDateTime end = start.plusMinutes((long) b.dur * Slots.SLOT_MIN);

            ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
            values.put(CalendarContract.Events.TITLE, b.name);
            values.put(CalendarContract.Events.DESCRIPTION, marker + "\n" + note(b));
            values.put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli());
            values.put(CalendarContract.Events.DTEND, end.toInstant().toEpochMilli());
            values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.getId());
            values.put(CalendarContract.Events.HAS_ALARM, reminderMinutes >= 0 ? 1 : 0);
            values.put(CalendarContract.Events.AVAILABILITY,
                       b.goalId != null ? CalendarContract.Events.AVAILABILITY_BUSY
                                        : CalendarContract.Events.AVAILABILITY_FREE);
            if (colours != null) colours.apply(values, b);

            // The event and its reminder in one transaction. Inserted one after the other, the
            // Google sync adapter could send the event up before its reminder existed; Google then
            // gave it the calendar's default alert (30 minutes before) and synced that back,
            // in place of the one you set.
            java.util.ArrayList<android.content.ContentProviderOperation> ops = new java.util.ArrayList<>();
            ops.add(android.content.ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValues(values).build());
            if (reminderMinutes >= 0)
                ops.add(android.content.ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                    .withValue(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    .build());
            try {
                android.content.ContentProviderResult[] res = resolver.applyBatch(CalendarContract.AUTHORITY, ops);
                if (res.length > 0 && res[0].uri != null) written++;
            } catch (Exception e) {
                // A provider that refuses batches: the old way, one row after the other.
                Uri uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values);
                if (uri == null) continue;
                written++;
                if (reminderMinutes >= 0) {
                    ContentValues reminder = new ContentValues();
                    reminder.put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri));
                    reminder.put(CalendarContract.Reminders.MINUTES, reminderMinutes);
                    reminder.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                    resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder);
                }
            }
        }
        return new SyncResult(written, removed,
            written + " block" + (written == 1 ? "" : "s") + " written"
            + (removed > 0 ? ", " + removed + " replaced" : ""));
    }

    /** Remove only this app's events for a date. Anything else stays. */
    public static int clear(ContentResolver resolver, long calendarId, LocalDate date, ZoneId zone) {
        long from = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        String selection = CalendarContract.Events.CALENDAR_ID + " = ? AND "
                         + CalendarContract.Events.DTSTART + " >= ? AND "
                         + CalendarContract.Events.DTSTART + " < ? AND "
                         + CalendarContract.Events.DESCRIPTION + " LIKE ?";
        String[] args = {String.valueOf(calendarId), String.valueOf(from), String.valueOf(to),
                         MARKER + "%"};
        return resolver.delete(CalendarContract.Events.CONTENT_URI, selection, args);
    }

    public static int clearRange(Context context, long calendarId, LocalDate from, int days) {
        ZoneId zone = ZoneId.systemDefault();
        int total = 0;
        for (int i = 0; i < days; i++)
            total += clear(context.getContentResolver(), calendarId, from.plusDays(i), zone);
        return total;
    }

    private static String note(Block b) {
        if (b.goalId == null) return "Kept clear by Chronos.";
        return String.format("Planned capacity %.2f. Effective output about %.0f of %d minutes.",
            b.meanEta, b.yieldMin, b.dur * Slots.SLOT_MIN);
    }
}
