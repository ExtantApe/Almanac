package com.extantape.almanac;

import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Almanac's config, checked. A schedule with a problem is left out with one line saying why and how to fix it; the
 * others still run. Nothing about any particular server lives in code: worlds, boxes and commands come from config.
 */
record Settings(ZoneId zone, Map<String, Schedule> schedules, List<String> problems) {

    /** Where nobody may be when a schedule runs, and how long to wait for them to leave. */
    record Quiet(String world, int[] box, int retryMinutes, int giveUpHours) {
        boolean inside(double x, double y, double z) {
            return x >= Math.min(box[0], box[3]) && x < Math.max(box[0], box[3]) + 1
                && y >= Math.min(box[1], box[4]) && y < Math.max(box[1], box[4]) + 1
                && z >= Math.min(box[2], box[5]) && z < Math.max(box[2], box[5]) + 1;
        }
    }

    record Schedule(String name, String command, LocalTime notBefore, Quiet quiet) {}

    static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,32}");
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9]+)}");
    static final Set<String> PLACEHOLDERS = Set.of("month0", "month", "year");
    static final Set<String> KEYS = Set.of("every", "command", "not-before", "quiet");
    static final Set<String> QUIET_KEYS = Set.of("world", "box", "retry-minutes", "give-up-after-hours");

    static Settings read(ConfigurationSection c) {
        List<String> problems = new ArrayList<>();
        ZoneId zone;
        String tz = c.getString("timezone", "UTC");
        try { zone = ZoneId.of(tz); }
        catch (DateTimeException e) {
            problems.add("timezone \"" + tz + "\" isn't a time zone, so nothing runs. Fix: use a name like Pacific/Honolulu or UTC.");
            return new Settings(ZoneId.of("UTC"), Map.of(), List.copyOf(problems));
        }
        Map<String, Schedule> out = new LinkedHashMap<>();
        ConfigurationSection all = c.getConfigurationSection("schedules");
        if (all != null) for (String name : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(name);
            String why = s == null ? "isn't a section" : problemOf(name, s);
            if (why != null) { problems.add("Schedule " + name + " " + why + " It's skipped; the others still run."); continue; }
            ConfigurationSection q = s.getConfigurationSection("quiet");
            Quiet quiet = q == null ? null : new Quiet(q.getString("world"), q.getIntegerList("box").stream().mapToInt(Integer::intValue).toArray(),
                q.getInt("retry-minutes", 5), q.getInt("give-up-after-hours", 6));
            out.put(name, new Schedule(name, s.getString("command").trim(), LocalTime.parse(s.getString("not-before", "04:00")), quiet));
        }
        return new Settings(zone, Map.copyOf(out), List.copyOf(problems));
    }

    /** What's wrong with one schedule, as the end of a sentence with its fix, or null. */
    static String problemOf(String name, ConfigurationSection s) {
        if (!NAME.matcher(name).matches()) return "needs a name of 1 to 32 lowercase letters, digits or dashes.";
        for (String k : s.getKeys(false)) if (!KEYS.contains(k)) return "has a setting Almanac doesn't know, " + k + ". Fix: remove it or check its spelling.";
        String every = s.getString("every", "month");
        if (!every.equals("month")) return "says every: " + every + ", but only month is supported so far.";
        String command = s.getString("command", "");
        if (command.isBlank()) return "has no command.";
        if (command.startsWith("/")) return "has a command starting with /. Fix: leave the slash out; it runs as the console.";
        Matcher m = PLACEHOLDER.matcher(command);
        while (m.find()) if (!PLACEHOLDERS.contains(m.group(1)))
            return "uses {" + m.group(1) + "}, which Almanac doesn't fill in. Fix: use {month0} (0-11), {month} (1-12) or {year}.";
        try { LocalTime.parse(s.getString("not-before", "04:00")); }
        catch (DateTimeParseException e) { return "has not-before \"" + s.getString("not-before") + "\". Fix: use a 24-hour time like 04:00."; }
        ConfigurationSection q = s.getConfigurationSection("quiet");
        if (s.contains("quiet") && q == null) return "has a quiet setting that isn't a section.";
        if (q != null) {
            for (String k : q.getKeys(false)) if (!QUIET_KEYS.contains(k)) return "has a quiet setting Almanac doesn't know, " + k + ".";
            if (q.getString("world", "").isBlank()) return "has a quiet rule with no world.";
            List<?> box = q.getList("box");
            if (box == null || box.size() != 6 || !box.stream().allMatch(v -> v instanceof Integer))
                return "has a quiet box that isn't six whole numbers. Fix: box: [x1, y1, z1, x2, y2, z2].";
            int retry = q.getInt("retry-minutes", 5), give = q.getInt("give-up-after-hours", 6);
            if (retry < 1 || retry > 60) return "has retry-minutes " + retry + ". Fix: use 1 to 60.";
            if (give < 1 || give > 72) return "has give-up-after-hours " + give + ". Fix: use 1 to 72.";
        }
        return null;
    }
}
