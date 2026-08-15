import SwiftUI
import AppKit
import HonorShareTransfer
import HonorShareProtocol
import HonorShareStorage
import HonorShareDiscovery
import UniformTypeIdentifiers

extension Notification.Name {
    static let honorShareSend = Notification.Name("honorShareSend")
    static let honorShareOpenFiles = Notification.Name("honorShareOpenFiles")
}

final class HonorShareAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.servicesProvider = HonorShareServices.shared
        NSUpdateDynamicServices()
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        NotificationCenter.default.post(name: .honorShareOpenFiles, object: urls)
    }
}

final class HonorShareServices: NSObject {
    static let shared = HonorShareServices()

    @objc func sendWithHonorShare(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        var urls: [URL] = []
        if let objects = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] {
            urls = objects
        }
        if urls.isEmpty, let paths = pboard.propertyList(forType: .init("NSFilenamesPboardType")) as? [String] {
            urls = paths.map { URL(fileURLWithPath: $0) }
        }
        guard !urls.isEmpty else { return }
        DispatchQueue.main.async {
            NSApp.activate(ignoringOtherApps: true)
            NotificationCenter.default.post(name: .honorShareOpenFiles, object: urls)
        }
    }
}

@main
struct HONORShareApp: App {
    @NSApplicationDelegateAdaptor(HonorShareAppDelegate.self) var appDelegate
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .frame(minWidth: 720, minHeight: 560)
        }
        .windowStyle(.hiddenTitleBar)

        MenuBarExtra("HONOR Share", systemImage: "iphone") {
            VStack(alignment: .leading, spacing: 8) {
                Text("HONOR Share").font(.headline)
                HStack {
                    Circle().fill(Color.green).frame(width: 8, height: 8)
                    Text("Ready")
                }
                Divider()
                Button("Send files…") {
                    NSApp.activate(ignoringOtherApps: true)
                    NotificationCenter.default.post(name: .honorShareSend, object: nil)
                }
                Button("Open HONOR Share") {
                    NSApp.activate(ignoringOtherApps: true)
                }
                Button("Settings") {
                    NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
                    NSApp.activate(ignoringOtherApps: true)
                }
                Divider()
                Button("Quit") { NSApp.terminate(nil) }
            }
            .padding()
        }

        Settings {
            SettingsView()
                .environmentObject(model)
                .frame(width: 360, height: 220)
        }
    }
}

struct ContentView: View {
    @EnvironmentObject var model: AppModel

    private var inFlow: Bool {
        model.showFiles
            || model.pendingPackage != nil
            || model.lookingForCode
            || model.connectingToPhone
            || model.pairingCode != nil
            || model.incoming != nil
            || model.progress != nil
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                if inFlow {
                    Button(action: goBack) {
                        Label("Back", systemImage: "chevron.left")
                    }
                    .buttonStyle(.bordered)
                    .keyboardShortcut("[", modifiers: [.command])
                    .help("Go back")
                }
                Text("HONOR Share")
                    .font(.title3.weight(.semibold))
                Spacer()
                if model.pendingPackage != nil && model.pairingCode == nil && model.incoming == nil {
                    Button("New code") { model.regenerateInvitation() }
                        .buttonStyle(.bordered)
                        .help("Create a new QR code and number")
                }
                if !inFlow || model.showFiles {
                    Picker("Section", selection: $model.showFiles) {
                        Text("Transfer").tag(false)
                        Text("Files").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 220)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 18)
            .padding(.bottom, 8)
            if model.showFiles {
                FileBrowserView()
            } else {
                TransferPane()
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .frame(minWidth: 720, minHeight: 620)
    }

    private func goBack() {
        if model.pairingCode != nil {
            model.confirmPairing(false)
        } else if model.incoming != nil {
            model.confirmIncoming(false)
        } else if model.lookingForCode || model.connectingToPhone {
            model.cancelCodeLookup()
        } else if model.progress != nil {
            model.finishCompleted()
        } else if model.pendingPackage != nil {
            model.cancelPendingSend()
        } else {
            model.showFiles = false
        }
    }
}

struct TransferPane: View {
    @EnvironmentObject var model: AppModel
    @State private var hovering = false
    @State private var showScanner = false
    @State private var link = ""

