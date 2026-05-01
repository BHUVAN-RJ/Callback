#!/usr/bin/env bash
# Download Callback model assets into app/src/main/assets/.
# Models are gated or large — they are gitignored per AGENTS.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/app/src/main/assets"
mkdir -p "${DEST}"

echo "Callback — model download helper"
echo "Destination: ${DEST}"
echo ""
echo "Prerequisites:"
echo "  1. Hugging Face account; accept Gemma / EmbeddingGemma license gates in the browser."
echo "  2. huggingface-cli:  pip install -U huggingface_hub  &&  huggingface-cli login"
echo "  3. Qualcomm AI Hub account for detector.tflite (YOLOv8 / MobileNetV3-SSD / EfficientDet-Lite0 — pick per SPEC §4)."
echo ""
echo "Repo IDs (confirm filenames on each model card — LiteRT-LM extensions may vary):"
echo "  - google/gemma-3n-E2B-it-litert-lm"
echo "  - litert-community/embeddinggemma-300m"
echo ""
echo "Example (adjust filenames to match the files you need):"
echo "  huggingface-cli download google/gemma-3n-E2B-it-litert-lm --local-dir \"${DEST}/hf-gemma\""
echo "  huggingface-cli download litert-community/embeddinggemma-300m --local-dir \"${DEST}/hf-embedding\""
echo ""
echo "Then copy or rename artifacts to:"
echo "  ${DEST}/detector.tflite"
echo "  ${DEST}/gemma-3n-e2b.task   # or the exact LiteRT-LM bundle name from the repo"
echo "  ${DEST}/embedding-gemma.task"
echo ""
echo "See ${DEST}/README.md and SPEC.md §4."
