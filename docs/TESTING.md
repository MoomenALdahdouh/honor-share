# Testing

## Automated

```bash
cd android && ./gradlew :protocol:test
```

Command Line Tools do not ship XCTest. Protocol checks run with:

```bash
cd macos && swift run HonorShareCheck
```

Covered: protocol encode/decode (golden vectors), SAS, frames, SHA-256, filename conflicts, state machines, retry policy, progress ETA hiding, loopback send of a small text file (Android JVM).

Android instrumented: `HomeScreenTest` (home actions visible). Run on a device/emulator:

```bash
cd android && ./gradlew :app:connectedDebugAndroidTest
```

## Integration scenarios (`tests/integration`)

The JVM loopback test covers scenario 1 (small text file) without radios. The rest need two processes or physical devices:

1. One small text file
2. One image
3. One video
4. 100 images
5. Mixed types
6. 1 GB+ file (disk permitting)
7. Duplicate filenames → `photo (1).jpg`
8. Cancel → no partials in Downloads
9. Disconnect → connection lost, retry
10. Insufficient storage → reject before start
11. Permission denied → rationale, not an empty list
12. Device unavailable → No Mac found
13. Corrupt hash → not marked complete

## UI workflows

A. Open → Send → one photo → Mac appears → select → accept → success  
B. 50 photos → complete → verify files  
C. Mac → Android → accept → complete  
D. Disconnect mid-transfer → clear error → retry  
E. Select files → cancel before send → no partials

## Physical devices

Do not treat discovery or multi-gigabyte transfers as verified until a real Android phone and Mac on the same Wi-Fi have completed:

- 10+ mixed files both directions
- SHA-256 of each received file
- Open the files
- One multi-gigabyte file if disk allows
