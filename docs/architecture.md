# Callback — architecture overview

Single-page companion to **`SPEC.md` §2**.

## Cascade (summary)

1. **Watch** — ARCore pose + camera frames; LiteRT detector on NPU (~10 Hz).
2. **Remember** — Stable detection or explicit remember → crop → Gemma-3n-E2B (LiteRT-LM) → EmbeddingGemma → anchor + in-memory store.
3. **Recall** — Speech → embedding → similarity → projected anchor → 2D arrow + TTS.

The authoritative diagram and data model live in **`SPEC.md`** (sections 2–3). This file is expanded in ROADMAP Phase 6.
