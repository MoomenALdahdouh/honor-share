import Foundation
import HonorShareProtocol
import HonorShareHistory
import HonorShareStorage

@main
struct HonorShareCheck {
    static func main() {
        var failed = 0
        func check(_ name: String, _ condition: @autoclosure () -> Bool) {
            if condition() {
                print("PASS \(name)")
            } else {
                print("FAIL \(name)")
                failed += 1
            }
        }

        let code = Sas.compute(
            fingerprintA: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            fingerprintB: "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            authNonce: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )
        check("sas golden", code == "693253" && Sas.display(code) == "693 253")

        do {
            let taken: Set<String> = ["photo.jpg"]
            let name = try FilenameConflict.uniqueName("photo.jpg") { taken.contains($0) }
            check("filename conflict", name == "photo (1).jpg")
        } catch {
            check("filename conflict", false)
        }

        do {
            _ = try FilenameConflict.sanitizeRelativePath("../secret")
            check("sanitize rejects ..", false)
        } catch {
            check("sanitize rejects ..", true)
        }

        do {
            let json = try ProtocolJSON.encode(makeEnvelope(type: .error, payload: ["code": .string("TIMEOUT")]))
            let encoded = try FrameCodec.encodeControl(json)
            let stream = InputStream(data: encoded)
            stream.open()
            let decoded = try FrameCodec.read(from: stream)
            if case .control(let data) = decoded {
                check("frame roundtrip", data == json)
            } else {
                check("frame roundtrip", false)
            }
        } catch {
            check("frame roundtrip", false)
        }

        check(
            "sha256 empty",
            Checksums.sha256Hex(Data()) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        check("retry disconnect", RetryPolicy.shouldRetry(code: .connectionLost, attempt: 0))
        check("retry checksum", !RetryPolicy.shouldRetry(code: .checksumMismatch, attempt: 0))
        check("connection idle->discover", ConnectionMachine.canTransition(from: .idle, to: .discovering))
        check("connection idle->connected illegal", !ConnectionMachine.canTransition(from: .idle, to: .connected))

        do {
            let json = """
            {"v":1,"type":"HELLO","msgId":"11111111-1111-4111-8111-111111111111","ts":1700000000000,"payload":{"deviceId":"22222222-2222-4222-8222-222222222222","name":"MacBook Pro","os":"macos","protocolVersion":1,"certFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","authNonce":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}
            """.data(using: .utf8)!
            let envelope = try ProtocolJSON.decode(json)
            check("hello golden", envelope.type == "HELLO" && envelope.payload["name"]?.string == "MacBook Pro")
        } catch {
            check("hello golden", false)
        }

        let estimator = SpeedEstimator(minElapsedMs: 2000)
        _ = estimator.onProgress(bytesTransferred: 0, nowMs: 0)
        _ = estimator.onProgress(bytesTransferred: 100_000, nowMs: 100)
        check("eta hidden until stable", estimator.etaSeconds(remaining: 1_000_000, nowMs: 100) == nil)

        let link = ShareLink(host: "10.189.45.67", port: 49221, id: "abc-id", name: "Moomen's Mac", os: "macos")
        check("share link round trip", ShareLink.parse(link.encode()) == link)
        check("share link rejects junk", ShareLink.parse("not-a-code") == nil)

        let invite = PackageInvitation.create(host: "10.189.45.67", port: 49221, deviceId: "abc-id", os: "macos", packageId: "pkg-1", nowEpochSec: 1_700_000_000, numericCode: "482731")
        check("package invite round trip", PackageInvitation.parse(invite.encode()) == invite)
        check("package invite rejects HS1", PackageInvitation.parse(link.encode()) == nil)
        check("package invite expired", invite.isExpired(nowEpochSec: 1_700_000_000 + 601))
        check("package invite display", invite.displayCode() == "482 731")
        check("package id not display name", TransferPackage(sourceDeviceId: "id", sourceDeviceName: "Mac", sourceOs: "macos", files: []).packageId.contains("Today") == false)
        check("package illegal transition", !PackageMachine.canTransition(from: .draft, to: .completed))

        let hashed = PackageFile(fileId: "1", name: "photo.jpg", relativePath: "photo.jpg", size: 100, mimeType: "image/jpeg", modifiedAt: 1, hash: "abcd")
        let sameHash = ComparisonEngine.compare(incoming: [hashed], destination: [DestinationFile(name: "other.jpg", size: 100, sha256: "ABCD")])
        check("compare same hash skips", sameHash.alreadyPresent.count == 1 && sameHash.needsTransfer.isEmpty)
        let nameOnly = PackageFile(fileId: "2", name: "photo.jpg", relativePath: "photo.jpg", size: 100, mimeType: "image/jpeg", modifiedAt: 1, hash: nil)
        let nameConflict = ComparisonEngine.compare(incoming: [nameOnly], destination: [DestinationFile(name: "photo.jpg", size: 100, sha256: nil)])
        check("compare name is not identity", nameConflict.conflicts.count == 1 && nameConflict.alreadyPresent.isEmpty)

        let limiter = InviteRateLimiter(maxAttempts: 3, windowMs: 60_000, lockoutMs: 10_000)
        limiter.recordFailure("k", now: 0)
        limiter.recordFailure("k", now: 1)
        check("rate limit allows before lock", limiter.allow("k", now: 2))
        limiter.recordFailure("k", now: 2)
        check("rate limit locks", !limiter.allow("k", now: 3))
        check("invite service name", ProtocolConstants.inviteServiceName("851802") == "HS-851802")
        check("invite from service name", ProtocolConstants.inviteCode(fromServiceName: "HS-851802") == "851802")
        check("invite from suffixed service name", ProtocolConstants.inviteCode(fromServiceName: "HS-851802 (2)") == "851802")
        check("invite matches service name", ProtocolConstants.matchesInviteCode("858607", inviteCode: nil, name: "HS-858607", id: "uuid"))
        check("invite matches txt code", ProtocolConstants.matchesInviteCode("858607", inviteCode: "858607", name: "Honor 200", id: "uuid"))
        check("invite does not match digits in name", !ProtocolConstants.matchesInviteCode("858607", inviteCode: nil, name: "Honor 200", id: "abc858607def"))
        check("receive subfolder", ProtocolConstants.receiveSubfolder(peerName: "Honor 200", now: Date(timeIntervalSince1970: 1_787_000_000)).hasSuffix("/Honor 200"))
        check("receive batch folder", ProtocolConstants.receiveSubfolder(peerName: "Honor 200", now: Date(timeIntervalSince1970: 1_787_000_000), fileCount: 3).contains("_"))
        check("receive subfolder sanitizes slash", !ProtocolConstants.receiveSubfolder(peerName: "a/b").contains("a/b"))

        do {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("honor-lib-\(UUID().uuidString)", isDirectory: true)
            let nested = root.appendingPathComponent("2026-08-15/Honor 200", isDirectory: true)
            try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)
            let file = nested.appendingPathComponent("photo.jpg")
            try Data("x".utf8).write(to: file)
            let scanned = FileLibrary.scan(destination: root, records: [])
            check("library scan nested", scanned.contains { $0.name == "photo.jpg" && $0.relativePath.contains("Honor 200") })
            check("library folders", FileLibrary.folders(in: scanned, at: "2026-08-15") == ["Honor 200"])
            try? FileManager.default.removeItem(at: root)
        } catch {
            check("library scan nested", false)
        }

        do {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("honor-sink-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try Data("a".utf8).write(to: root.appendingPathComponent("photo.jpg"))
            let factory = DirectorySinkFactory(directory: root)
            let meta = FileMeta(fileId: "f1", name: "photo.jpg", size: 1, mimeType: "image/jpeg", relativePath: "photo.jpg")
            _ = try factory.open(file: meta, offset: 0)
            check("uniquified name recorded", factory.openedURLs["f1"]?.lastPathComponent == "photo (1).jpg")
            try? FileManager.default.removeItem(at: root)
        } catch {
            check("uniquified name recorded", false)
        }

        if failed == 0 {
            print("All checks passed")
        } else {
            print("\(failed) checks failed")
            exit(1)
        }
    }
}
