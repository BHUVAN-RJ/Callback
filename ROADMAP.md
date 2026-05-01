# ROADMAP.md — Callback

This is the build plan. It is organized as phases, not hours. Each
phase has an entry condition, a goal, and an exit condition. Move on
only when the exit condition is met. If a phase blocks, fall back per
SPEC.md §11.

The hardware constraint shapes everything:

- **Tonight (pre-hackathon):** laptop only. No S25.
- **Pre-hackathon test device:** any ARCore-supported Android 12+
  device (matches `minSdk = 31` in SPEC §9). The Vivo V15 was
  considered and dropped — its Android 9 floor is below `minSdk`.
  Phases 1–2 (AR plumbing, ML stubs) run against this device; ML-
  on-NPU work waits for the S25.
- **At the venue Thursday evening through 8 pm:** S25 in hand, on-site
  Wi-Fi, mentors available.
- **Overnight Thursday → Friday morning:** S25 left at venue, laptop
  only. No on-device iteration possible.
- **Friday morning until submission:** S25 back, last push, polish,
  demo prep.

Anything that does not require the S25 must be done in phases 0–2 so
the on-device time is spent only on what only the S25 can answer.

Amogh handles all non-coding work in parallel through every phase:
logistics, mentor liaison, demo prep, presentation, video recording,
README copy review, submission paperwork. He does not touch the build.

---

## Phase 0: Environment setup (pre-hackathon, laptop)

Entry: now.

Goal: have a working Android development environment with all
required SDKs, samples cloned and building, models downloaded
locally, and the project repo initialized.

Tasks:
- Install Android Studio (Ladybug or current stable).
- Install Android SDK 35 (compileSdk + targetSdk per SPEC §9), NDK
  r27 (latest 27.x patch), CMake, platform tools.
- Install ARCore SDK.
- Clone the Track 1 sample
  (github.com/carrycooldude/ModelGarden-QNN-LiteRT)
  and the Track 2 sample (litert-samples image_segmentation). Open
  each in Android Studio and confirm Gradle sync succeeds.
- Download model files locally:
  - Gemma-3n-E2B from litert-community on Hugging Face.
  - EmbeddingGemma from litert-community.
  - At least one detector candidate from Qualcomm AI Hub (TFLite,
    INT8, NPU-targeted): YOLOv8-Detection-Quantized,
    MobileNetV3-SSD, EfficientDet-Lite0-Quantized.
- Create `callback` git repo on GitHub. Public. MIT license.
- Commit SPEC.md, AUDIENCE.md, ROADMAP.md.
- Generate the empty Android Studio project skeleton with the
  package structure from SPEC.md §8. Commit.

Exit: Gradle sync succeeds on both sample projects. The empty
Callback project builds and runs an empty Activity on the test
device over USB. All three model files exist in
`app/src/main/assets/` (bundled in the APK from this point on).
Repo is pushed.

---

## Phase 1: ARCore plumbing (laptop + test device)

Entry: phase 0 complete.

Goal: a working AR app on the test device that does not yet use ML,
but demonstrates every AR mechanic Callback needs.

Tasks:
- Open camera via ARCore in `MainActivity.kt`.
- Set up `ArSession`, frame loop on a background thread.
- Render the camera preview full-screen.
- Show a tracking-state indicator overlay.
- On screen tap: `frame.hitTest`, place an anchor, draw a small
  marker in 2D screen space at the projected anchor position.
- Anchor marker must stay locked to its world location as the
  phone moves; verify by walking around it.
- Add a thumbnail strip view at the bottom (empty for now, just
  the container).
- Add an "ask" button (no behavior yet).
- Add a stub `Pipeline.kt` with method signatures matching SPEC.md
  but bodies returning placeholder data.
- **Camera passthrough (follow-up):** the GL background must draw the
  ARCore external texture for **every valid frame** (skip only while
  `frame.timestamp == 0` per ARCore samples). Gating draw on
  `TrackingState.TRACKING` alone caused flicker / garbage on devices
  when tracking flapped; fixed on branch before Phase 2 merge.
- **Dev triage (optional):** debuggable builds may write
  `debug/RunLogger.kt` session files; the host runs
  `scripts/capture-logcat.sh` for full native / ARCore logcat (see
  SPEC §8).

Exit: tap-to-anchor works on the test device. Walking around the
anchor keeps the marker pinned. App does not crash when tracking is
lost and recovered. Stable full-screen camera preview (not flashing
noise). Code is committed (Phase 1 follow-up commit may include
passthrough fix + log helpers).

---

## Phase 2: ML plumbing without real models (laptop)

Entry: phase 1 complete.

