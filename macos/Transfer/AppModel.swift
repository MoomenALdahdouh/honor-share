import Foundation
import Network
import AppKit
import Security
import UniformTypeIdentifiers
import HonorShareCore
import HonorShareProtocol
import HonorShareStorage
import HonorShareHistory
import HonorShareDiscovery

public actor FramedNW {
    private let pipe: NWBytePipe

    public init(connection: NWConnection) {
        self.pipe = NWBytePipe(connection: connection)
    }

    public func send(_ envelope: Envelope) async throws {
        try await pipe.send(try FrameCodec.encodeControl(try ProtocolJSON.encode(envelope)))
    }

    public func sendBinary(fileId: UUID, offset: UInt64, data: Data) async throws {
        try await pipe.send(try FrameCodec.encodeBinary(fileId: fileId, offset: offset, data: data))
    }

    public func receive() async throws -> Frame {
        let header = try await pipe.readExact(4)
        let length = Int(getUInt32BE(header))
        if length < 1 || length > ProtocolConstants.maxFrameLength {
            throw ProtocolError(.protocolViolation, "invalid frame length")
        }
        let body = try await pipe.readExact(length)
        if body[0] == ProtocolConstants.kindControl {
            return .control(Data(body.dropFirst()))
        }
        if body[0] == ProtocolConstants.kindBinary {
            let fileId = FrameCodec.uuidFromBytes(body.subdata(in: 1..<17))
            let offset = getUInt64BE(body.subdata(in: 17..<25))
            return .binary(fileId: fileId, offset: offset, data: body.subdata(in: 25..<body.count))
        }
        throw ProtocolError(.protocolViolation, "unknown frame kind")
    }

    public func receiveControl() async throws -> Envelope {
        switch try await receive() {
        case .control(let data):
            return try ProtocolJSON.decode(data)
        case .binary:
            throw ProtocolError(.protocolViolation, "expected control")
        }
    }
}

public final class IncomingOffer: Identifiable {
    public var id: String { request.transferId }
    public var peer: RemotePeer
    public var request: TransferRequest
    public var comparison: PackageComparison
    public var resolutions: [String: ConflictAction] = [:]
    public init(peer: RemotePeer, request: TransferRequest, comparison: PackageComparison) {
        self.peer = peer
        self.request = request
        self.comparison = comparison
        for conflict in comparison.conflicts {
            resolutions[conflict.incoming.fileId] = .keepBoth
        }
    }
}

