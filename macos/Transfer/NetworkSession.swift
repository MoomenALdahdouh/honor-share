import Foundation
import Network
import Security
import HonorShareCore
import HonorShareProtocol

public final class NWBytePipe {
    private let connection: NWConnection
    private var buffer = Data()
    private let lock = NSLock()
    private var waiters: [CheckedContinuation<Void, Error>] = []
    private var finished = false
    private var finishError: Error?

    public init(connection: NWConnection) {
        self.connection = connection
        receiveLoop()
    }

    public func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            })
        }
    }

    public func readExact(_ count: Int) async throws -> Data {
        while true {
            lock.lock()
            if buffer.count >= count {
                let slice = buffer.prefix(count)
                buffer.removeSubrange(0..<count)
                lock.unlock()
                return Data(slice)
            }
            if finished {
                let error = finishError ?? ProtocolError(.connectionLost, "closed")
                lock.unlock()
                throw error
            }
            lock.unlock()
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                lock.lock()
                if buffer.count >= count {
                    lock.unlock()
                    cont.resume()
                    return
                }
                if finished {
                    let error = finishError ?? ProtocolError(.connectionLost, "closed")
                    lock.unlock()
                    cont.resume(throwing: error)
                    return
                }
                waiters.append(cont)
                lock.unlock()
            }
        }
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] content, _, isComplete, error in
            guard let self else { return }
            self.lock.lock()
            if let content { self.buffer.append(content) }
            let waiters = self.waiters
            self.waiters.removeAll()
            if let error {
                self.finished = true
                self.finishError = error
                self.lock.unlock()
                waiters.forEach { $0.resume(throwing: error) }
                return
            }
            if isComplete {
                self.finished = true
                self.finishError = ProtocolError(.connectionLost, "closed")
                self.lock.unlock()
                waiters.forEach { $0.resume(throwing: self.finishError!) }
                return
            }
            self.lock.unlock()
            waiters.forEach { $0.resume() }
            self.receiveLoop()
        }
    }
}

public func tlsParameters(identity: DeviceIdentity, pin: String?, ipv4Only: Bool = false, onPeerFingerprint: @escaping (String) -> Void) -> NWParameters {
    _ = pin
    let tls = NWProtocolTLS.Options()
    sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
    if let secIdentity = sec_identity_create(identity.secIdentity) {
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, secIdentity)
    }
    sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, true)
    sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
        let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
        if let cert = SecTrustGetCertificateAtIndex(secTrust, 0) {
            let der = SecCertificateCopyData(cert) as Data
            onPeerFingerprint(Checksums.sha256Hex(der))
        }
        complete(true)
    }, DispatchQueue.global())
    let tcp = NWProtocolTCP.Options()
    tcp.connectionTimeout = 8
    let params = NWParameters(tls: tls, tcp: tcp)
    params.includePeerToPeer = true
    params.allowLocalEndpointReuse = true
    if ipv4Only, let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
        ip.version = .v4
    }
    return params
}
