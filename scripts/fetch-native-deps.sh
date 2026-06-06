#!/usr/bin/env bash
#
# fetch-native-deps.sh
#
# Downloads orbn's prebuilt native dependencies (kept out of git to keep the
# repo lean) and unpacks them into the locations the Gradle/CMake build expects:
#
#   app/libs/essentia/        libessentia.a + headers (cross-compiled, arm64-v8a)
#   app/libs/ort/include/     ONNX Runtime C/C++ headers
#   app/libs/eigen/           Eigen 3.4.0 headers
#   app/src/main/jniLibs/     libonnxruntime.so (arm64-v8a)
#   app/src/main/assets/models/   MusiCNN ONNX models
#
# Run this once after cloning, before building:
#   ./scripts/fetch-native-deps.sh
#
set -euo pipefail

# Resolve repo root (this script lives in <root>/scripts/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION="native-deps-v2"
ASSET="orbn-native-deps-v2.tar.gz"
URL="https://github.com/earlyspark/orbn/releases/download/${VERSION}/${ASSET}"

# A sentinel file we expect to exist once deps are unpacked.
SENTINEL="$REPO_ROOT/app/libs/essentia/libessentia.a"

echo "orbn: fetching native dependencies (${VERSION})"

if [[ -f "$SENTINEL" ]]; then
    echo "  Native deps already present (found libessentia.a). Nothing to do."
    echo "  Delete app/libs/ and re-run to force a refresh."
    exit 0
fi

for tool in curl tar; do
    command -v "$tool" >/dev/null 2>&1 || { echo "  ERROR: '$tool' is required but not installed." >&2; exit 1; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "  Downloading: $URL"
curl -fSL --retry 3 -o "$TMP/$ASSET" "$URL"

echo "  Unpacking into $REPO_ROOT"
tar xzf "$TMP/$ASSET" -C "$REPO_ROOT"

if [[ -f "$SENTINEL" ]]; then
    echo "  Done. Native dependencies are in place — you can now build:"
    echo "      JAVA_HOME=<JBR> ./gradlew :app:installDebug"
else
    echo "  ERROR: unpack completed but expected files are missing." >&2
    exit 1
fi
