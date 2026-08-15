# HONOR Share — pre-change baseline

Recorded: 2026-08-15  
Scope: repository audit + existing tests/builds **before** Primary Transfer Package work.

This file describes the repository **as it existed before Transfer Package implementation**. It is not a product spec for the new workflow.

---

## Build status

| Target | Command | Result |
| --- | --- | --- |
| Android debug APK | `cd android && ./gradlew :app:assembleDebug` | **PASS** (BUILD SUCCESSFUL, ~2m 29s) |
| Android protocol unit tests | `cd android && ./gradlew :protocol:test --rerun-tasks` | **PASS** — 23 tests, 0 failures, 0 ignored, 0.347s |
| Android instrumented UI | `:app:connectedDebugAndroidTest` / `HomeScreenTest` | **NOT RUN** — requires a connected device/emulator in this baseline pass |
| macOS protocol checks | `cd macos && swift run HonorShareCheck` | **PASS** — all checks passed |
| macOS app bundle | `./macos/packaging/package-macos.sh` | **PASS** — `macos/HONORShare.app` |

No Xcode `.xcodeproj` / `.xcworkspace` exists. macOS is SwiftPM + `packaging/package-macos.sh`.

---

## Test status

### Android `:protocol:test` (23, all passing)

JSON golden vectors, frames, SAS `693253`, SHA-256, filename conflicts, connection/transfer state machines, retry policy, ETA hiding, self-signed cert, loopback small-file transfer (`LocalTransferTest`), ShareLink HS1 round-trip, user error mapping.

### macOS `HonorShareCheck`

SAS golden, filename conflict, path sanitize, frame round-trip, SHA-256 empty, retry policy, connection machine, HELLO golden, ETA, ShareLink round-trip.

### Not covered by automated tests today

- Physical LAN discovery (NSD / Bonjour)
- TLS between HONOR phone and Mac
- Camera / QR scan
- Share sheet / Finder Services
- Compose UI (`HomeScreenTest` exists but was not executed here)
- Resume of partial files (`FILE_RESUME` is defined, not exercised)

---

## Warnings (pre-existing)

| Item | Classification |
| --- | --- |
| Gradle 8.14 “deprecated features / incompatible with Gradle 9.0” | PRE-EXISTING |
| AGP 8.7.2 vs `compileSdk 36` (typical SDK mismatch warning when present) | PRE-EXISTING |
| `macos/Tests/main.swift`: `var taken` never mutated | PRE-EXISTING |
| `SecTrustGetCertificateAtIndex` deprecated (macOS 12+) in `NetworkSession.swift` | PRE-EXISTING |
| CameraX `setTargetResolution` deprecation (if shown in app compile) | PRE-EXISTING |

No test failures were recorded in this baseline. Any later failure that is not in this list is a **NEW FAILURE**.

---

## Existing Android modules

| Module | Role |
| --- | --- |
| `app` | `MainActivity`, `ShareActivity` (ACTION_SEND / SEND_MULTIPLE), foreground service, FileProvider |
| `core` | Identity, trusted peers, radio, logging, `ShareError` |
| `protocol` | JVM protocol: framing, JSON, SAS, TLS helpers, `FileTransfer`, state machines |
| `discovery` | `NsdManager` advertise/browse `_honor-share._tcp.` |
| `storage` | SAF URIs, disk space, MediaStore Downloads/`HONOR Share`, partial files |
| `history` | Room v2: `transfers`, `shared_files` |
| `transfer` | Listen/connect, handshake, send/receive |
| `ui` | Compose screens |
| `tests` | Shared fixtures |

**SDK:** `minSdk 26`, `compileSdk`/`targetSdk` 36, Kotlin 2.0.21, AGP 8.7.2. Debug applicationId `com.honor.share.debug`.

**Permissions:** INTERNET, network/Wi-Fi state, multicast, FGS dataSync, notifications, NEARBY_WIFI_DEVICES, location (legacy NSD), CAMERA, READ_EXTERNAL_STORAGE maxSdk 32. No `MANAGE_EXTERNAL_STORAGE`.

---

## Existing macOS modules (SwiftPM)

HonorShareProtocol, HonorShareCore, HonorShareDiscovery, HonorShareStorage, HonorShareHistory, HonorShareTransfer, HONORShare (SwiftUI), HonorShareCheck.

**Target:** macOS 13+. Sandbox entitlements: network client/server, Downloads R/W, user-selected files R/W, camera. Local Network + Bonjour `_honor-share._tcp` + camera usage strings in `Info.plist`.

**No Finder Services / NSServices / CFBundleDocumentTypes** — dock/right-click “Send with HONOR Share” is not implemented.

---

## Existing transfer protocol

Version **1**. Envelope `{v, type, msgId, ts, payload}`. Frame: uint32 BE length + kind (0 control JSON / 1 binary). Binary: fileId UUID + offset u64 + data. Chunk 256 KiB. Max frame 1 MiB.

**Messages:** HELLO, HELLO_ACK, AUTH_CHALLENGE, AUTH_RESPONSE, TRANSFER_REQUEST, TRANSFER_ACCEPTED, TRANSFER_REJECTED, FILE_START, FILE_PROGRESS, FILE_COMPLETE, FILE_RESUME, TRANSFER_PAUSE, TRANSFER_COMPLETE, TRANSFER_CANCELLED, ERROR.

