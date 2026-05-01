# Assets — Callback

Phase 0 expects these files here **on your machine** after running `./scripts/download-models.sh` (or manual download):

| File | Purpose |
|------|---------|
| `detector.tflite` | Qualcomm AI Hub detector (INT8, NPU-targeted). |
| `gemma-3n-e2b.task` | Gemma 3n E2B LiteRT-LM artifact (exact extension may vary — align with LiteRT-LM samples). |
| `embedding-gemma.task` | EmbeddingGemma LiteRT-LM artifact (see Hugging Face `litert-community/embeddinggemma-300m`). |

Large binaries are listed in `.gitignore`; use the download script or Hugging Face CLI after accepting Gemma license gates.
