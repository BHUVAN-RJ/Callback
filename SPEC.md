# SPEC.md — Callback

This is the technical specification. It is written for Claude Code as
the primary reader. It tells Claude Code what to build and how the
parts fit together. It does not narrate intent; it describes the
system.

Project name: **Callback** (placeholder, may change before submission).

Alternatives considered: Recall, Trace, Cairn, Breadcrumb, Anchor,
Lookback, Loop, Where, Stash, Marker. Pick later, do not block on
naming.

---

## 1. System overview

Callback is an Android application that runs entirely on a Samsung
Galaxy S25 Ultra (Snapdragon 8 Elite). It uses ARCore for spatial
tracking, three on-device ML models for perception and retrieval,
and Android voice APIs for input and output. Nothing leaves the
device.

The app does three things:

1. **Watch.** The camera streams frames. ARCore tracks the camera's
   6DoF pose continuously. A small object detector runs on every
   frame and produces bounding boxes with class labels.

2. **Remember.** When an object is detected and stable (stable-bbox
   gate; no voice "remember this" trigger in v1), the cropped bounding
   box is sent to a vision-language model that produces a one-phrase
   description. The description is embedded by a text embedding model.
   The 3D anchor at the bounding box center is created via ARCore
   hit-test. The triple `(anchor, description, embedding)` is appended
   to an in-memory store.

3. **Recall.** When the user asks via voice, the query is transcribed
   by Android's speech recognizer, embedded by the same embedding model,
   and matched against the store (see §3 for the `dot` score on
   unit-normalized vectors). The chosen match's anchor is queried for
   its current pose, projected into screen space, and rendered as a 2D
   arrow overlay. Text-to-speech reads a short answer. **Phase 2
   (stubs):** recall targets the **most recent** `Memory` so the demo
   works with deterministic stub embeddings; **Phase 4** implements
   full §3 ranking and the 0.4 threshold.

---

## 2. Cascade architecture

The pipeline is intentionally tiered so the NPU is busy across
multiple model types but no single model runs more often than it
needs to.

```
                 Camera frames @ 30 Hz
                          |
                          v
                  +-----------------+
                  |     ARCore      |  CPU + GPU
                  | SLAM, pose, hit |  (parallel to NPU work)
                  | test, anchors   |
                  +-----------------+
                          |
                          v
              +---------------------------+
              | Stage 1: Object detector  |  NPU
              | LiteRT, ~10 Hz            |
              | Output: bbox + class      |
              +---------------------------+
                          |
            (gated: stable bbox > 2s)
                          |
                          v
              +---------------------------+
              | Stage 2: Gemma-3n-E2B VLM |  NPU (or GPU fallback)
              | LiteRT-LM                 |
              | Input: cropped bbox image |
              | Output: short description |
              +---------------------------+
                          |
                          v
              +---------------------------+
              | Stage 3: EmbeddingGemma   |  NPU
              | LiteRT-LM                 |
              | Output: 768-d vector      |
              +---------------------------+
                          |
                          v
                  +---------------+
                  | MemoryStore   |  in-memory
                  | List<Memory>  |
                  +---------------+

On query:
  voice -> SpeechRecognizer -> EmbeddingGemma -> dot search (§3)
       -> anchor -> screen-space arrow + TTS
       (Phase 2 stubs: most-recent memory; Phase 4: ranked top-1)
```

ARCore runs continuously on CPU/GPU at 30 Hz. The detector runs at
10 Hz on the NPU: every third ARCore frame is dispatched to the
detector and the other two are dropped inside the frame callback (no
queue, no backpressure to manage). The VLM runs only when gated. The
embedding model runs on writes (one per remembered object) and on
reads (one per query).

The VLM gate is `stable bbox`. There is no voice "remember this"
trigger — writes are auto-only.

---

## 3. Data model

```kotlin
data class Memory(
    val id: String,                  // UUID
    val anchor: Anchor,              // ARCore anchor handle
    val description: String,         // VLM output, e.g. "blue Yeti water bottle"
    val embedding: FloatArray,       // 768-d, EmbeddingGemma output, unit-norm
    val classLabel: String,          // detector output, used for write-time dedup
    val thumbnail: Bitmap,           // cropped bbox, for UI display
    val createdAtMs: Long,
    var lastSeenBbox: RectF,         // updated by the dedup path on each repeat
    var lastSeenAtMs: Long           // wall clock of the most recent matching detection
)
```

The store is a `MutableList<Memory>` held in a singleton object. No
persistence across app restarts. No deletion logic. No pagination.

For ranking on query (vectors are pre-normalized at embed time per
§4, so cosine reduces to a dot product):

