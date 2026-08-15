import Foundation
import CryptoKit

public enum MessageType: String, Codable, CaseIterable {
    case hello = "HELLO"
    case helloAck = "HELLO_ACK"
    case authChallenge = "AUTH_CHALLENGE"
    case authResponse = "AUTH_RESPONSE"
    case transferRequest = "TRANSFER_REQUEST"
    case transferAccepted = "TRANSFER_ACCEPTED"
    case transferRejected = "TRANSFER_REJECTED"
    case fileStart = "FILE_START"
    case fileProgress = "FILE_PROGRESS"
    case fileComplete = "FILE_COMPLETE"
    case fileResume = "FILE_RESUME"
    case transferPause = "TRANSFER_PAUSE"
    case transferComplete = "TRANSFER_COMPLETE"
    case transferCancelled = "TRANSFER_CANCELLED"
    case error = "ERROR"
}

public enum ErrorCode: String, Codable {
    case unsupportedVersion = "UNSUPPORTED_VERSION"
    case unknownMessage = "UNKNOWN_MESSAGE"
    case authFailed = "AUTH_FAILED"
    case timeout = "TIMEOUT"
    case connectionLost = "CONNECTION_LOST"
    case fileUnavailable = "FILE_UNAVAILABLE"
    case diskFull = "DISK_FULL"
    case checksumMismatch = "CHECKSUM_MISMATCH"
    case cancelled = "CANCELLED"
    case protocolViolation = "PROTOCOL_VIOLATION"
    case permissionDenied = "PERMISSION_DENIED"
    case radioOff = "RADIO_OFF"
    case noDevice = "NO_DEVICE"
    case destinationUnavailable = "DESTINATION_UNAVAILABLE"
    case filePermissionLost = "FILE_PERMISSION_LOST"
    case deviceDisconnected = "DEVICE_DISCONNECTED"
    case invitationExpired = "INVITATION_EXPIRED"
    case invalidInvitation = "INVALID_INVITATION"
    case rateLimited = "RATE_LIMITED"
    case userRejected = "USER_REJECTED"
}

public enum ConnectionState: String {
    case idle, discovering, deviceFound, connecting, authenticating, connected, disconnected, failed
}

public enum TransferState: String {
    case idle, preparing, waitingForAcceptance, transferring, verifying, completed, cancelled, failed
}

public enum FileStatus: String {
    case queued, transferring, completed, failed, cancelled
}

public enum ProtocolConstants {
    public static let version = 1
    public static let serviceType = "_honor-share._tcp"
    public static let maxFrameLength = 1_048_576
    public static let chunkSize = 256 * 1024
    public static let kindControl: UInt8 = 0
    public static let kindBinary: UInt8 = 1
    public static let deviceStaleMs: Double = 8_000
    public static let partialSuffix = ".honor-share-partial"
    public static let receiveFolder = "HONOR Share"

    public static func receiveSubfolder(peerName: String, now: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        let day = formatter.string(from: now)
        let cleaned = peerName
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let folder = cleaned.isEmpty ? "Device" : String(cleaned.prefix(40))
        return "\(day)/\(folder)"
    }
    public static let txtInvite = "inv"
    public static let txtHost = "h"
    public static let txtPort = "p"
    public static let invitationTtlSec: Int64 = 600
    public static let inviteMaxAttempts = 8

    public static func inviteServiceName(_ numericCode: String) -> String { "HS-\(numericCode)" }

    public static func inviteCode(fromServiceName name: String) -> String? {
        guard let match = name.range(of: #"HS-\d{6}"#, options: .regularExpression) else { return nil }
        return String(name[match].dropFirst(3))
    }

    public static func matchesInviteCode(_ code: String, inviteCode: String?, name: String, id: String) -> Bool {
        if inviteCode == code { return true }
        if Self.inviteCode(fromServiceName: name) == code { return true }
        if Self.inviteCode(fromServiceName: id) == code { return true }
        return false
    }
}

public struct Envelope: Codable, Equatable {
    public var v: Int
    public var type: String
    public var msgId: String
    public var ts: Int64
    public var payload: [String: JSONValue]

