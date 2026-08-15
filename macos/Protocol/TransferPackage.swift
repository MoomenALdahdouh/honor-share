import Foundation
import CryptoKit

public enum PackageState: String, Equatable {
    case draft
    case preparing
    case ready
    case waitingForReceiver
    case connecting
    case authenticating
    case comparing
    case transferring
    case verifying
    case completed
    case partiallyCompleted
    case cancelled
    case expired
    case failed
}

public enum PackageFileStatus: String, Equatable {
    case pending, unavailable, skipped, transferring, verifying, completed, failed
}

public struct PackageFile: Equatable {
    public var fileId: String
    public var name: String
    public var relativePath: String
    public var size: Int64
    public var mimeType: String
    public var modifiedAt: Int64?
    public var hash: String?
    public var status: PackageFileStatus

    public init(fileId: String, name: String, relativePath: String, size: Int64, mimeType: String, modifiedAt: Int64?, hash: String?, status: PackageFileStatus = .pending) {
        self.fileId = fileId
        self.name = name
        self.relativePath = relativePath
        self.size = size
        self.mimeType = mimeType
        self.modifiedAt = modifiedAt
        self.hash = hash
        self.status = status
    }

    public func toMeta() -> FileMeta {
        FileMeta(
            fileId: fileId,
            name: name,
            size: size,
            mimeType: mimeType.isEmpty ? "application/octet-stream" : mimeType,
            relativePath: relativePath.isEmpty ? name : relativePath,
            sha256: hash,
            modifiedAt: modifiedAt
        )
    }
}

public struct TransferPackage: Equatable {
    public var packageId: String
    public var protocolVersion: Int
    public var createdAt: Int64
    public var sourceDeviceId: String
    public var sourceDeviceName: String
    public var sourceOs: String
    public var files: [PackageFile]
    public var state: PackageState
    public var invitation: PackageInvitation?

    public var totalBytes: Int64 { files.reduce(0) { $0 + $1.size } }

    public init(packageId: String = UUID().uuidString, protocolVersion: Int = ProtocolConstants.version, createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000), sourceDeviceId: String, sourceDeviceName: String, sourceOs: String, files: [PackageFile], state: PackageState = .draft, invitation: PackageInvitation? = nil) {
        self.packageId = packageId
        self.protocolVersion = protocolVersion
        self.createdAt = createdAt
        self.sourceDeviceId = sourceDeviceId
        self.sourceDeviceName = sourceDeviceName
        self.sourceOs = sourceOs
        self.files = files
        self.state = files.isEmpty ? .draft : state
        self.invitation = invitation
    }

    public func displayName(now: Date = Date()) -> String {
        let created = Date(timeIntervalSince1970: TimeInterval(createdAt) / 1000)
        let time = Self.timeFormatter.string(from: created)
        if Calendar.current.isDate(created, inSameDayAs: now) {
            return "Today — \(time)"
        }
        if let yesterday = Calendar.current.date(byAdding: .day, value: -1, to: now), Calendar.current.isDate(created, inSameDayAs: yesterday) {
            return "Yesterday — \(time)"
        }
        return "\(Self.dayFormatter.string(from: created)) — \(time)"
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "d MMM"
        return formatter
    }()
}

public enum PackageMachine {
    private static let allowed: [PackageState: Set<PackageState>] = [
        .draft: [.preparing, .cancelled, .failed],
        .preparing: [.ready, .draft, .cancelled, .failed],
        .ready: [.waitingForReceiver, .preparing, .expired, .cancelled, .failed],
        .waitingForReceiver: [.connecting, .ready, .expired, .cancelled, .failed],
        .connecting: [.authenticating, .waitingForReceiver, .failed, .cancelled],
        .authenticating: [.comparing, .transferring, .failed, .cancelled],
        .comparing: [.transferring, .cancelled, .failed],
        .transferring: [.verifying, .completed, .partiallyCompleted, .waitingForReceiver, .cancelled, .failed],
        .verifying: [.transferring, .completed, .partiallyCompleted, .failed, .cancelled],
        .completed: [],
        .partiallyCompleted: [.transferring, .waitingForReceiver, .cancelled, .failed],
        .cancelled: [.ready, .draft],
        .expired: [.ready, .waitingForReceiver, .cancelled],
        .failed: [.ready, .waitingForReceiver, .transferring, .cancelled],
    ]

