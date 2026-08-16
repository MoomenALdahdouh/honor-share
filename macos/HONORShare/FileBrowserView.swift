import SwiftUI
import AppKit
import HonorShareTransfer
import HonorShareHistory
import HonorShareProtocol

struct FileBrowserView: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    @State private var filter: FileFilter = .all
    @State private var folder = ""

    private var filtered: [LibraryFile] {
        var files = model.library
        switch filter {
        case .all: break
        case .received: files = files.filter { $0.direction == "RECEIVED" }
        case .sent: files = files.filter { $0.direction == "SENT" }
        case .photos: files = files.filter { $0.kind == .photo }
        case .videos: files = files.filter { $0.kind == .video }
        case .documents: files = files.filter { $0.kind == .document }
        }
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !needle.isEmpty {
            files = files.filter {
                $0.name.lowercased().contains(needle) || $0.relativePath.lowercased().contains(needle)
            }
        }
        return files
    }

    private var searching: Bool { !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    private var folders: [String] { searching ? [] : FileLibrary.folders(in: filtered, at: folder) }
    private var filesHere: [LibraryFile] {
        if searching { return filtered }
        if filter == .sent { return FileLibrary.files(in: filtered, at: "", sent: true) }
        return FileLibrary.files(in: filtered, at: folder, sent: false)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Files").font(.title2.weight(.semibold))
                Spacer()
                Text("\(filtered.count) \(filtered.count == 1 ? "file" : "files")").foregroundStyle(.secondary)
            }
            if !folder.isEmpty && !searching {
                HStack(spacing: 6) {
                    Button("Files") { folder = "" }.buttonStyle(.link)
                    ForEach(crumbs, id: \.path) { crumb in
                        Text("/").foregroundStyle(.secondary)
                        Button(crumb.name) { folder = crumb.path }.buttonStyle(.link)
                    }
                }
            }
            TextField("Search files", text: $query)
                .textFieldStyle(.roundedBorder)
            HStack(spacing: 8) {
                ForEach([FileFilter.all, .received, .sent], id: \.self) { item in
                    FilterPill(title: item.title, selected: filter == item) {
                        filter = item
                        if item == .sent { folder = "" }
                    }
                }
                Spacer()
                ForEach([FileFilter.photos, .videos, .documents], id: \.self) { item in
                    FilterPill(title: item.title, selected: filter == item) {
                        filter = item
                    }
                }
            }
            if folders.isEmpty && filesHere.isEmpty {
                VStack(spacing: 8) {
                    Spacer()
                    Image(systemName: "folder")
                        .font(.system(size: 36))
                        .foregroundStyle(.secondary)
                    Text("No files yet").font(.headline)
                    Text("Send or receive files and they will show up here so you can open them instantly.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 360)
                    Button("Send files") { model.showFiles = false }
                        .buttonStyle(.borderedProminent)
                        .tint(HonorColor.blue)
                    Spacer()
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(folders, id: \.self) { name in
                            FolderRow(name: name) {
                                folder = folder.isEmpty ? name : folder + "/" + name
                            }
                        }
                        ForEach(filesHere) { file in
                            FileRow(file: file, showPath: searching)
                        }
                    }
                    .padding(.bottom, 12)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear { model.refreshLibrary() }
        .onChange(of: model.showFiles) { shown in
            if shown { model.refreshLibrary() }
        }
    }

    private var crumbs: [(name: String, path: String)] {
        var path = ""
        return folder.split(separator: "/").map { part in
            path = path.isEmpty ? String(part) : path + "/" + part
            return (String(part), path)
        }
    }
}

private struct FilterPill: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.callout.weight(.medium))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(selected ? HonorColor.blue : Color(nsColor: .controlBackgroundColor), in: Capsule())
                .foregroundStyle(selected ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
    }
}

private struct FolderRow: View {
    let name: String
    let open: () -> Void
    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                Image(systemName: "folder.fill")
                    .font(.title2)
                    .foregroundStyle(.tint)
                    .frame(width: 36, height: 36)
                Text(name).font(.body.weight(.medium))
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct FileRow: View {
    @EnvironmentObject var model: AppModel
    let file: LibraryFile
    var showPath = false
    var body: some View {
        HStack(spacing: 12) {
            FileIcon(file: file).frame(width: 36, height: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(file.name).lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Button("Show in Finder") { model.revealInFinder(file) }
                .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { model.openFile(file) }
        .contextMenu {
            Button("Open") { model.openFile(file) }
            Button("Show in Finder") { model.revealInFinder(file) }
            if file.direction == "RECEIVED" {
                Button("Move to Trash", role: .destructive) { model.deleteFile(file) }
            }
        }
    }

    private var subtitle: String {
        let kind = file.direction == "SENT" ? "Sent" : "Received"
        let size = ByteFormat.humanSize(file.size)
        if showPath, !file.relativePath.isEmpty, file.relativePath != file.name {
            return "\(size) · \(kind) · \(file.relativePath)"
        }
        return "\(size) · \(kind)"
    }
}

private struct FileIcon: View {
    let file: LibraryFile
    var body: some View {
        if file.kind == .photo, let nsImage = NSImage(contentsOf: file.url) {
            Image(nsImage: nsImage)
                .resizable()
                .scaledToFill()
                .frame(width: 36, height: 36)
                .clipped()
                .cornerRadius(8)
        } else {
            Image(systemName: symbol)
                .font(.title2)
                .foregroundStyle(.tint)
        }
    }

    private var symbol: String {
        switch file.kind {
        case .photo: return "photo"
        case .video: return "video"
        case .audio: return "waveform"
        case .document: return "doc.text"
        case .other: return "doc"
        }
    }
}
