package com.extantape.almanac;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides, at each check, whether a schedule runs: its period is due, and nobody is in its quiet box, or they've been
 * there past the give-up time. Runs the command, then records it. Everything it needs comes in through small
 * interfaces, so the bot test and the unit tests drive it the same way.
 */
final class Runner {
    /** Whether anyone is in a quiet box right now; null if the world isn't loaded. */
    interface Occupancy { Boolean anyoneInside(Settings.Quiet quiet); }

    /** Runs a console command. True if it was dispatched without an exception. */
    interface Console { boolean dispatch(String command); }

    private final Settings settings;
    private final Records records;
    private final Supplier<Instant> clock;
    private final Occupancy occupancy;
    private final Console console;
    private final Logger log;
    private final Map<String, Instant> waitingSince = new HashMap<>(), nextTry = new HashMap<>();

    Runner(Settings settings, Records records, Supplier<Instant> clock, Occupancy occupancy, Console console, Logger log) {
        this.settings = settings;
        this.records = records;
        this.clock = clock;
        this.occupancy = occupancy;
        this.console = console;
        this.log = log;
    }

    /** One check of every schedule. Cheap: a date comparison, and a look at one box only when something is due. */
    void check() {
        Instant now = clock.get();
        for (Settings.Schedule s : settings.schedules().values()) {
            YearMonth period = Calendar.periodOf(now, settings.zone());
            if (!Calendar.due(now, settings.zone(), s, records.lastPeriod(s.name()))) { waitingSince.remove(s.name()); continue; }
            Instant retry = nextTry.get(s.name());
            if (retry != null && now.isBefore(retry)) continue;
            String why = "the month changed";
            if (s.quiet() != null) {
                Boolean busy = occupancy.anyoneInside(s.quiet());
                if (busy == null) {
                    log.warning("Schedule " + s.name() + " waits: its quiet world " + s.quiet().world() + " isn't loaded. Fix: load it, or change quiet.world.");
                    nextTry.put(s.name(), now.plus(Duration.ofMinutes(s.quiet().retryMinutes())));
                    continue;
                }
                Instant since = waitingSince.computeIfAbsent(s.name(), k -> now);
                if (busy) {
                    Duration waited = Duration.between(since, now);
                    if (waited.compareTo(Duration.ofHours(s.quiet().giveUpHours())) < 0) {
                        if (retry == null) log.info("Schedule " + s.name() + " is due, but someone is in its quiet area. Checking again every "
                            + s.quiet().retryMinutes() + " minutes, for up to " + s.quiet().giveUpHours() + " hours.");
                        nextTry.put(s.name(), now.plus(Duration.ofMinutes(s.quiet().retryMinutes())));
                        continue;
                    }
                    why = "someone was still in its quiet area after " + s.quiet().giveUpHours() + " hours";
                } else if (retry != null) {
                    why = "its quiet area is empty now";
                }
            }
            run(s, period, why);
        }
    }

    /** Runs a schedule for a period now, whatever the time or the quiet rule, and records it. Returns what it ran. */
    String run(Settings.Schedule s, YearMonth period, String why) {
        String command = Calendar.render(s.command(), period);
        boolean ok = console.dispatch(command);
        waitingSince.remove(s.name());
        nextTry.remove(s.name());
        String result = ok ? "dispatched" : "failed";
        try {
            records.save(s.name(), new Records.Entry(period, clock.get(), result));
        } catch (IOException e) {
            log.log(Level.WARNING, "Schedule " + s.name() + " ran, but applied.yml couldn't be written; it may run again at the next start.", e);
        }
        if (ok) log.info("Schedule " + s.name() + " ran for " + period + " (" + why + "): " + command);
        else log.warning("Schedule " + s.name() + " failed for " + period + ": " + command + ". It's recorded, and runs again next month or with /almanac run.");
        return command;
    }

    /** Whether a schedule is waiting for its quiet area, and since when; null if not. */
    Instant waitingSince(String schedule) { return waitingSince.get(schedule); }
}