    public static func canTransition(from: PackageState, to: PackageState) -> Bool {
        from == to || allowed[from]?.contains(to) == true
    }
}

public struct PackageInvitation: Equatable {
    public var protocolVersion: Int
    public var host: String
    public var port: Int
    public var deviceId: String
    public var os: String
    public var packageId: String
    public var inviteId: String
    public var expiresAtEpochSec: Int64
    public var numericCode: String

    public static let prefix = "HS2"

    public init(protocolVersion: Int = ProtocolConstants.version, host: String, port: Int, deviceId: String, os: String, packageId: String, inviteId: String = UUID().uuidString, expiresAtEpochSec: Int64, numericCode: String) {
        self.protocolVersion = protocolVersion
        self.host = host
        self.port = port
        self.deviceId = deviceId
        self.os = os
        self.packageId = packageId
        self.inviteId = inviteId
        self.expiresAtEpochSec = expiresAtEpochSec
        self.numericCode = numericCode
    }

    public static func create(host: String, port: Int, deviceId: String, os: String, packageId: String, ttlSec: Int64 = ProtocolConstants.invitationTtlSec, nowEpochSec: Int64 = Int64(Date().timeIntervalSince1970), numericCode: String = randomNumericCode()) -> PackageInvitation {
        PackageInvitation(
            host: host,
            port: port,
            deviceId: deviceId,
            os: os,
            packageId: packageId,
            expiresAtEpochSec: nowEpochSec + ttlSec,
            numericCode: numericCode
        )
    }

    public func encode() -> String {
        [Self.prefix, "\(protocolVersion)", host, "\(port)", deviceId, os, packageId, inviteId, "\(expiresAtEpochSec)", numericCode].joined(separator: "|")
    }

    public func isExpired(nowEpochSec: Int64 = Int64(Date().timeIntervalSince1970)) -> Bool {
        nowEpochSec >= expiresAtEpochSec
    }

    public func remainingSeconds(nowEpochSec: Int64 = Int64(Date().timeIntervalSince1970)) -> Int64 {
        max(0, expiresAtEpochSec - nowEpochSec)
    }

    public func displayCode() -> String {
        let chars = Array(numericCode)
        guard chars.count == 6 else { return numericCode }
        return String(chars[0..<3]) + " " + String(chars[3..<6])
    }

    public static func randomNumericCode() -> String {
        String(format: "%06d", Int.random(in: 0...999_999))
    }

    public static func parse(_ raw: String) -> PackageInvitation? {
        let parts = raw.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 10, parts[0] == prefix, let version = Int(parts[1]), version == ProtocolConstants.version else { return nil }
        let host = parts[2]
        guard !host.isEmpty, host.rangeOfCharacter(from: .whitespaces) == nil, let port = Int(parts[3]), (1...65535).contains(port) else { return nil }
        let code = parts[9]
        guard code.count == 6, code.allSatisfy(\.isNumber), let exp = Int64(parts[8]) else { return nil }
        guard !parts[4].isEmpty, !parts[6].isEmpty, !parts[7].isEmpty else { return nil }
        return PackageInvitation(
            protocolVersion: version,
            host: host,
            port: port,
            deviceId: parts[4],
            os: parts[5].isEmpty ? "unknown" : parts[5],
            packageId: parts[6],
            inviteId: parts[7],
            expiresAtEpochSec: exp,
            numericCode: code
        )
    }
}

public final class InviteRateLimiter {
    private struct Bucket {
        var failures: Int
        var windowStart: Int64
        var lockedUntil: Int64
    }

    private let maxAttempts: Int
    private let windowMs: Int64
    private let lockoutMs: Int64
    private var buckets: [String: Bucket] = [:]

    public init(maxAttempts: Int = ProtocolConstants.inviteMaxAttempts, windowMs: Int64 = 5 * 60 * 1000, lockoutMs: Int64 = 60_000) {
        self.maxAttempts = maxAttempts
        self.windowMs = windowMs
        self.lockoutMs = lockoutMs
    }

    public func allow(_ key: String, now: Int64) -> Bool {
        guard let bucket = buckets[key] else { return true }
        if now < bucket.lockedUntil { return false }
        if now - bucket.windowStart > windowMs { return true }
        return bucket.failures < maxAttempts
    }