    public init(v: Int = ProtocolConstants.version, type: String, msgId: String, ts: Int64, payload: [String: JSONValue] = [:]) {
        self.v = v
        self.type = type
        self.msgId = msgId
        self.ts = ts
        self.payload = payload
    }
}

public enum JSONValue: Codable, Equatable {
    case string(String)
    case int(Int64)
    case double(Double)
    case bool(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null; return }
        if let value = try? container.decode(Bool.self) { self = .bool(value); return }
        if let value = try? container.decode(Int64.self) { self = .int(value); return }
        if let value = try? container.decode(Double.self) { self = .double(value); return }
        if let value = try? container.decode(String.self) { self = .string(value); return }
        if let value = try? container.decode([String: JSONValue].self) { self = .object(value); return }
        if let value = try? container.decode([JSONValue].self) { self = .array(value); return }
        throw DecodingError.dataCorruptedError(in: container, debugDescription: "unsupported JSON")
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .double(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    public var string: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    public var int: Int64? {
        if case .int(let value) = self { return value }
        return nil
    }
}

public enum ProtocolJSON {
    public static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    public static let decoder = JSONDecoder()

    public static func encode(_ envelope: Envelope) throws -> Data {
        try encoder.encode(envelope)
    }

    public static func decode(_ data: Data) throws -> Envelope {
        try decoder.decode(Envelope.self, from: data)
    }
}

public enum Sas {
    public static func compute(fingerprintA: String, fingerprintB: String, authNonce: String) -> String {
        let a = fingerprintA.lowercased()
        let b = fingerprintB.lowercased()
        let first = min(a, b)
        let second = max(a, b)
        let digest = SHA256.hash(data: Data("\(first)|\(second)|\(authNonce)".utf8))
        let bytes = Array(digest)
        var n: UInt64 = 0
        for i in 0..<4 {
            n = (n << 8) | UInt64(bytes[i])
        }
        return String(format: "%06d", n % 1_000_000)
    }

    public static func display(_ code: String) -> String {
        let padded = code.padLeft(toLength: 6, withPad: "0")
        let idx = padded.index(padded.startIndex, offsetBy: 3)
        return "\(padded[..<idx]) \(padded[idx...])"
    }
}

private extension String {
    func padLeft(toLength: Int, withPad: String) -> String {
        if count >= toLength { return self }
        return String(repeating: withPad, count: toLength - count) + self
    }
}

public enum FilenameConflict {
    public static func uniqueName(_ desired: String, exists: (String) -> Bool) throws -> String {
        let safe = desired.split(separator: "/").last.map(String.init) ?? "file"
        if safe == ".." || safe.contains("..") {
            throw ProtocolError(.protocolViolation, "illegal file name")
        }
        if !exists(safe) { return safe }
        let ns = safe as NSString
        let ext = ns.pathExtension
        let base = ext.isEmpty ? safe : ns.deletingPathExtension
        for i in 1...10_000 {
            let candidate = ext.isEmpty ? "\(base) (\(i))" : "\(base) (\(i)).\(ext)"
            if !exists(candidate) { return candidate }
        }
        throw ProtocolError(.fileUnavailable, "too many name conflicts")
    }

    public static func sanitizeRelativePath(_ path: String) throws -> String {
        let parts = path.replacingOccurrences(of: "\\", with: "/").split(separator: "/").map(String.init).filter { $0 != "." && !$0.isEmpty }
        if parts.isEmpty || parts.contains("..") {
            throw ProtocolError(.protocolViolation, "illegal relative path")
        }
        return parts.joined(separator: "/")
    }
}

public struct ProtocolError: Error, LocalizedError {
    public let code: ErrorCode
    public let message: String
    public init(_ code: ErrorCode, _ message: String) {
        self.code = code
        self.message = message
    }
    public var errorDescription: String? { message }
}

public enum Checksums {
    public static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    public static func equalsHex(_ expected: String, _ actual: String) -> Bool {
        expected.lowercased() == actual.lowercased()
    }
}

public final class IncrementalSHA256 {
    private var hasher = SHA256()
    public init() {}
    public func update(_ data: Data) { hasher.update(data: data) }
    public func hex() -> String {
        hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

public enum ByteFormat {
    public static func humanSize(_ bytes: Int64) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        let units = ["KB", "MB", "GB", "TB"]
        var value = Double(bytes) / 1024
        var unit = 0
        while value >= 1024 && unit < units.count - 1 {
            value /= 1024
            unit += 1
        }
        if value >= 10 {
            return String(format: "%.0f %@", value, units[unit])
        }
        return String(format: "%.1f %@", value, units[unit])
    }

    public static func humanSpeed(_ bps: Double) -> String {
        humanSize(Int64(bps)) + "/s"
    }
}

public final class SpeedEstimator {
    private let minElapsedMs: Int64
    private let smoothing: Double
    private var lastBytes: Int64 = 0
    private var lastTime: Int64 = 0
    private var ema: Double = 0
    private var startedAt: Int64 = -1
    private var samples = 0

