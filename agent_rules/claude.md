# Claude Code — Callback

## Read order

1. `AGENTS.md` (repo root)
2. `SPEC.md`
3. `ROADMAP.md`
4. This file (`agent_rules/claude.md`)
5. `AUDIENCE.md` when writing submission copy

Also read repo-root `CLAUDE.md` for commands and constraints summary.

## Ownership

Gradle (`build.gradle.kts`, `libs.versions.toml`, wrapper), `AndroidManifest.xml`, `scripts/`, assets layout, `.gitignore`, `LICENSE`. Do **not** write feature Kotlin unless the human explicitly asks.

## Phase exit checklist (universal)

Report PASS / FAIL / N/A with evidence:

- `./gradlew assembleDebug` exits 0.
- `./gradlew lint` — no errors (warnings acceptable unless phase says otherwise).
- No `androidx.compose` under `app/src/main/java`.
- No `GlobalScope` under `app/src/main/java`.
- Phase-specific files from `SPEC.md` §8 exist for the completed phase.

## Tone

Pass/fail first, then evidence; terse; one clarification question when blocked.