public final class InboundFingerprintBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value = ""
    public func store(_ fp: String) {
        lock.lock()
        value = fp
        lock.unlock()
    }
    public func snapshot() -> String {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

@MainActor
public final class AppModel: ObservableObject {
    @Published public var identity: DeviceIdentity
    @Published public var devices: [NearbyDevice] = []
    @Published public var connectedPeer: RemotePeer?
    @Published public var pairingCode: String?
    @Published public var pairingPeerName: String?
    @Published public var incoming: IncomingOffer?
    @Published public var progress: TransferProgress?
    @Published public var errorMessage: String?
    @Published public var localNetworkDenied = false
    @Published public var destination: URL
    @Published public var lastSavedFolder: URL?
    @Published public var receiving = false
    @Published public var dropConfirmCount = 0
    @Published public var dropConfirmSize: Int64 = 0
    @Published public var pendingSendURLs: [URL] = []
    @Published public var pendingPackage: TransferPackage?
    @Published public var receiveCode: String = ""
    @Published public var lookingForCode = false
    @Published public var connectingToPhone = false
    @Published public var library: [LibraryFile] = []
    @Published public var showFiles = false

    public let discovery: BonjourDiscovery
    public let history = HistoryStore()
    private var pairingContinuation: CheckedContinuation<Bool, Never>?
    private var incomingContinuation: CheckedContinuation<Bool, Never>?
    private var framed: FramedNW?
    private var cancelled = false
    private var capturedFingerprint = ""
    private var activeConnection: NWConnection?
    private let inboundFingerprint = InboundFingerprintBox()

    public init() {
        let loaded = (try? DeviceIdentityStore.loadOrCreate()) ?? {
            fatalError("Unable to create device identity")
        }()
        self.identity = loaded
        if let path = UserDefaults.standard.string(forKey: Self.destinationKey) {
            self.destination = URL(fileURLWithPath: path)
        } else {
            self.destination = DirectorySinkFactory.defaultDirectory()
        }
        self.discovery = BonjourDiscovery(identity: loaded)
        discovery.$devices.assign(to: &$devices)
        startListening()
        refreshLibrary()
    }

    public func startListening() {
        let box = inboundFingerprint
        let params = tlsParameters(identity: identity, pin: nil) { fp in
            box.store(fp)
        }
        discovery.onConnection = { [weak self] connection in
            Task { await self?.handleIncomingConnection(connection) }
        }
        discovery.startAdvertising(tls: params)
        discovery.startBrowse()
    }

    public func confirmPairing(_ connect: Bool) {
        pairingContinuation?.resume(returning: connect)
        pairingContinuation = nil
        pairingCode = nil
    }

    public func confirmIncoming(_ accept: Bool) {
        incomingContinuation?.resume(returning: accept)
        incomingContinuation = nil
    }

    public func setConflict(_ fileId: String, action: ConflictAction) {
        incoming?.resolutions[fileId] = action
        objectWillChange.send()
    }

    public func cancelTransfer() {
        cancelled = true
    }

    public func send(urls: [URL], to device: NearbyDevice) {
        pendingSendURLs = []
        dropConfirmCount = 0
        showFiles = false
        Task { await sendNow(urls: urls, to: device) }
    }

    public func send(fromQR raw: String, urls: [URL]) {
        if let invite = PackageInvitation.parse(raw) {
            if invite.isExpired() {
                errorMessage = String(localized: "This transfer has expired.")
                return
            }
            receive(invite: invite)
            return
        }
        guard let link = ShareLink.parse(raw) else {
            errorMessage = String(localized: "This isn’t a Direct Share transfer code.")
            return
        }
        send(urls: urls, link: link)
    }

    public func receive(invite: PackageInvitation) {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: invite.port)) else { return }
        let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(invite.host), port: nwPort)
        let device = NearbyDevice(
            id: invite.deviceId,
            name: invite.os,
            os: invite.os,
            endpoint: endpoint,
            lastSeen: Date(),
            inviteCode: invite.numericCode,
            host: invite.host,
            port: nwPort.rawValue
        )
        connectingToPhone = true
        cancelled = false
        Task {
            await receiveNow(from: device)
            connectingToPhone = false
        }
    }

    private var lookupGeneration = 0

    public func connectWithCode(_ raw: String) {
        let digits = raw.filter(\.isNumber)
        guard digits.count == 6 else {
            errorMessage = String(localized: "Enter the 6-digit code from your phone.")
            return
        }
        cancelled = false
        errorMessage = nil
        lookingForCode = true
        lookupGeneration += 1
        let generation = lookupGeneration
        discovery.startBrowse()
        Task { await waitAndConnect(code: digits, generation: generation) }
    }

    public func cancelCodeLookup() {
        lookupGeneration += 1
        cancelled = true
        dropActiveConnection()
        lookingForCode = false
        connectingToPhone = false
        errorMessage = nil
    }

    public func cancelPendingSend() {
        lookupGeneration += 1
        cancelled = true
        dropActiveConnection()
        pendingPackage = nil
        pendingSendURLs = []
        dropConfirmCount = 0
        lookingForCode = false
        connectingToPhone = false
        errorMessage = nil
        progress = nil
        discovery.setInviteCode(nil)
    }

    public func goHome() {
        cancelPendingSend()
        showFiles = false
        incoming = nil
        pairingCode = nil
        pairingContinuation?.resume(returning: false)
        pairingContinuation = nil
        incomingContinuation?.resume(returning: false)
        incomingContinuation = nil
    }

    private func waitAndConnect(code: String, generation: Int) async {
        for _ in 0..<40 {
            if generation != lookupGeneration { return }
            if let device = devices.first(where: { matchesInvite($0, code: code) }) {
                ShareLog.i("transfer", "matched \(device.name) host=\(device.host ?? "-") port=\(device.port.map(String.init) ?? "-")")
                lookingForCode = false
                connectingToPhone = true
                await receiveNow(from: device)
                connectingToPhone = false
                return
            }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        if generation != lookupGeneration { return }
        lookingForCode = false
        errorMessage = String(localized: "No transfer found for that code. Keep Direct Share open on your phone, same Wi-Fi, and try again.")
    }

    private func matchesInvite(_ device: NearbyDevice, code: String) -> Bool {
        ProtocolConstants.matchesInviteCode(code, inviteCode: device.inviteCode, name: device.name, id: device.id)
    }

    public func packageLink() -> String {
        pendingPackage?.invitation?.encode() ?? ""
    }

    public func requestSend(urls: [URL]) {
        preparePackage(urls: urls)
    }

    public func preparePackage(urls: [URL]) {
        dropConfirmCount = 0
        errorMessage = nil
        showFiles = false
        Task { @MainActor in
            var files: [PackageFile] = []
            var accessible: [URL] = []
            for url in urls {
                let accessed = url.startAccessingSecurityScopedResource()
                defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .contentTypeKey])
                let size = Int64(values?.fileSize ?? 0)
                let mime = values?.contentType?.preferredMIMEType ?? "application/octet-stream"
                let modified = values?.contentModificationDate.map { Int64($0.timeIntervalSince1970 * 1000) }
                let hash = try? FileHasher.sha256Hex(url: url)
                files.append(PackageFile(fileId: UUID().uuidString, name: url.lastPathComponent, relativePath: url.lastPathComponent, size: size, mimeType: mime, modifiedAt: modified, hash: hash, status: hash == nil ? .unavailable : .pending))
                if hash != nil { accessible.append(url) }
            }
            let usable = files.filter { $0.status != .unavailable }
            guard !usable.isEmpty else {
                errorMessage = String(localized: "A selected file is no longer available.")
                return
            }
            for _ in 0..<30 where discovery.port == 0 {
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            var pkg = TransferPackage(sourceDeviceId: identity.deviceId, sourceDeviceName: identity.displayName, sourceOs: "macos", files: usable, state: .waitingForReceiver)
            if let host = LocalAddress.ipv4(), discovery.port > 0 {
                let invite = PackageInvitation.create(host: host, port: Int(discovery.port), deviceId: identity.deviceId, os: "macos", packageId: pkg.packageId)
                pkg.invitation = invite
                discovery.setInviteCode(invite.numericCode)
            } else {
                errorMessage = String(localized: "Turn on Wi-Fi so your phone can find this Mac.")
            }
            pendingPackage = pkg
            pendingSendURLs = accessible
        }
    }

    public func regenerateInvitation() {
        guard var pkg = pendingPackage, let host = LocalAddress.ipv4(), discovery.port > 0 else { return }
        errorMessage = nil
        let invite = PackageInvitation.create(host: host, port: Int(discovery.port), deviceId: identity.deviceId, os: "macos", packageId: pkg.packageId)
        pkg.invitation = invite
        pkg.state = .waitingForReceiver
        pendingPackage = pkg
        discovery.setInviteCode(invite.numericCode)
    }

    public func send(urls: [URL], link: ShareLink) {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: link.port)) else { return }
        let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(link.host), port: nwPort)
        let device = NearbyDevice(id: link.id, name: link.name, os: link.os, endpoint: endpoint, lastSeen: Date(), host: link.host, port: nwPort.rawValue)
        send(urls: urls, to: device)
    }

    public func shareLink() -> String {
        guard let host = LocalAddress.ipv4(), discovery.port > 0 else { return "" }
        return ShareLink(host: host, port: Int(discovery.port), id: identity.deviceId, name: identity.displayName, os: "macos").encode()
    }

    public func clearHistory() {
        history.clear()
        refreshLibrary()
    }

    public func refreshLibrary() {
        _ = FileLibrary.recoverCompletedPartials(destination: destination, records: history.files)
        library = FileLibrary.scan(destination: destination, records: history.files)
    }

    private static let destinationKey = "saveFolder"

    public func setDestination(_ url: URL) {
        destination = url
        UserDefaults.standard.set(url.path, forKey: Self.destinationKey)
        refreshLibrary()
    }

    public func deleteFile(_ file: LibraryFile) {
        let url = resolvedURL(file)
        let accessed = url.startAccessingSecurityScopedResource()
        NSWorkspace.shared.recycle([url], completionHandler: nil)
        if accessed { url.stopAccessingSecurityScopedResource() }
        history.removeFile(id: file.id)
        refreshLibrary()
    }

    public func openSavedFolder() {
        let url = lastSavedFolder ?? destination
        NSWorkspace.shared.open(url)
    }

    public func finishCompleted() {
        pendingPackage = nil
        pendingSendURLs = []
        dropConfirmCount = 0
        receiveCode = ""
        errorMessage = nil
        progress = nil
        lookingForCode = false
        connectingToPhone = false
        discovery.setInviteCode(nil)
        showFiles = false
        refreshLibrary()
    }

    public func openFile(_ file: LibraryFile) {
        let url = resolvedURL(file)
        let accessed = url.startAccessingSecurityScopedResource()
        NSWorkspace.shared.open(url)
        if accessed { url.stopAccessingSecurityScopedResource() }
    }

    public func revealInFinder(_ file: LibraryFile) {
        let url = resolvedURL(file)
        let accessed = url.startAccessingSecurityScopedResource()
        NSWorkspace.shared.activateFileViewerSelecting([url])
        if accessed { url.stopAccessingSecurityScopedResource() }
    }

    private func resolvedURL(_ file: LibraryFile) -> URL {
        if let data = file.bookmark {
            var stale = false
            if let resolved = try? URL(resolvingBookmarkData: data, options: [.withSecurityScope], relativeTo: nil, bookmarkDataIsStale: &stale) {
                return resolved
            }
        }
        return file.url
    }

    private func bookmark(for url: URL) -> Data? {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        return try? url.bookmarkData(options: .withSecurityScope, includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    public func openSystemSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork") {
            NSWorkspace.shared.open(url)
        }
    }

    private func handleIncomingConnection(_ connection: NWConnection) async {
        connection.start(queue: DispatchQueue.global(qos: .userInitiated))
        let ready = await waitReady(connection)
        guard ready else {
            connection.cancel()
            return
        }
        capturedFingerprint = inboundFingerprint.snapshot()
        let session = FramedNW(connection: connection)
        framed = session
        do {
            let peer = try await handshakeServer(session)
            connectedPeer = peer
            let sending = pendingPackage != nil && !pendingSendURLs.isEmpty
            if sending {
                try await sendFiles(session, urls: pendingSendURLs, peer: peer, pkg: pendingPackage)
            } else {
                try await receiveFiles(session, peer: peer)
            }
        } catch {
            if !cancelled { errorMessage = userFacing(error) }
        }
        connection.cancel()
        connectedPeer = nil
    }

    private func sendNow(urls: [URL], to device: NearbyDevice) async {
        cancelled = false
        do {
            let (connection, session) = try await openOutbound(to: device)
            do {
                let peer = try await handshakeClient(session)
                connectedPeer = peer
                try await sendFiles(session, urls: urls, peer: peer)
            } catch {
                if !cancelled { errorMessage = userFacing(error) }
            }
            connection.cancel()
            if activeConnection === connection { activeConnection = nil }
            connectedPeer = nil
        } catch {
            if !cancelled { errorMessage = userFacing(error) }
        }
    }

    private func receiveNow(from device: NearbyDevice) async {
        do {
            let (connection, session) = try await openOutbound(to: device)
            do {
                let peer = try await handshakeClient(session)
                connectedPeer = peer
                try await receiveFiles(session, peer: peer)
            } catch {
                if !cancelled { errorMessage = userFacing(error) }
            }
            connection.cancel()
            if activeConnection === connection { activeConnection = nil }
            connectedPeer = nil
        } catch {
            if !cancelled { errorMessage = userFacing(error) }
        }
    }

    private func openOutbound(to device: NearbyDevice) async throws -> (NWConnection, FramedNW) {
        errorMessage = nil
        capturedFingerprint = ""
        let pin = DeviceIdentityStore.pin(for: device.id)
        let endpoint = connectEndpoint(device)
        ShareLog.i("transfer", "connecting endpoint=\(endpoint)")
        if let connection = await attemptConnection(to: endpoint, pin: pin, ipv4Only: true, seconds: 6) {
            return (connection, FramedNW(connection: connection))
        }
        if cancelled { throw ProtocolError(.cancelled, "cancelled") }
        if let connection = await attemptConnection(to: endpoint, pin: pin, ipv4Only: false, seconds: 8) {
            return (connection, FramedNW(connection: connection))
        }
        if cancelled { throw ProtocolError(.cancelled, "cancelled") }
        throw ProtocolError(.timeout, "not ready")
    }

    private func attemptConnection(to endpoint: NWEndpoint, pin: String?, ipv4Only: Bool, seconds: Double) async -> NWConnection? {
        var fingerprint = ""
        let params = tlsParameters(identity: identity, pin: pin, ipv4Only: ipv4Only) { fingerprint = $0 }
        let connection = NWConnection(to: endpoint, using: params)
        activeConnection = connection
        connection.start(queue: DispatchQueue.global(qos: .userInitiated))
        let ready = await waitReady(connection, seconds: seconds)
        if ready {
            capturedFingerprint = fingerprint
            return connection
        }
        ShareLog.w("transfer", "connect \(ipv4Only ? "ipv4" : "any") failed state=\(String(describing: connection.state))")
        connection.cancel()
        if activeConnection === connection { activeConnection = nil }
        return nil
    }

    private func connectEndpoint(_ device: NearbyDevice) -> NWEndpoint {
        if let host = device.host, let port = device.port, port > 0 {
            return NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!)
        }
        return device.endpoint
    }

    private func dropActiveConnection() {
        activeConnection?.cancel()
        activeConnection = nil
    }

    private func handshakeClient(_ session: FramedNW) async throws -> RemotePeer {
        let nonce = randomNonce()
        try await session.send(makeEnvelope(type: .hello, payload: helloPayload(nonce: nonce)))
        let timeout = Task { @MainActor in
            try await Task.sleep(nanoseconds: 12_000_000_000)
            ShareLog.w("transfer", "hello timed out")
            dropActiveConnection()
        }
        defer { timeout.cancel() }
        let ack = try await session.receiveControl()
        return try await authenticate(session, hello: ack, nonce: nonce, initiator: true)
    }

    private func handshakeServer(_ session: FramedNW) async throws -> RemotePeer {
        let hello = try await session.receiveControl()
        guard hello.type == MessageType.hello.rawValue else {
            throw ProtocolError(.protocolViolation, "expected HELLO")
        }
        let nonce = hello.payload["authNonce"]?.string ?? ""
        if hello.payload["protocolVersion"]?.int != Int64(ProtocolConstants.version) {
            try await session.send(makeEnvelope(type: .error, payload: ["code": .string(ErrorCode.unsupportedVersion.rawValue)]))
            throw ProtocolError(.unsupportedVersion, "version")
        }
        try await session.send(makeEnvelope(type: .helloAck, payload: helloPayload(nonce: nonce)))
        return try await authenticate(session, hello: hello, nonce: nonce, initiator: false)
    }

    private func authenticate(_ session: FramedNW, hello: Envelope, nonce: String, initiator: Bool) async throws -> RemotePeer {
        guard let deviceId = hello.payload["deviceId"]?.string,
              let name = hello.payload["name"]?.string,
              let os = hello.payload["os"]?.string else {
            throw ProtocolError(.protocolViolation, "bad hello")
        }
        let helloFp = hello.payload["certFingerprint"]?.string ?? ""
        let fp = capturedFingerprint
        if fp.isEmpty {
            throw ProtocolError(.authFailed, "missing peer cert")
        }
        if !helloFp.isEmpty && !Checksums.equalsHex(helloFp, fp) {
            throw ProtocolError(.authFailed, "hello fingerprint does not match TLS certificate")
        }
        let pin = DeviceIdentityStore.pin(for: deviceId)
        let weTrust = pin.map { Checksums.equalsHex($0, fp) } ?? false
        if weTrust {
            try await session.send(makeEnvelope(type: .authResponse, payload: ["confirmed": .bool(true), "trusted": .bool(true)]))
            let reply = try await session.receiveControl()
            if reply.type == MessageType.authResponse.rawValue {
                if reply.payload["confirmed"] != .bool(true) {
                    throw ProtocolError(.authFailed, "peer rejected pairing")
                }
                return RemotePeer(deviceId: deviceId, name: name, os: os, fingerprint: fp, newlyPaired: false)
            }
            if reply.type == MessageType.authChallenge.rawValue {
                return try await completeSas(session, deviceId: deviceId, name: name, os: os, fingerprint: fp, nonce: nonce, initiator: initiator, skipChallengeExchange: true)
            }
            throw ProtocolError(.protocolViolation, "expected AUTH_RESPONSE got \(reply.type)")
        }
        return try await completeSas(session, deviceId: deviceId, name: name, os: os, fingerprint: fp, nonce: nonce, initiator: initiator, skipChallengeExchange: false)
    }

    private func completeSas(
        _ session: FramedNW,
        deviceId: String,
        name: String,
        os: String,
        fingerprint: String,
        nonce: String,
        initiator: Bool,
        skipChallengeExchange: Bool
    ) async throws -> RemotePeer {
        let sas = Sas.compute(fingerprintA: identity.fingerprint, fingerprintB: fingerprint, authNonce: nonce)
        if !skipChallengeExchange {
            if initiator {
                try await session.send(makeEnvelope(type: .authChallenge, payload: ["method": .string("sas-v1")]))
            } else {
                let message = try await session.receiveControl()
                if message.type == MessageType.authResponse.rawValue {
                    try await session.send(makeEnvelope(type: .authChallenge, payload: ["method": .string("sas-v1")]))
                } else if message.type != MessageType.authChallenge.rawValue {
                    throw ProtocolError(.protocolViolation, "expected AUTH_CHALLENGE")
                }
            }
        }
        pairingPeerName = name
        pairingCode = Sas.display(sas)
        let confirmed = await withCheckedContinuation { pairingContinuation = $0 }
        pairingCode = nil
        if !confirmed {
            try await session.send(makeEnvelope(type: .authResponse, payload: ["confirmed": .bool(false), "trusted": .bool(false)]))
            throw ProtocolError(.authFailed, "rejected")
        }
        let sendAuthResponse = !skipChallengeExchange || initiator
        if sendAuthResponse {
            try await session.send(makeEnvelope(type: .authResponse, payload: ["confirmed": .bool(true), "trusted": .bool(false)]))
        }
        let peerAuth = try await session.receiveControl()
        if peerAuth.payload["confirmed"] != .bool(true) {
            throw ProtocolError(.authFailed, "peer rejected pairing")
        }
        DeviceIdentityStore.trust(deviceId: deviceId, fingerprint: fingerprint, name: name)
        return RemotePeer(deviceId: deviceId, name: name, os: os, fingerprint: fingerprint, newlyPaired: true)
    }

    private func sendFiles(_ session: FramedNW, urls: [URL], peer: RemotePeer, pkg: TransferPackage? = nil) async throws {
        receiving = false
        let files: [FileMeta]
        if let pkg {
            files = pkg.files.map { $0.toMeta() }
        } else {
            files = urls.map { url in
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
                return FileMeta(fileId: UUID().uuidString, name: url.lastPathComponent, size: size)
            }
        }
        let request = TransferRequest(transferId: UUID().uuidString, files: files, totalBytes: files.reduce(0) { $0 + $1.size }, packageId: pkg?.packageId)
        try await session.send(makeEnvelope(type: .transferRequest, payload: request.json))
        let response = try await session.receiveControl()
        if response.type != MessageType.transferAccepted.rawValue {
            throw ProtocolError(.cancelled, "rejected")
        }
        let skipIds: Set<String>
        if case .array(let values) = response.payload["skipFileIds"] {
            skipIds = Set(values.compactMap { $0.string })
        } else {
            skipIds = []
        }
        let estimator = SpeedEstimator()
        var overall: Int64 = files.filter { skipIds.contains($0.fileId) }.reduce(0) { $0 + $1.size }
        let outgoing = zip(files, urls).filter { !skipIds.contains($0.0.fileId) }
        let already = files.count - outgoing.count
        for (index, pair) in outgoing.enumerated() {
            try await sendOne(session, url: pair.1, meta: pair.0, transferId: request.transferId, index: already + index, totalFiles: files.count, overallBefore: overall, totalBytes: request.totalBytes, estimator: estimator)
            overall += pair.0.size
        }
        try await session.send(makeEnvelope(type: .transferComplete, payload: ["transferId": .string(request.transferId)]))
        history.add(HistoryItem(id: request.transferId, direction: "SENT", deviceName: peer.name, fileCount: files.count, totalBytes: request.totalBytes, status: "COMPLETED", createdAt: Date().timeIntervalSince1970))
        history.addFiles(urls.enumerated().map { index, url in
            SharedFileRecord(
                id: "\(request.transferId):\(files[index].fileId)",
                name: files[index].name,
                size: files[index].size,
                mime: files[index].mimeType,
                direction: "SENT",
                deviceName: peer.name,
                createdAt: Date().timeIntervalSince1970,
                path: url.path,
                bookmark: bookmark(for: url)
            )
        })
        pendingPackage = nil
        pendingSendURLs = []
        discovery.setInviteCode(nil)
        lastSavedFolder = nil
        receiving = false
        refreshLibrary()
        progress = TransferProgress(transferId: request.transferId, filesCompleted: files.count, filesTotal: files.count, bytesTransferred: request.totalBytes, bytesTotal: request.totalBytes, currentName: "", currentBytes: 0, currentSize: 0, bytesPerSecond: estimator.bytesPerSecond, etaSeconds: 0, state: .completed)
    }

    private func sendOne(_ session: FramedNW, url: URL, meta: FileMeta, transferId: String, index: Int, totalFiles: Int, overallBefore: Int64, totalBytes: Int64, estimator: SpeedEstimator) async throws {
        try await session.send(makeEnvelope(type: .fileStart, payload: [
            "transferId": .string(transferId), "fileId": .string(meta.fileId.lowercased()), "name": .string(meta.name),
            "size": .int(meta.size), "mimeType": .string(meta.mimeType), "offset": .int(0),
        ]))
        let digest = IncrementalSHA256()
        var sent: Int64 = 0
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        while let chunk = try handle.read(upToCount: ProtocolConstants.chunkSize), !chunk.isEmpty {
            if cancelled { throw ProtocolError(.cancelled, "cancelled") }
            digest.update(chunk)
            try await session.sendBinary(fileId: UUID(uuidString: meta.fileId) ?? UUID(), offset: UInt64(sent), data: chunk)
            sent += Int64(chunk.count)
            let overall = overallBefore + sent
            let speed = estimator.onProgress(bytesTransferred: overall, nowMs: Int64(Date().timeIntervalSince1970 * 1000))
            progress = TransferProgress(transferId: transferId, filesCompleted: index, filesTotal: totalFiles, bytesTransferred: overall, bytesTotal: totalBytes, currentName: meta.name, currentBytes: sent, currentSize: meta.size, bytesPerSecond: speed, etaSeconds: estimator.etaSeconds(remaining: totalBytes - overall), state: .transferring)
        }
        try await session.send(makeEnvelope(type: .fileComplete, payload: [
            "transferId": .string(transferId), "fileId": .string(meta.fileId.lowercased()), "bytes": .int(sent), "sha256": .string(digest.hex()),
        ]))
    }

    private func receiveFiles(_ session: FramedNW, peer: RemotePeer) async throws {
        receiving = true
        let envelope = try await session.receiveControl()
        let request = try TransferRequest.from(envelope.payload)
        let subfolder = ProtocolConstants.receiveSubfolder(peerName: peer.name, fileCount: request.files.count)
        let factory = DirectorySinkFactory(directory: destination, subfolder: subfolder)
        let packageFiles = request.files.map {
            PackageFile(fileId: $0.fileId, name: $0.name, relativePath: $0.relativePath, size: $0.size, mimeType: $0.mimeType, modifiedAt: $0.modifiedAt, hash: $0.sha256)
        }
        let dest = factory.destinationIndex(matchingSizes: Set(packageFiles.map(\.size)))
        var comparison = ComparisonEngine.compare(incoming: packageFiles, destination: dest)
        incoming = IncomingOffer(peer: peer, request: request, comparison: comparison)
        let accepted = await withCheckedContinuation { incomingContinuation = $0 }
        if let offer = incoming {
            comparison = comparison.withResolutions(offer.resolutions)
            factory.replaceNames = Set(offer.resolutions.compactMap { id, action in
                action == .replace ? offer.comparison.conflicts.first(where: { $0.incoming.fileId == id })?.existingName : nil
            })
        }
        incoming = nil
        if !accepted {
            try await session.send(makeEnvelope(type: .transferRejected, payload: ["transferId": .string(request.transferId), "reason": .string("USER_DECLINED")]))
            return
        }
        let needed = comparison.neededBytes
        if !factory.hasSpace(bytes: needed) {
            try await session.send(makeEnvelope(type: .transferRejected, payload: ["transferId": .string(request.transferId), "reason": .string("INSUFFICIENT_STORAGE")]))
            errorMessage = String(localized: "Not enough storage")
            return
        }
        try await session.send(makeEnvelope(type: .transferAccepted, payload: [
            "transferId": .string(request.transferId),
            "skipFileIds": .array(comparison.skipFileIds.map { .string($0) }),
        ]))
        let estimator = SpeedEstimator()
        var overall: Int64 = request.files.filter { comparison.skipFileIds.contains($0.fileId) }.reduce(0) { $0 + $1.size }
        var completed = comparison.skipFileIds.count
        while true {
            switch try await session.receive() {
            case .control(let data):
                let control = try ProtocolJSON.decode(data)
                if control.type == MessageType.fileStart.rawValue {
                    let start = try FileStart.from(control.payload)
                    let sink = try factory.open(file: FileMeta(fileId: start.fileId, name: start.name, size: start.size, mimeType: start.mimeType), offset: start.offset)
                    let digest = IncrementalSHA256()
                    var committed = false
                    while !committed {
                        switch try await session.receive() {
                        case .binary(_, _, let chunk):
                            digest.update(chunk)
                            try sink.write(chunk)
                            overall = overall + Int64(chunk.count) - 0
                            progress = TransferProgress(transferId: request.transferId, filesCompleted: completed, filesTotal: request.files.count, bytesTransferred: overall, bytesTotal: request.totalBytes, currentName: start.name, currentBytes: sink.bytesWritten, currentSize: start.size, bytesPerSecond: estimator.onProgress(bytesTransferred: overall, nowMs: Int64(Date().timeIntervalSince1970 * 1000)), etaSeconds: estimator.etaSeconds(remaining: request.totalBytes - overall), state: .transferring)
                        case .control(let completeData):
                            let complete = try ProtocolJSON.decode(completeData)
                            if complete.type != MessageType.fileComplete.rawValue {
                                sink.abort()
                                throw ProtocolError(.protocolViolation, "expected FILE_COMPLETE")
                            }
                            let sha = complete.payload["sha256"]?.string ?? ""
                            if !Checksums.equalsHex(sha, digest.hex()) {
                                sink.abort()
                                throw ProtocolError(.checksumMismatch, "hash mismatch")
                            }
                            try sink.commit(expectedSha256: sha)
                            committed = true
                        }
                    }
                    completed += 1
                } else if control.type == MessageType.transferComplete.rawValue {
                    let written = request.files.compactMap { meta -> SharedFileRecord? in
                        guard let url = factory.openedURLs[meta.fileId] else { return nil }
                        return SharedFileRecord(
                            id: "\(request.transferId):\(meta.fileId)",
                            name: url.lastPathComponent,
                            size: meta.size,
                            mime: meta.mimeType,
                            direction: "RECEIVED",
                            deviceName: peer.name,
                            createdAt: Date().timeIntervalSince1970,
                            path: url.path
                        )
                    }
                    history.add(HistoryItem(id: request.transferId, direction: "RECEIVED", deviceName: peer.name, fileCount: written.count, totalBytes: request.totalBytes, status: "COMPLETED", createdAt: Date().timeIntervalSince1970))
                    history.addFiles(written)
                    lastSavedFolder = factory.directory
                    pendingPackage = nil
                    pendingSendURLs = []
                    discovery.setInviteCode(nil)
                    refreshLibrary()
                    progress = TransferProgress(transferId: request.transferId, filesCompleted: completed, filesTotal: request.files.count, bytesTransferred: request.totalBytes, bytesTotal: request.totalBytes, currentName: "", currentBytes: 0, currentSize: 0, bytesPerSecond: estimator.bytesPerSecond, etaSeconds: 0, state: .completed)
                    return
                } else if control.type == MessageType.transferCancelled.rawValue {
                    throw ProtocolError(.cancelled, "cancelled")
                }
            case .binary:
                throw ProtocolError(.protocolViolation, "binary without start")
            }
        }
    }

    private func helloPayload(nonce: String) -> [String: JSONValue] {
        [
            "deviceId": .string(identity.deviceId),
            "name": .string(identity.displayName),
            "os": .string("macos"),
            "protocolVersion": .int(Int64(ProtocolConstants.version)),
            "certFingerprint": .string(identity.fingerprint),
            "authNonce": .string(nonce),
        ]
    }

    private func failed(_ state: NWConnection.State) -> Bool {
        switch state {
        case .failed, .cancelled: return true
        default: return false
        }
    }

    private func waitReady(_ connection: NWConnection, seconds: Double = 8) async -> Bool {
        let steps = max(1, Int(seconds / 0.1))
        for _ in 0..<steps {
            if cancelled {
                connection.cancel()
                return false
            }
            if connection.state == .ready { return true }
            if failed(connection.state) { return false }
            if case .waiting = connection.state { localNetworkDenied = true }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return connection.state == .ready
    }

    private func randomNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    private func userFacing(_ error: Error) -> String {
        if let proto = error as? ProtocolError {
            switch proto.code {
            case .authFailed: return String(localized: "Could not confirm the connection. Make sure the codes match.")
            case .diskFull: return String(localized: "There is not enough storage on this device.")
            case .checksumMismatch: return String(localized: "File verification failed. The file may have been corrupted during transfer.")
            case .unsupportedVersion: return String(localized: "This version of Direct Share cannot talk to the other device. Update both apps.")
            case .cancelled, .userRejected: return String(localized: "Transfer cancelled")
            case .invitationExpired: return String(localized: "This transfer has expired.")
            case .invalidInvitation: return String(localized: "This isn’t a Direct Share transfer code.")
            case .rateLimited: return String(localized: "Too many attempts. Try again later.")
            case .timeout: return String(localized: "Could not reach your phone. Keep Ready to send open, stay on the same Wi-Fi, and try again.")
            default: return String(localized: "Connection lost. Make sure both devices are nearby and try again.")
            }
        }
        return String(localized: "Connection lost. Make sure both devices are nearby and try again.")
    }
}