    var body: some View {
        Group {
            if let code = model.pairingCode {
                pairing(code: code)
            } else if let incoming = model.incoming {
                incomingView(incoming)
            } else if let progress = model.progress, progress.state == .transferring {
                progressView(progress)
            } else if let pkg = model.pendingPackage, let invite = pkg.invitation {
                waitingToSend(pkg: pkg, invite: invite)
            } else if let progress = model.progress, progress.state == .completed || progress.state == .failed || progress.state == .cancelled {
                progressView(progress)
            } else {
                homeTransfer
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color(nsColor: .windowBackgroundColor))
        .onAppear { link = model.shareLink() }
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { _ in
            link = model.shareLink()
        }
        .onDrop(of: [.fileURL], isTargeted: $hovering) { providers in
            handleDrop(providers)
        }
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(hovering ? Color.accentColor : Color.clear, lineWidth: 2)
        )
        .onReceive(NotificationCenter.default.publisher(for: .honorShareSend)) { _ in
            pickFiles()
        }
        .onReceive(NotificationCenter.default.publisher(for: .honorShareOpenFiles)) { note in
            if let urls = note.object as? [URL], !urls.isEmpty {
                model.preparePackage(urls: urls)
            }
        }
        .sheet(isPresented: $showScanner) {
            QrScannerSheet(
                onCode: { raw in
                    showScanner = false
                    if let invite = PackageInvitation.parse(raw) {
                        model.receive(invite: invite)
                    } else {
                        model.send(fromQR: raw, urls: model.pendingSendURLs)
                    }
                },
                onCancel: { showScanner = false }
            )
        }
    }

