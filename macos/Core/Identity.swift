import Foundation
import Security
import CryptoKit
import HonorShareProtocol

public struct DeviceIdentity {
    public var deviceId: String
    public var displayName: String
    public var fingerprint: String
    public var secIdentity: SecIdentity
}

public enum ShareLog {
    public static var debugEnabled = false
    public static func d(_ tag: String, _ message: String) {
        if debugEnabled { print("HonorShare/\(tag) DEBUG \(sanitize(message))") }
    }
    public static func i(_ tag: String, _ message: String) { print("HonorShare/\(tag) INFO \(sanitize(message))") }
    public static func w(_ tag: String, _ message: String) { print("HonorShare/\(tag) WARN \(sanitize(message))") }
    public static func e(_ tag: String, _ message: String) { print("HonorShare/\(tag) ERROR \(sanitize(message))") }
    private static func sanitize(_ message: String) -> String {
        message.replacingOccurrences(of: #"(?i)(token|secret|password|private key)\s*[=:]\s*\S+"#, with: "$1=*", options: .regularExpression)
    }
}

public enum DeviceIdentityStore {
    private static let keyTag = "com.honor.share.identity"
    private static let idKey = "deviceId"

    public static func loadOrCreate() throws -> DeviceIdentity {
        let defaults = UserDefaults.standard
        let deviceId = defaults.string(forKey: idKey) ?? {
            let id = UUID().uuidString
            defaults.set(id, forKey: idKey)
            return id
        }()
        let name = Host.current().localizedName ?? "Mac"
        if let existing = loadIdentity(deviceId: deviceId, name: name) {
            return existing
        }
        return try generate(deviceId: deviceId, name: name)
    }

    public static func trust(deviceId: String, fingerprint: String, name: String) {
        UserDefaults.standard.set(fingerprint, forKey: "pin.\(deviceId)")
        UserDefaults.standard.set(name, forKey: "pin.name.\(deviceId)")
    }

    public static func pin(for deviceId: String) -> String? {
        UserDefaults.standard.string(forKey: "pin.\(deviceId)")
    }

    private static func generate(deviceId: String, name: String) throws -> DeviceIdentity {
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: keyTag.data(using: .utf8)!,
            ],
        ]
        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error),
              let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw ProtocolError(.protocolViolation, "key generation failed")
        }
        let cert = try SelfSignedCert.make(cn: "HONOR Share", privateKey: privateKey, publicKey: publicKey)
        let add: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: cert,
            kSecAttrLabel as String: "HONOR Share",
        ]
        SecItemDelete(add as CFDictionary)
        SecItemAdd(add as CFDictionary, nil)
        var identity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(nil, cert, &identity)
        guard status == errSecSuccess, let identity else {
            throw ProtocolError(.protocolViolation, "identity create failed \(status)")
        }
        let der = SecCertificateCopyData(cert) as Data
        let fingerprint = Checksums.sha256Hex(der)
        UserDefaults.standard.set(fingerprint, forKey: "fingerprint")
        return DeviceIdentity(deviceId: deviceId, displayName: String(name.prefix(40)), fingerprint: fingerprint, secIdentity: identity)
    }

    private static func loadIdentity(deviceId: String, name: String) -> DeviceIdentity? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecAttrLabel as String: "HONOR Share",
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let identity = item else { return nil }
        let secIdentity = identity as! SecIdentity
        var cert: SecCertificate?
        SecIdentityCopyCertificate(secIdentity, &cert)
        guard let cert else { return nil }
        let der = SecCertificateCopyData(cert) as Data
        return DeviceIdentity(
            deviceId: deviceId,
            displayName: String(name.prefix(40)),
            fingerprint: Checksums.sha256Hex(der),
            secIdentity: secIdentity
        )
    }
}

