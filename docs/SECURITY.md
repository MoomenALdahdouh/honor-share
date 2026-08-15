# Security

## Threat model

An attacker on the same LAN who can advertise a fake service or intercept TCP. There is no cloud attacker because nothing leaves the LAN. There are no accounts.

## Authentication

Each install has a device UUID and a self-signed RSA-2048 certificate. TLS is required. The first time two devices meet they show a 6-digit SAS:

```text
a, b = sort(localFingerprint, peerFingerprint)
SHA-256(a + "|" + b + "|" + authNonce) → 6 digits
```

Under MITM the codes differ. The user must tap Connect only when they match. After that the peer certificate is pinned.

HELLO fingerprints must match the TLS peer certificate.

## Encryption

TLS 1.3 where the OS supports it, otherwise TLS 1.2. File bytes travel only as TLS application data, never as JSON.

## Integrity

SHA-256 is computed while sending and while receiving. A file is completed only after the hashes match. Incomplete data stays in a temp name and is deleted on cancel/fail.

## Temporary files

`.filename.honor-share-partial` (or cache equivalent). Startup deletes stale partials. Final Downloads never get a half-written user file.

## Privacy

No analytics. No accounts. History stores direction, device name, counts, sizes, status, timestamps — not file contents. Logs strip tokens and home-directory paths. Authentication secrets and private keys are not logged.

## Pins and identity storage

- Android: app-private PKCS#12 + SharedPreferences device id
- macOS: Keychain identity + UserDefaults pins

## Transfer Package invitations

QR (`HS2|…`) carries host, port, device id, package id, invite id, expiry, and a 6-digit code. It does not contain file bytes or file names. Invitations expire after 10 minutes and can be regenerated without rebuilding the package.

The numeric code is a discovery hint in Bonjour TXT `inv`, not the TLS credential. The TLS session still uses certificates and SAS (or a stored pin) as in v1.

A wrong numeric code fails to match an advertised service. Unlimited guessing against a waiting sender via TCP is still subject to SAS confirmation.

## Replay

Each invitation has a unique `inviteId` and `expiresAt`. Stale QR payloads are rejected as expired. HS2 codes are not valid indefinitely. Connecting with an old QR after expiry does not start a transfer.

## Device trust

After a matching SAS, the peer certificate is pinned (existing behavior). Pins are not a substitute for TLS. There is no cloud account and no automatic accept from unknown devices: incoming packages still show a preview (file count, already-present vs needs-transfer) before Receive.

## Comparison and overwrite

Files are skipped only when SHA-256 matches. Same filename with a different hash is a conflict. Nearby fallback still auto-renames (`photo (1).jpg`) when not in the package conflict UI.

## Logging

Logs may include package id, transfer id, and state. They must not include file contents, private keys, or invitation secrets beyond what is already on the local QR screen.

