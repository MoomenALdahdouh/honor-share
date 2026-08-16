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
    static let honorShareSettings = Notification.Name("honorShareSettings")
}

final class HonorShareAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.servicesProvider = HonorShareServices.shared
        NSUpdateDynamicServices()
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        HonorShareWindow.reveal()
        return true
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        HonorShareWindow.reveal()
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
            HonorShareWindow.reveal()
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
                .tint(HonorColor.blue)
        }

        MenuBarExtra("Direct Share", systemImage: "iphone") {
            MenuBarMenu()
                .environmentObject(model)
        }

        Settings {
            SettingsView()
                .environmentObject(model)
                .frame(minWidth: 400, minHeight: 280)
        }
    }
}

private struct MenuBarMenu: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Direct Share").font(.headline)
            HStack(spacing: 6) {
                Circle().fill(statusColor).frame(width: 8, height: 8)
                Text(statusText)
            }
            Divider()
            Button("Send files…") {
                HonorShareWindow.reveal()
                NotificationCenter.default.post(name: .honorShareSend, object: nil)
            }
            Button("Open Direct Share") {
                HonorShareWindow.reveal()
            }
            Button("Settings…") {
                HonorShareWindow.reveal()
                NotificationCenter.default.post(name: .honorShareSettings, object: nil)
            }
            Divider()
            Button("Quit Direct Share") { NSApp.terminate(nil) }
        }
        .padding(12)
        .frame(minWidth: 220, alignment: .leading)
    }

    private var statusText: String {
        if model.progress?.state == .transferring { return model.receiving ? "Receiving…" : "Sending…" }
        if model.pairingCode != nil { return "Confirm the code" }
        if model.incoming != nil { return "Files waiting" }
        if model.connectingToPhone { return "Connecting…" }
        if model.lookingForCode { return "Looking for phone" }
        if model.pendingPackage != nil { return "Waiting for phone" }
        return "Ready"
    }

    private var statusColor: Color {
        if model.progress?.state == .failed { return .red }
        if model.pairingCode != nil || model.incoming != nil || model.pendingPackage != nil { return HonorColor.blue }
        if model.progress?.state == .transferring || model.connectingToPhone || model.lookingForCode { return .orange }
        return .green
    }
}

struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @State private var showSettings = false

    private var inFlow: Bool {
        model.pendingPackage != nil
            || model.lookingForCode
            || model.connectingToPhone
            || model.pairingCode != nil
            || model.incoming != nil
            || model.progress != nil
    }

    var body: some View {
        VStack(spacing: 0) {
            if model.showFiles {
                FileBrowserView()
            } else {
                TransferPane()
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .frame(minWidth: 720, minHeight: 620)
        .navigationTitle("Direct Share")
        .toolbar {
            ToolbarItem(placement: .navigation) {
                if inFlow || model.showFiles {
                    Button("Back") { goBack() }
                        .help("Go back")
                        .keyboardShortcut("[", modifiers: [.command])
                }
            }
            ToolbarItem(placement: .principal) {
                if !inFlow || model.showFiles {
                    Picker("Section", selection: $model.showFiles) {
                        Text("Transfer").tag(false)
                        Text("Files").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 220)
                }
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
                .environmentObject(model)
                .frame(width: 420, height: 400)
        }
        .onReceive(NotificationCenter.default.publisher(for: .honorShareSettings)) { _ in
            showSettings = true
        }
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