```
score(memory, queryEmbedding) = dot(memory.embedding, queryEmbedding)
```

Top-1 wins. If top-1 score < threshold (0.4 starting value, tune
empirically), respond "I don't remember anything like that." (The
threshold path applies once real embeddings are wired in **Phase 4**;
see ROADMAP Phase 2 for stub-era recall behavior.)

`Similarity.kt` exposes `dot(FloatArray, FloatArray): Float` only;
there is no separate cosine path. If a future change adds non-
normalized vectors, switch to cosine in one place.

### Write-time deduplication

The detector will fire on the same physical object many times within
a session. To keep `MemoryStore` from filling with near-duplicates:

```
On a candidate write (newDetection, newBbox):
  for each existing memory M with M.classLabel == newDetection.classLabel:
    if IoU(newBbox, M.lastSeenBbox) > 0.5:
      skip the write; set M.lastSeenBbox = newBbox, M.lastSeenAtMs = now
      return
  proceed with VLM → embed → append (lastSeenBbox = newBbox initially)
```

`Memory` therefore carries a mutable `lastSeenBbox: RectF` field
alongside the immutable identity fields. Cross-class collisions are
ignored (a "cup" never dedupes against a "bottle").

---

## 4. Models

All three models are loaded at app startup. Loading happens on a
background thread with a splash screen until ready.

### Stage 1: Object detector
- **Model:** YOLOv8n (Ultralytics), compiled by Qualcomm AI Hub for
  Snapdragon 8 Elite (SM8750), float precision.
- **Asset:** `app/src/main/assets/detector_yolov8.tflite` (12 MB).
- **Labels:** `app/src/main/assets/yolov8_labels.txt` (COCO-80).
- **AI Hub job:** j5wm6ok4g — 258/258 ops on NPU, 1.9 ms on S25.
- **Runtime:** LiteRT (`com.google.ai.edge.litert:litert`) with the
  QNN delegate for Hexagon NPU.
- **Input:** 640×640 RGB.
- **Output:** N detections of `(bbox, class_id, score)`.
- **Filter:** keep detections with score > 0.5 and class in a
  curated whitelist. The whitelist is a top-level `val` in
  `app/src/main/java/com/bhuvan/callback/ml/Detector.kt`:
  ```kotlin
  // Edit this set to change which COCO classes are tracked.
  // Keep small (3–10) to reduce vector-store noise.
  val TRACKED_CLASSES: Set<String> = setOf(
      "cup",        // mugs and cups
      "bottle",
      "cell phone"
  )
  ```

### Stage 2: Gemma-4-E2B-IT (vision-language)
- **Model:** Gemma-4-E2B-IT, Qualcomm SM8750 build.
- **Source:** `litert-community/gemma-4-E2B-it-litert-lm` on HF.
- **Asset:** `app/src/main/assets/gemma-4-e2b-it.litertlm` (2.8 GB).
- **Runtime:** LiteRT-LM Kotlin API.
- **Acceleration:** NPU (SM8750-specific build); GPU is fallback per R1.
- **Input:** cropped bbox image. Exact resolution confirmed in phase 3/4
  by reading the model card; do not hardcode before then. Plus the
  prompt below.
- **Prompt template:**
  ```
  Describe this object in one short phrase suitable for later
  retrieval. Include color and one or two distinguishing features.
  Do not add commentary. Examples:
    "blue ceramic coffee mug, chipped rim"
    "black spiral notebook with red sticker"
    "silver MacBook, lid open"
  Now describe the object in the image.
  ```
- **Output:** a single line of text. Trim, lowercase, store.
- **Calls:** ~1–3 per minute average during the demo. Each call is
  independent; no chat history, no multi-turn context.

### Stage 3: EmbeddingGemma-300M
- **Model:** EmbeddingGemma-300M, seq1024, Qualcomm SM8750 build.
- **Source:** `litert-community/EmbeddingGemma-300M` on HF.
- **Asset:** `app/src/main/assets/embedding-gemma.tflite` (186 MB).
- **Runtime:** LiteRT (`com.google.ai.edge.litert:litert`) with QNN
  delegate. Note: this model ships as `.tflite`, not `.litertlm`.
- **Acceleration:** NPU.
- **Input:** a single string (description on write, query on read).
- **Output:** a 768-d float vector. Normalize to unit length.

---

## 5. ARCore integration

