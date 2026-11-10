package com.extantape.almanac;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What each schedule last applied, in {@code applied.yml}: the period, when, and whether the command was dispatched.
 * Plain YAML, readable without the plugin. Written to a temporary file and renamed, so it's never left half-written.
 */
final class Records {
    record Entry(YearMonth period, Instant at, String result) {}

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    Records(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) return;
        YamlConfiguration y = new YamlConfiguration();
        try { y.loadFromString(Files.readString(file, StandardCharsets.UTF_8)); }
        catch (InvalidConfigurationException e) { throw new IOException("applied.yml can't be read: " + e.getMessage(), e); }
        for (String name : y.getKeys(false)) {
            var s = y.getConfigurationSection(name);
            if (s == null) continue;
            entries.put(name, new Entry(YearMonth.parse(s.getString("period")), Instant.parse(s.getString("at")), s.getString("result", "")));
        }
    }

    Entry last(String schedule) { return entries.get(schedule); }

    YearMonth lastPeriod(String schedule) { Entry e = entries.get(schedule); return e == null ? null : e.period(); }

    void save(String schedule, Entry e) throws IOException {
        entries.put(schedule, e);
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of("What each Almanac schedule last applied. Written by Almanac; safe to read."));
        entries.forEach((k, v) -> {
            y.set(k + ".period", v.period().toString());
            y.set(k + ".at", v.at().toString());
            y.set(k + ".result", v.result());
        });
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
