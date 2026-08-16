# HONOR Share Protocol

Product name: **Direct Share**. Wire identifiers (`_honor-share._tcp`, TLS `CN=HONOR Share`) stay as written below so Android and Mac keep talking.

Version: **1**  
Service type: `_honor-share._tcp`  
Transport: TLS 1.3 (fallback TLS 1.2) over TCP on the local network.

This document is the source of truth for Android and macOS. Control messages are compact JSON. File bytes are never placed inside JSON.

Nearby Connections is **not** used. It cannot interoperate with macOS. Discovery is Bonjour/mDNS on both platforms.

---

## 1. Protocol version

| Field | Value |
| --- | --- |
| `protocolVersion` / envelope `v` | `1` |
| Incompatible peer | send `ERROR` with code `UNSUPPORTED_VERSION` and close |

A peer that speaks a higher major version must still send `v: 1` envelopes when talking to v1, or fail clearly. Unknown message `type` values are an `ERROR` with code `UNKNOWN_MESSAGE`.

---

## 2. Device discovery

Both apps advertise and browse:

- **Type:** `_honor-share._tcp`
- **Name:** `HS-<first 8 chars of device UUID>`
- **Port:** ephemeral TCP listen port (SRV record)
- **TXT** (keep tiny):

| Key | Example | Meaning |
| --- | --- | --- |
| `v` | `1` | Protocol version |
| `id` | UUID | Stable device identity |
| `name` | `MacBook Pro` | Display name, max 40 UTF-8 bytes |
| `os` | `android` or `macos` | Platform |

Rules:

- Start discovery when the relevant screen opens; stop when it closes.
- Dedupe by `id`.
- Drop a peer if it has not been seen for 8 seconds.
- Do not require the user to enter an IP or port.

---

## 3. Device identity

Each installation generates once and stores securely:

- `deviceId` — UUID v4
- RSA-2048 key pair
- Self-signed X.509 certificate (`CN=HONOR Share`)
- `certFingerprint` — lowercase hex SHA-256 of the DER certificate

Android: EncryptedSharedPreferences + app-private files.  
macOS: Keychain.

---

## 4. Framing

Every frame on the TLS socket:

```text
uint32 BE  frameLength     // bytes after this field
uint8      kind            // 0 = control JSON, 1 = binary file data
uint8[]    payload         // frameLength - 1 bytes
```

Limits:

- Maximum `frameLength`: 1 048 576 (1 MiB)
- Control JSON should stay well under 64 KiB
- Binary chunks: 256 KiB of file data per frame

### Binary payload (`kind = 1`)

```text
uint8[16]  fileId          // UUID, RFC 4122 byte order
uint64 BE  offset          // byte offset of this chunk in the file
uint8[]    data            // remaining bytes of the payload
```

---

## 5. Control envelope

Every control message:

