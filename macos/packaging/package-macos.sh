#!/bin/zsh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
swift build -c release --product HONORShare
BIN="$(swift build -c release --show-bin-path)/HONORShare"
APP="$ROOT/HONORShare.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/HONORShare"
cp "$ROOT/packaging/Info.plist" "$APP/Contents/Info.plist"
codesign --force --sign - --entitlements "$ROOT/packaging/HONORShare.entitlements" "$APP" || true
echo "Built $APP"