enum SelfSignedCert {
    static func make(cn: String, privateKey: SecKey, publicKey: SecKey) throws -> SecCertificate {
        guard let pkcs1 = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            throw ProtocolError(.protocolViolation, "public key export failed")
        }
        let spki = wrapRSAPublicKeySPKI(pkcs1)
        let serial = Data((0..<8).map { _ in UInt8.random(in: 0...255) })
        let tbs = tbsCertificate(cn: cn, serial: serial, spki: spki)
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(privateKey, .rsaSignatureMessagePKCS1v15SHA256, tbs as CFData, &error) as Data? else {
            throw ProtocolError(.protocolViolation, "sign failed")
        }
        let certDer = Der.sequence([
            tbs,
            Der.sequence([Der.oid([1, 2, 840, 113549, 1, 1, 11]), Der.nullValue()]),
            Der.bitString(signature),
        ])
        guard let cert = SecCertificateCreateWithData(nil, certDer as CFData) else {
            throw ProtocolError(.protocolViolation, "certificate parse failed")
        }
        return cert
    }

    private static func wrapRSAPublicKeySPKI(_ pkcs1: Data) -> Data {
        Der.sequence([
            Der.sequence([Der.oid([1, 2, 840, 113549, 1, 1, 1]), Der.nullValue()]),
            Der.bitString(pkcs1),
        ])
    }

    private static func tbsCertificate(cn: String, serial: Data, spki: Data) -> Data {
        let name = Der.sequence([
            Der.set([
                Der.sequence([Der.oid([2, 5, 4, 3]), Der.utf8(cn)]),
            ]),
        ])
        let now = Date()
        let validity = Der.sequence([Der.utcTime(now.addingTimeInterval(-60)), Der.utcTime(now.addingTimeInterval(10 * 365 * 24 * 3600))])
        return Der.sequence([
            Der.context(0, Der.integer(Data([0x02]))),
            Der.integer(serial),
            Der.sequence([Der.oid([1, 2, 840, 113549, 1, 1, 11]), Der.nullValue()]),
            name,
            validity,
            name,
            spki,
        ])
    }
}

enum Der {
    static func sequence(_ parts: [Data]) -> Data { tlv(0x30, concat(parts)) }
    static func set(_ parts: [Data]) -> Data { tlv(0x31, concat(parts)) }
    static func integer(_ value: Data) -> Data {
        var bytes = [UInt8](value)
        if let first = bytes.first, first >= 0x80 {
            bytes.insert(0x00, at: 0)
        }
        while bytes.count > 1 && bytes[0] == 0 && bytes[1] < 0x80 {
            bytes.removeFirst()
        }
        return tlv(0x02, Data(bytes))
    }
    static func oid(_ parts: [UInt]) -> Data {
        var out = Data()
        out.append(UInt8(40 * parts[0] + parts[1]))
        for part in parts.dropFirst(2) {
            out.append(contentsOf: base128(part))
        }
        return tlv(0x06, out)
    }
    static func utf8(_ value: String) -> Data { tlv(0x0C, Data(value.utf8)) }
    static func bitString(_ value: Data) -> Data { tlv(0x03, Data([0]) + value) }
    static func nullValue() -> Data { Data([0x05, 0x00]) }
    static func utcTime(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return tlv(0x17, Data(formatter.string(from: date).utf8))
    }
    static func context(_ number: Int, _ content: Data) -> Data { tlv(0xA0 | number, content) }
    private static func tlv(_ tag: Int, _ content: Data) -> Data {
        var out = Data([UInt8(tag)])
        out.append(lengthBytes(content.count))
        out.append(content)
        return out
    }
    private static func lengthBytes(_ length: Int) -> Data {
        if length < 128 { return Data([UInt8(length)]) }
        var value = length
        var bytes: [UInt8] = []
        while value > 0 {
            bytes.insert(UInt8(value & 0xFF), at: 0)
            value >>= 8
        }
        return Data([UInt8(0x80 | bytes.count)] + bytes)
    }
    private static func concat(_ parts: [Data]) -> Data {
        parts.reduce(into: Data()) { $0.append($1) }
    }
    private static func base128(_ value: UInt) -> [UInt8] {
        if value < 128 { return [UInt8(value)] }
        var stack: [UInt8] = [UInt8(value & 0x7F)]
        var v = value >> 7
        while v > 0 {
            stack.insert(UInt8((v & 0x7F) | 0x80), at: 0)
            v >>= 7
        }
        return stack
    }
}
