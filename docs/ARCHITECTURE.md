# Architecture

Direct Share is two native apps and one versioned local protocol. There is no backend. The user-facing name is Direct Share; on-disk folders, Bonjour, and TLS still use HONOR Share identifiers so existing devices keep working.

```text
Android (Compose)                 macOS (SwiftUI)
  UI  → TransferController          UI → AppModel
  NSD  (advertise/browse)           Bonjour NWListener/NWBrowser
  TLS 1.2/1.3 socket                Network.framework TLS
                 \                  /
                  framed JSON + binary
```

## Why not Nearby Connections

Nearby Connections is Android-only (Bluetooth / Wi-Fi Direct). A Mac cannot join that fabric. Using it would make Mac discovery fake. Both platforms use Bonjour/mDNS `_honor-share._tcp` and TCP+TLS on the LAN.

## Modules

### Android

| Module | Role |
| --- | --- |
| `protocol` | Framing, JSON, SAS, state machines, TLS helpers, file transfer loop (pure JVM) |
| `core` | Identity, trusted peers, radio, logging |
| `discovery` | `NsdManager` advertise/browse, dedupe, stale timeout |
| `storage` | SAF, temp files, disk space, MediaStore Downloads |
| `history` | Room metadata only |
| `transfer` | Listen/connect, handshake, send/receive |
| `ui` | Compose screens |
| `app` | Activities, share sheet, foreground service |

### macOS

| Target | Role |
| --- | --- |
| HonorShareProtocol | Same protocol as Android |
| HonorShareCore | Identity, Keychain, logging |
| HonorShareDiscovery | Bonjour |
| HonorShareStorage | `~/Downloads/HONOR Share`, partial files, name conflicts |
| HonorShareHistory | JSON history |
| HonorShareTransfer | TLS session, AppModel |
| HONORShare | SwiftUI window + menu bar |

## Connection states

`IDLE → DISCOVERING → DEVICE_FOUND → CONNECTING → AUTHENTICATING → CONNECTED → DISCONNECTED | FAILED`

## Transfer states

`IDLE → PREPARING → WAITING_FOR_ACCEPTANCE → TRANSFERRING → VERIFYING → COMPLETED | CANCELLED | FAILED`

## Data path

1. Control JSON frames (`kind=0`)
2. Binary file frames (`kind=1`) with fileId + offset + bytes
3. One file in flight
4. SHA-256 while streaming
5. Write `*.honor-share-partial`, verify, atomic move

## Share sheet and drag-and-drop

- Android: `ACTION_SEND` / `ACTION_SEND_MULTIPLE` → `ShareActivity` → selected files → **Ready to send** builds a Transfer Package (numeric code). Nearby Mac list is fallback.
- macOS: drop file URLs (or Finder Services **Send with Direct Share**) → same Transfer Package → HS2 QR + numeric code. Receive: enter the phone’s 6-digit code.

## Transfer Package (primary)

```text
UI → TransferPackage + invitation (HS2 / numeric TXT inv)
    → ComparisonEngine
    → existing Handshake + FileTransfer (TLS, framed JSON/binary, SHA-256)
```

The sender with a waiting package uses the existing listen socket and **sends** after handshake. The receiver **connects** using the invitation. Nearby/direct send still uses the sender as TCP client.

