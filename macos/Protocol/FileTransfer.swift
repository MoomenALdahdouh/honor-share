import Foundation

public struct OutgoingFile {
    public var meta: FileMeta
    public var open: () throws -> InputStream
    public init(meta: FileMeta, open: @escaping () throws -> InputStream) {
        self.meta = meta
        self.open = open
    }
}

public protocol ReceiveSink: AnyObject {
    var bytesWritten: Int64 { get }
    func write(_ data: Data) throws
    func commit(expectedSha256: String) throws
    func abort()
}

public protocol ReceiveSinkFactory {
    func hasSpace(bytes: Int64) -> Bool
    func open(file: FileMeta, offset: Int64) throws -> ReceiveSink
}

public struct TransferProgress: Equatable {
    public var transferId: String
    public var filesCompleted: Int
    public var filesTotal: Int
    public var bytesTransferred: Int64
    public var bytesTotal: Int64
    public var currentName: String
    public var currentBytes: Int64
    public var currentSize: Int64
    public var bytesPerSecond: Double
    public var etaSeconds: Int64?
    public var state: TransferState

    public init(transferId: String, filesCompleted: Int, filesTotal: Int, bytesTransferred: Int64, bytesTotal: Int64, currentName: String, currentBytes: Int64, currentSize: Int64, bytesPerSecond: Double, etaSeconds: Int64?, state: TransferState) {
        self.transferId = transferId
        self.filesCompleted = filesCompleted
        self.filesTotal = filesTotal
        self.bytesTransferred = bytesTransferred
        self.bytesTotal = bytesTotal
        self.currentName = currentName
        self.currentBytes = currentBytes
        self.currentSize = currentSize
        self.bytesPerSecond = bytesPerSecond
        self.etaSeconds = etaSeconds
        self.state = state
    }
}

public final class FileTransfer {
    private let session: ProtocolSession
    public var cancelled = false

    public init(session: ProtocolSession) {
        self.session = session
    }

    public func send(request: TransferRequest, files: [OutgoingFile], onProgress: (TransferProgress) -> Void) throws {
        try session.send(makeEnvelope(type: .transferRequest, payload: request.json))
        let response = try session.receiveControl()
        if response.type == MessageType.transferRejected.rawValue {
            let reason = response.payload["reason"]?.string ?? ""
            throw ProtocolError(reason == "INSUFFICIENT_STORAGE" ? .diskFull : .cancelled, reason)
        }
        if response.type != MessageType.transferAccepted.rawValue {
            throw ProtocolError(.protocolViolation, "expected TRANSFER_ACCEPTED")
        }
        let skipIds: Set<String>
        if case .array(let values) = response.payload["skipFileIds"] {
            skipIds = Set(values.compactMap { $0.string })
        } else {
            skipIds = []
        }
        let outgoing = files.filter { !skipIds.contains($0.meta.fileId) }
        let skippedBytes = files.filter { skipIds.contains($0.meta.fileId) }.reduce(Int64(0)) { $0 + $1.meta.size }
        let estimator = SpeedEstimator()
        var overall: Int64 = skippedBytes
        let alreadyDone = files.count - outgoing.count
        for (index, file) in outgoing.enumerated() {
            try throwIfCancelled(request.transferId)
            try sendOne(transferId: request.transferId, file: file, index: alreadyDone + index, totalFiles: files.count, overallBefore: overall, totalBytes: request.totalBytes, estimator: estimator, onProgress: onProgress)
            overall += file.meta.size
        }
        try session.send(makeEnvelope(type: .transferComplete, payload: ["transferId": .string(request.transferId)]))
        onProgress(TransferProgress(transferId: request.transferId, filesCompleted: files.count, filesTotal: files.count, bytesTransferred: request.totalBytes, bytesTotal: request.totalBytes, currentName: "", currentBytes: 0, currentSize: 0, bytesPerSecond: estimator.bytesPerSecond, etaSeconds: 0, state: .completed))
    }

