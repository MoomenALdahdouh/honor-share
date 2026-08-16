import Foundation
import HonorShareProtocol

public struct HistoryItem: Codable, Identifiable, Equatable {
    public var id: String
    public var direction: String
    public var deviceName: String
    public var fileCount: Int
    public var totalBytes: Int64
    public var status: String
    public var createdAt: TimeInterval

    public init(id: String, direction: String, deviceName: String, fileCount: Int, totalBytes: Int64, status: String, createdAt: TimeInterval) {
        self.id = id
        self.direction = direction
        self.deviceName = deviceName
        self.fileCount = fileCount
        self.totalBytes = totalBytes
        self.status = status
        self.createdAt = createdAt
    }
}

public struct SharedFileRecord: Codable, Identifiable, Equatable {
    public var id: String
    public var name: String
    public var size: Int64
    public var mime: String
    public var direction: String
    public var deviceName: String
    public var createdAt: TimeInterval
    public var path: String
    public var bookmark: Data?

    public init(id: String, name: String, size: Int64, mime: String, direction: String, deviceName: String, createdAt: TimeInterval, path: String, bookmark: Data? = nil) {
        self.id = id
        self.name = name
        self.size = size
        self.mime = mime
        self.direction = direction
        self.deviceName = deviceName
        self.createdAt = createdAt
        self.path = path
        self.bookmark = bookmark
    }
}

public enum FileKind: String, Codable {
    case photo, video, audio, document, other
}

public struct LibraryFile: Identifiable, Hashable {
    public var id: String
    public var name: String
    public var url: URL
    public var size: Int64
    public var modified: Date
    public var direction: String
    public var deviceName: String
    public var mime: String
    public var kind: FileKind
    public var bookmark: Data?
    public var relativePath: String

    public init(id: String, name: String, url: URL, size: Int64, modified: Date, direction: String, deviceName: String, mime: String, kind: FileKind, bookmark: Data? = nil, relativePath: String = "") {
        self.id = id
        self.name = name
        self.url = url
        self.size = size
        self.modified = modified
        self.direction = direction
        self.deviceName = deviceName
        self.mime = mime
        self.kind = kind
        self.bookmark = bookmark
        self.relativePath = relativePath
    }

    public var parentPath: String {
        let parts = relativePath.split(separator: "/").map(String.init)
        guard parts.count > 1 else { return "" }
        return parts.dropLast().joined(separator: "/")
    }
}

public enum FileFilter: String, CaseIterable, Identifiable {
    case all, received, sent, photos, videos, documents
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .all: return "All"
        case .received: return "Received"
        case .sent: return "Sent"
        case .photos: return "Photos"
        case .videos: return "Videos"
        case .documents: return "Documents"
        }
    }
}

public enum FileGroupBy: String, CaseIterable, Identifiable {
    case date, type, device, none
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .date: return "Date"
        case .type: return "Type"
        case .device: return "Device"
        case .none: return "None"
        }
    }
}

public enum FileSort: String, CaseIterable, Identifiable {
    case newest, oldest, name, size
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .newest: return "Newest"
        case .oldest: return "Oldest"
        case .name: return "Name"
        case .size: return "Size"
        }
    }
}

public func fileKind(name: String, mime: String) -> FileKind {
    let ext = (name as NSString).pathExtension.lowercased()
    let lower = mime.lowercased()
    if lower.hasPrefix("image/") || ["jpg", "jpeg", "png", "gif", "webp", "heic", "heif"].contains(ext) { return .photo }
    if lower.hasPrefix("video/") || ["mp4", "mov", "mkv", "webm"].contains(ext) { return .video }
    if lower.hasPrefix("audio/") || ["mp3", "m4a", "wav", "aac"].contains(ext) { return .audio }
    if lower == "application/pdf" || ["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv"].contains(ext) { return .document }
    return .other
}

public final class HistoryStore: ObservableObject {
    @Published public var items: [HistoryItem] = []
    @Published public var files: [SharedFileRecord] = []
    private let url: URL
    private let filesURL: URL

    public init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("HONORShare")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("history.json")
        filesURL = dir.appendingPathComponent("files.json")
        load()
    }

    public func add(_ item: HistoryItem) {
        items.insert(item, at: 0)
        save()
    }

    public func addFiles(_ records: [SharedFileRecord]) {
        files.insert(contentsOf: records, at: 0)
        saveFiles()
    }

    public func removeFile(id: String) {
        files.removeAll { $0.id == id }
        saveFiles()
    }

    public func clear() {
        items = []
        files = []
        save()
        saveFiles()
    }

    private func load() {
        if let data = try? Data(contentsOf: url) {
            items = (try? JSONDecoder().decode([HistoryItem].self, from: data)) ?? []
        }
        if let data = try? Data(contentsOf: filesURL) {
            files = (try? JSONDecoder().decode([SharedFileRecord].self, from: data)) ?? []
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(items) {
            try? data.write(to: url)
        }
    }

    private func saveFiles() {
        if let data = try? JSONEncoder().encode(files) {
            try? data.write(to: filesURL)
        }
    }
}

