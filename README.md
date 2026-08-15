# HONOR Share

Transfer files directly between an HONOR Android phone and a Mac. No accounts, no cloud, no internet, no server.

> Files are transferred directly between your devices. No cloud upload is required.

## How it works

Both apps advertise a Bonjour/mDNS service (`_honor-share._tcp`) on the local network. The default flow is **Transfer Package**: prepare files, join with a QR or 6-digit code, compare, transfer only what is needed, verify SHA-256. Nearby device picking remains as a fallback. Google Nearby Connections is not used.


Google Nearby Connections is not used. It cannot talk to macOS. Discovery is Bonjour on both sides.

## Requirements

- Android 8 (API 26) or newer, same Wi-Fi (or phone hotspot) as the Mac
- macOS 13 or newer
- Local network / nearby devices permission when asked

## Run Android

```bash
cd android
./gradlew :app:assembleDebug
```

Install `android/app/build/outputs/apk/debug/app-debug.apk` on the phone, or open the `android/` folder in Android Studio and Run.

## Run macOS

Full Xcode is not required. From this repo:

```bash
chmod +x macos/packaging/package-macos.sh
./macos/packaging/package-macos.sh
open macos/HONORShare.app
```

Or `cd macos && swift build && swift run HONORShare`.

## How devices connect

1. Open HONOR Share on both devices (same Wi-Fi). Internet is not required.
2. **Mac → phone:** Drop files on the Mac (or Finder → Services → Send with HONOR Share). On the phone tap **Receive** and scan the QR.
3. **Phone → Mac:** On the phone tap **Send files**, choose files, **Ready to send**. On the Mac enter the 6-digit code and Connect.
4. Confirm the matching pairing code the first time two devices meet. Accept the package preview. Files land in `Downloads/HONOR Share`.

**Nearby devices** is the older direct-discovery path if QR/code cannot connect.

## Permissions

- Android: Nearby Wi-Fi devices (or location on older Android), notifications only while a transfer runs, no all-files access (Storage Access Framework + Downloads).
- macOS: Local Network, Bonjour `_honor-share._tcp`, Downloads.

## Testing

```bash
cd android && ./gradlew :protocol:test :app:assembleDebug
cd macos && swift run HonorShareCheck
./macos/packaging/package-macos.sh
```

See [docs/TESTING.md](docs/TESTING.md) for device scenarios. Physical HONOR phone ↔ Mac discovery still needs a real LAN.

## Layout

- `android/` — Kotlin, Jetpack Compose
- `macos/` — Swift, SwiftUI, Network.framework
- `protocol/` and `docs/PROTOCOL.md` — shared wire format
- `docs/` — architecture, protocol, security, baseline, test matrix, troubleshooting