```json
{
  "v": 1,
  "type": "HELLO",
  "msgId": "11111111-1111-4111-8111-111111111111",
  "ts": 1700000000000,
  "payload": {}
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `v` | int | Protocol version |
| `type` | string | Message type, uppercase with underscores |
| `msgId` | string | UUID of this message |
| `ts` | long | Unix epoch milliseconds |
| `payload` | object | Type-specific, compact |

Unknown keys in `payload` are ignored.

---

## 6. Message types

### HELLO

Sent by the TCP client immediately after TLS completes.

```json
{
  "deviceId": "uuid",
  "name": "HONOR Phone",
  "os": "android",
  "protocolVersion": 1,
  "certFingerprint": "64-char hex",
  "authNonce": "32-char hex"
}
```

`authNonce` is 16 random bytes, hex-encoded. It is used to derive the pairing code.

### HELLO_ACK

Sent by the TCP server in response. Same payload shape. Echo `authNonce` from HELLO.

If `protocolVersion` is unsupported, send `ERROR` instead and close.

### AUTH_CHALLENGE

Sent when the peer certificate is **not** pinned yet. Both UIs show the locally computed 6-digit code. The code is **not** the security boundary on the wire; the user comparing both screens is.

```json
{ "method": "sas-v1" }
```

### AUTH_RESPONSE

```json
{ "confirmed": true, "trusted": false }
```

- `trusted: true` — sender already has a matching pin; no SAS UI
- `confirmed: true` — user tapped Connect
- `confirmed: false` — user cancelled

No `TRANSFER_REQUEST` is legal before both sides have an accepted auth.

### TRANSFER_REQUEST

```json
{
  "transferId": "uuid",
  "files": [
    {
      "fileId": "uuid",
      "name": "photo.jpg",
      "size": 4390000,
      "mimeType": "image/jpeg",
      "relativePath": "photo.jpg"
    }
  ],
  "totalBytes": 4390000
}
```

Do not put file contents or hashes of huge payloads here. SHA-256 is sent in `FILE_COMPLETE` after streaming.

Cap: 10 000 files per transfer. Names only, no paths that escape the destination (`..` is rejected).

### TRANSFER_ACCEPTED

```json
{ "transferId": "uuid" }
```

### TRANSFER_REJECTED

```json
{ "transferId": "uuid", "reason": "USER_DECLINED" }
```

Reasons: `USER_DECLINED`, `INSUFFICIENT_STORAGE`, `DESTINATION_UNAVAILABLE`.

### FILE_START

```json
{
  "transferId": "uuid",
  "fileId": "uuid",
  "name": "photo.jpg",
  "size": 4390000,
  "mimeType": "image/jpeg",
  "offset": 0
}
```

`offset` is 0 for a new file, or `bytesReceived` when resuming.

### FILE_PROGRESS

Optional. Receivers should prefer counting binary bytes.

```json
{
  "transferId": "uuid",
  "fileId": "uuid",
  "bytesTransferred": 1024
}
```

### FILE_COMPLETE

```json
{
  "transferId": "uuid",
  "fileId": "uuid",
  "bytes": 4390000,
  "sha256": "64-char hex"
}
```

Receiver verifies size + SHA-256, then atomically moves the temp file into place. On mismatch: do not mark complete; send `ERROR` `CHECKSUM_MISMATCH`.

### FILE_RESUME

After a reconnect, receiver asks to continue the current file:

```json
{
  "transferId": "uuid",
  "fileId": "uuid",
  "bytesReceived": 1048576
}
```

If the sender cannot seek safely, it restarts that file from offset 0. Completed files are never resent.

### TRANSFER_PAUSE

```json
{ "transferId": "uuid" }
```

### TRANSFER_COMPLETE

```json
{ "transferId": "uuid" }
```

### TRANSFER_CANCELLED

```json
{ "transferId": "uuid", "reason": "USER_CANCELLED" }
```

### ERROR

```json
{
  "code": "UNSUPPORTED_VERSION",
  "transferId": null,
  "fileId": null
}
```

`code` is a stable token. User-facing text is mapped locally, never taken from a raw exception.

---

## 7. Handshake

```text
TCP connect
TLS 1.3 (self-signed certs; pin after pairing)
client HELLO
server HELLO_ACK
if fingerprints already pinned on both sides:
    AUTH_RESPONSE { trusted: true } both ways
else:
    AUTH_CHALLENGE
    both show SAS
    AUTH_RESPONSE { confirmed: true } both ways
CONNECTED — transfers allowed
```

### SAS (pairing code)

```text
a, b = sort(localFingerprint, peerFingerprint)
digest = SHA-256( UTF8(a + "|" + b + "|" + authNonce) )
n = uint32_be(digest[0..3])
code = n % 1_000_000   // zero-padded to 6 digits, display as "482 731"
```

Fingerprints are lowercase hex. `authNonce` is the value from HELLO.

Under MITM the two screens show different codes. The user must not tap Connect unless they match.

---

## 8. Transfer

Default: **one file in flight**.

```text
TRANSFER_REQUEST
TRANSFER_ACCEPTED
loop files:
    FILE_START
    binary frames until size reached
    FILE_COMPLETE
    receiver verifies, atomic rename
TRANSFER_COMPLETE
```

Receiver writes `.filename.honor-share-partial` (or an equivalent temp name in an app temp directory). After verify: move to the destination. On cancel/fail: delete the temp. Completed files stay.

Never present a partial file as the final name in Downloads.

---

## 9. Progress, cancel, disconnect

- Progress is computed from bytes actually written, not guessed.
- Cancel: `TRANSFER_CANCELLED`, delete temps, keep completed files.
- Disconnect: connection state `DISCONNECTED`. Retry restarts the **current** file (resume offset if both can; otherwise 0). Already verified files are skipped.
- A file is `COMPLETED` only after hash verification.

---

## 10. Failure codes

| Code | Meaning |
| --- | --- |
| `UNSUPPORTED_VERSION` | Protocol mismatch |
| `UNKNOWN_MESSAGE` | Unknown `type` |
| `AUTH_FAILED` | Pairing rejected or pin mismatch |
| `TIMEOUT` | Handshake or stall |
| `CONNECTION_LOST` | Socket died |
| `FILE_UNAVAILABLE` | Sender cannot read the file |
| `DISK_FULL` | Not enough space |
| `CHECKSUM_MISMATCH` | Hash failed |
| `CANCELLED` | User cancelled |
| `PROTOCOL_VIOLATION` | Illegal state / framing |

---

## 11. Incompatibility

If HELLO `protocolVersion` is not `1`, respond with `ERROR` `UNSUPPORTED_VERSION` and close. The UI says the other app needs to be updated — never a socket exception.
