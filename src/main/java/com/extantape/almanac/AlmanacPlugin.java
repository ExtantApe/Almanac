package com.extantape.almanac;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class AlmanacPlugin extends JavaPlugin {
    static final String ADMIN = "almanac.admin";
    /** Test only, set at launch: a file holding the time to use instead of the clock, so months pass in seconds. */
    private static final String TEST_CLOCK = "almanac.test.clockFile";
    /** Test only: how often to check, in ticks, instead of once a minute. */
    private static final String TEST_CHECK = "almanac.test.checkTicks";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private Settings settings;
    private Records records;
    private Runner runner;
    private Supplier<Instant> clock = Instant::now;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String clockFile = System.getProperty(TEST_CLOCK);
        if (clockFile != null) {
            Path p = Path.of(clockFile);
            clock = () -> { try { return Instant.parse(Files.readString(p).trim()); } catch (IOException | RuntimeException e) { return Instant.now(); } };
            getLogger().warning("Using the test clock in " + clockFile + ". Only for throwaway test servers.");
        }
        try {
            records = new Records(getDataFolder().toPath().resolve("applied.yml"));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "applied.yml can't be read, so Almanac runs nothing until it's fixed or removed.", e);
            return;
        }
        load();
        long every = Long.getLong(TEST_CHECK, 20L * 60);
        // A tick later, so every plugin's worlds are loaded before the first check (and the catch-up after downtime).
        Bukkit.getScheduler().runTaskTimer(this, () -> runner.check(), 1L, every);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e -> e.registrar().register(
            Commands.literal("almanac").requires(s -> s.getSender().hasPermission(ADMIN))
                .then(Commands.literal("status").executes(c -> { status(c.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("reload").executes(c -> { reload(c.getSource().getSender()); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("run").then(Commands.argument("schedule", StringArgumentType.word())
                    .suggests((c, b) -> { settings.schedules().keySet().forEach(b::suggest); return b.buildFuture(); })
                    .executes(this::run)))
                .then(Commands.literal("preview").then(Commands.argument("schedule", StringArgumentType.word())
                    .suggests((c, b) -> { settings.schedules().keySet().forEach(b::suggest); return b.buildFuture(); })
                    .then(Commands.argument("month", IntegerArgumentType.integer(1, 12)).executes(this::preview))))
                .build(),
            "Status, run and preview of Almanac's calendar schedules."));
    }

    private void load() {
        reloadConfig();
        settings = Settings.read(getConfig());
        for (String p : settings.problems()) getLogger().warning(p);
        runner = new Runner(settings, records, clock, this::anyoneInside, this::dispatch, getLogger());
        getLogger().info(settings.schedules().size() + " schedule(s) loaded, time zone " + settings.zone() + ".");
    }

    private Boolean anyoneInside(Settings.Quiet q) {
        World w = Bukkit.getWorld(q.world());
        if (w == null) return null;
        for (Player p : w.getPlayers()) { var l = p.getLocation(); if (q.inside(l.getX(), l.getY(), l.getZ())) return true; }
        return false;
    }

    private boolean dispatch(String command) {
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "The command threw an error: " + command, e);
            return false;
        }
    }

    // --- commands ---

    private void status(CommandSender to) {
        Instant now = clock.get();
        tell(to, "Almanac: " + settings.schedules().size() + " schedule(s), time zone " + settings.zone() + ", now " + WHEN.format(now.atZone(settings.zone())) + ".");
        for (String p : settings.problems()) tell(to, p, NamedTextColor.RED);
        for (Settings.Schedule s : settings.schedules().values()) {
            Records.Entry last = records.last(s.name());
            YearMonth period = Calendar.periodOf(now, settings.zone());
            String applied = last == null ? "nothing yet" : last.period() + " (" + last.result() + " " + WHEN.format(last.at().atZone(settings.zone())) + ")";
            boolean due = Calendar.due(now, settings.zone(), s, records.lastPeriod(s.name()));
            Instant waiting = runner.waitingSince(s.name());
            String next = due ? (waiting != null ? "due now, waiting for its quiet area since " + WHEN.format(waiting.atZone(settings.zone())) : "due now")
                : "next " + WHEN.format(Calendar.opensAt(last != null && !last.period().isBefore(period) ? period.plusMonths(1) : period, s, settings.zone()).atZone(settings.zone()));
            tell(to, s.name() + ": applied " + applied + "; " + next + ".");
        }
    }

    private int run(CommandContext<CommandSourceStack> c) {
        CommandSender to = c.getSource().getSender();
        Settings.Schedule s = settings.schedules().get(StringArgumentType.getString(c, "schedule"));
        if (s == null) { tell(to, "There's no schedule called that. /almanac status lists them.", NamedTextColor.RED); return 0; }
        String ran = runner.run(s, Calendar.periodOf(clock.get(), settings.zone()), "run by " + to.getName());
        tell(to, "Ran " + s.name() + ": " + ran);
        return Command.SINGLE_SUCCESS;
    }

    private int preview(CommandContext<CommandSourceStack> c) {
        CommandSender to = c.getSource().getSender();
        Settings.Schedule s = settings.schedules().get(StringArgumentType.getString(c, "schedule"));
        if (s == null) { tell(to, "There's no schedule called that. /almanac status lists them.", NamedTextColor.RED); return 0; }
        YearMonth month = YearMonth.of(Calendar.periodOf(clock.get(), settings.zone()).getYear(), IntegerArgumentType.getInteger(c, "month"));
        tell(to, s.name() + " for " + month + " would run: " + Calendar.render(s.command(), month) + " (not run)");
        return Command.SINGLE_SUCCESS;
    }

    private void reload(CommandSender to) {
        load();
        tell(to, "Reloaded. " + settings.schedules().size() + " schedule(s)" + (settings.problems().isEmpty() ? "." : ", with problems in the console."));
    }

    private static void tell(CommandSender to, String text) { tell(to, text, NamedTextColor.WHITE); }

    private static void tell(CommandSender to, String text, NamedTextColor colour) { to.sendMessage(Component.text(text, colour)); }
}