    public func recordFailure(_ key: String, now: Int64) {
        var bucket = buckets[key] ?? Bucket(failures: 0, windowStart: now, lockedUntil: 0)
        if now - bucket.windowStart > windowMs {
            bucket = Bucket(failures: 0, windowStart: now, lockedUntil: 0)
        }
        bucket.failures += 1
        if bucket.failures >= maxAttempts {
            bucket.lockedUntil = now + lockoutMs
        }
        buckets[key] = bucket
    }

    public func recordSuccess(_ key: String) {
        buckets.removeValue(forKey: key)
    }
}

public struct DestinationFile: Equatable {
    public var name: String
    public var size: Int64
    public var sha256: String?
    public var relativePath: String
    public init(name: String, size: Int64, sha256: String?, relativePath: String? = nil) {
        self.name = name
        self.size = size
        self.sha256 = sha256
        self.relativePath = relativePath ?? name
    }
}

public enum ConflictAction: String, Hashable {
    case replace, keepBoth, skip
}

public struct FileConflict: Equatable {
    public var incoming: PackageFile
    public var existingName: String
}

public struct PackageComparison: Equatable {
    public var alreadyPresent: [PackageFile]
    public var needsTransfer: [PackageFile]
    public var conflicts: [FileConflict]

    public var skipFileIds: [String] { alreadyPresent.map(\.fileId) }
    public var neededBytes: Int64 {
        needsTransfer.reduce(0) { $0 + $1.size } + conflicts.reduce(0) { $0 + $1.incoming.size }
    }

    public func withResolutions(_ actions: [String: ConflictAction]) -> PackageComparison {
        if conflicts.isEmpty { return self }
        var extraSkip: [PackageFile] = []
        var extraTransfer: [PackageFile] = []
        var remaining: [FileConflict] = []
        for conflict in conflicts {
            switch actions[conflict.incoming.fileId] {
            case .skip:
                var file = conflict.incoming
                file.status = .skipped
                extraSkip.append(file)
            case .replace, .keepBoth:
                extraTransfer.append(conflict.incoming)
            case nil:
                remaining.append(conflict)
            }
        }
        return PackageComparison(alreadyPresent: alreadyPresent + extraSkip, needsTransfer: needsTransfer + extraTransfer, conflicts: remaining)
    }
}

public enum ComparisonEngine {
    public static func compare(incoming: [PackageFile], destination: [DestinationFile]) -> PackageComparison {
        var destByHash: [String: DestinationFile] = [:]
        for file in destination {
            if let hash = file.sha256?.lowercased() {
                destByHash[hash] = file
            }
        }
        var destByName: [String: DestinationFile] = [:]
        for file in destination {
            destByName[file.name] = file
        }
        var already: [PackageFile] = []
        var needs: [PackageFile] = []
        var conflicts: [FileConflict] = []
        var claimed: Set<String> = []

        for file in incoming {
            let hash = file.hash?.lowercased()
            if let hash, let destMatch = destByHash[hash], !claimed.contains(destMatch.name) {
                var skipped = file
                skipped.status = .skipped
                already.append(skipped)
                claimed.insert(destMatch.name)
                continue
            }
            if let sameName = destByName[file.name], !claimed.contains(sameName.name) {
                let sameHash = hash != nil && sameName.sha256 != nil && Checksums.equalsHex(hash!, sameName.sha256!)
                if sameHash {
                    var skipped = file
                    skipped.status = .skipped
                    already.append(skipped)
                    claimed.insert(sameName.name)
                } else {
                    conflicts.append(FileConflict(incoming: file, existingName: sameName.name))
                    claimed.insert(sameName.name)
                }
            } else {
                needs.append(file)
            }
        }
        return PackageComparison(alreadyPresent: already, needsTransfer: needs, conflicts: conflicts)
    }
}

public struct ReceiveDecision {
    public var accepted: Bool
    public var skipFileIds: [String]
    public var neededBytes: Int64?
    public init(accepted: Bool, skipFileIds: [String] = [], neededBytes: Int64? = nil) {
        self.accepted = accepted
        self.skipFileIds = skipFileIds
        self.neededBytes = neededBytes
    }
}

public enum FileHasher {
    public static func sha256Hex(url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = try handle.read(upToCount: ProtocolConstants.chunkSize) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
