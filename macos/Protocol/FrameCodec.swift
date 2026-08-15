import Foundation

public enum Frame {
    case control(Data)
    case binary(fileId: UUID, offset: UInt64, data: Data)
}

public enum FrameCodec {
    public static func encodeControl(_ json: Data) throws -> Data {
        let length = 1 + json.count
        guard length <= ProtocolConstants.maxFrameLength else {
            throw ProtocolError(.protocolViolation, "control frame too large")
        }
        var out = Data()
        out.append(uint32BE(UInt32(length)))
        out.append(ProtocolConstants.kindControl)
        out.append(json)
        return out
    }

    public static func encodeBinary(fileId: UUID, offset: UInt64, data: Data) throws -> Data {
        let length = 1 + 16 + 8 + data.count
        guard length <= ProtocolConstants.maxFrameLength else {
            throw ProtocolError(.protocolViolation, "binary frame too large")
        }
        var out = Data()
        out.append(uint32BE(UInt32(length)))
        out.append(ProtocolConstants.kindBinary)
        out.append(uuidBytes(fileId))
        out.append(uint64BE(offset))
        out.append(data)
        return out
    }

    public static func read(from handle: FileHandle) throws -> Frame {
        let header = try readFully(handle, count: 4)
        let length = Int(getUInt32BE(header))
        if length < 1 || length > ProtocolConstants.maxFrameLength {
            throw ProtocolError(.protocolViolation, "invalid frame length")
        }
        let body = try readFully(handle, count: length)
        let kind = body[0]
        if kind == ProtocolConstants.kindControl {
            return .control(body.dropFirst())
        }
        if kind == ProtocolConstants.kindBinary {
            guard body.count >= 1 + 16 + 8 else {
                throw ProtocolError(.protocolViolation, "short binary frame")
            }
            let fileId = uuidFromBytes(body.subdata(in: 1..<17))
            let offset = getUInt64BE(body.subdata(in: 17..<25))
            let data = body.subdata(in: 25..<body.count)
            return .binary(fileId: fileId, offset: offset, data: data)
        }
        throw ProtocolError(.protocolViolation, "unknown frame kind")
    }

    public static func read(from stream: InputStream) throws -> Frame {
        let header = try readStream(stream, count: 4)
        let length = Int(getUInt32BE(header))
        if length < 1 || length > ProtocolConstants.maxFrameLength {
            throw ProtocolError(.protocolViolation, "invalid frame length")
        }
        let body = try readStream(stream, count: length)
        let kind = body[0]
        if kind == ProtocolConstants.kindControl {
            return .control(body.dropFirst())
        }
        if kind == ProtocolConstants.kindBinary {
            let fileId = uuidFromBytes(body.subdata(in: 1..<17))
            let offset = getUInt64BE(body.subdata(in: 17..<25))
            let data = body.subdata(in: 25..<body.count)
            return .binary(fileId: fileId, offset: offset, data: data)
        }
        throw ProtocolError(.protocolViolation, "unknown frame kind")
    }

    public static func uuidBytes(_ uuid: UUID) -> Data {
        var tuple = uuid.uuid
        return withUnsafeBytes(of: &tuple) { Data($0) }
    }

    public static func uuidFromBytes(_ data: Data) -> UUID {
        var tuple = uuid_t(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        _ = withUnsafeMutableBytes(of: &tuple) { ptr in
            data.copyBytes(to: ptr.bindMemory(to: UInt8.self))
        }
        return UUID(uuid: tuple)
    }
}

public func uint32BE(_ value: UInt32) -> Data {
    var be = value.bigEndian
    return withUnsafeBytes(of: &be) { Data($0) }
}

public func uint64BE(_ value: UInt64) -> Data {
    var be = value.bigEndian
    return withUnsafeBytes(of: &be) { Data($0) }
}

public func getUInt32BE(_ data: Data) -> UInt32 {
    let b = [UInt8](data)
    return (UInt32(b[0]) << 24) | (UInt32(b[1]) << 16) | (UInt32(b[2]) << 8) | UInt32(b[3])
}

public func getUInt64BE(_ data: Data) -> UInt64 {
    let b = [UInt8](data)
    var value: UInt64 = 0
    for i in 0..<8 { value = (value << 8) | UInt64(b[i]) }
    return value
}

private func readFully(_ handle: FileHandle, count: Int) throws -> Data {
    var data = Data()
    while data.count < count {
        let chunk = try handle.read(upToCount: count - data.count) ?? Data()
        if chunk.isEmpty { throw ProtocolError(.connectionLost, "eof") }
        data.append(chunk)
    }
    return data
}

private func readStream(_ stream: InputStream, count: Int) throws -> Data {
    var buffer = [UInt8](repeating: 0, count: count)
    var read = 0
    while read < count {
        let n = stream.read(&buffer, maxLength: count - read)
        if n <= 0 { throw ProtocolError(.connectionLost, "eof") }
        read += n
    }
    return Data(buffer)
}

public final class ProtocolSession {
    private let input: InputStream
    private let output: OutputStream
    private let lock = NSLock()

    public init(input: InputStream, output: OutputStream) {
        self.input = input
        self.output = output
    }

    public func send(_ envelope: Envelope) throws {
        let data = try ProtocolJSON.encode(envelope)
        let frame = try FrameCodec.encodeControl(data)
        lock.lock()
        defer { lock.unlock() }
        try writeAll(frame)
    }

    public func sendBinary(fileId: UUID, offset: UInt64, data: Data) throws {
        let frame = try FrameCodec.encodeBinary(fileId: fileId, offset: offset, data: data)
        lock.lock()
        defer { lock.unlock() }
        try writeAll(frame)
    }

    public func receive() throws -> Frame {
        try FrameCodec.read(from: input)
    }

    public func receiveControl() throws -> Envelope {
        switch try receive() {
        case .control(let data):
            return try ProtocolJSON.decode(data)
        case .binary:
            throw ProtocolError(.protocolViolation, "expected control frame")
        }
    }

    private func writeAll(_ data: Data) throws {
        try data.withUnsafeBytes { raw in
            var ptr = raw.bindMemory(to: UInt8.self).baseAddress!
            var remaining = data.count
            while remaining > 0 {
                let n = output.write(ptr, maxLength: remaining)
                if n <= 0 { throw ProtocolError(.connectionLost, "write failed") }
                remaining -= n
                ptr += n
            }
        }
        output.outputStreamWriteFlush()
    }
}

private extension OutputStream {
    func outputStreamWriteFlush() {
        // OutputStream has no flush; TCP send buffer is enough.
    }
}

public func makeEnvelope(type: MessageType, payload: [String: JSONValue], msgId: String = UUID().uuidString, ts: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Envelope {
    Envelope(type: type.rawValue, msgId: msgId, ts: ts, payload: payload)
}