    private var homeTransfer: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Send to phone")
                        .font(.headline)
                    Text("Drop photos here. Your phone scans the QR code that appears next.")
                        .foregroundStyle(.secondary)
                    VStack(spacing: 8) {
                        Text(hovering ? "Drop to send" : "Drop files here")
                            .font(.title2.weight(.medium))
                        Text("or")
                            .foregroundStyle(.secondary)
                        Button("Choose Files") { pickFiles() }
                            .keyboardShortcut("s", modifiers: [.command])
                    }
                    .frame(maxWidth: .infinity, minHeight: 110)
                    .padding()
                    .background(hovering ? Color.accentColor.opacity(0.08) : Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 16))
                }

                Divider()

                VStack(alignment: .leading, spacing: 8) {
                    Text("Receive from phone")
                        .font(.headline)
                    Text("On your phone tap Send files, then Ready to send. Type that code here, or scan the QR on the phone.")
                        .foregroundStyle(.secondary)
                    HStack {
                        TextField("000 000", text: $model.receiveCode)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 120)
                            .onChange(of: model.receiveCode) { value in
                                let digits = String(value.filter(\.isNumber).prefix(6))
                                let formatted = digits.count > 3 ? String(digits.prefix(3)) + " " + String(digits.dropFirst(3)) : digits
                                if formatted != value { model.receiveCode = formatted }
                                if digits.count == 6 && !model.lookingForCode && !model.connectingToPhone {
                                    model.connectWithCode(digits)
                                }
                            }
                        Button(model.lookingForCode ? "Looking…" : "Connect") {
                            model.connectWithCode(model.receiveCode)
                        }
                        .disabled(model.lookingForCode || model.connectingToPhone || model.receiveCode.filter(\.isNumber).count != 6)
                        .keyboardShortcut(.defaultAction)
                        if model.lookingForCode || model.connectingToPhone {
                            Button("Cancel") { model.cancelCodeLookup() }
                        }
                    }
                    if model.lookingForCode {
                        HStack(spacing: 8) {
                            ProgressView()
                                .controlSize(.small)
                            Text("Looking for your phone…")
                                .foregroundStyle(.secondary)
                        }
                    } else if model.connectingToPhone {
                        HStack(spacing: 8) {
                            ProgressView()
                                .controlSize(.small)
                            Text("Connecting to your phone…")
                                .foregroundStyle(.secondary)
                        }
                    }
                    Button("Scan QR on your phone") { showScanner = true }
                        .buttonStyle(.link)
                }

                if let peer = model.connectedPeer {
                    Label("Connected to \(peer.name)", systemImage: "circle.fill")
                        .foregroundStyle(.green)
                }

                if let error = model.errorMessage {
                    Text(error).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
                }

                if model.localNetworkDenied {
                    Text("HONOR Share needs local network access to find your phone and transfer files directly.")
                    Button("Open System Settings") { model.openSystemSettings() }
                }
            }
            .frame(maxWidth: 520, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .center)
        }
    }

    private func waitingToSend(pkg: TransferPackage, invite: PackageInvitation) -> some View {
        ScrollView {
            VStack(spacing: 14) {
                Text("Send to phone")
                    .font(.title2.weight(.semibold))
                Text("\(pkg.files.count) \(pkg.files.count == 1 ? "file" : "files") · \(ByteFormat.humanSize(pkg.totalBytes))")
                    .foregroundStyle(.secondary)
                QrImageView(text: invite.encode())
                    .frame(width: 200, height: 200)
                Text(invite.displayCode())
                    .font(.system(size: 36, weight: .semibold, design: .rounded).monospacedDigit())
                Text("On your phone, open HONOR Share and tap Receive, then scan this QR code.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 420)
                if invite.isExpired() {
                    Text("This code has expired.")
                        .foregroundStyle(.red)
                } else {
                    Text("Expires in \(String(format: "%d:%02d", invite.remainingSeconds() / 60, invite.remainingSeconds() % 60))")
                        .foregroundStyle(.secondary)
                }
                if let peer = model.connectedPeer {
                    Label("Connected to \(peer.name)", systemImage: "circle.fill")
                        .foregroundStyle(.green)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 24)
        }
    }

    private func pairing(code: String) -> some View {
        VStack(spacing: 12) {
            Text("Connect to \(model.pairingPeerName ?? "device")?")
                .font(.headline)
            Text("Code").foregroundStyle(.secondary)
            Text(code).font(.largeTitle.monospacedDigit())
            Text("Make sure the same code appears on your phone.")
                .foregroundStyle(.secondary)
            Button("Connect") { model.confirmPairing(true) }
                .keyboardShortcut(.defaultAction)
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func incomingView(_ offer: IncomingOffer) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(offer.peer.name) wants to send files").font(.headline)
            Text("\(offer.request.files.count) files · \(ByteFormat.humanSize(offer.request.totalBytes))")
            if !offer.comparison.alreadyPresent.isEmpty {
                Text("\(offer.comparison.alreadyPresent.count) already on this Mac")
                    .foregroundStyle(.secondary)
            }
            if !offer.comparison.conflicts.isEmpty {
                Text("Files that already exist").font(.subheadline.weight(.medium))
                ForEach(offer.comparison.conflicts, id: \.incoming.fileId) { conflict in
                    HStack {
                        Text(conflict.incoming.name).lineLimit(1)
                        Spacer()
                        Picker("Action", selection: conflictBinding(conflict.incoming.fileId, offer: offer)) {
                            Text("Keep both").tag(ConflictAction.keepBoth)
                            Text("Replace").tag(ConflictAction.replace)
                            Text("Skip").tag(ConflictAction.skip)
                        }
                        .labelsHidden()
                        .frame(width: 140)
                    }
                }
            }
            Text("Save to \(model.lastSavedFolder?.path ?? model.destination.appendingPathComponent(ProtocolConstants.receiveSubfolder(peerName: offer.peer.name)).path)")
                .foregroundStyle(.secondary)
                .font(.caption)
            Button("Receive") { model.confirmIncoming(true) }
                .keyboardShortcut(.defaultAction)
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func conflictBinding(_ fileId: String, offer: IncomingOffer) -> Binding<ConflictAction> {
        Binding(
            get: { offer.resolutions[fileId] ?? .keepBoth },
            set: { model.setConflict(fileId, action: $0) }
        )
    }

    private func progressView(_ progress: TransferProgress) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(progressTitle(progress))
                .font(.headline)
            Text("\(progress.filesCompleted) / \(progress.filesTotal) \(progress.filesTotal == 1 ? "file" : "files")")
            ProgressView(value: progress.bytesTotal == 0 ? 0 : Double(progress.bytesTransferred) / Double(progress.bytesTotal))
            Text("\(ByteFormat.humanSize(progress.bytesTransferred)) / \(ByteFormat.humanSize(progress.bytesTotal))")
            if progress.bytesPerSecond > 0 {
                Text(ByteFormat.humanSpeed(progress.bytesPerSecond))
            }
            if progress.state == .transferring {
                Button("Cancel") { model.cancelTransfer() }
            }
            if progress.state == .completed {
                if let folder = model.lastSavedFolder {
                    Text(folder.path).foregroundStyle(.secondary).font(.caption)
                    Button("Open folder") { model.openSavedFolder() }
                }
                Button("View files") { model.showFiles = true }
                Button(model.receiving ? "Receive more" : "Send more") { model.finishCompleted() }
                Button("Done") { model.finishCompleted() }
            }
            if progress.state == .failed || progress.state == .cancelled {
                Button("Done") { model.finishCompleted() }
            }
        }
    }

    private func progressTitle(_ progress: TransferProgress) -> String {
        switch progress.state {
        case .completed: return model.receiving ? "Saved to your Mac" : "Sent"
        case .failed: return "Couldn’t finish"
        case .cancelled: return "Cancelled"
        default: return model.receiving ? "Receiving" : "Sending"
        }
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.allowedContentTypes = [.item]
        if panel.runModal() == .OK {
            model.preparePackage(urls: panel.urls)
        }
    }

    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        var urls: [URL] = []
        let group = DispatchGroup()
        for provider in providers {
            group.enter()
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                if let url { urls.append(url) }
                group.leave()
            }
        }
        group.notify(queue: .main) {
            model.requestSend(urls: urls)
        }
        return true
    }
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Form {
            Text("HONOR Share needs local network access to find your phone and transfer files directly.")
            Button("Change save folder") {
                let panel = NSOpenPanel()
                panel.canChooseDirectories = true
                panel.canChooseFiles = false
                panel.allowsMultipleSelection = false
                if panel.runModal() == .OK, let url = panel.url {
                    model.setDestination(url)
                }
            }
            Button("Clear history") { model.clearHistory() }
            Button("Open System Settings") { model.openSystemSettings() }
        }
        .padding()
    }
}
