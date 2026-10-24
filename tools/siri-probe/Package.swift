// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SiriProbeCore",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [.library(name: "SiriProbeCore", targets: ["SiriProbeCore"])],
    targets: [
        .target(name: "SiriProbeCore", path: "Shared"),
        .testTarget(name: "SiriProbeCoreTests", dependencies: ["SiriProbeCore"], path: "Tests")
    ]
)
