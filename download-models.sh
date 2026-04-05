#!/bin/zsh

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
MODELS_DIR="$ROOT_DIR/models"
SCRIPT="$ROOT_DIR/vendor/whisper.cpp/models/download-ggml-model.sh"
PROFILE="${1:-exact}"

mkdir -p "$MODELS_DIR"

case "$PROFILE" in
  exact)
    MODELS=(
      medium
      large-v3
    )
    ;;
  mobile)
    MODELS=(
      medium-q5_0
      large-v3-q5_0
    )
    ;;
  *)
    echo "Usage: $0 [exact|mobile]" >&2
    exit 1
    ;;
esac

for model in "${MODELS[@]}"; do
  echo "==> Downloading $model"
  "$SCRIPT" "$model" "$MODELS_DIR"
done

echo
echo "Downloaded models into:"
echo "$MODELS_DIR"