    public func receive(sinkFactory: ReceiveSinkFactory, accept: (TransferRequest) -> ReceiveDecision, onProgress: (TransferProgress) -> Void) throws -> TransferRequest {
        let envelope = try session.receiveControl()
        guard envelope.type == MessageType.transferRequest.rawValue else {
            throw ProtocolError(.protocolViolation, "expected TRANSFER_REQUEST")
        }
        let request = try TransferRequest.from(envelope.payload)
        let decision = accept(request)
        let skipIds = Set(decision.skipFileIds)
        let needed = decision.neededBytes ?? request.files.filter { !skipIds.contains($0.fileId) }.reduce(Int64(0)) { $0 + $1.size }
        if !decision.accepted {
            try session.send(makeEnvelope(type: .transferRejected, payload: ["transferId": .string(request.transferId), "reason": .string("USER_DECLINED")]))
            throw ProtocolError(.cancelled, "user declined")
        }
        if !sinkFactory.hasSpace(bytes: needed) {
            try session.send(makeEnvelope(type: .transferRejected, payload: ["transferId": .string(request.transferId), "reason": .string("INSUFFICIENT_STORAGE")]))
            throw ProtocolError(.diskFull, "not enough storage")
        }
        try session.send(makeEnvelope(type: .transferAccepted, payload: [
            "transferId": .string(request.transferId),
            "skipFileIds": .array(decision.skipFileIds.map { .string($0) }),
        ]))
        let estimator = SpeedEstimator()
        var overall: Int64 = request.files.filter { skipIds.contains($0.fileId) }.reduce(0) { $0 + $1.size }
        var completed = skipIds.count
        while true {
            try throwIfCancelled(request.transferId)
            switch try session.receive() {
            case .control(let data):
                let control = try ProtocolJSON.decode(data)
                switch MessageType(rawValue: control.type) {
                case .fileStart:
                    let start = try FileStart.from(control.payload)
                    overall = try receiveOne(request: request, start: start, sinkFactory: sinkFactory, completed: completed, overallBefore: overall, estimator: estimator, onProgress: onProgress)
                    completed += 1
                case .transferComplete:
                    onProgress(TransferProgress(transferId: request.transferId, filesCompleted: completed, filesTotal: request.files.count, bytesTransferred: request.totalBytes, bytesTotal: request.totalBytes, currentName: "", currentBytes: 0, currentSize: 0, bytesPerSecond: estimator.bytesPerSecond, etaSeconds: 0, state: .completed))
                    return request
                case .transferCancelled:
                    throw ProtocolError(.cancelled, "peer cancelled")
                default:
                    throw ProtocolError(.protocolViolation, "unexpected \(control.type)")
                }
            case .binary:
                throw ProtocolError(.protocolViolation, "binary without FILE_START")
            }
        }
    }

    public func cancel(transferId: String) {
        cancelled = true
        try? session.send(makeEnvelope(type: .transferCancelled, payload: ["transferId": .string(transferId), "reason": .string("USER_CANCELLED")]))
    }

    private func sendOne(transferId: String, file: OutgoingFile, index: Int, totalFiles: Int, overallBefore: Int64, totalBytes: Int64, estimator: SpeedEstimator, onProgress: (TransferProgress) -> Void) throws {
        try session.send(makeEnvelope(type: .fileStart, payload: [
            "transferId": .string(transferId),
            "fileId": .string(file.meta.fileId.lowercased()),
            "name": .string(file.meta.name),
            "size": .int(file.meta.size),
            "mimeType": .string(file.meta.mimeType),
            "offset": .int(0),
        ]))
        let digest = IncrementalSHA256()
        var sent: Int64 = 0
        let stream = try file.open()
        stream.open()
        defer { stream.close() }
        var buffer = [UInt8](repeating: 0, count: ProtocolConstants.chunkSize)
        while true {
            try throwIfCancelled(transferId)
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n < 0 { throw ProtocolError(.fileUnavailable, "read failed") }
            if n == 0 { break }
            let chunk = Data(buffer[0..<n])
            digest.update(chunk)
            try session.sendBinary(fileId: UUID(uuidString: file.meta.fileId) ?? UUID(), offset: UInt64(sent), data: chunk)
            sent += Int64(n)
            let overall = overallBefore + sent
            let speed = estimator.onProgress(bytesTransferred: overall, nowMs: nowMs())
            onProgress(TransferProgress(transferId: transferId, filesCompleted: index, filesTotal: totalFiles, bytesTransferred: overall, bytesTotal: totalBytes, currentName: file.meta.name, currentBytes: sent, currentSize: file.meta.size, bytesPerSecond: speed, etaSeconds: estimator.etaSeconds(remaining: totalBytes - overall), state: .transferring))
        }
        if sent != file.meta.size {
            throw ProtocolError(.fileUnavailable, "size changed while sending")
        }
        try session.send(makeEnvelope(type: .fileComplete, payload: [
            "transferId": .string(transferId),
            "fileId": .string(file.meta.fileId.lowercased()),
            "bytes": .int(sent),
            "sha256": .string(digest.hex()),
        ]))
    }

