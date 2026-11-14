import SwiftUI

/// The Syncliko mark from brand/mark.svg: a geometric S, white over mint, on a cobalt tile.
/// Drawn as shapes so it stays sharp at any size (menu header, pairing window, About).
struct BrandMark: View {
    var size: CGFloat = 28

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.225, style: .continuous)
                .fill(LinearGradient(colors: [Color(red: 0.227, green: 0.361, blue: 1), Color(red: 0.122, green: 0.231, blue: 0.859)],
                                     startPoint: .top, endPoint: .bottom))
            SHalf(top: true).stroke(.white, style: StrokeStyle(lineWidth: size * 0.104))
            SHalf(top: false).stroke(Color(red: 0.608, green: 0.961, blue: 0.784), style: StrokeStyle(lineWidth: size * 0.104))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// One half of the S on the mark's 100-unit grid (top: the terminal and the upper bowl; bottom: the
/// lower bowl and its terminal), scaled to 80% of the tile like the site logo. Quarter circles are
/// drawn as cubic Béziers so the result doesn't depend on arc-direction conventions.
private struct SHalf: Shape {
    let top: Bool

    func path(in rect: CGRect) -> Path {
        let k = 0.5523 * 14  // control-point offset for a quarter circle of radius 14
        var p = Path()
        if top {
            p.move(to: CGPoint(x: 66, y: 22))
            p.addLine(to: CGPoint(x: 50, y: 22))
            p.addCurve(to: CGPoint(x: 36, y: 36), control1: CGPoint(x: 50 - k, y: 22), control2: CGPoint(x: 36, y: 36 - k))
            p.addCurve(to: CGPoint(x: 50, y: 50), control1: CGPoint(x: 36, y: 36 + k), control2: CGPoint(x: 50 - k, y: 50))
        } else {
            p.move(to: CGPoint(x: 50, y: 50))
            p.addCurve(to: CGPoint(x: 64, y: 64), control1: CGPoint(x: 50 + k, y: 50), control2: CGPoint(x: 64, y: 64 - k))
            p.addCurve(to: CGPoint(x: 50, y: 78), control1: CGPoint(x: 64, y: 64 + k), control2: CGPoint(x: 50 + k, y: 78))
            p.addLine(to: CGPoint(x: 34, y: 78))
        }
        let scale = rect.width / 100 * 0.8
        let transform = CGAffineTransform(translationX: rect.midX, y: rect.midY)
            .scaledBy(x: scale, y: scale)
            .translatedBy(x: -50, y: -50)
        return p.applying(transform)
    }
}
