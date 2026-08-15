import Foundation

public struct FileMeta: Equatable {
    public var fileId: String
    public var name: String
    public var size: Int64
    public var mimeType: String
    public var relativePath: String
    public var sha256: String?
    public var modifiedAt: Int64?
    public init(fileId: String, name: String, size: Int64, mimeType: String = "application/octet-stream", relativePath: String? = nil, sha256: String? = nil, modifiedAt: Int64? = nil) {
        self.fileId = fileId
        self.name = name
        self.size = size
        self.mimeType = mimeType
        self.relativePath = relativePath ?? name
        self.sha256 = sha256
        self.modifiedAt = modifiedAt
    }

    public var json: [String: JSONValue] {
        var obj: [String: JSONValue] = [
            "fileId": .string(fileId),
            "name": .string(name),
            "size": .int(size),
            "mimeType": .string(mimeType),
            "relativePath": .string(relativePath),
        ]
        if let sha256 { obj["sha256"] = .string(sha256) }
        if let modifiedAt { obj["modifiedAt"] = .int(modifiedAt) }
        return obj
    }

    public static func from(_ obj: [String: JSONValue]) throws -> FileMeta {
        guard let fileId = obj["fileId"]?.string,
              let name = obj["name"]?.string,
              let size = obj["size"]?.int else {
            throw ProtocolError(.protocolViolation, "bad file meta")
        }
        return FileMeta(
            fileId: fileId,
            name: name,
            size: size,
            mimeType: obj["mimeType"]?.string ?? "application/octet-stream",
            relativePath: obj["relativePath"]?.string ?? name,
            sha256: obj["sha256"]?.string,
            modifiedAt: obj["modifiedAt"]?.int
        )
    }
}

public struct TransferRequest {
    public var transferId: String
    public var files: [FileMeta]
    public var totalBytes: Int64
    public var packageId: String?
    public init(transferId: String, files: [FileMeta], totalBytes: Int64, packageId: String? = nil) {
        self.transferId = transferId
        self.files = files
        self.totalBytes = totalBytes
        self.packageId = packageId
    }

    public var json: [String: JSONValue] {
        var obj: [String: JSONValue] = [
            "transferId": .string(transferId),
            "files": .array(files.map { .object($0.json) }),
            "totalBytes": .int(totalBytes),
        ]
        if let packageId { obj["packageId"] = .string(packageId) }
        return obj
    }

    public static func from(_ payload: [String: JSONValue]) throws -> TransferRequest {
        guard let transferId = payload["transferId"]?.string,
              let total = payload["totalBytes"]?.int,
              case .array(let filesValue) = payload["files"] else {
            throw ProtocolError(.protocolViolation, "bad transfer request")
        }
        let files = try filesValue.compactMap { value -> FileMeta? in
            if case .object(let obj) = value { return try FileMeta.from(obj) }
            return nil
        }
        return TransferRequest(transferId: transferId, files: files, totalBytes: total, packageId: payload["packageId"]?.string)
    }
}

public struct LocalProfile {
    public var deviceId: String
    public var name: String
    public var os: String
    public var fingerprint: String
    public init(deviceId: String, name: String, os: String, fingerprint: String) {
        self.deviceId = deviceId
        self.name = name
        self.os = os
        self.fingerprint = fingerprint
    }
}

public struct RemotePeer: Equatable {
    public var deviceId: String
    public var name: String
    public var os: String
    public var fingerprint: String
    public var newlyPaired: Bool
    public init(deviceId: String, name: String, os: String, fingerprint: String, newlyPaired: Bool) {
        self.deviceId = deviceId
        self.name = name
        self.os = os
        self.fingerprint = fingerprint
        self.newlyPaired = newlyPaired
    }
}

public struct Handshake {
    public var session: ProtocolSession
    public var local: LocalProfile
    public var capturedFingerprint: String
    public var knownPin: (String) -> String?
    public var confirmSas: (String, String) -> Bool

    public init(
        session: ProtocolSession,
        local: LocalProfile,
        capturedFingerprint: String,
        knownPin: @escaping (String) -> String?,
        confirmSas: @escaping (String, String) -> Bool
    ) {
        self.session = session
        self.local = local
        self.capturedFingerprint = capturedFingerprint
        self.knownPin = knownPin
        self.confirmSas = confirmSas
    }

    public func runAsClient() throws -> RemotePeer {
        let nonce = randomNonce()
        try session.send(helloEnvelope(nonce: nonce, type: .hello))
        let ack = try session.receiveControl()
        return try finish(ack: ack, nonce: nonce, initiator: true)
    }

    public func runAsServer() throws -> RemotePeer {
        let hello = try session.receiveControl()
        if hello.type == MessageType.error.rawValue {
            throw ProtocolError(.unsupportedVersion, "peer sent error")
        }
        guard hello.type == MessageType.hello.rawValue else {
            throw ProtocolError(.protocolViolation, "expected HELLO")
        }
        let nonce = hello.payload["authNonce"]?.string ?? ""
        let peerVersion = hello.payload["protocolVersion"]?.int ?? 0
        if peerVersion != Int64(ProtocolConstants.version) {
            try session.send(makeEnvelope(type: .error, payload: ["code": .string(ErrorCode.unsupportedVersion.rawValue)]))
            throw ProtocolError(.unsupportedVersion, "peer version")
        }
        try session.send(helloEnvelope(nonce: nonce, type: .helloAck))
        return try authenticate(hello: hello, nonce: nonce, initiator: false)
    }

