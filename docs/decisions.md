# Decisions

Dated decisions for this repo, newest last.

- 2026-11-09: created from the ops starter kit, area `minecraft-plugins`.
- 2026-11-08 (owner, in the server's notes): monthly, on the 1st, in a configurable time zone. Almanac only runs a configured console command; the parkour data pack does the work and makes repeats harmless. Generic: every schedule, command and quiet rule lives in config, with no world, rank or player names in code.
- 2026-11-10 (owner, relayed word for word by Helm):
  - Quiet moment: "Wait for an empty course, max 6 h (Recommended)". A schedule waits while anyone is inside its configured box, checks again every few minutes, and runs anyway after 6 hours.
  - Time of day: "After 04:00 server time (Recommended)". Nothing runs before 04:00 on the 1st in the configured zone. After downtime it catches up at the next start.
  - Commands: "status, run, preview (Recommended)", for admins only (`almanac.admin`, op by default).
  - Notify: "Console log only (Recommended)". Almanac tells nobody in game; the data pack announces the new course itself.
  - All four are settings in `config.yml`, with these as the defaults.

## 2026-11-14

- **Name (owner, D194):** Almanac is renamed Sundial, because a Minecraft mod with about 22.7 million downloads already uses the name Almanac. The owner chose "Rename to Sundial (Recommended)". Released as 0.2.0. The owner asked that no old names be kept, so there's no alias, old permission or folder copy: a server renames its plugin folder and permission when it installs 0.2.0.
