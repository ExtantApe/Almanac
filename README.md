# Sundial

Runs server commands on a calendar for Paper: "on the 1st of every month, run this". It waits for a quiet moment and catches up after downtime.

**Status:** 0.2.0. 14 unit tests pass, and the bot test passes 18/18 on throwaway Paper 26.1.2, 26.2 and 26.3 servers, with a fake clock so months pass in seconds. On 26.2 it drove a copy of a real monthly data pack; on 26.1.2 and 26.3, a made-up stand-in pack. It hasn't run on a real server.

## What it does

- **Monthly, on the 1st, after a set time.** Each schedule runs once a month, no earlier than `not-before` (04:00 by default) on the 1st, in the time zone you set. It doesn't run again until next month.
- **Catches up.** If the server is off over the 1st, the schedule runs at the next start, once, for the current month. Restarting mid-month runs nothing.
- **Waits for a quiet moment.** A schedule can name an area: a world and a box. While anyone is inside, it waits, checks again every `retry-minutes`, and runs anyway after `give-up-after-hours` (6 by default). Someone outside the box, or in another world, doesn't hold it up. A missing world holds it until the world is back, and says so.
- **Runs a console command.** Sundial knows nothing about what the command does. `{month0}` (0 to 11), `{month}` (1 to 12) and `{year}` are filled in. It records what it ran in `applied.yml`, written safely, so a crash never leaves it half-written. If the server dies between running a command and recording it, the command runs again at the next start. Use commands that are safe to repeat.
- **Tells only the console.** Nothing is said in game; the command can announce whatever it needs to.
- **Checks once a minute.** A date comparison, plus a look at one box only when something is due. Nothing runs every tick.
- **Clear about bad config.** An unknown placeholder, a bad box, a bad time or an unknown setting leaves that schedule out, with one console line saying how to fix it. The other schedules still run.

## Install

Paper 26.1 or later, Java 25. Put the jar in `plugins/`, start the server once, then edit `plugins/Sundial/config.yml`:

```yaml
timezone: "Europe/Berlin"
schedules:
  monthly-course:
    command: "function example:rotate {n:{month0}}"
    not-before: "04:00"
    quiet:
      world: example_world
      box: [0, 60, 0, 40, 90, 40]
      retry-minutes: 5
      give-up-after-hours: 6
```

Then run `/sundial reload` and `/sundial status`. On a first install, a schedule applies the current month straight away, at the first check after its `not-before` time.

## Commands

| Command | What it does |
| --- | --- |
| `/sundial status` | Each schedule: what it last applied, when, and what's next, or that it's waiting for its quiet area. Config problems too. |
| `/sundial run <schedule>` | Applies the current month now, whatever the time or the quiet rule. |
| `/sundial preview <schedule> <month>` | Shows the command it would run for that month, without running it. |
| `/sundial reload` | Rereads `config.yml`. |

All need `sundial.admin`, which operators have by default.

## Testing

`./gradlew test` runs the unit tests. `test/acceptance.mjs` runs the bot test on a throwaway server; the top of the file says what it needs. The launch flags `-Dalmanac.test.clockFile` and `-Dalmanac.test.checkTicks` are for throwaway test servers only.

## License

MIT. See `LICENSE`.
