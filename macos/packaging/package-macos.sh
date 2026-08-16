#!/bin/zsh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
swift build -c release --product HONORShare
BIN="$(swift build -c release --show-bin-path)/HONORShare"
APP="$ROOT/Direct Share.app"
OLD_APP="$ROOT/HONORShare.app"
rm -rf "$APP" "$OLD_APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/HONORShare"
cp "$ROOT/packaging/Info.plist" "$APP/Contents/Info.plist"
cp "$ROOT/packaging/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
codesign --force --sign - --entitlements "$ROOT/packaging/HONORShare.entitlements" "$APP" || true
echo "Built $APP"

if [[ -z "${CI:-}${GITHUB_ACTIONS:-}" ]]; then
  INSTALL_APP="/Applications/Direct Share.app"
  rm -rf "$INSTALL_APP"
  cp -R "$APP" "$INSTALL_APP"
  SERVICE_SRC="$ROOT/packaging/Send with Direct Share.workflow"
  SERVICE_DST="$HOME/Library/Services/Send with Direct Share.workflow"
  mkdir -p "$HOME/Library/Services"
  rm -rf "$SERVICE_DST"
  cp -R "$SERVICE_SRC" "$SERVICE_DST"
  /System/Library/CoreServices/pbs -flush >/dev/null 2>&1 || true
  echo "Installed $INSTALL_APP"
  echo "Installed Finder service $SERVICE_DST"
fi
