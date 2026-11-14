package com.extantape.sundial;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

/** The arithmetic of "on the 1st, after 04:00, in the server's zone". No server, so it can be tested with any clock. */
final class Calendar {
    private Calendar() {}

    /** The period a moment falls in, in the configured zone. */
    static YearMonth periodOf(Instant now, ZoneId zone) { return YearMonth.from(now.atZone(zone)); }

    /**
     * The first moment this period's run may happen: the 1st at the not-before time, in the zone. Around a daylight-saving
     * change, a time that doesn't exist that night moves forward with the clock.
     */
    static Instant opensAt(YearMonth period, Settings.Schedule s, ZoneId zone) {
        return LocalDateTime.of(period.atDay(1), s.notBefore()).atZone(zone).toInstant();
    }

    /** Whether this period still needs running: it's open, and the last applied period is older. */
    static boolean due(Instant now, ZoneId zone, Settings.Schedule s, YearMonth lastApplied) {
        YearMonth period = periodOf(now, zone);
        if (lastApplied != null && !lastApplied.isBefore(period)) return false;
        return !now.isBefore(opensAt(period, s, zone));
    }

    /** The command with its placeholders filled in for a period. */
    static String render(String command, YearMonth period) {
        return command.replace("{month0}", Integer.toString(period.getMonthValue() - 1))
            .replace("{month}", Integer.toString(period.getMonthValue()))
            .replace("{year}", Integer.toString(period.getYear()));
    }
}
