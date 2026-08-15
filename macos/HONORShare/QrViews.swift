import SwiftUI
import AppKit
import AVFoundation
import Vision
import CoreImage
import CoreImage.CIFilterBuiltins
import CoreMedia
import HonorShareCore
import HonorShareProtocol

struct QrImageView: View {
    let text: String
    var body: some View {
        if let image = QrImage.make(text) {
            Image(nsImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(12)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 20))
        }
    }
}

enum QrImage {
    static func make(_ text: String) -> NSImage? {
        guard !text.isEmpty, let data = text.data(using: .utf8) else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}

struct QrScannerSheet: View {
    var onCode: (String) -> Void
    var onCancel: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Button("Back", action: onCancel)
                Spacer()
            }
            Text("Scan a HONOR Share QR code")
                .font(.title2.weight(.semibold))
            Text("Point this camera at the QR code on your phone.")
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            QrCameraRepresentable(onCode: onCode)
                .frame(minWidth: 420, minHeight: 280)
                .clipShape(RoundedRectangle(cornerRadius: 16))
            Button("Cancel", action: onCancel)
        }
        .padding(24)
        .frame(width: 520, height: 480)
    }
}

final class QrCameraView: NSView, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private var preview: AVCaptureVideoPreviewLayer?
    private var found = false

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        setup()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        wantsLayer = true
        setup()
    }

    deinit {
        session.stopRunning()
    }

    override func layout() {
        super.layout()
        preview?.frame = bounds
    }

    private func setup() {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard granted else { return }
            DispatchQueue.main.async { self?.start() }
        }
    }

    private func start() {
        guard session.inputs.isEmpty, let device = AVCaptureDevice.default(for: .video) else { return }
        do {
            let input = try AVCaptureDeviceInput(device: device)
            if session.canAddInput(input) { session.addInput(input) }
            output.setSampleBufferDelegate(self, queue: DispatchQueue(label: "honor.share.qr"))
            output.alwaysDiscardsLateVideoFrames = true
            if session.canAddOutput(output) { session.addOutput(output) }
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            layer.frame = bounds
            self.layer?.addSublayer(layer)
            preview = layer
            session.startRunning()
        } catch {
            ShareLog.e("qr", error.localizedDescription)
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if found { return }
        guard let pixel = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let request = VNDetectBarcodesRequest { [weak self] request, _ in
            guard let self, !self.found else { return }
            let payload = (request.results as? [VNBarcodeObservation])?.first(where: { $0.symbology == .qr })?.payloadStringValue
            guard let payload, ShareLink.parse(payload) != nil else { return }
            self.found = true
            DispatchQueue.main.async { self.onCode?(payload) }
        }
        request.symbologies = [.qr]
        try? VNImageRequestHandler(cvPixelBuffer: pixel, options: [:]).perform([request])
    }
}

struct QrCameraRepresentable: NSViewRepresentable {
    var onCode: (String) -> Void

    func makeNSView(context: Context) -> QrCameraView {
        let view = QrCameraView(frame: .zero)
        view.onCode = onCode
        return view
    }

    func updateNSView(_ nsView: QrCameraView, context: Context) {
        nsView.onCode = onCode
    }
}
