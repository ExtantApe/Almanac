# Test audit

## 2026-11-10, for 0.1.0

| Promise | Proved by |
| --- | --- |
| Runs on the 1st, not before `not-before`, in the configured zone, not UTC | `SundialTest`: zone, 04:00 and daylight-saving cases. Bot test: 03:30 nothing, 04:01 runs |
| Runs once per month, with the right placeholders | Unit tests; bot test "runs once, with the right month" |
| Catches up once after downtime; a restart mid-month runs nothing; a first install applies the current month | Unit tests; bot test with two restarts |
| Quiet rule: someone in the box delays it, leaving lets it run within one retry, staying past 6 hours runs it anyway, someone elsewhere doesn't delay it, a missing world never runs blind | Unit tests (fake clock); bot test with a bot in and out of the box |
| A schedule without a quiet area isn't held up by another's | Bot test |
| Bad config is reported with its fix and skipped; the others still run; a bad time zone runs nothing | Unit tests; bot test "a bad schedule is reported" |
| `status`, `run` and `preview` | Bot test |
| `applied.yml` is plain YAML, survives restarts, and is never left half-written | Unit test; bot test |
| Nothing runs every tick | By design: one check a minute. Not measured |

### Gaps

- **A kill -9 between running the command and recording it.** The window is microseconds, and no test hits it. By design the command runs again at the next start, so commands must be safe to repeat; the README says so. A real monthly pack's own repeat-safety was shown with `/almanac run` twice.
- **A real data pack on 26.3.** The copy used on 26.2 doesn't load on 26.3 (one of its predicates fails to parse, and the server refuses to start with it). That's the pack's problem, not Almanac's, so 26.3 used a made-up stand-in pack.
- **Daylight-saving nights on a real server.** Covered by unit tests with real zone rules only.
