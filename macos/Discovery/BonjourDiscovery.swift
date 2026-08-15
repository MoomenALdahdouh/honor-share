import Foundation
import Network
import HonorShareCore
import HonorShareProtocol

public struct NearbyDevice: Identifiable, Equatable, Hashable {
    public var id: String
    public var name: String
    public var os: String
    public var endpoint: NWEndpoint
    public var lastSeen: Date
    public var inviteCode: String?
    public var host: String?
    public var port: UInt16?
    public init(id: String, name: String, os: String, endpoint: NWEndpoint, lastSeen: Date, inviteCode: String? = nil, host: String? = nil, port: UInt16? = nil) {
        self.id = id
        self.name = name
        self.os = os
        self.endpoint = endpoint
        self.lastSeen = lastSeen
        self.inviteCode = inviteCode
        self.host = host
        self.port = port
    }
}

public final class BonjourDiscovery: ObservableObject {
    @Published public var devices: [NearbyDevice] = []
    private var listener: NWListener?
    private var browser: NWBrowser?
    private let identity: DeviceIdentity
    private var seen: [String: NearbyDevice] = [:]
    @Published public private(set) var port: UInt16 = 0
    public var onConnection: ((NWConnection) -> Void)?

    public init(identity: DeviceIdentity) {
        self.identity = identity
    }

    public func startAdvertising(tls: NWParameters, inviteCode: String? = nil) {
        stopAdvertising()
        do {
            let listener = try NWListener(using: tls)
            listener.service = Self.service(identity: identity, inviteCode: inviteCode)
            listener.stateUpdateHandler = { [weak self, weak listener] state in
                if case .ready = state {
                    let port = listener?.port?.rawValue ?? 0
                    ShareLog.i("bonjour", "listening on port \(port)")
                    DispatchQueue.main.async { self?.port = port }
                }
                if case .failed(let error) = state {
                    ShareLog.e("bonjour", "listener \(error)")
                }
            }
            listener.newConnectionHandler = { [weak self] connection in
                self?.onConnection?(connection)
            }
            listener.start(queue: DispatchQueue.global(qos: .userInitiated))
            self.listener = listener
        } catch {
            ShareLog.e("bonjour", "listen failed \(error.localizedDescription)")
        }
    }

    public func setInviteCode(_ code: String?) {
        listener?.service = Self.service(identity: identity, inviteCode: code)
    }

    private static func service(identity: DeviceIdentity, inviteCode: String?) -> NWListener.Service {
        var txt = NWTXTRecord()
        txt["v"] = "\(ProtocolConstants.version)"
        txt["id"] = identity.deviceId
        txt["name"] = String(identity.displayName.prefix(40))
        txt["os"] = "macos"
        if let inviteCode, !inviteCode.isEmpty {
            txt[ProtocolConstants.txtInvite] = inviteCode
            return NWListener.Service(name: ProtocolConstants.inviteServiceName(inviteCode), type: ProtocolConstants.serviceType, txtRecord: txt)
        }
        return NWListener.Service(name: "HS-\(identity.deviceId.prefix(8))", type: ProtocolConstants.serviceType, txtRecord: txt)
    }

    public func stopAdvertising() {
        listener?.cancel()
        listener = nil
    }

    public func startBrowse() {
        if browser != nil { return }
        beginBrowse()
    }

    public func restartBrowse() {
        stopBrowse()
        beginBrowse()
    }

    private func beginBrowse() {
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: ProtocolConstants.serviceType, domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            var next: [String: NearbyDevice] = [:]
            for result in results {
                var serviceName = ""
                if case .service(let name, _, _, _) = result.endpoint {
                    serviceName = name
                }
                var id = serviceName
                var display = serviceName.isEmpty ? "HONOR Share" : serviceName
                var os = "unknown"
                var invite = ProtocolConstants.inviteCode(fromServiceName: serviceName)
                var host: String?
                var port: UInt16?
                if case .bonjour(let txt) = result.metadata {
                    let dict = txt.dictionary
                    if let txtId = dict["id"], !txtId.isEmpty { id = txtId }
                    if let txtName = dict["name"], !txtName.isEmpty { display = txtName }
                    os = dict["os"] ?? os
                    if let txtInvite = dict[ProtocolConstants.txtInvite], !txtInvite.isEmpty {
                        invite = txtInvite
                    }
                    if let txtHost = dict[ProtocolConstants.txtHost], !txtHost.isEmpty {
                        host = txtHost
                    }
                    if let txtPort = dict[ProtocolConstants.txtPort], let parsed = UInt16(txtPort), parsed > 0 {
                        port = parsed
                    }
                }
                if id.isEmpty || id == self.identity.deviceId { continue }
                let endpoint: NWEndpoint
                if let host, let port {
                    endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!)
                } else {
                    endpoint = result.endpoint
                }
                next[id] = NearbyDevice(
                    id: id,
                    name: display,
                    os: os,
                    endpoint: endpoint,
                    lastSeen: Date(),
                    inviteCode: invite,
                    host: host,
                    port: port
                )
            }
            DispatchQueue.main.async {
                self.seen = next
                self.devices = next.values.sorted { $0.name < $1.name }
            }
        }
        browser.stateUpdateHandler = { state in
            if case .failed(let error) = state {
                ShareLog.e("bonjour", "browse \(error)")
            }
        }
        browser.start(queue: DispatchQueue.global(qos: .userInitiated))
        self.browser = browser
    }

    public func stopBrowse() {
        browser?.cancel()
        browser = nil
        DispatchQueue.main.async {
            self.devices = []
            self.seen = [:]
        }
    }
}
