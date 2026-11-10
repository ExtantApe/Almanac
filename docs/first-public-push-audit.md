# First public push audit

2026-11-10, before the repository went public for the 0.1.0 release (the owner's standing yes of 2026-11-07 for Minecraft plugins).

- **History:** 3 commits, all authored and committed with the GitHub noreply address.
- **Files:** every file ever committed was checked. No build output. The Gradle wrapper jar is the standard one.
- **Secrets and addresses:** no tokens, keys, passwords or IP addresses apart from 127.0.0.1 in the test. The only "token" hits are the release workflow's own `GH_TOKEN` reference and an npm package name in the test's lock file.
- **Server details:** no server, world, rank or player names. The test reads a real pack's namespace from a copy outside the repo, and ships a made-up stand-in pack. The example time zone is a neutral one.
- **Private terms:** `scripts/leak-check.sh` passes locally with the private term list, and runs in CI on every change.
- **License:** MIT, EXTANT APE.
