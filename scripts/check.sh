#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "$0")/.."
python3 scripts/verify-assets.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease --console=plain "$@"
