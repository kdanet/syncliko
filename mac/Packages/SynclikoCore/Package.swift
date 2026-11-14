// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "SynclikoCore",
    platforms: [.macOS(.v26)],
    products: [
        .library(name: "SynclikoProtocol", targets: ["SynclikoProtocol"]),
        .library(name: "SynclikoTransport", targets: ["SynclikoTransport"]),
        .executable(name: "syncliko-probe", targets: ["syncliko-probe"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-certificates.git", from: "1.0.0"),
    ],
    targets: [
        .target(name: "SynclikoProtocol"),
        .target(
            name: "SynclikoTransport",
            dependencies: [
                "SynclikoProtocol",
                .product(name: "X509", package: "swift-certificates"),
            ]
        ),
        .executableTarget(
            name: "syncliko-probe",
            dependencies: ["SynclikoProtocol", "SynclikoTransport"]
        ),
        .testTarget(name: "SynclikoProtocolTests", dependencies: ["SynclikoProtocol"]),
        .testTarget(name: "SynclikoTransportTests", dependencies: ["SynclikoTransport", "SynclikoProtocol"]),
    ]
)
