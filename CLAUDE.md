# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read order before any change

This repo is governed by docs at the repo root and under `agent_rules/`. Read in this order at session start:

1. `AGENTS.md` — common rules + role split between Cursor and Claude Code.
2. `SPEC.md` — architecture, file layout, models, dependencies. Authoritative.
3. `ROADMAP.md` — current phase and exit criteria. Implement only the current phase.
4. `agent_rules/claude.md` — Claude Code-specific checklist and tone.
5. `AUDIENCE.md` — only when writing README, demo copy, or commit messages.

If a request appears to conflict with these, ask the human; never silently override them.

## Project context

Callback is a hackathon Android app (LiteRT on Snapdragon, Apr 30 – May 1 2026) that runs an on-device cascade of three ML models on a Samsung Galaxy S25 Ultra: object detector (LiteRT, NPU) → Gemma-3n-E2B VLM (LiteRT-LM, NPU/GPU) → EmbeddingGemma (LiteRT-LM, NPU), with ARCore for spatial anchors. See `SPEC.md` §2 for the cascade diagram. **Current phase: Phase 1 complete** (ARCore session, GL camera preview, tap-to-anchor, overlay shell). Phase 2 is next: ML stubs + pipeline wiring on the test device.

## Role split (critical)

This is a two-agent project. Claude Code does **not** write feature Kotlin unless explicitly asked.

