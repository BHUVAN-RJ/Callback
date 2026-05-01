# AGENTS.md — Callback

Both Cursor and Claude Code read this file first. It points to the
specs, defines the rules common to both agents, and divides
responsibility between them.

## Read order (every session, before any code change)

1. `AGENTS.md` (this file) — common rules, role split.
2. `SPEC.md` — architecture, file layout, models, dependencies.
3. `ROADMAP.md` — current phase, exit criteria.
4. `agent_rules/cursor.md` (if you are Cursor) or
   `agent_rules/claude.md` (if you are Claude Code).
5. `AUDIENCE.md` — only when writing README, demo copy, or commit
   messages that mention "users."

## Role split

- **Cursor writes feature code.** Kotlin classes under
  `app/src/main/java/com/bhuvan/callback/`, XML layouts,
  `strings.xml`, `dimens.xml`, `colors.xml`. The presenter (Rj)
  drives Cursor interactively in IDE.
- **Claude Code handles integration, validation, and
  infrastructure.** Build files, manifests, scripts, dependency
  resolution, lint/format runs, phase-exit checks. Claude Code
  can also write feature code when explicitly asked, but does
  not do so unprompted.
- When the two agents disagree, the human decides. Neither agent
  may unilaterally rewrite the other's recent work.

## Rules common to both agents

### Stack (do not deviate)

- Kotlin only. No Java.
- Android View system + XML layouts. **No Jetpack Compose.** No
  ComposeView, no `@Composable`, no `androidx.compose.*` imports
  anywhere.
- Kotlin DSL build files (`build.gradle.kts`, `settings.gradle.kts`).
  No Groovy.
- ARCore for AR. LiteRT and LiteRT-LM for ML. No alternatives,
  no MediaPipe, no ML Kit, no third-party AR libraries.
- ktlint as the only linter/formatter. No detekt.

### LiteRT / LiteRT-LM specific

- **Never invent a class signature for a LiteRT or LiteRT-LM API.**
  Both libraries are new (early 2026) and both agents have stale
  training-data priors. Before writing a model-loading line, read
  the actual sample app code under `samples/` (cloned in phase 0)
  or `docs/litert-reference/` if added. If the API surface is
  unclear, leave a `TODO(human-verify)` and stop.
- The QNN delegate for Hexagon NPU may require additional setup
  (env vars, native libs, ABI filters). Do not silently fall back
  to CPU/GPU if NPU init fails. Surface the error.
- Model files live in `app/src/main/assets/`, never `res/raw/`.
- Model loading happens once at startup on a background coroutine,
  not lazily on first inference. Block the UI with a splash until
  ready.

### Architecture discipline

- Follow `SPEC.md` exactly. Do not reorganize packages.
- Do not add classes, files, or features not in `SPEC.md`.
- Do not invent new dependencies. The dependency list in
  `SPEC.md` §9 is authoritative; if a coordinate is wrong,
  resolve from the cloned sample apps and update `SPEC.md` in
  the same commit.
- If something feels missing, add a `TODO(human-verify)` comment
  and stop. Do not extrapolate. (Single tag across the repo —
  greppable.)

### Phase discipline

- Implement only the current phase from `ROADMAP.md`.
- A phase is "current" when the previous phase's exit condition
  is met and committed.
- Stubs (phases 1–2) must return realistic mock data, not null,
  not empty lists, not exceptions. The mock should be plausible
  enough that the UI looks alive when running against it.
- Do not wire real models until phase 4. Phase 4 starts only
  after phase 3 produces a written latency number on the S25.

### Code quality

- No hardcoded user-visible strings outside `res/values/strings.xml`.
- No hardcoded dimensions outside `res/values/dimens.xml`.
- No hardcoded colors outside `res/values/colors.xml`.
- Every public class and every non-trivial public function gets a
  one-line KDoc comment.
- No suppressed lint warnings without a comment explaining why on
  the same line as the suppression.
- No `GlobalScope`. Use `lifecycleScope`, `viewModelScope`, or a
  scope owned by the class.
- No `runBlocking` in production code paths. (Allowed in `main()`
  of standalone scripts only, of which we have none.)
- Coroutine dispatchers must be explicit:
  `Dispatchers.IO` for file/network, `Dispatchers.Default` for
  CPU-bound work, `Dispatchers.Main` for UI. No bare `launch {}`
  on an unspecified dispatcher.
- All ML inference happens off the main thread. No exceptions.

### Git hygiene

- **Agents may commit only after a phase exit condition is met
  and the human has verified.** The human says "phase N done,
  commit it" before any `git commit` runs.
- Commit messages follow this format:
  ```
  phase N: <short description>

  <optional body, wrapped at 72 cols>
  ```
  Example: `phase 2: ml stubs and pipeline wiring`
- One commit per phase by default. Sub-commits within a phase
  are allowed if they are clean and bisectable.
- Never `git push --force` or rewrite history.
- Never commit model files larger than 100 MB. Use the download
  script (`scripts/download-models.sh`) and `.gitignore` the
  large assets.
- `.gitignore` must exclude: `app/build/`, `.gradle/`, `local.properties`,
  `*.iml`, `.idea/` (except inspection profiles if used),
  `app/src/main/assets/*.task`, `app/src/main/assets/*.tflite`
  if any model exceeds GitHub's 100 MB limit.

### Things both agents must never do

- Add Compose dependencies or imports.
- Add Hilt, Dagger, or any DI framework. Manual constructor
  injection only.
- Add Retrofit, OkHttp, Ktor, or any networking library beyond
  what Android already provides.
- Add Room, SQLite wrappers, or any persistence library. The
  store is in-memory (SPEC §3).
- Add analytics, crash reporters, telemetry SDKs.
- Modify `SPEC.md`, `ROADMAP.md`, `AUDIENCE.md`, or files under
  `agent_rules/` without an explicit human instruction. If a
  change is needed, propose it in chat and wait.
- `git push --force`, rebase shared history, or rewrite commits
  the human has already pulled.

## Hackathon-specific rules (judging criteria)

The submission is graded on five equally-weighted criteria
(`AUDIENCE.md` §1). These rules exist to protect the score:

1. **LiteRT / LiteRT-LM is mandatory.** Every model in the
   pipeline must be invoked through one of these APIs. Do not
   add a model that runs through a different runtime (no raw
   TFLite Java API, no ONNX Runtime, no PyTorch Mobile).
2. **NPU utilization must be visible.** Detector and embedding
   stay on NPU (QNN delegate) by default. The VLM may run on
   GPU as a fallback (R1 in SPEC §11), but never silently;
   the active delegate is logged at startup.
3. **The APK must be installable in one step.** No setup wizard,
   no account creation, no permission walls beyond the system
   camera+mic prompts. The README has a 4-step build section.
4. **Documentation is part of the grade.** README, inline KDoc
   on the model-loading paths, and `docs/architecture.md` are
   not optional.
5. **The demo runs in airplane mode.** No code path may require
   network at runtime after model files are present.

## When in doubt

Ask the human. The human is in the room and answers in seconds.
Wrong assumptions cost an hour. Asking costs ten seconds.