    private func finish(ack: Envelope, nonce: String, initiator: Bool) throws -> RemotePeer {
        if ack.type == MessageType.error.rawValue {
            throw ProtocolError(.unsupportedVersion, ack.payload["code"]?.string ?? "error")
        }
        guard ack.type == MessageType.helloAck.rawValue else {
            throw ProtocolError(.protocolViolation, "expected HELLO_ACK")
        }
        return try authenticate(hello: ack, nonce: nonce, initiator: initiator)
    }

    private func authenticate(hello: Envelope, nonce: String, initiator: Bool) throws -> RemotePeer {
        guard let deviceId = hello.payload["deviceId"]?.string,
              let name = hello.payload["name"]?.string,
              let os = hello.payload["os"]?.string,
              let fp = hello.payload["certFingerprint"]?.string else {
            throw ProtocolError(.protocolViolation, "bad hello")
        }
        if !Checksums.equalsHex(fp, capturedFingerprint) {
            throw ProtocolError(.authFailed, "hello fingerprint does not match TLS certificate")
        }
        let pin = knownPin(deviceId)
        let weTrust = pin.map { Checksums.equalsHex($0, capturedFingerprint) } ?? false
        if weTrust {
            try session.send(makeEnvelope(type: .authResponse, payload: ["confirmed": .bool(true), "trusted": .bool(true)]))
            let reply = try session.receiveControl()
            if reply.type == MessageType.authResponse.rawValue {
                if reply.payload["confirmed"] != .bool(true) {
                    throw ProtocolError(.authFailed, "peer rejected pairing")
                }
                return RemotePeer(deviceId: deviceId, name: name, os: os, fingerprint: capturedFingerprint, newlyPaired: false)
            }
            if reply.type == MessageType.authChallenge.rawValue {
                return try completeSas(helloName: name, deviceId: deviceId, os: os, nonce: nonce, initiator: initiator, skipChallengeExchange: true)
            }
            throw ProtocolError(.protocolViolation, "expected AUTH_RESPONSE")
        }
        return try completeSas(helloName: name, deviceId: deviceId, os: os, nonce: nonce, initiator: initiator, skipChallengeExchange: false)
    }

    private func completeSas(helloName: String, deviceId: String, os: String, nonce: String, initiator: Bool, skipChallengeExchange: Bool) throws -> RemotePeer {
        let sas = Sas.compute(fingerprintA: local.fingerprint, fingerprintB: capturedFingerprint, authNonce: nonce)
        if !skipChallengeExchange {
            if initiator {
                try session.send(makeEnvelope(type: .authChallenge, payload: ["method": .string("sas-v1")]))
            } else {
                let message = try session.receiveControl()
                if message.type == MessageType.authResponse.rawValue {
                    try session.send(makeEnvelope(type: .authChallenge, payload: ["method": .string("sas-v1")]))
                } else if message.type != MessageType.authChallenge.rawValue {
                    throw ProtocolError(.protocolViolation, "expected AUTH_CHALLENGE")
                }
            }
        }
        if !confirmSas(sas, helloName) {
            try session.send(makeEnvelope(type: .authResponse, payload: ["confirmed": .bool(false), "trusted": .bool(false)]))
            throw ProtocolError(.authFailed, "user rejected pairing")
        }
        if !skipChallengeExchange || initiator {
            try session.send(makeEnvelope(type: .authResponse, payload: ["confirmed": .bool(true), "trusted": .bool(false)]))
        }
        try waitPeerAuth(expectTrusted: false)
        return RemotePeer(deviceId: deviceId, name: helloName, os: os, fingerprint: capturedFingerprint, newlyPaired: true)
    }

    private func waitPeerAuth(expectTrusted: Bool) throws {
        let response = try session.receiveControl()
        if response.type != MessageType.authResponse.rawValue {
            throw ProtocolError(.protocolViolation, "expected AUTH_RESPONSE")
        }
        if response.payload["confirmed"] != .bool(true) {
            throw ProtocolError(.authFailed, "peer rejected pairing")
        }
        if expectTrusted && response.payload["trusted"] != .bool(true) {
            throw ProtocolError(.authFailed, "peer is not trusted")
        }
    }

    private func helloEnvelope(nonce: String, type: MessageType) -> Envelope {
        makeEnvelope(type: type, payload: [
            "deviceId": .string(local.deviceId),
            "name": .string(local.name),
            "os": .string(local.os),
            "protocolVersion": .int(Int64(ProtocolConstants.version)),
            "certFingerprint": .string(local.fingerprint),
            "authNonce": .string(nonce),
        ])
    }

    private func randomNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}
