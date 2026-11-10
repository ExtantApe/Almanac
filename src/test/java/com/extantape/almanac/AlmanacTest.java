package com.extantape.almanac;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The calendar, the config checks and the quiet rule, with a fake clock. The real server is in the bot test. */
class AlmanacTest {
    static final ZoneId HNL = ZoneId.of("Pacific/Honolulu"), NY = ZoneId.of("America/New_York");
    static final Settings.Schedule PLAIN = new Settings.Schedule("course", "function x:to {n:{month0}}", LocalTime.of(4, 0), null);

    static Settings settings(String yaml) { return Settings.read(YamlConfiguration.loadConfiguration(new StringReader(yaml))); }
    static Instant at(int y, int m, int d, int h, int min, ZoneId z) { return ZonedDateTime.of(y, m, d, h, min, 0, 0, z).toInstant(); }

    // --- the calendar ---

    @Test void theMonthIsTheServersNotUtc() {
        Instant justAfterMidnightUtcOnThe1st = at(2026, 12, 1, 3, 0, ZoneId.of("UTC"));
        assertEquals(YearMonth.of(2026, 11), Calendar.periodOf(justAfterMidnightUtcOnThe1st, HNL), "still November there");
    }

    @Test void nothingRunsBefore4OnThe1st() {
        assertFalse(Calendar.due(at(2026, 12, 1, 3, 59, HNL), HNL, PLAIN, YearMonth.of(2026, 11)));
        assertTrue(Calendar.due(at(2026, 12, 1, 4, 0, HNL), HNL, PLAIN, YearMonth.of(2026, 11)));
    }

    @Test void itCatchesUpAfterDowntimeAndNeverRepeats() {
        assertTrue(Calendar.due(at(2026, 12, 9, 1, 0, HNL), HNL, PLAIN, YearMonth.of(2026, 11)), "off over the 1st: runs at the next start, any hour");
        assertTrue(Calendar.due(at(2027, 2, 9, 1, 0, HNL), HNL, PLAIN, YearMonth.of(2026, 11)), "off for months: runs once, for the current month");
        assertFalse(Calendar.due(at(2026, 12, 20, 12, 0, HNL), HNL, PLAIN, YearMonth.of(2026, 12)), "restarting mid-month runs nothing");
        assertTrue(Calendar.due(at(2026, 12, 20, 12, 0, HNL), HNL, PLAIN, null), "a first install applies the current month");
    }

    @Test void daylightSavingDoesntMoveTheDay() {
        var at2 = new Settings.Schedule("dst", "say x", LocalTime.of(2, 30), null);
        // 2027-03-14 02:30 doesn't exist in New York; the 1st isn't a change day, so check a 1st near one anyway.
        assertEquals(at(2027, 3, 1, 2, 30, NY), Calendar.opensAt(YearMonth.of(2027, 3), at2, NY));
        assertEquals(at(2026, 11, 1, 4, 0, NY), Calendar.opensAt(YearMonth.of(2026, 11), PLAIN, NY), "on 2026-11-01, when the clocks went back");
        assertFalse(Calendar.due(at(2026, 11, 1, 1, 30, NY), NY, PLAIN, YearMonth.of(2026, 10)), "01:30 happens twice that night; still before 04:00");
    }

    @Test void placeholdersFillIn() {
        assertEquals("function x:to {n:0} 1 2027", Calendar.render("function x:to {n:{month0}} {month} {year}", YearMonth.of(2027, 1)));
        assertEquals("function x:to {n:11}", Calendar.render(PLAIN.command(), YearMonth.of(2026, 12)));
    }

    // --- config checks ---

