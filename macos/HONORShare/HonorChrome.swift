import AppKit
import SwiftUI

enum HonorColor {
    static let blue = Color(red: 36 / 255, green: 124 / 255, blue: 1)
}

enum HonorSupport {
    static let coffeeURL = URL(string: "https://ko-fi.com/moomenaldahdouh")!
}

enum HonorShareWindow {
    static func reveal() {
        NSApp.activate(ignoringOtherApps: true)
        let window = NSApp.windows.first { window in
            window.canBecomeMain && window.title != "Settings"
        } ?? NSApp.windows.first
        window?.makeKeyAndOrderFront(nil)
    }
}

struct HonorCard<Content: View>: View {
    var emphasized = false
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            content
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            emphasized ? HonorColor.blue : Color(nsColor: .controlBackgroundColor),
            in: RoundedRectangle(cornerRadius: 18, style: .continuous)
        )
    }
}

struct ErrorBanner: View {
    let message: String
    var body: some View {
        Text(message)
            .foregroundStyle(Color.red)
            .fixedSize(horizontal: false, vertical: true)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}
