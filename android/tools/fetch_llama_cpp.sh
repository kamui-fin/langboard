#!/usr/bin/env bash
# Fetches llama.cpp at the pinned commit into third_party/llama.cpp, which :llama builds from.
# Bump LLAMA_CPP_COMMIT deliberately and re-run the model checks in PROTOTYPE_STATUS.md.
set -euo pipefail

LLAMA_CPP_COMMIT=84e76d8a23162eca70490da131945ebec1f09bf4   # master, 24 Sep 2026
DIR="$(cd "$(dirname "$0")/.." && pwd)/third_party/llama.cpp"

if [ -d "$DIR/.git" ] && [ "$(git -C "$DIR" rev-parse HEAD)" = "$LLAMA_CPP_COMMIT" ]; then
  echo "llama.cpp already at $LLAMA_CPP_COMMIT"
  exit 0
fi
rm -rf "$DIR"
mkdir -p "$DIR"
git -C "$DIR" init -q
git -C "$DIR" remote add origin https://github.com/ggml-org/llama.cpp.git
git -C "$DIR" fetch -q --depth 1 origin "$LLAMA_CPP_COMMIT"
git -C "$DIR" checkout -q FETCH_HEAD
echo "llama.cpp at $(git -C "$DIR" rev-parse HEAD)"
