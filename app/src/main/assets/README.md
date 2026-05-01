# Assets — Callback

After running `./scripts/download-models.sh` (or manual Hugging Face CLI), expect:

| File | Purpose |
|------|---------|
| `detector_yolov8.tflite` | YOLOv8n SM8750 (Qualcomm AI Hub job j5wm6ok4g). |
| `embedding-gemma.tflite` | EmbeddingGemma-300M seq1024 SM8750 (litert-community). |
| `sentencepiece.model` | Same HF repo — required to build int32 token ids for the embedding model (~4.6 MB). |
| `yolov8_labels.txt` | COCO-80 labels (committed). |

**Not in assets:** `gemma-4-e2b-it.litertlm` (2.8 GB) — push to `getExternalFilesDir` per `scripts/download-models.sh` and ROADMAP.

Large `.tflite` files are gitignored when oversized; `sentencepiece.model` is small enough to commit if you prefer.