- Min ARCore SDK: latest stable.
- **GL camera preview:** `ArGlRenderer` runs `Session.update()` on the
  GL thread, sets `Session.setCameraTextureName` each frame, clears
  color/depth, and draws a full-screen quad via `ArBackgroundRenderer`
  using `Frame.transformCoordinates2d` for UVs when display geometry
  changes (and once UVs are first needed). Follow ARCore samples: draw
  the external-OES camera texture for **every valid frame** and skip
  draw only while `frame.timestamp == 0` (before the first camera image
  is ready). **Do not** draw the background **only** when
  `TrackingState.TRACKING`; that causes visible flicker when tracking
  flaps.
- Camera resolution: ARCore default; do not request high-res, it
  hurts tracking.
- Frame access: pull `Frame.acquireCameraImage()` and copy pixels
  for the detector input. Do not block the ARCore frame callback.
  Do detection on a background thread.
- Throttle: the detector runs at 10 Hz against ARCore's 30 Hz. Drop
  two of every three frames inside the frame callback (`frameIndex
  % 3 == 0` dispatches; the other two are released immediately).
  No queue, no `Channel`, no backpressure to manage.
- Anchor placement on `remember`:
  1. Take bbox center in image coordinates.
  2. `frame.hitTest(centerX, centerY)` — preferred.
  3. If hit-test returns nothing, fall back to depth-based placement
     using the ARCore Depth API: read depth at center pixel, project
     to camera space, transform to world space, create anchor at
     that pose.
- Anchor query on `recall`: `anchor.pose` returns current world
  pose (ARCore self-corrects across SLAM updates). Project to screen
  using current camera matrices.

---

## 6. Voice in/out

- **STT:** `android.speech.SpeechRecognizer` with
  `EXTRA_PREFER_OFFLINE = true`. On the S25 this should resolve to
  on-device recognition. If it does not, fall back to a tap-to-type
  text field. Document which path is active in the README.
- **TTS:** `android.speech.tts.TextToSpeech` with the default
  engine. Default voice. Short responses only.

The voice loop is:
- A single button on screen says "ask".
- Tap to start STT, release on result.
- The recognized text is shown briefly on screen and embedded.
- The response is spoken via TTS and shown on screen.

Phase 2 layout includes a dedicated `voice_feedback` line for the
heard phrase and error hints (strings in `strings.xml`).

A wake word is out of scope.

---

## 7. UI

Minimal. The whole UI is one Activity with a camera preview and a
small overlay layer.

Overlay elements:
- Tracking-state indicator in a corner ("tracking" / "limited" /
  "not tracking").
- A list of memorized object thumbnails along the bottom edge,
  showing what the app currently remembers.
- A single "ask" button bottom-center.
- An NPU-activity indicator (optional, time permitting): a small
  bar that pulses when any of the three models is running. Visual
  proof of NPU work for the judges.
- The 2D arrow overlay during recall: an arrow drawn on a Canvas
  view, pointing from the screen center toward the projected
  anchor position. If the anchor is behind the camera, draw an
  edge-of-screen indicator.

No menus, no settings, no onboarding, no permissions screen beyond
the system camera+mic prompts on first launch.

---

## 8. File layout

```
callback/
├── README.md
├── LICENSE                          # MIT
├── SPEC.md                          # this file
├── AUDIENCE.md
├── ROADMAP.md
├── docs/
│   └── architecture.md              # diagram + 1-page overview
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── detector.tflite
│       │   ├── gemma-3n-e2b.task    # or whatever LiteRT-LM expects
│       │   └── embedding-gemma.task
│       ├── java/com/bhuvan/callback/
│       │   ├── MainActivity.kt
│       │   ├── ar/
│       │   │   ├── ArSessionWrapper.kt   # wraps com.google.ar.core.Session
│       │   │   ├── ArGlRenderer.kt       # GLSurfaceView.Renderer + frame loop
│       │   │   ├── ArBackgroundRenderer.kt  # camera external-OES quad
│       │   │   ├── WorldToScreen.kt      # anchor → screen pixels
│       │   │   ├── AnchorManager.kt
│       │   │   └── HitTester.kt
│       │   ├── debug/
│       │   │   └── RunLogger.kt          # optional file log (debuggable APK)
│       │   ├── ml/
│       │   │   ├── Detector.kt
│       │   │   ├── VLMService.kt
│       │   │   ├── EmbeddingService.kt
│       │   │   └── ModelLoader.kt
│       │   ├── memory/
│       │   │   ├── Memory.kt
│       │   │   ├── MemoryStore.kt
│       │   │   └── Similarity.kt
│       │   ├── pipeline/
│       │   │   ├── Pipeline.kt           # orchestrates the cascade
│       │   │   └── BoxTracker.kt         # stable-bbox detection
│       │   ├── voice/
│       │   │   ├── SttController.kt
│       │   │   └── TtsController.kt
│       │   └── ui/
│       │           ├── TouchRoutingFrameLayout.kt  # routes taps to GL vs chrome
│       │           ├── OverlayView.kt
│       │           ├── ArrowDrawState.kt
│       │           ├── ArrowRenderer.kt
│       │           └── ThumbnailStrip.kt
└── scripts/
    ├── download-models.sh           # if APK ships without models
    └── capture-logcat.sh            # host: adb logcat capture (ARCore / native triage)
```

