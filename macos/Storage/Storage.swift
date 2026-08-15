import Foundation
import HonorShareProtocol

public final class FileReceiveSink: ReceiveSink {
    private let tempURL: URL
    public let finalURL: URL
    private var handle: FileHandle
    public private(set) var bytesWritten: Int64

    public init(tempURL: URL, finalURL: URL, offset: Int64) throws {
        self.tempURL = tempURL
        self.finalURL = finalURL
        if offset == 0, FileManager.default.fileExists(atPath: tempURL.path) {
            try FileManager.default.removeItem(at: tempURL)
        }
        if !FileManager.default.fileExists(atPath: tempURL.path) {
            FileManager.default.createFile(atPath: tempURL.path, contents: nil)
        }
        self.handle = try FileHandle(forWritingTo: tempURL)
        if offset > 0 { try handle.seek(toOffset: UInt64(offset)) }
        self.bytesWritten = offset
    }

    public func write(_ data: Data) throws {
        try handle.write(contentsOf: data)
        bytesWritten += Int64(data.count)
    }

    public func commit(expectedSha256: String) throws {
        try handle.close()
        if FileManager.default.fileExists(atPath: finalURL.path) {
            try FileManager.default.removeItem(at: finalURL)
        }
        try FileManager.default.moveItem(at: tempURL, to: finalURL)
    }

    public func abort() {
        try? handle.close()
        try? FileManager.default.removeItem(at: tempURL)
    }
}

public final class DirectorySinkFactory: ReceiveSinkFactory {
    public let root: URL
    public let directory: URL
    public var replaceNames: Set<String> = []
    public private(set) var openedURLs: [String: URL] = [:]

    public init(directory: URL, subfolder: String? = nil) {
        self.root = directory
        if let subfolder, !subfolder.isEmpty {
            self.directory = directory.appendingPathComponent(subfolder, isDirectory: true)
        } else {
            self.directory = directory
        }
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
        Self.cleanupStale(in: self.directory)
    }

    public func hasSpace(bytes: Int64) -> Bool {
        let values = try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        let available = values?.volumeAvailableCapacityForImportantUsage ?? Int64.max
        return available > bytes + 1_000_000
    }

    public func destinationIndex(matchingSizes: Set<Int64>) -> [DestinationFile] {
        Self.regularFiles(in: directory).compactMap { url in
            let size = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
            guard matchingSizes.contains(size) else { return nil }
            let hash = try? FileHasher.sha256Hex(url: url)
            return DestinationFile(name: url.lastPathComponent, size: size, sha256: hash)
        }
    }

    public func availableBytes() -> Int64 {
        let values = try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    public func open(file: FileMeta, offset: Int64) throws -> ReceiveSink {
        let replace = replaceNames.contains(file.name)
        let name: String
        if replace {
            name = file.name
        } else {
            name = try FilenameConflict.uniqueName(file.name) {
                FileManager.default.fileExists(atPath: directory.appendingPathComponent($0).path)
            }
        }
        let temp = directory.appendingPathComponent(".\(name)\(ProtocolConstants.partialSuffix)")
        let final = directory.appendingPathComponent(name)
        openedURLs[file.fileId] = final
        return try FileReceiveSink(tempURL: temp, finalURL: final, offset: offset)
    }

    public static func cleanupStale(in directory: URL) {
        for file in regularFiles(in: directory) where file.lastPathComponent.contains(ProtocolConstants.partialSuffix) {
            try? FileManager.default.removeItem(at: file)
        }
    }

    public static func defaultDirectory() -> URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(ProtocolConstants.receiveFolder)
    }

    public static func regularFiles(in directory: URL) -> [URL] {
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