Goal: every Kotlin class in `ml/`, `memory/`, `pipeline/`, and
`voice/` exists and compiles, with stubs returning realistic
mock data. The cascade is wired but the inference calls are no-ops.

Tasks:
- `Detector.kt`: stub `detect(bitmap): List<Detection>` returning
  one fake detection in the center of the image.
- `VLMService.kt`: stub `describe(crop: Bitmap): String` returning
  "stub object description".
- `EmbeddingService.kt`: stub `embed(text: String): FloatArray`
  returning a deterministic 768-d hash-based vector.
- `MemoryStore.kt`: real, in-memory `MutableList<Memory>`.
- `Similarity.kt`: real **dot product** for ranking (`dot()` only;
  matches SPEC §3 for unit-normalized vectors).
- `BoxTracker.kt`: real IoU-based tracker that flags a box as
  "stable" after 2 seconds in the same place.
- `SttController.kt`: real `SpeechRecognizer` wrapper. Test on the
  test device.
- `TtsController.kt`: real `TextToSpeech` wrapper. Test on the test
  device.
- `Pipeline.kt`: ties it together. On stable bbox, calls
  describe → embed → store. On voice query: **Phase 2** uses the
  **most recently stored memory** for the recall arrow + TTS so the
  demo works with deterministic stub embeddings (open-ended speech
  does not match stub vectors). **Phase 4** switches to SPEC-ranked
  recall (`bestMatch` / 0.4 threshold on real embeddings).
- `OverlayView.kt`, `ArrowRenderer.kt`, `ThumbnailStrip.kt`:
  real Canvas drawing.
- Wire the "ask" button to the STT controller; request
  `RECORD_AUDIO`; show brief on-screen transcription (`voice_feedback`
  line in layout).

Exit: with mocks, the full loop works on the test device: hold a
**tracking** view ~2 s so a **thumbnail** appears (stub center
detection + stable bbox), tap **Ask**, speak briefly, **TTS** plays,
and the **arrow** targets the **most recent** remembered anchor
(Phase 2 rule above). Voice in and voice out audible. App merged
Phase 1 passthrough + log-helper commits. Phase 2 feature work
committed when exit is met.

---

## Phase 3: First contact with the S25 (Thursday evening at venue)

Entry: phases 0–2 complete; S25 in hand; on-site Wi-Fi.

Goal: validate Gemma-4-E2B-IT latency on the S25 NPU before any
further development. This is the gating step.

Model locked: `gemma-4-e2b-it.litertlm` (SM8750 build, 2.8 GB,
already in `app/src/main/assets/`).

Tasks:
- Install Android Studio on whatever machine is at the venue, or
  develop from laptop with S25 over USB.
- Sideload Google's AI Edge Gallery app onto the S25.
- Load Gemma-4-E2B-IT in the Gallery app (or sideload our APK and
  trigger the benchmark harness via `adb shell am start -e
  RUN_MODEL_BENCHMARK true`).
- Point the camera at five different physical objects. Time each
  response.
- Note the delegate selected (NPU / GPU / CPU) and the per-call
  latency. Take screenshots of the latency numbers.

Exit: a written latency number for Gemma-4-E2B-IT on the S25, on
each available delegate. If <2.5 s on NPU: lock in NPU. If
slower on NPU but acceptable on GPU: lock in GPU and update
SPEC.md §11 R1. If unacceptable on both: invoke R1; ask mentors.

---

## Phase 4: Real models on device (Thursday evening)

Entry: phase 3 complete and a delegate locked.

Goal: replace each stub in the Callback app with the real model,
running on the S25, one at a time.

Tasks (in this order, do not skip ahead):
- Detector first. Replace `Detector.kt` stub with LiteRT inference
  using the chosen TFLite file and QNN delegate. Verify boxes
  appear on real objects in the camera preview. Tune the score
  threshold on real frames.
- VLM second. Replace `VLMService.kt` stub with LiteRT-LM. Pass
  a fixed test bitmap from assets first; print the output to
  logcat. Then wire the live crop. Verify the description is
  reasonable.
- Embedding third. Replace `EmbeddingService.kt` stub with
  LiteRT-LM. Embed a known test string, print the vector norm and
  first few values. Verify embedding two similar strings yields
  cosine ~0.7+, two unrelated strings yields cosine ~0.2-.

Exit: all three real models load on app startup, run on their
target delegate, and produce sane outputs in logcat. Pipeline
runs end-to-end with real models for at least one full
remember+recall cycle.

---

## Phase 5: End-to-end demo on the S25 (Thursday evening, before 8 pm cutoff)

Entry: phase 4 complete.

Goal: the demo flow works five times in a row without a restart.

Tasks:
- Run the demo script from the SPEC's audience doc: place 4
  objects on a table, walk around them, ask for one by name,
  confirm arrow points correctly.
