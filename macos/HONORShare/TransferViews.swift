import SwiftUI
import AppKit
import HonorShareTransfer
import HonorShareProtocol
import UniformTypeIdentifiers

struct TransferPane: View {
    @EnvironmentObject var model: AppModel
    @State private var hovering = false
    @State private var showScanner = false
    @State private var tick = Date()

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
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { value in
            tick = value
        }
        .onReceive(NotificationCenter.default.publisher(for: .honorShareSend)) { _ in
            HonorShareWindow.reveal()
            pickFiles()
        }
        .onReceive(NotificationCenter.default.publisher(for: .honorShareOpenFiles)) { note in
            if let urls = note.object as? [URL], !urls.isEmpty {
                HonorShareWindow.reveal()
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
            VStack(alignment: .leading, spacing: 16) {
                Text("Send to your phone, or receive from it.")
                    .foregroundStyle(.secondary)

                sendCard
                receiveCard

                Button {
                    model.showFiles = true
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "folder")
                            .font(.title3)
                            .foregroundStyle(HonorColor.blue)
                            .frame(width: 28)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("My files").font(.headline)
                            Text(model.library.isEmpty ? "Nothing here yet" : "\(model.library.count) files ready to open")
                                .foregroundStyle(.secondary)
                                .font(.callout)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(.secondary)
                    }
                    .padding(16)
                    .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)

                if let error = model.errorMessage {
                    ErrorBanner(message: error)
                }
                if model.localNetworkDenied {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Allow local network access so this Mac can find your phone.")
                        Button("Open System Settings") { model.openSystemSettings() }
                    }
                }