- **Claude Code owns:** `build.gradle.kts` (project + module), `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `AndroidManifest.xml`, `gradle/` wrapper, `scripts/`, `app/src/main/assets/` (model files), `.gitignore` / `.editorconfig` / `.gitattributes`, `LICENSE`, ktlint config, ABI/native config for the QNN delegate, dependency resolution.
- **Cursor owns:** all Kotlin under `app/src/main/java/com/bhuvan/callback/`, all XML layouts, all `res/values/*.xml`, all `res/drawable/*.xml`. Claude Code may **read** these freely but does not rewrite or refactor them unless the human explicitly asks. If a Kotlin file has a bug, surface it; do not silently fix it.
- **Human-only:** `SPEC.md`, `ROADMAP.md`, `AUDIENCE.md`, `AGENTS.md`, and files under `agent_rules/` unless the human explicitly asks. Propose changes in chat; do not edit.

When Cursor adds an import that needs a new dependency, Claude Code resolves the Gradle coordinate, pins it in `libs.versions.toml`, and verifies with `./gradlew assembleDebug`. Never use dynamic versions (`+`, `latest.release`).

## Common commands

From the repo root:

```sh
./gradlew assembleDebug              # build debug APK; run after any non-trivial change
./gradlew installDebug               # install on connected device (test device phases 1–2, S25 phase 3+)
./gradlew lint                       # Android lint; errors block phase exit, warnings do not
./gradlew ktlintCheck                # ktlint (added in phase 0 / phase 6 polish)
./gradlew ktlintFormat               # auto-format before each phase commit
./gradlew test                       # JVM unit tests (app/src/test/)
./gradlew connectedAndroidTest       # instrumented tests on a connected device
./gradlew :app:dependencies          # resolve a dependency tree when debugging coordinate/version conflicts
adb logcat -s Callback:V             # device logs (model-loading lines, delegate selection, latency)
```

Run a single unit test: `./gradlew :app:testDebugUnitTest --tests com.bhuvan.callback.ExampleUnitTest.<method>`.
Run a single instrumented test: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bhuvan.callback.ExampleInstrumentedTest`.

## Phase-exit verification

When the human says "verify phase N," run the checklist in `agent_rules/claude.md` ("Phase exit checklist") and report PASS / FAIL / N/A per item with concrete evidence. Universal items: `assembleDebug` exits 0; `lint` no errors; `ktlintCheck` exits 0 when configured; no `androidx.compose` imports; no `GlobalScope` references; SPEC §8 files for completed phases exist; `git status` clean. Phase-specific items are listed in `agent_rules/claude.md`.

Quick greps used during phase exit:

```sh
grep -r "androidx.compose" app/src/main/java || echo "clean"
grep -rn "GlobalScope" app/src/main/java || echo "clean"
```

## Hard constraints (never violate)

From `AGENTS.md`:

- Kotlin only, no Java. Kotlin DSL build files only, no Groovy.
- Android View system + XML layouts. **No Jetpack Compose** anywhere — no `androidx.compose.*`, no `ComposeView`, no `@Composable`.
- ARCore for AR. LiteRT (`com.google.ai.edge.litert`) + LiteRT-LM for ML. No MediaPipe, no ML Kit, no raw TFLite Java API, no ONNX, no PyTorch Mobile, no third-party AR libs.
- ktlint only — no detekt.
- No DI framework (Hilt, Dagger, Koin). Manual constructor injection only.
- No networking libraries (Retrofit, OkHttp, Ktor) beyond what Android provides.
- No persistence library (Room, SQLite wrappers). The store is in-memory (`SPEC.md` §3).
- No analytics, crash reporters, or telemetry SDKs.
- LiteRT / LiteRT-LM APIs: **never invent a class signature**; both libs are new (early 2026) and training-data priors are stale. Read the cloned sample apps under `samples/` (added in phase 0) before writing model-loading code, or leave `TODO(human-verify): <question>` and stop.
- The QNN delegate must not silently fall back to CPU/GPU on init failure — surface the error.
- Model files live in `app/src/main/assets/`, not `res/raw/`. Models load once at startup on a background coroutine behind a splash, not lazily.
- All ML inference off the main thread. Coroutine dispatchers must be explicit (`Dispatchers.IO` / `Default` / `Main`); no bare `launch {}`. No `runBlocking` in production paths.
- No hardcoded user-visible strings outside `res/values/strings.xml`, dimensions outside `dimens.xml`, colors outside `colors.xml`.

## Git hygiene

- **Never commit unprompted.** Commits happen at phase exits, after human verification, when the human says "phase N done, commit it."
- One commit per phase by default. Format: `phase N: <short description>` (e.g. `phase 2: ml stubs and pipeline wiring`). The current `HEAD` is `phase 1: ARCore session, GL preview, tap-to-anchor, overlay shell`.
- Never `git push --force`, never rewrite published history, never use `--no-verify`.
- Never commit model files >100 MB; use `scripts/download-models.sh` and `.gitignore` the large assets (`app/src/main/assets/*.task`, `*.tflite` if oversize).

## AR layer architecture (Phase 1 implemented)

`ArGlRenderer` (implements `GLSurfaceView.Renderer`) drives the frame loop. `Session.update()` runs on the GL thread — **not** the main thread. Callbacks back to `MainActivity` go through the `ArGlRenderer.Host` interface (`onGlFrame`, `onGlDisplayGeometryChanged`, `onTapAnchor`).

Tap-to-anchor threading: `queueTap(x, y)` is called from the main thread touch handler, stored in an `AtomicReference<Pair<Float,Float>?>`, and consumed atomically inside `consumeTap()` on the GL thread during `onDrawFrame`.

Camera background draw rule: `ArBackgroundRenderer.draw()` is called for **every frame** where `frame.timestamp != 0L`. It is **not** gated on `TrackingState.TRACKING` — gating on tracking causes visible flicker/noise when tracking flaps (ARCore augmented_image_java pattern). UV coords are recomputed via `frame.transformCoordinates2d` whenever `frame.hasDisplayGeometryChanged()`.

`debug/ModelBenchmarkHarness.kt` is present in the codebase (not in SPEC §8) — a benchmarking helper added during Phase 1. Treat it as debug-only infrastructure.

## Phase 2 build pre-requisites (not yet done)

These Gradle coordinates are still absent from `gradle/libs.versions.toml` and must be resolved from the cloned sample apps under `samples/` before Phase 2 ML stubs can compile:

- `com.google.ai.edge.litert:litert` (classical inference)
- `com.google.ai.edge.litert:litert-gpu` (GPU delegate)
- `com.google.ai.edge.litert.qnn:litert-qnn` (Qualcomm NPU delegate)
- `com.google.ai.edge.litertlm:litertlm-android` (LiteRT-LM for VLM + embedding)
- `androidx.camera:camera-camera2` and `camera-lifecycle` (CameraX, if used alongside ARCore)

ktlint plugin is not yet configured — `ktlintCheck` will fail until the plugin is added to `build.gradle.kts` and `libs.versions.toml`.

## Code review rules

When the human runs `/review` or `/code-review` (the installed code-review plugin), apply these rules in addition to the hard constraints above. Report findings as a flat list, one finding per line, with `file:line` and a one-line reason. Verdict-first per finding (FLAG / OK / N/A); no prose between findings.

- Flag missing null checks on nullable types (Kotlin `?`-typed values dereferenced without `?.`, `?:`, or a smart-cast guard).
- Flag Context leaks — any `static`/companion-object/top-level reference holding an `Activity`, `Service`, `View`, or non-application `Context`. Application context is OK.
- Flag network calls not wrapped in `try`/`catch` or returning a `Result`/sealed error type — including `HttpURLConnection`, `URL.openStream`, and any future networking added (note: networking libs are banned by the hard constraints above; this rule covers the platform APIs that remain).
- Flag missing coroutine scope cancellation — `CoroutineScope` created in a class without a matching `cancel()` in `onDestroy` / `close()` / lifecycle teardown; jobs launched on a scope that outlives the owning component.
- Flag hardcoded user-visible strings that should be in `res/values/strings.xml` (string literals passed to `setText`, `Toast.makeText`, `AlertDialog`, `contentDescription`, etc.). Log tags, exception messages, and BuildConfig keys are OK.
- Flag Activity/Fragment lifecycle mistakes — `findViewById` after `onDestroyView`, `requireContext()`/`requireActivity()` outside the attached window, missing `super.on*()` calls, retained references to destroyed Fragments, registering listeners in `onCreate` without unregistering in `onDestroy`.

## Tone

Per `agent_rules/claude.md`: terse, pass/fail per item, verdict-first then evidence. No code explanations unless asked. One question at a time when something is ambiguous.
