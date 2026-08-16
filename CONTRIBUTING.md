# Contributing

Thanks for helping with Direct Share.

1. Keep transfers local. Do not add cloud accounts, analytics, or a server.
2. Do not rename the on-disk folder `Downloads/HONOR Share`, the Bonjour type `_honor-share._tcp`, or TLS common name `HONOR Share`. Those IDs keep Android and Mac talking.
3. User-facing name is **Direct Share**.
4. Run tests before a pull request:

```bash
cd android && ./gradlew :protocol:test :app:assembleDebug
cd macos && swift run HonorShareCheck
./macos/packaging/package-macos.sh
```
