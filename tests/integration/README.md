# Integration scenarios

These are the required Android ↔ macOS cases. The automated JVM loopback test in `:protocol` covers a real TLS-free framed transfer of a text file on localhost. Full radio/TLS discovery requires hardware.

| # | Scenario | How to run |
| --- | --- | --- |
| 1 | Small text file | `./gradlew :protocol:test --tests com.honor.share.protocol.LocalTransferTest` |
| 2 | Image | Phone → Mac, one `.jpg` |
| 3 | Video | Phone → Mac, one `.mp4` |
| 4 | 100 images | Multi-select in picker |
| 5 | Mixed types | Photos + PDF + zip |
| 6 | 1 GB+ | Only if free disk >> 2 GB |
| 7 | Duplicate names | Send `photo.jpg` twice; expect `photo (1).jpg` |
| 8 | Cancel | Cancel mid-transfer; Downloads has no `*.honor-share-partial` |
| 9 | Disconnect | Toggle Wi-Fi; UI shows connection lost |
| 10 | Disk full | Accept path uses `hasSpace` reject |
| 11 | Permission denied | Deny nearby; rationale screen |
| 12 | Device unavailable | Mac app quit; No Mac found |
| 13 | Corrupt transfer | Hash mismatch never commits |

Checklist script (manual):

```bash
# After a receive on Mac:
find ~/Downloads/"HONOR Share" -name '*.honor-share-partial'
# Must print nothing.
```
