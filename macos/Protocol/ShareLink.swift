import Foundation

public struct ShareLink: Equatable {
    public var host: String
    public var port: Int
    public var id: String
    public var name: String
    public var os: String

    public init(host: String, port: Int, id: String, name: String, os: String) {
        self.host = host
        self.port = port
        self.id = id
        self.name = name
        self.os = os
    }

    public func encode() -> String {
        let encodedName = name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? name
        return ["HS1", host, "\(port)", id, os, encodedName].joined(separator: "|")
    }

    public static func parse(_ raw: String) -> ShareLink? {
        let parts = raw.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 6, parts[0] == "HS1" else { return nil }
        guard let port = Int(parts[2]), (1...65535).contains(port) else { return nil }
        let host = parts[1]
        guard !host.isEmpty, host.rangeOfCharacter(from: .whitespaces) == nil else { return nil }
        let encoded = parts.dropFirst(5).joined(separator: "|")
        let name = encoded.removingPercentEncoding ?? encoded
        return ShareLink(host: host, port: port, id: parts[3].isEmpty ? host : parts[3], name: name.isEmpty ? host : name, os: parts[4].isEmpty ? "unknown" : parts[4])
    }
}