    @Test void theShippedConfigLoadsWithNothingToRun() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            var s = Settings.read(YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in)));
            assertEquals(List.of(), s.problems());
            assertTrue(s.schedules().isEmpty());
        }
    }

    @Test void aBadScheduleIsSkippedAndTheOthersStillRun() {
        var s = settings("""
            timezone: Pacific/Honolulu
            schedules:
              good: {command: "say {month}"}
              typo: {command: "say {mnth}"}
              box: {command: "say x", quiet: {world: w, box: [1, 2, 3]}}
              slash: {command: "/say x"}
              weekly: {every: week, command: "say x"}
              extra: {command: "say x", colour: red}
              late: {command: "say x", not-before: "25:00"}
            """);
        assertEquals(List.of("good"), List.copyOf(s.schedules().keySet()));
        assertEquals(6, s.problems().size(), String.valueOf(s.problems()));
        assertTrue(s.problems().stream().allMatch(p -> p.contains("It's skipped; the others still run.")));
        assertTrue(s.problems().stream().anyMatch(p -> p.contains("{mnth}") && p.contains("{month0}")), "says which placeholders exist");
    }

    @Test void aBadTimeZoneRunsNothingAndSaysWhy() {
        var s = settings("timezone: Mars/Olympus\nschedules: {good: {command: \"say x\"}}\n");
        assertTrue(s.schedules().isEmpty());
        assertTrue(s.problems().getFirst().contains("isn't a time zone"));
    }

    @Test void theQuietBoxIsInclusiveWhicheverWayRoundTheCornersAre() {
        var q = new Settings.Quiet("w", new int[] {10, 60, 10, 0, 50, 0}, 5, 6);
        assertTrue(q.inside(0.2, 50, 10.9));
        assertTrue(q.inside(10.99, 60.5, 0));
        assertFalse(q.inside(11.01, 55, 5));
        assertFalse(q.inside(5, 49.9, 5));
    }

    // --- the runner and the quiet rule ---

    final AtomicReference<Instant> now = new AtomicReference<>(at(2026, 12, 1, 4, 0, HNL));
    final AtomicBoolean busy = new AtomicBoolean(false);
    final List<String> ran = new ArrayList<>();
    @TempDir Path dir;

    Runner runner(Records records, boolean worldLoaded) {
        var quiet = new Settings.Quiet("w", new int[] {0, 0, 0, 10, 10, 10}, 5, 6);
        var s = new Settings(HNL, java.util.Map.of("course", new Settings.Schedule("course", PLAIN.command(), LocalTime.of(4, 0), quiet)), List.of());
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        return new Runner(s, records, now::get, q -> worldLoaded ? busy.get() : null, c -> ran.add(c), log);
    }

    Records records() throws Exception { return new Records(dir.resolve("applied.yml")); }

    @Test void anEmptyAreaRunsOnTheDotOnce() throws Exception {
        var r = runner(records(), true);
        r.check();
        r.check();
        assertEquals(List.of("function x:to {n:11}"), ran);
    }

    @Test void someoneInTheAreaDelaysItUntilTheyLeaveWithinOneRetry() throws Exception {
        var r = runner(records(), true);
        busy.set(true);
        r.check();
        assertEquals(List.of(), ran);
        busy.set(false);
        now.set(now.get().plus(Duration.ofMinutes(4)));
        r.check();
        assertEquals(List.of(), ran, "not before the next retry");
        now.set(now.get().plus(Duration.ofMinutes(1)));
        r.check();
        assertEquals(1, ran.size(), "within one retry of them leaving");
    }

    @Test void afterSixHoursItRunsAnyway() throws Exception {
        var r = runner(records(), true);
        busy.set(true);
        for (int m = 0; m < 6 * 60; m += 5) { r.check(); now.set(now.get().plus(Duration.ofMinutes(5))); }
        assertEquals(List.of(), ran);
        r.check();
        assertEquals(1, ran.size(), "gave up waiting at 6 hours");
    }

    @Test void aMissingWorldRunsNothingUntilItsBack() throws Exception {
        var r = runner(records(), false);
        r.check();
        now.set(now.get().plus(Duration.ofHours(7)));
        r.check();
        assertEquals(List.of(), ran, "never runs blind, even past the give-up time");
    }

    @Test void theRecordSurvivesARestartAndIsNeverHalfWritten() throws Exception {
        runner(records(), true).check();
        assertEquals(1, ran.size());
        var again = runner(records(), true);
        now.set(now.get().plus(Duration.ofDays(3)));
        again.check();
        assertEquals(1, ran.size(), "restarting mid-month runs nothing");
        assertTrue(Files.readString(dir.resolve("applied.yml")).contains("period: 2026-12"));
        assertFalse(Files.exists(dir.resolve("applied.yml.tmp")));
    }
}