---

## 9. Build configuration

- **Language:** Kotlin (Java 17 toolchain)
- **Min SDK:** 31 (Android 12). The deliverable runs only on the
  S25 Ultra; minSdk just needs to be low enough for any test
  device. 31 is a safe floor.
- **Target SDK:** 35 (Android 15). The S25 Ultra ships with
  Android 15 / One UI 7. Match the device's actual OS so we
  do not get backwards-compat shims on stage.
- **Compile SDK:** 35.
- **NDK:** Android NDK r27 (latest 27.x patch). Pin the exact version
  in `gradle/libs.versions.toml` during phase 0; do not float.
- **ABI filters:** `arm64-v8a` only. Set `abiFilters = ["arm64-v8a"]`
  in `app/build.gradle.kts`'s `defaultConfig.ndk` block from phase 0
  onward. The S25 Ultra and the early-phase test devices are all
  arm64; shipping more ABIs only inflates the APK.
- **Permissions:** `CAMERA`, `RECORD_AUDIO`. Models are bundled in
  the APK (see ROADMAP phase 6); `INTERNET` is **not** declared. If
  phase 6 falls back to first-launch download, add `INTERNET` then
  and document the change.
- **Dependencies (stable versions to be confirmed at build time):**
  ```
  com.google.ar:core
  com.google.ai.edge.litert:litert            # classical
  com.google.ai.edge.litert:litert-gpu        # GPU delegate
  com.google.ai.edge.litert.qnn:litert-qnn    # Qualcomm NPU delegate
  com.google.ai.edge.litertlm:litertlm-android# LLM/VLM runtime
  androidx.camera:camera-camera2
  androidx.camera:camera-lifecycle
  ```
  Resolve exact coordinates and versions during phase 1 by reading
  the two provided sample apps.

---

## 10. Performance budget

| Stage           | Target latency | Frequency           |
|-----------------|---------------|----------------------|
| ARCore frame    | <33 ms        | 30 Hz, parallel      |
| Detector        | <30 ms        | 10 Hz                |
| VLM (Gemma-3n)  | <2.5 s        | 1–3 calls / minute   |
| Embedding write | <100 ms       | 1 per remember       |
| Embedding read  | <100 ms       | 1 per query          |
| Cosine search   | <5 ms         | 1 per query, <100 entries |
| Total query→arrow | <3 s        | end-to-end           |

If the VLM cannot hit 2.5 s on NPU, switch its delegate to GPU.
Do not switch the detector or embedding off NPU; they must stay
on NPU for the criterion to be defensible.

---

## 11. Risk register

| ID | Risk | Mitigation | Decision point |
|----|------|------------|----------------|
| R1 | Gemma-3n-E2B too slow on NPU | Run on GPU instead | End of phase 3 on device |
| R2 | ARCore + LiteRT camera contention | ARCore owns session, copy frames on background thread | Phase 2 first run |
| R3 | Hit-test fails on featureless surface | Depth-API fallback for anchor placement | Phase 4, only if observed |
| R4 | APK > 2 GB due to bundled models | Download on first launch with progress UI | Phase 6 |
| R5 | Live demo crashes on stage | Pre-recorded MP4 backup on a laptop | Phase 7 |
| R6 | Cosine top-1 picks wrong object | Show top-3 in UI, let user voice-disambiguate | Phase 5 |

---

## 12. Out of scope for v1

- Persistence across app restarts.
- Background operation; the camera must be open and the app
  foregrounded.
- Multi-user, multi-device, sync.
- Object re-identification across sessions.
- Cross-room tracking; single ARCore session per launch.
- Wake word.
- 3D AR arrows in world space (2D screen overlay only). May upgrade
  in phase 8 if time allows.
- Map view of remembered locations.
- Settings, onboarding, accounts.
- Fine-tuned models. Off-the-shelf only.

---

## 13. Testing approach

No formal test suite within the build window. Manual smoke tests
at each phase boundary as listed in ROADMAP.md. The README will
include a "test the app" section with a 5-step smoke test a judge
can run in 30 seconds.