                Link("Buy me a coffee", destination: HonorSupport.coffeeURL)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .center)
        }
        .onAppear { model.refreshLibrary() }
    }

    private var sendCard: some View {
        HonorCard(emphasized: true) {
            Label("Send files", systemImage: "square.and.arrow.up")
                .font(.title3.weight(.semibold))
                .foregroundStyle(.white)
            Text("Drop files here. Your phone taps Receive and scans the QR.")
                .foregroundStyle(.white.opacity(0.9))
            VStack(spacing: 10) {
                Image(systemName: hovering ? "arrow.down.app.fill" : "plus.rectangle.on.folder")
                    .font(.system(size: 28))
                Text(hovering ? "Drop to send" : "Drop files here")
                    .font(.headline)
                Button("Choose files") { pickFiles() }
                    .keyboardShortcut("s", modifiers: [.command])
                    .buttonStyle(.borderedProminent)
                    .tint(.white)
                    .foregroundStyle(HonorColor.blue)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 132)
            .background(.white.opacity(hovering ? 0.18 : 0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(.white.opacity(hovering ? 1 : 0.35), style: StrokeStyle(lineWidth: 2, dash: hovering ? [] : [7, 6]))
            )
            .onDrop(of: [.fileURL], isTargeted: $hovering) { providers in
                handleDrop(providers)
            }
        }
    }

    private var receiveCard: some View {
        HonorCard {
            Label("Receive from phone", systemImage: "arrow.down.to.line")
                .font(.title3.weight(.semibold))
            Text("On your phone tap Send files, then Send. Type that 6-digit code here.")
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                TextField("000 000", text: $model.receiveCode)
                    .textFieldStyle(.roundedBorder)
                    .font(.title3.monospacedDigit())
                    .frame(width: 132)
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
                .buttonStyle(.borderedProminent)
                .tint(HonorColor.blue)
                .disabled(model.lookingForCode || model.connectingToPhone || model.receiveCode.filter(\.isNumber).count != 6)
                .keyboardShortcut(.defaultAction)
                if model.lookingForCode || model.connectingToPhone {
                    Button("Cancel") { model.cancelCodeLookup() }
                }
            }
            if model.lookingForCode {
                Label("Looking for your phone…", systemImage: "wifi")
                    .foregroundStyle(.secondary)
            } else if model.connectingToPhone {
                Label("Connecting to your phone…", systemImage: "link")
                    .foregroundStyle(.secondary)
            }
            Button("Scan QR") { showScanner = true }
                .buttonStyle(.link)
        }
    }

    private func waitingToSend(pkg: TransferPackage, invite: PackageInvitation) -> some View {
        let _ = tick
        return VStack(spacing: 16) {
            Text("Send")
                .font(.title2.weight(.semibold))
            Text("\(pkg.files.count) \(pkg.files.count == 1 ? "file" : "files") · \(ByteFormat.humanSize(pkg.totalBytes))")
                .foregroundStyle(.secondary)
            QrImageView(text: invite.encode())
                .frame(width: 220, height: 220)
            Text(invite.displayCode())
                .font(.system(size: 40, weight: .semibold, design: .rounded).monospacedDigit())
                .textSelection(.enabled)
            Text("On your phone, open Direct Share, tap Receive, and scan this QR.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 420)
            if invite.isExpired() {
                Text("This code has expired. Create a new one.")
                    .foregroundStyle(.red)
            } else {
                Text("Expires in \(String(format: "%d:%02d", invite.remainingSeconds() / 60, invite.remainingSeconds() % 60))")
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button("New code") { model.regenerateInvitation() }
                Button("Cancel") { model.cancelPendingSend() }
            }
            if let error = model.errorMessage {
                ErrorBanner(message: error)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func pairing(code: String) -> some View {
        VStack(spacing: 16) {
            Spacer()
            Text("Connect to \(model.pairingPeerName ?? "this device")?")
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(code)
                .font(.system(size: 44, weight: .semibold, design: .rounded).monospacedDigit())
            Text("The same code must appear on your phone. If it does not, tap Don’t connect.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 400)
            HStack(spacing: 12) {
                Button("Don’t connect") { model.confirmPairing(false) }
                Button("Connect") { model.confirmPairing(true) }
                    .buttonStyle(.borderedProminent)
                    .tint(HonorColor.blue)
                    .keyboardShortcut(.defaultAction)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func incomingView(_ offer: IncomingOffer) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("\(offer.peer.name) wants to send files")
                .font(.title2.weight(.semibold))
            Text("\(offer.request.files.count) \(offer.request.files.count == 1 ? "file" : "files") · \(ByteFormat.humanSize(offer.request.totalBytes))")
                .foregroundStyle(.secondary)
            if !offer.comparison.alreadyPresent.isEmpty {
                Text("\(offer.comparison.alreadyPresent.count) already on this Mac")
                    .foregroundStyle(.secondary)
            }
            if !offer.comparison.conflicts.isEmpty {
                Text("Files that already exist").font(.headline)
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
            Text("Save to \(model.lastSavedFolder?.path ?? model.destination.appendingPathComponent(ProtocolConstants.receiveSubfolder(peerName: offer.peer.name, fileCount: offer.request.files.count)).path)")
                .foregroundStyle(.secondary)
                .font(.caption)
                .textSelection(.enabled)
            HStack(spacing: 12) {
                Button("Decline") { model.confirmIncoming(false) }
                Button("Receive") { model.confirmIncoming(true) }
                    .buttonStyle(.borderedProminent)
                    .tint(HonorColor.blue)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .frame(maxWidth: 520, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .center)
    }

    private func conflictBinding(_ fileId: String, offer: IncomingOffer) -> Binding<ConflictAction> {
        Binding(
            get: { offer.resolutions[fileId] ?? .keepBoth },
            set: { model.setConflict(fileId, action: $0) }
        )
    }

    private func progressView(_ progress: TransferProgress) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(progressTitle(progress))
                .font(.title2.weight(.semibold))
            Text("\(progress.filesCompleted) / \(progress.filesTotal) \(progress.filesTotal == 1 ? "file" : "files")")
            ProgressView(value: progress.bytesTotal == 0 ? 0 : Double(progress.bytesTransferred) / Double(progress.bytesTotal))
            Text("\(ByteFormat.humanSize(progress.bytesTransferred)) / \(ByteFormat.humanSize(progress.bytesTotal))")
            if progress.bytesPerSecond > 0 {
                Text(ByteFormat.humanSpeed(progress.bytesPerSecond)).foregroundStyle(.secondary)
            }
            if let name = Optional(progress.currentName), !name.isEmpty, progress.state == .transferring {
                Text(name).foregroundStyle(.secondary)
            }
            if progress.state == .transferring {
                Button("Cancel") { model.cancelTransfer() }
            }
            if progress.state == .completed {
                if let folder = model.lastSavedFolder {
                    Text(folder.path).foregroundStyle(.secondary).font(.caption).textSelection(.enabled)
                    Button("Open folder") { model.openSavedFolder() }
                }
                HStack(spacing: 12) {
                    Button("View files") { model.showFiles = true }
                    Button("Done") { model.finishCompleted() }
                        .buttonStyle(.borderedProminent)
                        .tint(HonorColor.blue)
                        .keyboardShortcut(.defaultAction)
                }
            }
            if progress.state == .failed || progress.state == .cancelled {
                if let error = model.errorMessage {
                    ErrorBanner(message: error)
                }
                Button("Done") { model.finishCompleted() }
                    .buttonStyle(.borderedProminent)
                    .tint(HonorColor.blue)
            }
        }
        .frame(maxWidth: 480, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .center)
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
        panel.message = "Choose files to send to your phone"
        if panel.runModal() == .OK {
            model.preparePackage(urls: panel.urls)
        }
    }

    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        let group = DispatchGroup()
        let lock = NSLock()
        var urls: [URL] = []
        for provider in providers {
            group.enter()
            if provider.canLoadObject(ofClass: URL.self) {
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    if let url {
                        lock.lock()
                        urls.append(url)
                        lock.unlock()
                    }
                    group.leave()
                }
            } else {
                provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                    defer { group.leave() }
                    let url: URL?
                    if let data = item as? Data {
                        url = URL(dataRepresentation: data, relativeTo: nil)
                    } else {
                        url = item as? URL
                    }
                    if let url {
                        lock.lock()
                        urls.append(url)
                        lock.unlock()
                    }
                }
            }
        }
        group.notify(queue: .main) {
            if !urls.isEmpty {
                model.preparePackage(urls: urls)
            }
        }
        return true
    }
}

struct SettingsView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Form {
            Section("Saving") {
                Text(model.destination.path)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                Button("Change save folder…") {
                    let panel = NSOpenPanel()
                    panel.canChooseDirectories = true
                    panel.canChooseFiles = false
                    panel.allowsMultipleSelection = false
                    panel.prompt = "Choose"
                    if panel.runModal() == .OK, let url = panel.url {
                        model.setDestination(url)
                    }
                }
            }
            Section("This Mac") {
                Text("Direct Share needs local network access to find your phone. Files stay on your devices.")
                    .foregroundStyle(.secondary)
                Button("Open System Settings") { model.openSystemSettings() }
                Button("Clear history", role: .destructive) { model.clearHistory() }
            }
            Section("Support") {
                Text("Direct Share is free. If it helps, you can buy me a coffee.")
                    .foregroundStyle(.secondary)
                Link("Buy me a coffee", destination: HonorSupport.coffeeURL)
            }
        }
        .formStyle(.grouped)
        .padding()
        .tint(HonorColor.blue)
    }
}
