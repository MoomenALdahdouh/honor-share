# Direct Share — test matrix

Automated coverage is in `:protocol:test` (36 tests) and `swift run HonorShareCheck`. Physical LAN cases need an Android phone and a Mac on the same Wi-Fi.

## Automated (this repo)

| Case | Where | Status |
| --- | --- | --- |
| Package id ≠ display name | `:protocol` / HonorShareCheck | PASS |
| Illegal package state transition | both | PASS |
| HS2 invitation round-trip, reject HS1/junk | both | PASS |
| Invitation expiry | both | PASS |
| Numeric display `482 731` | both | PASS |
| Rate limiter lockout | both | PASS |
| Same SHA-256 skips even if name differs | both | PASS |
| Filename alone does not skip | both | PASS |
| Conflict Replace/Skip | `:protocol` | PASS |
| Stream SHA-256 matches in-memory | `:protocol` | PASS |
| Loopback transfer + skip already-present file | `:protocol` LocalTransferTest | PASS |
| v1 HELLO/SAS/frames/ShareLink HS1 | both | PASS |

## Mac → Android (manual)

| Case | How | Result |
| --- | --- | --- |
| Drop files on Mac → HS2 QR → phone Receive/Scan | physical | NOT RUN this session |
| Finder “Send with Direct Share” | Services menu after install | NOT RUN this session |
| One file / mixed types / Unicode names | physical | NOT RUN this session |
| Duplicate package (comparison skip) | physical | NOT RUN this session |
| Cancel / disconnect / retry | physical | NOT RUN this session |
| Expired QR | wait 10 min or clock | NOT RUN this session |
| Invalid QR | scan unrelated code | unit-level parse only |

## Android → Mac (manual)

| Case | How | Result |
| --- | --- | --- |
| Share sheet → Ready to send → numeric code → Mac Connect | physical | NOT RUN this session |
| Choose files in app → code → Mac | physical | NOT RUN this session |
| Wrong code on Mac | expect no match | NOT RUN this session |

## Nearby fallback

| Case | How | Result |
| --- | --- | --- |
| Android Nearby devices → choose Mac | existing v1 path | must still work |
| HS1 ShareLink scan (send nearby) | “Can't connect?” | must still work |
| Legacy receive wait QR | `openLegacyReceive` | preserved in code |

## Resume / integrity

| Case | Expected |
| --- | --- |
| Interrupt after some files complete | completed files stay; remaining transfer on retry if dest hashes match |
| Partial file | discarded (`.honor-share-partial`); that file restarts |
| Checksum mismatch | not finalized |

## Large / many files

Not executed on hardware in this change set. Streaming uses 256 KiB chunks; package prepare hashes via stream (not full-file RAM).