    private func receiveOne(request: TransferRequest, start: FileStart, sinkFactory: ReceiveSinkFactory, completed: Int, overallBefore: Int64, estimator: SpeedEstimator, onProgress: (TransferProgress) -> Void) throws -> Int64 {
        let relative = try FilenameConflict.sanitizeRelativePath(start.name)
        let meta = FileMeta(fileId: start.fileId, name: (relative as NSString).lastPathComponent, size: start.size, mimeType: start.mimeType, relativePath: relative)
        let sink = try sinkFactory.open(file: meta, offset: start.offset)
        let digest = IncrementalSHA256()
        do {
            while sink.bytesWritten < start.size {
                try throwIfCancelled(request.transferId)
                switch try session.receive() {
                case .binary(let fileId, _, let data):
                    if fileId.uuidString.lowercased() != start.fileId.lowercased() {
                        throw ProtocolError(.protocolViolation, "unexpected file id")
                    }
                    digest.update(data)
                    try sink.write(data)
                    let overall = overallBefore + sink.bytesWritten
                    let speed = estimator.onProgress(bytesTransferred: overall, nowMs: nowMs())
                    onProgress(TransferProgress(transferId: request.transferId, filesCompleted: completed, filesTotal: request.files.count, bytesTransferred: overall, bytesTotal: request.totalBytes, currentName: meta.name, currentBytes: sink.bytesWritten, currentSize: start.size, bytesPerSecond: speed, etaSeconds: estimator.etaSeconds(remaining: request.totalBytes - overall), state: .transferring))
                case .control(let data):
                    let control = try ProtocolJSON.decode(data)
                    if control.type == MessageType.fileComplete.rawValue {
                        let sha = control.payload["sha256"]?.string ?? ""
                        if !Checksums.equalsHex(sha, digest.hex()) {
                            throw ProtocolError(.checksumMismatch, "hash mismatch")
                        }
                        try sink.commit(expectedSha256: sha)
                        return overallBefore + sink.bytesWritten
                    }
                    if control.type == MessageType.transferCancelled.rawValue {
                        sink.abort()
                        throw ProtocolError(.cancelled, "peer cancelled")
                    }
                    throw ProtocolError(.protocolViolation, "unexpected \(control.type)")
                }
            }
            let complete = try session.receiveControl()
            let sha = complete.payload["sha256"]?.string ?? ""
            if !Checksums.equalsHex(sha, digest.hex()) {
                throw ProtocolError(.checksumMismatch, "hash mismatch")
            }
            try sink.commit(expectedSha256: sha)
            return overallBefore + sink.bytesWritten
        } catch {
            sink.abort()
            throw error
        }
    }

    private func throwIfCancelled(_ transferId: String) throws {
        if cancelled {
            try session.send(makeEnvelope(type: .transferCancelled, payload: ["transferId": .string(transferId), "reason": .string("USER_CANCELLED")]))
            throw ProtocolError(.cancelled, "cancelled")
        }
    }

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

public struct FileStart {
    public var transferId: String
    public var fileId: String
    public var name: String
    public var size: Int64
    public var mimeType: String
    public var offset: Int64
    public init(transferId: String, fileId: String, name: String, size: Int64, mimeType: String, offset: Int64) {
        self.transferId = transferId
        self.fileId = fileId
        self.name = name
        self.size = size
        self.mimeType = mimeType
        self.offset = offset
    }
    public static func from(_ payload: [String: JSONValue]) throws -> FileStart {
        guard let transferId = payload["transferId"]?.string,
              let fileId = payload["fileId"]?.string,
              let name = payload["name"]?.string,
              let size = payload["size"]?.int else {
            throw ProtocolError(.protocolViolation, "bad FILE_START")
        }
        return FileStart(transferId: transferId, fileId: fileId, name: name, size: size, mimeType: payload["mimeType"]?.string ?? "application/octet-stream", offset: payload["offset"]?.int ?? 0)
    }
}
