# Callback

Android hackathon project: on-device spatial memory using ARCore, LiteRT (detector), and LiteRT-LM (Gemma-3n-E2B + EmbeddingGemma). See **`SPEC.md`** for architecture and **`ROADMAP.md`** for phased delivery.

## Prerequisites (Phase 0)

- **Android Studio** (current stable).
- **JDK 17+** for Gradle (Android Studio’s bundled JBR is fine). If `./gradlew` in cloned **`samples/`** fails with “requires JVM 11”, set `JAVA_HOME` to that JDK — macOS defaults often point only at Java 8.
- **Android SDK Platform 35**, **Build-Tools**, **Platform-Tools**, **NDK** (pinned in `app/build.gradle.kts` / `gradle/libs.versions.toml`).
- **Physical device**: Phase 1+ targets ARCore + Android 12+ (`minSdk = 31`). Primary demo device: Samsung Galaxy S25 Ultra.

## Clone reference samples (local only)

Recommended clones for Gradle coordinates and LiteRT APIs (not vendored into this repo):

```bash
mkdir -p samples
git clone --depth 1 https://github.com/carrycooldude/ModelGarden-QNN-LiteRT.git samples/ModelGarden-QNN-LiteRT
git clone --depth 1 https://github.com/google-ai-edge/litert-samples.git samples/litert-samples
```

Open each sample in Android Studio and sync Gradle (`samples/` is gitignored).

## Models

Run `./scripts/download-models.sh` for Hugging Face / Qualcomm pointers, then place files listed in **`app/src/main/assets/README.md`**. Large `*.task` / `*.tflite` assets are gitignored.

## Build (four steps)

1. Open this repo in Android Studio.
2. Install SDK Platform **35** and NDK **27.x** if prompted.
3. (Optional) Populate `app/src/main/assets/` per scripts above.
4. From repo root:

```bash
./gradlew assembleDebug
```

Install on a connected device:

```bash
./gradlew installDebug
```

## Governance

- **`AGENTS.md`** — agent rules (Cursor vs Claude Code).
- **`SPEC.md`** — technical specification (authoritative).
- **`ROADMAP.md`** — phases and exit criteria.
- **`AUDIENCE.md`** — judging criteria and demo framing.

## License

MIT — see **`LICENSE`**.