public enum FileLibrary {
    @discardableResult
    public static func recoverCompletedPartials(destination: URL, records: [SharedFileRecord]) -> Int {
        let suffix = ProtocolConstants.partialSuffix
        guard let enumerator = FileManager.default.enumerator(
            at: destination,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: []
        ) else { return 0 }
        var recovered = 0
        for case let url as URL in enumerator {
            let name = url.lastPathComponent
            guard name.hasPrefix("."), name.contains(suffix) else { continue }
            var finalName = String(name.dropFirst())
            if finalName.hasSuffix(suffix) {
                finalName = String(finalName.dropLast(suffix.count))
            }
            guard !finalName.isEmpty else { continue }
            let final = url.deletingLastPathComponent().appendingPathComponent(finalName)
            if FileManager.default.fileExists(atPath: final.path) { continue }
            let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            let match = records.first {
                $0.direction == "RECEIVED" && ($0.path == final.path || ($0.name == finalName && $0.size == size))
            }
            guard let match, match.size == size, size > 0 else { continue }
            do {
                try FileManager.default.moveItem(at: url, to: final)
                recovered += 1
            } catch {
                continue
            }
        }
        return recovered
    }

    public static func scan(destination: URL, records: [SharedFileRecord]) -> [LibraryFile] {
        var result: [LibraryFile] = []
        let destPath = destination.standardizedFileURL.path
        let files = regularFiles(in: destination)
        for url in files {
            if url.lastPathComponent.contains(ProtocolConstants.partialSuffix) { continue }
            if url.lastPathComponent.hasPrefix(".") { continue }
            let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            let size = Int64(values?.fileSize ?? 0)
            let modified = values?.contentModificationDate ?? Date()
            let relative = relativePath(of: url, under: destPath)
            let match = records.first { $0.path == url.path || ($0.name == url.lastPathComponent && $0.size == size) }
            result.append(LibraryFile(
                id: match?.id ?? url.path,
                name: url.lastPathComponent,
                url: url,
                size: size,
                modified: modified,
                direction: match?.direction ?? "RECEIVED",
                deviceName: match?.deviceName ?? "",
                mime: match?.mime ?? "",
                kind: fileKind(name: url.lastPathComponent, mime: match?.mime ?? ""),
                bookmark: match?.bookmark,
                relativePath: relative
            ))
        }
        for record in records where record.direction == "RECEIVED" {
            if result.contains(where: { $0.id == record.id || $0.url.path == record.path }) { continue }
            let url = URL(fileURLWithPath: record.path)
            guard FileManager.default.fileExists(atPath: url.path) else { continue }
            let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            result.append(LibraryFile(
                id: record.id,
                name: record.name,
                url: url,
                size: record.size,
                modified: values?.contentModificationDate ?? Date(timeIntervalSince1970: record.createdAt),
                direction: "RECEIVED",
                deviceName: record.deviceName,
                mime: record.mime,
                kind: fileKind(name: record.name, mime: record.mime),
                bookmark: record.bookmark,
                relativePath: relativePath(of: url, under: destPath)
            ))
        }
        for record in records where record.direction == "SENT" {
            if result.contains(where: { $0.id == record.id || $0.url.path == record.path }) { continue }
            let url = URL(fileURLWithPath: record.path)
            result.append(LibraryFile(
                id: record.id,
                name: record.name,
                url: url,
                size: record.size,
                modified: Date(timeIntervalSince1970: record.createdAt),
                direction: "SENT",
                deviceName: record.deviceName,
                mime: record.mime,
                kind: fileKind(name: record.name, mime: record.mime),
                bookmark: record.bookmark,
                relativePath: record.name
            ))
        }
        return result.sorted { $0.modified > $1.modified }
    }

    public static func folders(in files: [LibraryFile], at parent: String) -> [String] {
        var names = Set<String>()
        let prefix = parent.isEmpty ? "" : parent + "/"
        for file in files where file.direction != "SENT" {
            let path = file.relativePath
            guard path.hasPrefix(prefix) else { continue }
            let rest = String(path.dropFirst(prefix.count))
            let parts = rest.split(separator: "/").map(String.init)
            if parts.count > 1 { names.insert(parts[0]) }
        }
        return names.sorted()
    }

    public static func files(in files: [LibraryFile], at parent: String, sent: Bool) -> [LibraryFile] {
        files.filter { file in
            if sent { return file.direction == "SENT" && parent.isEmpty }
            return file.direction != "SENT" && file.parentPath == parent
        }
    }

    private static func relativePath(of url: URL, under destPath: String) -> String {
        let path = url.standardizedFileURL.path
        if path.hasPrefix(destPath + "/") {
            return String(path.dropFirst(destPath.count + 1))
        }
        return url.lastPathComponent
    }

    private static func regularFiles(in directory: URL) -> [URL] {
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else { return [] }
        var files: [URL] = []
        for case let url as URL in enumerator {
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isDirectoryKey])
            if values?.isDirectory == true { continue }
            if values?.isRegularFile == true { files.append(url) }
        }
        return files
    }
}