    public init(minElapsedMs: Int64 = 2000, smoothing: Double = 0.25) {
        self.minElapsedMs = minElapsedMs
        self.smoothing = smoothing
    }

    public func onProgress(bytesTransferred: Int64, nowMs: Int64) -> Double {
        if startedAt < 0 {
            startedAt = nowMs
            lastBytes = bytesTransferred
            lastTime = nowMs
            return 0
        }
        let dt = nowMs - lastTime
        if dt < 80 { return ema }
        let instant = Double(bytesTransferred - lastBytes) * 1000.0 / Double(dt)
        ema = samples == 0 ? instant : (smoothing * instant + (1 - smoothing) * ema)
        lastBytes = bytesTransferred
        lastTime = nowMs
        samples += 1
        if ema < 0 { ema = 0 }
        return ema
    }

    public var bytesPerSecond: Double { ema }

    public func etaSeconds(remaining: Int64, nowMs: Int64? = nil) -> Int64? {
        if remaining <= 0 { return 0 }
        if samples < 3 { return nil }
        let now = nowMs ?? lastTime
        if now - startedAt < minElapsedMs { return nil }
        if ema < 50_000 { return nil }
        let eta = Double(remaining) / ema
        if eta.isNaN || eta.isInfinite || eta > 24 * 3600 { return nil }
        return max(1, Int64(eta))
    }
}

public enum ConnectionMachine {
    public static func canTransition(from: ConnectionState, to: ConnectionState) -> Bool {
        if from == to { return true }
        switch from {
        case .idle: return [.discovering, .connecting, .failed].contains(to)
        case .discovering: return [.deviceFound, .idle, .failed, .discovering].contains(to)
        case .deviceFound: return [.connecting, .discovering, .idle, .failed].contains(to)
        case .connecting: return [.authenticating, .connected, .failed, .disconnected].contains(to)
        case .authenticating: return [.connected, .failed, .disconnected].contains(to)
        case .connected: return [.disconnected, .failed].contains(to)
        case .disconnected: return [.idle, .discovering, .connecting].contains(to)
        case .failed: return [.idle, .discovering, .connecting].contains(to)
        }
    }
}

public enum TransferMachine {
    public static func canTransition(from: TransferState, to: TransferState) -> Bool {
        if from == to { return true }
        switch from {
        case .idle: return [.preparing, .failed].contains(to)
        case .preparing: return [.waitingForAcceptance, .cancelled, .failed].contains(to)
        case .waitingForAcceptance: return [.transferring, .cancelled, .failed].contains(to)
        case .transferring: return [.verifying, .completed, .cancelled, .failed].contains(to)
        case .verifying: return [.transferring, .completed, .failed, .cancelled].contains(to)
        case .completed: return false
        case .cancelled: return false
        case .failed: return [.preparing, .idle].contains(to)
        }
    }
}

public enum RetryPolicy {
    public static let maxAttempts = 3
    public static func shouldRetry(code: ErrorCode, attempt: Int) -> Bool {
        guard attempt < maxAttempts else { return false }
        return code == .connectionLost || code == .timeout || code == .deviceDisconnected
    }
    public static func delayMs(_ attempt: Int) -> Int64 {
        400 * (1 << max(0, attempt))
    }
}