**FileMeta today:** `fileId`, `name`, `size`, `mimeType`, `relativePath`. **No hash, no modifiedAt, no packageId.** SHA-256 is only on FILE_COMPLETE after the stream.

**JSON:** `ignoreUnknownKeys = true` — additive optional fields are wire-compatible.

**ShareLink (QR today):** `HS1\|host\|port\|id\|os\|urlEncodedName` — out-of-band **device** connection, not a package invitation. No expiration, no package ID.

---

## Existing discovery

Bonjour/mDNS `_honor-share._tcp`. TXT: `v`, `id`, `name`, `os`. No invite/numeric-code TXT. Android `NsdManager` (resolve queue, IPv4 prefer, stale 30s). macOS `NWListener` / `NWBrowser`.

Google Nearby Connections is **not** used.

---

## Existing transfer engine

- One file in flight; 256 KiB streamed chunks (not loaded entirely into RAM)
- Sender is TCP **client**; receiver is TCP **server** (`FileTransfer.send` / `receive`)
- SHA-256 while streaming; commit only on match
- Temps: `*.honor-share-partial` (deleted on abort/startup cleanup)
- Disk check `hasSpace` before accept
- Name collisions: auto `photo (1).jpg` (keep both), no Replace/Skip UI
- `FILE_START.offset` exists; **send always uses offset 0**. `FILE_RESUME` is on the enum only — **not wired**
- Incoming handler always **receives**; there is no “wait with files then send on inbound connection”

---

## Existing persistence

- Android Room: transfer history + shared file index. No package store. History has `createdAt`, not a separate completed date.
- macOS: `history.json`, `files.json`. No package store.
- Trusted peer cert pins after SAS (Android prefs / macOS UserDefaults + Keychain identity).
- File bytes are never persisted in history.

---

## Existing UI navigation

**Android screens:** HOME, SELECTED, DEVICES, PAIRING, TRANSFER, HISTORY, FILES, RECEIVE, SCAN, INCOMING, PERMISSION, RADIO.

Home: Send files / Receive / Files. Scan is on the send portal (files already selected) or receive QR wait. Nearby Mac list is the primary send path after Choose Mac.

**macOS:** Transfer | Files. Transfer pane: device QR, Send files to phone, drag-and-drop, Mac camera scan of phone QR. Nearby list is implicit (first phone / QR).

Mental model today: **discover device → transfer**. Not: prepare package → invite → compare → transfer.

---

## Existing test infrastructure

- JUnit 4 in `:protocol`
- Compose `HomeScreenTest` (androidTest)
- `HonorShareCheck` CLI (not XCTest)
- `tests/integration/README.md` manual LAN matrix
- `docs/TESTING.md`

---

## Existing dependencies (high level)

Android: Compose BOM 2024.12.01, Material 3, Activity, Lifecycle, Room + KSP, CameraX + ML Kit barcode, ZXing, Coil, kotlinx-serialization-json 1.7.3.

macOS: system frameworks only in Package.swift (Foundation, Network, SwiftUI, AVFoundation, Vision, CryptoKit). No third-party Swift packages.

---

## Existing known TODOs / stubs

Repo search for `TODO` / `FIXME` / `HACK` / `not implemented`: **none in production Kotlin/Swift.**

- `fatalError("Unable to create device identity")` in macOS `AppModel` if Keychain identity cannot be created — legitimate hard failure.
- Compose `placeholder` on file-manager search is UI, not a stub.
- `FILE_RESUME` / byte-range resume: protocol present, engine unused.

---

## Gaps vs Primary Transfer Package spec (not bugs of the current product)

These are **absent features**, not baseline test failures:

1. No `TransferPackage` domain, package states, or package persistence
2. QR is a live device ShareLink, not an expiring package invitation
3. No short numeric **package** code (SAS 6-digit is pairing, different purpose)
4. No comparison engine (identity/size/hash); skip is not sent on TRANSFER_ACCEPTED
5. No Finder Services
6. Share sheet exists but feeds the old send-to-device flow
7. No package destination subfolders
8. No invitation rate limit / replay / expiry
9. Nearby/direct discovery **dominates** the UX (must be preserved as fallback, not deleted)
10. History is transfer-session based, not package-based
11. Conflict policy is silent rename, not Replace / Keep both / Skip

---

## Integration plan (from this audit — implement after this baseline)

Reuse TLS, framing, `FileTransfer` streaming, SHA-256, partial files, SAS, Bonjour/NSD, SAF, Downloads/`HONOR Share`, Room/JSON history.

Add package + invitation **above** transport. Keep HS1 ShareLink + device list as **Nearby / other ways to connect**.

Necessary transport adjustments (smallest safe changes):

- Optional `FileMeta.sha256` / `modifiedAt`; optional `packageId` on TRANSFER_REQUEST
- `TRANSFER_ACCEPTED.skipFileIds` (unknown keys already ignored)
- Server-side send when a package is waiting (QR/code receiver is the TCP client)
- Advertise optional TXT `inv` for numeric-code discovery
- New invitation encoding `HS2|…` with expiry; keep `HS1` for fallback QR

Do not replace Nearby Connections (it is not used). Do not add a cloud. Do not rewrite discovery.
