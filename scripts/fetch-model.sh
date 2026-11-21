#!/usr/bin/env bash
# Downloads the Vosk small English model (Apache-2.0) into the APK assets and verifies it.
set -euo pipefail
NAME=vosk-model-small-en-us-0.15
DEST=app/src/main/assets/model
CACHE=${MODEL_CACHE:-.model-cache}
mkdir -p "$CACHE"
if [ ! -f "$CACHE/$NAME.zip" ]; then
  curl -fsSL --retry 3 -o "$CACHE/$NAME.zip" "https://alphacephei.com/vosk/models/$NAME.zip"
fi
rm -rf "$DEST" "$CACHE/unz" && mkdir -p "$CACHE/unz"
unzip -q "$CACHE/$NAME.zip" -d "$CACHE/unz"
mv "$CACHE/unz/$NAME" "$DEST"
( cd "$DEST" && sha256sum -c ../../../../../scripts/model.sha256 )
echo "model ready: $(du -sh "$DEST" | cut -f1)"
