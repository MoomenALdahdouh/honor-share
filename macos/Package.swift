// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "HONORShare",
    defaultLocalization: "en",
    platforms: [
        .macOS(.v13),
    ],
    products: [
        .executable(name: "HONORShare", targets: ["HONORShare"]),
        .executable(name: "HonorShareCheck", targets: ["HonorShareCheck"]),
        .library(name: "HonorShareProtocol", targets: ["HonorShareProtocol"]),
        .library(name: "HonorShareCore", targets: ["HonorShareCore"]),
    ],
    targets: [
        .target(name: "HonorShareProtocol", path: "Protocol"),
        .target(name: "HonorShareCore", dependencies: ["HonorShareProtocol"], path: "Core"),
        .target(name: "HonorShareDiscovery", dependencies: ["HonorShareCore"], path: "Discovery"),
        .target(name: "HonorShareStorage", dependencies: ["HonorShareCore", "HonorShareProtocol"], path: "Storage"),
        .target(name: "HonorShareHistory", dependencies: ["HonorShareCore", "HonorShareProtocol"], path: "History"),
        .target(
            name: "HonorShareTransfer",
            dependencies: ["HonorShareCore", "HonorShareStorage", "HonorShareHistory", "HonorShareDiscovery"],
            path: "Transfer"
        ),
        .executableTarget(
            name: "HONORShare",
            dependencies: [
                "HonorShareCore",
                "HonorShareDiscovery",
                "HonorShareTransfer",
                "HonorShareStorage",
                "HonorShareHistory",
            ],
            path: "HONORShare",
            resources: [
                .process("Resources"),
            ]
        ),
        .executableTarget(
            name: "HonorShareCheck",
            dependencies: ["HonorShareProtocol", "HonorShareHistory", "HonorShareStorage"],
            path: "Tests"
        ),
    ]
)