- Fix top-3 disambiguation if needed.
- Tune VLM prompt if descriptions are too generic for retrieval.
- Tune the stable-bbox threshold if the VLM is firing too often
  or too rarely.
- Capture a screen recording of the working demo.

Exit: five consecutive clean runs of the 90-second demo on the
S25. Screen recording saved to laptop as MP4 (R5 mitigation).
Phone left at venue. Note any open issues for overnight thinking.

---

## Phase 6: Overnight polish (Thursday night, no S25)

Entry: phase 5 complete; S25 not available.

Goal: improve everything that does not require the S25 to verify.

Tasks:
- README: complete, with a 4-step setup, screenshots from the
  recording, architecture diagram, model attribution, license.
- Inline code comments on the model-loading paths and the cascade
  orchestrator.
- Repo hygiene: remove dead code, format with ktlint, ensure
  LICENSE, .gitignore, clean commit history.
- `docs/architecture.md`: one-page overview with the diagram from
  SPEC.md §2.
- Models are bundled in the APK by default (decision locked in
  phase 0). `scripts/download-models.sh` populates
  `app/src/main/assets/` from stable URLs for developers and CI;
  the prod runtime never downloads. Verify the script is idempotent
  and works on a fresh clone.
- First-launch download is the **fallback only** if the bundled APK
  exceeds Play Store / sideload limits. If invoked, add `INTERNET`
  to the manifest, implement the progress UI, and document in the
  README; otherwise leave both untouched.
- Pre-render the demo poster / one-slide summary if Amogh wants
  one for the presentation.
- Sleep some.

Exit: a `git push` that a stranger could clone and build for any
device with the S25 plugged in.

---

## Phase 7: Friday morning, on the S25 again

Entry: 9 am Friday, breakfast.

Goal: final integration, polish the demo, submit.

Tasks:
- Pull the overnight changes onto the venue machine, build to
  the S25, smoke test.
- Run five more clean demos.
- Fix any regressions from overnight work.
- Set the phone to airplane mode for the demo. Verify everything
  still works.
- Final APK build. Sign with debug key. Install on the S25.
- Verify the APK install path: uninstall, reinstall, launch,
  smoke test.
- Submit before 1:30 pm: code repo URL, APK link if requested,
  text description per submission requirements.

Exit: submission accepted. APK on the S25. Demo rehearsed.

---

## Phase 8: Stretch goals (only if Phase 7 finishes early)

Only attempt these if there is genuine spare time before the
1:30 pm deadline. Each is independent.

- 3D arrow rendering in world space using ARCore + Filament
  instead of 2D screen overlay.
- AR direction signs embedded in the scene, similar to Google
  Maps live navigation, where the arrow follows a path drawn on
  the floor.
- NPU activity indicator with real Hexagon utilization numbers
  pulled from the QNN delegate stats.
- Top-3 query results with thumbnails, voice disambiguation
  ("did you mean the blue one or the green one").
- Persistence: serialize the memory store to disk on
  background, reload on app start. Anchors do not persist
  across ARCore sessions, so this is mostly for show.

Do not start any of these before Phase 7 is complete.

---

## Decision points (do not skip)

These are the moments where a wrong call wastes hours. Each has
a clear answer in advance.

| When | Decision | Default |
|------|----------|---------|
| Phase 0 | Which detector | Whichever Qualcomm AI Hub has best NPU benchmarks for 8 Elite. Pick at the venue if needed. |
| Phase 3 | NPU or GPU for VLM | NPU if <2.5 s/call, else GPU. |
| Phase 4 | VLM prompt | The one in SPEC.md §4. Adjust only if descriptions are unusable. |
| Phase 5 | Threshold for "stable bbox" | Start at 2 seconds, drop to 1 if demo feels slow. |
| Phase 6 | Bundle models in APK or download | Bundle by default (locked in phase 0). First-launch download is the fallback only if the bundled APK exceeds Play / sideload size limits. |
| Phase 7 | Live demo or recorded MP4 | Live by default; MP4 only if the phone misbehaves on stage. |

---

## Rules of engagement for Claude Code

When using Claude Code with this repo:

- The SPEC is authoritative for architecture and file layout. Do
  not reorganize without explicit instruction.
- Implement one phase at a time. Do not jump ahead.
- After each non-trivial change, run `./gradlew assembleDebug` and
  fix compile errors before moving on.
- Commit at every phase exit with the phase name in the commit
  message.
- Prefer simplest implementation. The 2D overlay beats a fancy 3D
  one. The in-memory store beats SQLite.
- Do not invent features that are not in the SPEC. If something
  feels missing, ask first.
- Do not change model choices without confirming on device.
