import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// Crisp QR code rendered with CoreImage (pre-scaled by a whole number, no interpolation when fitted).
struct QRCodeView: View {
    let text: String

    var body: some View {
        if let image = Self.render(text) {
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .aspectRatio(1, contentMode: .fit)
                .accessibilityLabel("Pairing QR code")
        } else {
            Image(systemName: "qrcode").font(.largeTitle)
        }
    }

    static func render(_ text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        // Upscale by a whole number in CoreImage, so the final fit into the view barely resamples
        // and the modules keep sharp edges.
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 16, y: 16)) else { return nil }
        return CIContext().createCGImage(output, from: output.extent)
    }
}
