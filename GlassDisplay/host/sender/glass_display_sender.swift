#!/usr/bin/env swift

import ApplicationServices
import AppKit
import Compression
@preconcurrency import CoreBluetooth
import CoreGraphics
import CoreMedia
import CoreVideo
import CryptoKit
import Darwin
import Dispatch
import Foundation
import ImageIO
import ScreenCaptureKit
import Security
import UniformTypeIdentifiers
import VideoToolbox

// MARK: - Protocol

// Keep these values in sync with FrameProtocol.kt and FrameTypes.kt.
private enum FrameProtocol {
    static let magic: UInt32 = 0x52474431
    static let ackMagic: UInt32 = 0x52474131
    static let protocolVersion: UInt8 = 1
    static let flagDeflate: UInt8 = 0x01
    static let flagAesGcm: UInt8 = 0x02
    static let flagDelta: UInt8 = 0x04
    static let flagHostStatus: UInt8 = 0x08
    static let headerBytes = 18
    static let commandMagic: UInt32 = 0x52474331
    static let commandHeaderBytes = 8
    static let commandPayloadBytes = 4
    static let aesGcmNonceBytes = 12
    static let aesGcmTagBytes = 16
    static let maxEncryptedPayloadBytes = 1_048_576

    static var encryptedPayloadOverheadBytes: Int {
        aesGcmNonceBytes + aesGcmTagBytes
    }

    static var maxClearPayloadBytes: Int {
        maxEncryptedPayloadBytes - encryptedPayloadOverheadBytes
    }

    enum AckMagic {
        static let resolution480x640: UInt32 = 0x52475031
        static let resolution480x320: UInt32 = 0x52474C31
        static let resolutionOff: UInt32 = 0x52474F31
    }
}

private struct FrameSize: Equatable {
    static let rokidPortrait = FrameSize(width: 480, height: 640)
    static let rokidLandscape = FrameSize(width: 480, height: 320)

    let width: Int
    let height: Int

    var pixelCount: Int {
        width * height
    }

    var packedByteCount: Int {
        (pixelCount + 1) / 2
    }

    var isPlainFullFrame: Bool {
        self == Self.rokidPortrait || self == Self.rokidLandscape
    }

    var scriptArgument: String {
        "\(width)x\(height)"
    }

    static func validated(width: Int, height: Int) throws -> FrameSize {
        guard width > 0, height > 0, width <= Int(UInt16.max), height <= Int(UInt16.max) else {
            throw SenderError.usage("Invalid frame size: \(width)x\(height).")
        }

        let pixelCount = UInt64(width) * UInt64(height)
        let packedByteCount = (pixelCount + 1) / 2
        guard packedByteCount <= UInt64(FrameProtocol.maxClearPayloadBytes) else {
            throw SenderError.usage(
                "Frame size \(width)x\(height) is too large; max packed payload is \(FrameProtocol.maxClearPayloadBytes) bytes."
            )
        }

        return FrameSize(width: width, height: height)
    }
}

private enum OutputMode {
    case fullFrame(FrameSize)
    case zoomComposite(FrameSize)

    var size: FrameSize {
        switch self {
        case let .fullFrame(size),
             let .zoomComposite(size):
            return size
        }
    }

    static func make(size: FrameSize) -> OutputMode {
        size.isPlainFullFrame ? .fullFrame(size) : .zoomComposite(size)
    }
}

// MARK: - Rendering Constants

private let blackBackgroundColor = CGColor(gray: 0.0, alpha: 1.0)
private let separatorColor = CGColor(gray: 0.2, alpha: 1.0)
private let zoomPaneFraction: CGFloat = 0.4
private let panelSpacing: CGFloat = 4.0
private let maxZoomMagnification: CGFloat = 4.0
private let minZoomMagnification: CGFloat = 1.0
private let streamWatchdogIntervalNanoseconds: UInt64 = 66_666_667
private let streamWatchdogStaleSeconds: CFAbsoluteTime = 0.12

private enum TransportKind: String {
    case tcp
    case ble
}

// MARK: - Host Commands

private enum HostCommand: CustomStringConvertible {
    case resolution480x640
    case resolution480x320
    case resolutionOff

    var targetSize: FrameSize? {
        switch self {
        case .resolution480x640:
            return .rokidPortrait
        case .resolution480x320:
            return .rokidLandscape
        case .resolutionOff:
            return nil
        }
    }

    var scriptArgument: String {
        switch self {
        case .resolutionOff:
            return "off"
        case .resolution480x640,
             .resolution480x320:
            return targetSize?.scriptArgument ?? "off"
        }
    }

    var description: String {
        return "resolution:\(scriptArgument)"
    }

    static func ackCommand(for magic: UInt32) -> HostCommand? {
        switch magic {
        case FrameProtocol.AckMagic.resolution480x640:
            return .resolution480x640
        case FrameProtocol.AckMagic.resolution480x320:
            return .resolution480x320
        case FrameProtocol.AckMagic.resolutionOff:
            return .resolutionOff
        default:
            return nil
        }
    }
}

// MARK: - Options

private struct Options {
    var host = "127.0.0.1"
    var port = 19_400
    var width = 480
    var height = 640
    var outputSizeSpecified = false
    var fps: Double?
    var saveImagePath: String?
    var syntheticFrameCount: Int?
    var keyFilePath: String?
    var keyHex: String?
    var transport: TransportKind = .tcp
    var bleName = ""
    var bleDeviceID: Data?
    var bleHostID: Data?
    var bleScanTimeout: TimeInterval = 30.0
}

// MARK: - BLE Constants

private enum BLEUUIDString {
    static let service = "52474431-0001-4F4E-9F0C-524744313031"
    static let frameCharacteristic = "52474431-0002-4F4E-9F0C-524744313031"
    static let commandCharacteristic = "52474431-0003-4F4E-9F0C-524744313031"
    static let hostCharacteristic = "52474431-0004-4F4E-9F0C-524744313031"
}

// MARK: - Capture

private struct CaptureContext {
    let filter: SCContentFilter
    let displayFrame: CGRect
    let sourceWidth: Int
    let sourceHeight: Int
    let outputMode: OutputMode
    let zoomMagnification: CGFloat

    var outputSize: FrameSize {
        outputMode.size
    }

    var outputWidth: Int {
        outputSize.width
    }

    var outputHeight: Int {
        outputSize.height
    }
}

private final class StreamingFrameCapture: NSObject, SCStreamOutput, SCStreamDelegate {
    private let options: Options
    private let captureContext: CaptureContext
    private let outputQueue = DispatchQueue(label: "bio.aq.glassdisplay.scstream.output", qos: .userInteractive)
    private let stateQueue = DispatchQueue(label: "bio.aq.glassdisplay.scstream.state")
    private let frames: AsyncThrowingStream<CGImage, Error>

    private var continuation: AsyncThrowingStream<CGImage, Error>.Continuation?
    private var stream: SCStream?
    private var watchdogTask: Task<Void, Never>?
    private var lastFrameTime = CFAbsoluteTimeGetCurrent()

    init(options: Options, captureContext: CaptureContext) {
        self.options = options
        self.captureContext = captureContext

        var capturedContinuation: AsyncThrowingStream<CGImage, Error>.Continuation?
        self.frames = AsyncThrowingStream(CGImage.self, bufferingPolicy: .bufferingNewest(1)) { continuation in
            capturedContinuation = continuation
        }
        self.continuation = capturedContinuation

        super.init()
    }

    func start() async throws -> AsyncThrowingStream<CGImage, Error> {
        let configuration = makeStreamConfiguration(options: options, captureContext: captureContext)
        let activeStream = SCStream(filter: captureContext.filter, configuration: configuration, delegate: self)

        try activeStream.addStreamOutput(self, type: .screen, sampleHandlerQueue: outputQueue)
        stream = activeStream
        try await startCapture(activeStream)
        startWatchdog()

        return frames
    }

    func stop() async {
        watchdogTask?.cancel()
        watchdogTask = nil

        guard let activeStream = stream else {
            continuation?.finish()
            return
        }

        stream = nil
        try? await stopCapture(activeStream)
        continuation?.finish()
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen else {
            return
        }
        guard CMSampleBufferIsValid(sampleBuffer), isUsableVideoFrame(sampleBuffer) else {
            return
        }
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }

        var image: CGImage?
        let status = VTCreateCGImageFromCVPixelBuffer(imageBuffer, options: nil, imageOut: &image)
        guard status == noErr, let image else {
            continuation?.finish(throwing: SenderError.capture("Unable to create CGImage from stream frame (\(status))."))
            return
        }

        yieldFrame(image)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        continuation?.finish(throwing: SenderError.capture("capture stream stopped: \(error.localizedDescription)"))
    }

    private func yieldFrame(_ image: CGImage) {
        stateQueue.sync {
            lastFrameTime = CFAbsoluteTimeGetCurrent()
        }
        continuation?.yield(image)
    }

    private func startWatchdog() {
        watchdogTask?.cancel()
        watchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: streamWatchdogIntervalNanoseconds)
                if Task.isCancelled {
                    return
                }

                guard let self else {
                    return
                }

                let isStale = self.stateQueue.sync {
                    CFAbsoluteTimeGetCurrent() - self.lastFrameTime >= streamWatchdogStaleSeconds
                }
                if !isStale {
                    continue
                }

                do {
                    let image = try await captureDisplayImage(captureContext: self.captureContext)
                    if Task.isCancelled {
                        return
                    }
                    self.yieldFrame(image)
                } catch {
                    self.continuation?.finish(throwing: error)
                    return
                }
            }
        }
    }
}

private let usageText = """
Usage: swift host/sender/glass_display_sender.swift [--transport tcp|ble] \
[--host 127.0.0.1] [--port 19400] [--width 480] [--height 640] [--fps limit|0] \
[--key-file path] [--ble-name GlassDisplay] [--ble-device-id-hex id] [--ble-host-id-hex id] [--ble-scan-timeout seconds] [--save-image out.png] \
[--synthetic-frames count]
"""

// MARK: - Permissions

private func currentExecutablePath() -> String {
    let executable = CommandLine.arguments.first ?? ProcessInfo.processInfo.processName
    if executable.hasPrefix("/") {
        return executable
    }

    return executable
}

private func shouldAutoPromptForScreenCapture() -> Bool {
    let value = ProcessInfo.processInfo.environment["GLASS_SCREEN_CAPTURE_AUTO_PROMPT"]?.lowercased()
    switch value {
    case "0", "false", "no":
        return false
    default:
        return true
    }
}

private func shouldPreflightScreenCapturePermission() -> Bool {
    let value = ProcessInfo.processInfo.environment["GLASS_SCREEN_CAPTURE_PREFLIGHT"]?.lowercased()
    switch value {
    case "0", "false", "no":
        return false
    default:
        return true
    }
}

// MARK: - Errors

private enum SenderError: Error, CustomStringConvertible {
    case usage(String)
    case capture(String)
    case socket(String)
    case io(String)
    case crypto(String)

    var description: String {
        switch self {
        case let .usage(message),
             let .capture(message),
             let .socket(message),
             let .io(message),
             let .crypto(message):
            return message
        }
    }
}

// MARK: - Argument Parsing

private struct ArgumentCursor {
    private let arguments: [String]
    private var index = 0

    init(arguments: [String]) {
        self.arguments = arguments
    }

    var hasNext: Bool {
        index < arguments.count
    }

    mutating func next() -> String? {
        guard hasNext else {
            return nil
        }
        defer { index += 1 }
        return arguments[index]
    }

    mutating func requiredValue(for option: String) throws -> String {
        guard let value = next() else {
            throw SenderError.usage("Missing value for \(option).")
        }
        return value
    }
}

private func parseOptions(arguments: [String]) throws -> Options {
    var options = Options()
    var cursor = ArgumentCursor(arguments: arguments)

    while let argument = cursor.next() {
        switch argument {
        case "--host":
            options.host = try cursor.requiredValue(for: argument)
        case "--port":
            let value = try cursor.requiredValue(for: argument)
            options.port = try parseUInt16Argument(value, option: argument)
        case "--width":
            let value = try cursor.requiredValue(for: argument)
            options.width = try parseUInt16Argument(value, option: argument)
            options.outputSizeSpecified = true
        case "--height":
            let value = try cursor.requiredValue(for: argument)
            options.height = try parseUInt16Argument(value, option: argument)
            options.outputSizeSpecified = true
        case "--fps":
            let value = try cursor.requiredValue(for: argument)
            options.fps = try parseFPSArgument(value, option: argument)
        case "--unlimited-fps":
            options.fps = nil
        case "--save-image":
            options.saveImagePath = try cursor.requiredValue(for: argument)
        case "--synthetic-frames":
            let value = try cursor.requiredValue(for: argument)
            options.syntheticFrameCount = try parsePositiveIntArgument(value, option: argument)
        case "--key-file":
            options.keyFilePath = try cursor.requiredValue(for: argument)
        case "--key-hex":
            options.keyHex = try cursor.requiredValue(for: argument)
        case "--transport":
            let value = try cursor.requiredValue(for: argument)
            guard let kind = TransportKind(rawValue: value) else {
                throw SenderError.usage("Invalid value for --transport (expected tcp|ble).")
            }
            options.transport = kind
        case "--ble-name":
            options.bleName = try cursor.requiredValue(for: argument)
        case "--ble-device-id-hex":
            let value = try cursor.requiredValue(for: argument)
            options.bleDeviceID = try decodeHexData(value, expectedByteCount: 8, field: "BLE device id")
        case "--ble-host-id-hex":
            let value = try cursor.requiredValue(for: argument)
            options.bleHostID = try decodeHexData(value, expectedByteCount: 8, field: "BLE host id")
        case "--ble-scan-timeout":
            let value = try cursor.requiredValue(for: argument)
            options.bleScanTimeout = try parsePositiveDoubleArgument(value, option: argument)
        default:
            throw SenderError.usage("Unknown argument: \(argument)")
        }
    }

    _ = try FrameSize.validated(width: options.width, height: options.height)
    if options.syntheticFrameCount != nil && options.transport != .tcp {
        throw SenderError.usage("--synthetic-frames supports --transport tcp only.")
    }
    if options.syntheticFrameCount != nil && options.saveImagePath != nil {
        throw SenderError.usage("--synthetic-frames cannot be combined with --save-image.")
    }
    if options.transport == .ble && options.bleHostID == nil {
        throw SenderError.usage("--ble-host-id-hex is required for --transport ble.")
    }
    return options
}

private func parseUInt16Argument(_ value: String, option: String) throws -> Int {
    guard let parsed = Int(value), parsed > 0, parsed <= Int(UInt16.max) else {
        throw SenderError.usage("Invalid value for \(option).")
    }
    return parsed
}

private func parseFPSArgument(_ value: String, option: String) throws -> Double? {
    guard let fps = Double(value), fps >= 0 else {
        throw SenderError.usage("Invalid value for \(option).")
    }
    return fps == 0 ? nil : fps
}

private func parsePositiveDoubleArgument(_ value: String, option: String) throws -> Double {
    guard let parsed = Double(value), parsed > 0 else {
        throw SenderError.usage("Invalid value for \(option).")
    }
    return parsed
}

private func parsePositiveIntArgument(_ value: String, option: String) throws -> Int {
    guard let parsed = Int(value), parsed > 0 else {
        throw SenderError.usage("Invalid value for \(option).")
    }
    return parsed
}

// MARK: - Crypto

private func loadStreamKey(options: Options) throws -> SymmetricKey {
    let hex: String
    if let keyHex = options.keyHex {
        hex = keyHex
    } else if let keyFilePath = options.keyFilePath {
        do {
            hex = try String(contentsOfFile: keyFilePath, encoding: .utf8)
        } catch {
            throw SenderError.crypto("Unable to read stream key file: \(keyFilePath)")
        }
    } else {
        throw SenderError.crypto("Missing stream key. Run host/scripts/glass-stream.sh over adb first.")
    }

    let keyData = try decodeHexKey(hex)
    return SymmetricKey(data: keyData)
}

private func decodeHexKey(_ hex: String) throws -> Data {
    try decodeHexData(hex, expectedByteCount: 32, field: "stream key")
}

private func decodeHexData(_ hex: String, expectedByteCount: Int, field: String) throws -> Data {
    let trimmed = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    guard trimmed.count == expectedByteCount * 2 else {
        throw SenderError.crypto("Invalid \(field) length.")
    }

    var bytes = Data(capacity: expectedByteCount)
    var index = trimmed.startIndex
    while index < trimmed.endIndex {
        let next = trimmed.index(index, offsetBy: 2)
        let byteString = trimmed[index..<next]
        guard let byte = UInt8(byteString, radix: 16) else {
            throw SenderError.crypto("Invalid \(field) format.")
        }
        bytes.append(byte)
        index = next
    }
    return bytes
}

private func ensureScreenCapturePermission() throws {
    if !shouldPreflightScreenCapturePermission() {
        return
    }

    if CGPreflightScreenCaptureAccess() {
        return
    }

    let executablePath = currentExecutablePath()
    if !shouldAutoPromptForScreenCapture() {
        throw SenderError.capture("Screen Recording permission required for \(executablePath).")
    }

    fputs("Screen Recording permission required. Prompting now...\n", stderr)
    _ = CGRequestScreenCaptureAccess()
    throw SenderError.capture("Grant Screen Recording permission to \(executablePath), then re-run.")
}

// MARK: - Transports

private protocol Transport: AnyObject {
    func send(packet: Data, frameId: UInt32) async throws -> HostCommand?
    func pollCommand() async throws -> HostCommand?
    func close()
    var transportDescription: String { get }
}

private extension Transport {
    func pollCommand() async throws -> HostCommand? {
        nil
    }
}

// MARK: - TCP Transport

private final class TCPTransport: Transport {
    private var socketFd: Int32 = -1
    private let host: String
    private let port: Int

    init(host: String, port: Int) throws {
        self.host = host
        self.port = port
        self.socketFd = try connectSocket(host: host, port: port)
    }

    func send(packet: Data, frameId: UInt32) async throws -> HostCommand? {
        try writeAll(socketFd: socketFd, data: packet)
        return try readFrameAck(socketFd: socketFd, frameId: frameId)
    }

    func close() {
        if socketFd >= 0 {
            Darwin.close(socketFd)
            socketFd = -1
        }
    }

    var transportDescription: String { "tcp:\(host):\(port)" }
}

// MARK: - BLE Transport

private struct BLEWriteTarget {
    let peripheral: CBPeripheral
    let characteristic: CBCharacteristic
    let maxChunkBytes: Int
}

private struct BLEReadTarget {
    let peripheral: CBPeripheral
    let characteristic: CBCharacteristic
}

private final class BLEConnection {
    private let targetName: String
    private let targetDeviceID: Data?
    private let serviceUUID = CBUUID(string: BLEUUIDString.service)
    private let frameCharacteristicUUID = CBUUID(string: BLEUUIDString.frameCharacteristic)
    private let commandCharacteristicUUID = CBUUID(string: BLEUUIDString.commandCharacteristic)
    private let hostCharacteristicUUID = CBUUID(string: BLEUUIDString.hostCharacteristic)

    private(set) var peripheral: CBPeripheral?
    private(set) var frameCharacteristic: CBCharacteristic?
    private(set) var commandCharacteristic: CBCharacteristic?
    private(set) var hostCharacteristic: CBCharacteristic?

    init(targetName: String, targetDeviceID: Data?) {
        self.targetName = targetName
        self.targetDeviceID = targetDeviceID
    }

    var transportDescription: String {
        if let targetDeviceID {
            return "ble:\(targetDeviceID.hexString)"
        }
        return targetName.isEmpty ? "ble" : "ble:\(targetName)"
    }

    var requiresAdvertisementMatch: Bool {
        targetDeviceID != nil
    }

    func matches(peripheral: CBPeripheral, advertisementData: [String: Any]) -> Bool {
        if let targetDeviceID {
            guard
                let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
                let advertisedDeviceID = serviceData[serviceUUID],
                advertisedDeviceID == targetDeviceID
            else {
                return false
            }
        }

        let advertisedName = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? ""
        return matches(name: advertisedName)
    }

    func matches(name: String?) -> Bool {
        targetName.isEmpty || name == targetName
    }

    func attach(_ peripheral: CBPeripheral, delegate: CBPeripheralDelegate) {
        self.peripheral = peripheral
        self.frameCharacteristic = nil
        self.commandCharacteristic = nil
        self.hostCharacteristic = nil
        peripheral.delegate = delegate
    }

    func reset() {
        peripheral = nil
        frameCharacteristic = nil
        commandCharacteristic = nil
        hostCharacteristic = nil
    }

    func service() -> CBService? {
        peripheral?.services?.first(where: { $0.uuid == serviceUUID })
    }

    func selectCharacteristics(from service: CBService) -> Bool {
        frameCharacteristic = service.characteristics?.first(where: { $0.uuid == frameCharacteristicUUID })
        commandCharacteristic = service.characteristics?.first(where: { $0.uuid == commandCharacteristicUUID })
        hostCharacteristic = service.characteristics?.first(where: { $0.uuid == hostCharacteristicUUID })
        return frameCharacteristic != nil && commandCharacteristic != nil && hostCharacteristic != nil
    }

    func isCurrent(_ target: BLEWriteTarget) -> Bool {
        peripheral === target.peripheral &&
            (frameCharacteristic === target.characteristic || hostCharacteristic === target.characteristic)
    }

    func isCurrent(_ target: BLEReadTarget) -> Bool {
        peripheral === target.peripheral && commandCharacteristic === target.characteristic
    }

    func writeTarget() throws -> BLEWriteTarget {
        guard let peripheral, let frameCharacteristic else {
            throw SenderError.socket("BLE peripheral not connected.")
        }
        if peripheral.state != .connected {
            throw SenderError.socket("BLE peripheral disconnected during write.")
        }
        return BLEWriteTarget(
            peripheral: peripheral,
            characteristic: frameCharacteristic,
            maxChunkBytes: max(20, peripheral.maximumWriteValueLength(for: .withResponse))
        )
    }

    func hostWriteTarget() throws -> BLEWriteTarget {
        guard let peripheral, let hostCharacteristic else {
            throw SenderError.socket("BLE host characteristic not connected.")
        }
        if peripheral.state != .connected {
            throw SenderError.socket("BLE peripheral disconnected during host identity write.")
        }
        return BLEWriteTarget(
            peripheral: peripheral,
            characteristic: hostCharacteristic,
            maxChunkBytes: max(20, peripheral.maximumWriteValueLength(for: .withResponse))
        )
    }

    func commandReadTarget() throws -> BLEReadTarget {
        guard let peripheral, let commandCharacteristic else {
            throw SenderError.socket("BLE command characteristic not connected.")
        }
        if peripheral.state != .connected {
            throw SenderError.socket("BLE peripheral disconnected during command read.")
        }
        return BLEReadTarget(peripheral: peripheral, characteristic: commandCharacteristic)
    }
}

private final class BLEPendingContinuations {
    private let assertOnQueue: () -> Void
    private var state: CheckedContinuation<CBManagerState, Never>?
    private var connect: CheckedContinuation<Void, Error>?
    private var service: CheckedContinuation<Void, Error>?
    private var characteristic: CheckedContinuation<Void, Error>?
    private var write: CheckedContinuation<Void, Error>?
    private var read: CheckedContinuation<Data, Error>?

    init(assertOnQueue: @escaping () -> Void = {}) {
        self.assertOnQueue = assertOnQueue
    }

    var hasConnect: Bool {
        assertOnQueue()
        return connect != nil
    }

    var hasWrite: Bool {
        assertOnQueue()
        return write != nil
    }

    var hasRead: Bool {
        assertOnQueue()
        return read != nil
    }

    func waitForState(_ continuation: CheckedContinuation<CBManagerState, Never>) {
        assertOnQueue()
        state = continuation
    }

    @discardableResult
    func beginConnect(_ continuation: CheckedContinuation<Void, Error>) -> Bool {
        assertOnQueue()
        return begin(&connect, continuation, operation: "BLE connect")
    }

    @discardableResult
    func beginServiceDiscovery(_ continuation: CheckedContinuation<Void, Error>) -> Bool {
        assertOnQueue()
        return begin(&service, continuation, operation: "BLE service discovery")
    }

    @discardableResult
    func beginCharacteristicDiscovery(_ continuation: CheckedContinuation<Void, Error>) -> Bool {
        assertOnQueue()
        return begin(&characteristic, continuation, operation: "BLE characteristic discovery")
    }

    @discardableResult
    func beginWrite(_ continuation: CheckedContinuation<Void, Error>) -> Bool {
        assertOnQueue()
        return begin(&write, continuation, operation: "BLE write")
    }

    @discardableResult
    func beginRead(_ continuation: CheckedContinuation<Data, Error>) -> Bool {
        assertOnQueue()
        guard read == nil else {
            continuation.resume(throwing: SenderError.socket("BLE read already pending."))
            return false
        }
        read = continuation
        return true
    }

    func resumeState(returning managerState: CBManagerState) {
        assertOnQueue()
        guard let pending = state else {
            return
        }
        state = nil
        pending.resume(returning: managerState)
    }

    func resumeConnect() {
        assertOnQueue()
        resume(&connect)
    }

    @discardableResult
    func failConnect(throwing error: Error) -> Bool {
        assertOnQueue()
        return resume(&connect, throwing: error)
    }

    func resumeService() {
        assertOnQueue()
        resume(&service)
    }

    func failService(throwing error: Error) {
        assertOnQueue()
        resume(&service, throwing: error)
    }

    func resumeCharacteristic() {
        assertOnQueue()
        resume(&characteristic)
    }

    func failCharacteristic(throwing error: Error) {
        assertOnQueue()
        resume(&characteristic, throwing: error)
    }

    func resumeWrite() {
        assertOnQueue()
        resume(&write)
    }

    func failWrite(throwing error: Error) {
        assertOnQueue()
        resume(&write, throwing: error)
    }

    func resumeRead(returning data: Data) {
        assertOnQueue()
        guard let pending = read else {
            return
        }
        read = nil
        pending.resume(returning: data)
    }

    func failRead(throwing error: Error) {
        assertOnQueue()
        guard let pending = read else {
            return
        }
        read = nil
        pending.resume(throwing: error)
    }

    func failAll(throwing error: Error) {
        assertOnQueue()
        resumeState(returning: .unknown)
        failConnect(throwing: error)
        failService(throwing: error)
        failCharacteristic(throwing: error)
        failWrite(throwing: error)
        failRead(throwing: error)
    }

    private func resume(_ continuation: inout CheckedContinuation<Void, Error>?) {
        guard let pending = continuation else {
            return
        }
        continuation = nil
        pending.resume()
    }

    @discardableResult
    private func begin(
        _ storage: inout CheckedContinuation<Void, Error>?,
        _ continuation: CheckedContinuation<Void, Error>,
        operation: String
    ) -> Bool {
        guard storage == nil else {
            continuation.resume(throwing: SenderError.socket("\(operation) already pending."))
            return false
        }
        storage = continuation
        return true
    }

    @discardableResult
    private func resume(_ continuation: inout CheckedContinuation<Void, Error>?, throwing error: Error) -> Bool {
        guard let pending = continuation else {
            return false
        }
        continuation = nil
        pending.resume(throwing: error)
        return true
    }
}

private final class BLETimeout {
    private let queue: DispatchQueue
    private var workItem: DispatchWorkItem?
    private var generation = 0

    init(queue: DispatchQueue) {
        self.queue = queue
    }

    func cancel() {
        workItem?.cancel()
        workItem = nil
        generation &+= 1
    }

    func schedule(after delay: TimeInterval, _ action: @escaping () -> Void) {
        cancel()
        generation &+= 1
        let activeGeneration = generation
        let work = DispatchWorkItem { [weak self] in
            guard let self else {
                return
            }
            guard self.generation == activeGeneration else {
                return
            }
            self.workItem = nil
            action()
        }
        workItem = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }
}

private final class BLEConnectionTimeouts {
    let scan: BLETimeout
    let connect: BLETimeout

    init(queue: DispatchQueue) {
        self.scan = BLETimeout(queue: queue)
        self.connect = BLETimeout(queue: queue)
    }

    func cancelAll() {
        scan.cancel()
        connect.cancel()
    }
}

private enum BLEPacketWriter {
    static func send(
        packet: Data,
        maxChunkBytes: Int,
        pushChunk: (Data) async throws -> Void
    ) async throws {
        let chunkSize = max(maxChunkBytes, 20)
        var offset = 0

        while offset < packet.count {
            let end = min(offset + chunkSize, packet.count)
            try await pushChunk(packet.subdata(in: offset..<end))
            offset = end
        }
    }
}

private final class BLEWriteCoordinator: @unchecked Sendable {
    private let bleQueue: DispatchQueue
    private let connection: BLEConnection
    private let pending: BLEPendingContinuations
    private let streamKey: SymmetricKey

    init(
        bleQueue: DispatchQueue,
        connection: BLEConnection,
        pending: BLEPendingContinuations,
        streamKey: SymmetricKey
    ) {
        self.bleQueue = bleQueue
        self.connection = connection
        self.pending = pending
        self.streamKey = streamKey
    }

    func send(packet: Data) async throws {
        let target = try await currentWriteTarget()
        try await send(packet: packet, to: target)
    }

    func sendHostIdentity(_ hostIdentity: Data) async throws {
        let target = try await currentHostWriteTarget()
        try await send(packet: hostIdentity, to: target)
    }

    private func send(packet: Data, to target: BLEWriteTarget) async throws {
        try await BLEPacketWriter.send(packet: packet, maxChunkBytes: target.maxChunkBytes) { chunk in
            try await self.pushChunkWithResponse(chunk, target: target)
        }
    }

    func currentMTU() async -> Int {
        await withCheckedContinuation { (continuation: CheckedContinuation<Int, Never>) in
            bleQueue.async {
                guard let peripheral = self.connection.peripheral else {
                    continuation.resume(returning: 0)
                    return
                }
                continuation.resume(returning: max(20, peripheral.maximumWriteValueLength(for: .withResponse)))
            }
        }
    }

    func readCommand() async throws -> HostCommand? {
        let data = try await readCommandData()
        if data.count == 4 {
            let magic = data.readUInt32BE(at: 0)
            if magic == FrameProtocol.ackMagic {
                return nil
            }
            if let command = HostCommand.ackCommand(for: magic) {
                return command
            }
        }

        guard data.count >= FrameProtocol.commandHeaderBytes + FrameProtocol.aesGcmNonceBytes + FrameProtocol.aesGcmTagBytes else {
            throw SenderError.socket("BLE command response is too short.")
        }

        let header = data.prefix(FrameProtocol.commandHeaderBytes)
        let magic = header.readUInt32BE(at: 0)
        let version = header[4]
        let flags = header[5]
        let encryptedPayloadLength = Int(UInt16(header[6]) << 8 | UInt16(header[7]))

        guard magic == FrameProtocol.commandMagic else {
            throw SenderError.socket("invalid BLE command magic.")
        }
        guard version == FrameProtocol.protocolVersion else {
            throw SenderError.socket("invalid BLE command protocol version.")
        }
        guard flags == FrameProtocol.flagAesGcm else {
            throw SenderError.socket("invalid BLE command flags.")
        }
        guard data.count == FrameProtocol.commandHeaderBytes + encryptedPayloadLength else {
            throw SenderError.socket("invalid BLE command payload length.")
        }

        let payload = data.dropFirst(FrameProtocol.commandHeaderBytes)
        let nonceData = payload.prefix(FrameProtocol.aesGcmNonceBytes)
        let encryptedCommand = payload
            .dropFirst(FrameProtocol.aesGcmNonceBytes)
            .dropLast(FrameProtocol.aesGcmTagBytes)
        let tag = payload.suffix(FrameProtocol.aesGcmTagBytes)
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let sealedBox = try AES.GCM.SealedBox(
            nonce: nonce,
            ciphertext: encryptedCommand,
            tag: tag
        )
        let commandData = try AES.GCM.open(sealedBox, using: streamKey, authenticating: header)
        guard commandData.count == FrameProtocol.commandPayloadBytes else {
            throw SenderError.socket("invalid BLE command payload.")
        }

        let commandMagic = commandData.readUInt32BE(at: 0)
        if let command = HostCommand.ackCommand(for: commandMagic) {
            return command
        }
        throw SenderError.socket("unknown BLE command.")
    }

    private func currentWriteTarget() async throws -> BLEWriteTarget {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<BLEWriteTarget, Error>) in
            bleQueue.async {
                do {
                    continuation.resume(returning: try self.connection.writeTarget())
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func currentHostWriteTarget() async throws -> BLEWriteTarget {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<BLEWriteTarget, Error>) in
            bleQueue.async {
                do {
                    continuation.resume(returning: try self.connection.hostWriteTarget())
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func currentReadTarget() async throws -> BLEReadTarget {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<BLEReadTarget, Error>) in
            bleQueue.async {
                do {
                    continuation.resume(returning: try self.connection.commandReadTarget())
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func readCommandData() async throws -> Data {
        let target = try await currentReadTarget()
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            bleQueue.async {
                guard self.connection.isCurrent(target) else {
                    continuation.resume(throwing: SenderError.socket("BLE command characteristic not connected."))
                    return
                }
                if target.peripheral.state != .connected {
                    continuation.resume(throwing: SenderError.socket("BLE peripheral disconnected during command read."))
                    return
                }
                if self.pending.hasRead {
                    continuation.resume(throwing: SenderError.socket("BLE read already pending."))
                    return
                }
                guard self.pending.beginRead(continuation) else {
                    return
                }
                target.peripheral.readValue(for: target.characteristic)
            }
        }
    }

    private func pushChunkWithResponse(_ chunk: Data, target: BLEWriteTarget) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            bleQueue.async {
                guard self.connection.isCurrent(target) else {
                    continuation.resume(throwing: SenderError.socket("BLE peripheral not connected."))
                    return
                }
                if target.peripheral.state != .connected {
                    continuation.resume(throwing: SenderError.socket("BLE peripheral disconnected during write."))
                    return
                }
                if self.pending.hasWrite {
                    continuation.resume(throwing: SenderError.socket("BLE write already pending."))
                    return
                }
                guard self.pending.beginWrite(continuation) else {
                    return
                }
                target.peripheral.writeValue(chunk, for: target.characteristic, type: .withResponse)
            }
        }
    }
}

private final class BLETransport: @unchecked Sendable, Transport {
    private let session: BLECentralSession

    init(targetName: String, targetDeviceID: Data?, hostID: Data, scanTimeout: TimeInterval, streamKey: SymmetricKey) {
        self.session = BLECentralSession(
            targetName: targetName,
            targetDeviceID: targetDeviceID,
            hostID: hostID,
            scanTimeout: scanTimeout,
            streamKey: streamKey
        )
    }

    func connect() async throws {
        try await session.connect()
    }

    func send(packet: Data, frameId: UInt32) async throws -> HostCommand? {
        try await session.send(packet: packet)
        return try await session.readCommand()
    }

    func pollCommand() async throws -> HostCommand? {
        try await session.readCommand()
    }

    func close() {
        session.close()
    }

    var transportDescription: String {
        session.transportDescription
    }
}

private final class BLECentralSession: NSObject, @unchecked Sendable, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let bleQueue: DispatchQueue
    private let bleQueueSpecificKey = DispatchSpecificKey<Bool>()
    private var manager: CBCentralManager!
    private let connection: BLEConnection
    private let pending: BLEPendingContinuations
    private let writer: BLEWriteCoordinator
    private let timeouts: BLEConnectionTimeouts
    private let hostID: Data
    private let scanTimeout: TimeInterval

    init(targetName: String, targetDeviceID: Data?, hostID: Data, scanTimeout: TimeInterval, streamKey: SymmetricKey) {
        let queue = DispatchQueue(label: "rg.ble.transport")
        let connection = BLEConnection(targetName: targetName, targetDeviceID: targetDeviceID)
        let pending = BLEPendingContinuations {
            dispatchPrecondition(condition: .onQueue(queue))
        }
        self.bleQueue = queue
        self.connection = connection
        self.pending = pending
        self.writer = BLEWriteCoordinator(
            bleQueue: queue,
            connection: connection,
            pending: pending,
            streamKey: streamKey
        )
        self.timeouts = BLEConnectionTimeouts(queue: queue)
        self.hostID = hostID
        self.scanTimeout = scanTimeout
        super.init()
        bleQueue.setSpecific(key: bleQueueSpecificKey, value: true)
        self.manager = CBCentralManager(
            delegate: self,
            queue: bleQueue,
            options: [CBCentralManagerOptionShowPowerAlertKey: true]
        )
    }

    func connect() async throws {
        let state = await waitForPoweredOn()
        try validatePoweredOn(state)
        try await connectPeripheral()
        try await discoverFrameEndpoint()
        try await writer.sendHostIdentity(hostID)
        await logConnectedMTU()
    }

    func send(packet: Data) async throws {
        try await writer.send(packet: packet)
    }

    func readCommand() async throws -> HostCommand? {
        try await writer.readCommand()
    }

    func close() {
        syncOnBLEQueue {
            self.closeOnBLEQueue()
        }
    }

    var transportDescription: String { connection.transportDescription }

    private func validatePoweredOn(_ state: CBManagerState) throws {
        switch state {
        case .poweredOn:
            return
        case .unauthorized:
            throw SenderError.socket("Bluetooth permission denied. Grant Bluetooth access to this app.")
        case .unsupported:
            throw SenderError.socket("Bluetooth LE is not supported on this Mac.")
        case .poweredOff:
            throw SenderError.socket("Bluetooth is turned off. Enable it and retry.")
        default:
            throw SenderError.socket("Bluetooth not available (state \(state.rawValue)).")
        }
    }

    private func connectPeripheral() async throws {
        if let existing = await retrieveAlreadyConnected() {
            try await useRetrievedPeripheral(existing)
        } else {
            try await scanAndConnect()
        }
    }

    private func discoverFrameEndpoint() async throws {
        try await discoverService()
        try await discoverCharacteristic()
    }

    private func logConnectedMTU() async {
        let mtu = await writer.currentMTU()
        fputs("ble: connected mtu=\(mtu)\n", stderr)
    }

    private func closeOnBLEQueue() {
        let closeError = SenderError.socket("BLE transport closed.")
        timeouts.cancelAll()
        if manager.isScanning {
            manager.stopScan()
        }
        if let peripheral = connection.peripheral {
            manager.cancelPeripheralConnection(peripheral)
        }
        pending.failAll(throwing: closeError)
        connection.reset()
    }

    private func syncOnBLEQueue(_ work: () -> Void) {
        if DispatchQueue.getSpecific(key: bleQueueSpecificKey) == true {
            work()
        } else {
            bleQueue.sync(execute: work)
        }
    }

    private func waitForPoweredOn() async -> CBManagerState {
        return await withCheckedContinuation { continuation in
            bleQueue.async {
                if self.manager.state == .poweredOn {
                    continuation.resume(returning: .poweredOn)
                    return
                }
                if self.manager.state != .unknown && self.manager.state != .resetting {
                    continuation.resume(returning: self.manager.state)
                    return
                }
                self.pending.waitForState(continuation)
            }
        }
    }

    private func retrieveAlreadyConnected() async -> CBPeripheral? {
        await withCheckedContinuation { (continuation: CheckedContinuation<CBPeripheral?, Never>) in
            bleQueue.async {
                if self.connection.requiresAdvertisementMatch {
                    continuation.resume(returning: nil)
                    return
                }
                let serviceUUID = CBUUID(string: BLEUUIDString.service)
                let connected = self.manager.retrieveConnectedPeripherals(withServices: [serviceUUID])
                let matched = connected.first { self.connection.matches(name: $0.name) }
                continuation.resume(returning: matched)
            }
        }
    }

    private func useRetrievedPeripheral(_ peripheral: CBPeripheral) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            bleQueue.async {
                self.connection.attach(peripheral, delegate: self)
                if peripheral.state == .connected {
                    continuation.resume()
                    return
                }
                guard self.pending.beginConnect(continuation) else {
                    return
                }
                self.manager.connect(peripheral, options: nil)
                self.startConnectTimeout(for: peripheral)
            }
        }
    }

    private func startConnectTimeout(for peripheral: CBPeripheral) {
        timeouts.connect.schedule(after: 12.0) { [weak self] in
            guard let self else {
                return
            }
            if self.pending.hasConnect {
                self.manager.cancelPeripheralConnection(peripheral)
                self.pending.failConnect(throwing: SenderError.socket("BLE connect timed out"))
            }
        }
    }

    private func scanAndConnect() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            bleQueue.async {
                let serviceUUID = CBUUID(string: BLEUUIDString.service)
                guard self.pending.beginConnect(continuation) else {
                    return
                }
                self.manager.scanForPeripherals(
                    withServices: [serviceUUID],
                    options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
                )

                self.timeouts.scan.schedule(after: self.scanTimeout) { [weak self] in
                    guard let self else {
                        return
                    }
                    if self.manager.isScanning {
                        self.manager.stopScan()
                    }
                    self.pending.failConnect(throwing: SenderError.socket("BLE scan timed out; no peripheral matched service."))
                }
            }
        }
    }

    private func discoverService() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            bleQueue.async {
                guard let peripheral = self.connection.peripheral else {
                    continuation.resume(throwing: SenderError.socket("BLE peripheral missing during service discovery."))
                    return
                }
                let serviceUUID = CBUUID(string: BLEUUIDString.service)
                guard self.pending.beginServiceDiscovery(continuation) else {
                    return
                }
                peripheral.discoverServices([serviceUUID])
            }
        }
    }

    private func discoverCharacteristic() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            bleQueue.async {
                guard
                    let peripheral = self.connection.peripheral,
                    let service = self.connection.service()
                else {
                    continuation.resume(throwing: SenderError.socket("BLE service not found on peripheral."))
                    return
                }
                guard self.pending.beginCharacteristicDiscovery(continuation) else {
                    return
                }
                let characteristicUUIDs = [
                    CBUUID(string: BLEUUIDString.frameCharacteristic),
                    CBUUID(string: BLEUUIDString.commandCharacteristic),
                    CBUUID(string: BLEUUIDString.hostCharacteristic)
                ]
                peripheral.discoverCharacteristics(characteristicUUIDs, for: service)
            }
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        pending.resumeState(returning: central.state)
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        if !connection.matches(peripheral: peripheral, advertisementData: advertisementData) {
            return
        }
        if connection.peripheral != nil {
            return
        }
        timeouts.scan.cancel()
        central.stopScan()
        connection.attach(peripheral, delegate: self)
        central.connect(peripheral, options: nil)
        startConnectTimeout(for: peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard connection.peripheral === peripheral else {
            return
        }
        timeouts.connect.cancel()
        pending.resumeConnect()
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        guard connection.peripheral === peripheral else {
            return
        }
        timeouts.connect.cancel()
        pending.failConnect(throwing: SenderError.socket("BLE connect failed: \(error?.localizedDescription ?? "unknown")"))
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard connection.peripheral === peripheral else {
            return
        }
        let message = error?.localizedDescription ?? "peripheral disconnected"
        let disconnectError = SenderError.socket("BLE disconnected: \(message)")

        timeouts.cancelAll()
        pending.failAll(throwing: disconnectError)
        connection.reset()
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard connection.peripheral === peripheral else {
            return
        }
        if let error = error {
            pending.failService(throwing: SenderError.socket("Service discovery failed: \(error.localizedDescription)"))
        } else {
            pending.resumeService()
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard connection.peripheral === peripheral else {
            return
        }
        if let error = error {
            pending.failCharacteristic(throwing: SenderError.socket("Characteristic discovery failed: \(error.localizedDescription)"))
            return
        }

        if !connection.selectCharacteristics(from: service) {
            pending.failCharacteristic(throwing: SenderError.socket("Frame, command, or host characteristic missing on peripheral."))
        } else {
            pending.resumeCharacteristic()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard
            connection.peripheral === peripheral,
            connection.frameCharacteristic === characteristic || connection.hostCharacteristic === characteristic
        else {
            return
        }
        if let error = error {
            pending.failWrite(throwing: SenderError.socket("BLE write failed: \(error.localizedDescription)"))
        } else {
            pending.resumeWrite()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard connection.peripheral === peripheral, connection.commandCharacteristic === characteristic else {
            return
        }
        if let error = error {
            pending.failRead(throwing: SenderError.socket("BLE command read failed: \(error.localizedDescription)"))
        } else {
            pending.resumeRead(returning: characteristic.value ?? Data())
        }
    }
}

// MARK: - Socket IO

private func connectSocket(host: String, port: Int) throws -> Int32 {
    guard port > 0, port <= Int(UInt16.max) else {
        throw SenderError.socket("Invalid TCP port: \(port)")
    }

    let socketFd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
    guard socketFd >= 0 else {
        throw SenderError.socket("socket() failed: \(String(cString: strerror(errno)))")
    }

    var noSigPipe: Int32 = 1
    _ = setsockopt(
        socketFd,
        SOL_SOCKET,
        SO_NOSIGPIPE,
        &noSigPipe,
        socklen_t(MemoryLayout<Int32>.size)
    )

    var address = sockaddr_in()
    address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    address.sin_family = sa_family_t(AF_INET)
    address.sin_port = in_port_t(UInt16(port).bigEndian)

    let hostResult = host.withCString { cString in
        inet_pton(AF_INET, cString, &address.sin_addr)
    }
    guard hostResult == 1 else {
        Darwin.close(socketFd)
        throw SenderError.socket("Invalid IPv4 host: \(host)")
    }

    let connectResult = withUnsafePointer(to: &address) { pointer in
        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { rebound in
            Darwin.connect(socketFd, rebound, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }

    if connectResult != 0 {
        let errorString = String(cString: strerror(errno))
        Darwin.close(socketFd)
        throw SenderError.socket("connect() failed: \(errorString)")
    }

    return socketFd
}

private func writeAll(socketFd: Int32, data: Data) throws {
    try data.withUnsafeBytes { rawBuffer in
        guard var baseAddress = rawBuffer.bindMemory(to: UInt8.self).baseAddress else {
            return
        }

        var remaining = rawBuffer.count
        while remaining > 0 {
            let written = Darwin.write(socketFd, baseAddress, remaining)
            if written < 0 {
                if errno == EINTR {
                    continue
                }
                throw SenderError.io("write() failed: \(String(cString: strerror(errno)))")
            }
            if written == 0 {
                throw SenderError.io("write() wrote 0 bytes.")
            }

            remaining -= written
            baseAddress = baseAddress.advanced(by: written)
        }
    }
}

private func readExact(socketFd: Int32, byteCount: Int) throws -> Data {
    var output = Data(count: byteCount)
    var bytesRead = 0

    try output.withUnsafeMutableBytes { rawBuffer in
        guard let baseAddress = rawBuffer.bindMemory(to: UInt8.self).baseAddress else {
            throw SenderError.socket("read buffer unavailable.")
        }

        while bytesRead < byteCount {
            let result = Darwin.read(socketFd, baseAddress.advanced(by: bytesRead), byteCount - bytesRead)
            if result > 0 {
                bytesRead += result
                continue
            }

            if result == 0 {
                throw SenderError.socket("receiver closed connection.")
            }

            if errno == EINTR {
                continue
            }

            throw SenderError.socket("read() failed: \(String(cString: strerror(errno)))")
        }
    }

    return output
}

private func readFrameAck(socketFd: Int32, frameId: UInt32) throws -> HostCommand? {
    let ack = try readExact(socketFd: socketFd, byteCount: 8)
    let magic = ack.readUInt32BE(at: 0)
    let acknowledgedFrameId = ack.readUInt32BE(at: 4)

    guard acknowledgedFrameId == frameId else {
        throw SenderError.socket("invalid frame ack id.")
    }

    if magic == FrameProtocol.ackMagic {
        return nil
    }

    if let command = HostCommand.ackCommand(for: magic) {
        return command
    }

    throw SenderError.socket("invalid frame ack magic.")
}

// MARK: - Host Command Runner

private struct HostCommandResult {
    let succeeded: Bool
    let title: String
    let detail: String
}

private struct HostCommandRunner {
    private let fileManager = FileManager.default

    func run(_ command: HostCommand) -> HostCommandResult {
        guard let scriptPath = resolutionScriptPath() else {
            fputs("host command skipped: resolution script not found for \(command)\n", stderr)
            return HostCommandResult(
                succeeded: false,
                title: "Host script unavailable",
                detail: "host/sender/glass-betterdisplay-resolution.sh was not found on the Mac."
            )
        }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: scriptPath)
        process.arguments = [command.scriptArgument]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe

        do {
            fputs("host command: \(command) via \(scriptPath)\n", stderr)
            try process.run()
            process.waitUntilExit()
            let output = readProcessOutput(pipe)
            if !output.isEmpty {
                fputs(output, stderr)
                if !output.hasSuffix("\n") {
                    fputs("\n", stderr)
                }
            }
            fputs("host command finished: \(command) exit=\(process.terminationStatus)\n", stderr)
            if process.terminationStatus == 0 {
                return HostCommandResult(
                    succeeded: true,
                    title: "Resolution switched",
                    detail: "Applied \(command.scriptArgument) on the host display."
                )
            }

            return failureResult(command: command, output: output)
        } catch {
            fputs("host command failed: \(command): \(error)\n", stderr)
            return HostCommandResult(
                succeeded: false,
                title: "Host command failed",
                detail: "\(error)"
            )
        }
    }

    private func readProcessOutput(_ pipe: Pipe) -> String {
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func failureResult(command: HostCommand, output: String) -> HostCommandResult {
        let trimmedOutput = output.trimmingCharacters(in: .whitespacesAndNewlines)
        if output.contains("BetterDisplay CLI not found") {
            return HostCommandResult(
                succeeded: false,
                title: "BetterDisplay unavailable",
                detail: "Install or launch BetterDisplay on the Mac, then retry \(command.scriptArgument)."
            )
        }

        let detail = trimmedOutput.isEmpty
            ? "Unable to apply \(command.scriptArgument) on the host display."
            : trimmedOutput
        return HostCommandResult(
            succeeded: false,
            title: "Resolution switch failed",
            detail: detail
        )
    }

    private func resolutionScriptPath() -> String? {
        let cwd = URL(fileURLWithPath: fileManager.currentDirectoryPath, isDirectory: true)
        let executableURL = URL(fileURLWithPath: currentExecutablePath()).standardizedFileURL
        let executableDirectory = executableURL.deletingLastPathComponent()
        let appURL = executableDirectory
            .deletingLastPathComponent()
            .deletingLastPathComponent()

        let candidates = [
            cwd.appendingPathComponent("host/sender/glass-betterdisplay-resolution.sh"),
            cwd.appendingPathComponent("sender/glass-betterdisplay-resolution.sh"),
            cwd.appendingPathComponent("glass-betterdisplay-resolution.sh"),
            executableDirectory.appendingPathComponent("glass-betterdisplay-resolution.sh"),
            executableDirectory.deletingLastPathComponent().appendingPathComponent("glass-betterdisplay-resolution.sh"),
            appURL.deletingLastPathComponent().appendingPathComponent("glass-betterdisplay-resolution.sh"),
            appURL.deletingLastPathComponent()
                .deletingLastPathComponent()
                .appendingPathComponent("sender/glass-betterdisplay-resolution.sh")
        ]

        for candidate in candidates {
            let path = candidate.standardizedFileURL.path
            if fileManager.isExecutableFile(atPath: path) {
                return path
            }
        }

        return nil
    }
}

private func randomData(count: Int) throws -> Data {
    var data = Data(count: count)
    let status = data.withUnsafeMutableBytes { buffer in
        SecRandomCopyBytes(kSecRandomDefault, count, buffer.baseAddress!)
    }
    guard status == errSecSuccess else {
        throw SenderError.crypto("Unable to generate random nonce.")
    }
    return data
}

// MARK: - Screen Capture

private func makeCaptureContext(options: Options) async throws -> CaptureContext {
    let content = try await SCShareableContent.current
    let mainDisplayId = CGMainDisplayID()
    let display = content.displays.first(where: { $0.displayID == mainDisplayId }) ?? content.displays.first

    guard let display else {
        throw SenderError.capture("No shareable display found.")
    }

    let displayWidth = max(Int(display.width), 1)
    let displayHeight = max(Int(display.height), 1)
    let outputMode = try resolvedOutputMode(
        options: options,
        displayWidth: displayWidth,
        displayHeight: displayHeight
    )
    let outputSize = outputMode.size
    let sourceLongSide = max(outputSize.width * 2, outputSize.height * 2)
    let sourceWidth: Int
    let sourceHeight: Int

    if displayWidth >= displayHeight {
        sourceWidth = sourceLongSide
        sourceHeight = max(1, Int(round(Double(sourceLongSide * displayHeight) / Double(displayWidth))))
    } else {
        sourceHeight = sourceLongSide
        sourceWidth = max(1, Int(round(Double(sourceLongSide * displayWidth) / Double(displayHeight))))
    }

    let zoomRatio = CGFloat(displayWidth) / CGFloat(max(outputSize.width, 1))
    let zoomMagnification = clamp(zoomRatio, min: minZoomMagnification, max: maxZoomMagnification)

    return CaptureContext(
        filter: SCContentFilter(display: display, excludingWindows: []),
        displayFrame: display.frame,
        sourceWidth: sourceWidth,
        sourceHeight: sourceHeight,
        outputMode: outputMode,
        zoomMagnification: zoomMagnification
    )
}

private func resolvedOutputMode(options: Options, displayWidth: Int, displayHeight: Int) throws -> OutputMode {
    if !options.outputSizeSpecified {
        if displayWidth == FrameSize.rokidPortrait.width && displayHeight == FrameSize.rokidPortrait.height {
            return .fullFrame(.rokidPortrait)
        }
        if displayWidth == FrameSize.rokidLandscape.width && displayHeight == FrameSize.rokidLandscape.height {
            return .fullFrame(.rokidLandscape)
        }

        let outputSize = try FrameSize.validated(width: options.width, height: options.height)
        return .zoomComposite(outputSize)
    }

    let outputSize = try FrameSize.validated(width: options.width, height: options.height)
    return .make(size: outputSize)
}

private func makeStreamConfiguration(options: Options, captureContext: CaptureContext) -> SCStreamConfiguration {
    let configuration = makeCaptureConfiguration(captureContext: captureContext)
    configuration.queueDepth = 3
    configuration.minimumFrameInterval = minimumFrameInterval(fps: options.fps)

    return configuration
}

private func makeCaptureConfiguration(captureContext: CaptureContext) -> SCStreamConfiguration {
    let configuration = SCStreamConfiguration()
    configuration.width = captureContext.sourceWidth
    configuration.height = captureContext.sourceHeight
    configuration.showsCursor = true
    configuration.scalesToFit = true
    configuration.preservesAspectRatio = true
    configuration.backgroundColor = blackBackgroundColor
    configuration.pixelFormat = kCVPixelFormatType_32BGRA

    return configuration
}

private func minimumFrameInterval(fps: Double?) -> CMTime {
    guard let fps, fps > 0 else {
        return .zero
    }

    let timescale: CMTimeScale = 1_000_000
    let value = max(Int64(round(Double(timescale) / fps)), 1)
    return CMTime(value: value, timescale: timescale)
}

private func startCapture(_ stream: SCStream) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        stream.startCapture { error in
            if let error {
                continuation.resume(throwing: error)
            } else {
                continuation.resume()
            }
        }
    }
}

private func stopCapture(_ stream: SCStream) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        stream.stopCapture { error in
            if let error {
                continuation.resume(throwing: error)
            } else {
                continuation.resume()
            }
        }
    }
}

private func isUsableVideoFrame(_ sampleBuffer: CMSampleBuffer) -> Bool {
    guard
        let attachments = CMSampleBufferGetSampleAttachmentsArray(
            sampleBuffer,
            createIfNecessary: false
        ) as? [[SCStreamFrameInfo: Any]],
        let statusRawValue = attachments.first?[.status] as? Int,
        let status = SCFrameStatus(rawValue: statusRawValue)
    else {
        return true
    }

    return status == .complete || status == .started
}

private func captureDisplayImage(captureContext: CaptureContext) async throws -> CGImage {
    let configuration = makeCaptureConfiguration(captureContext: captureContext)
    return try await SCScreenshotManager.captureImage(
        contentFilter: captureContext.filter,
        configuration: configuration
    )
}

private func captureScreenshotFrame(captureContext: CaptureContext) async throws -> [UInt8] {
    let displayImage = try await captureDisplayImage(captureContext: captureContext)
    return try renderFrame(displayImage: displayImage, captureContext: captureContext)
}

// MARK: - Frame Rendering

private enum FrameOutputLayout {
    case fullFrame(overviewRect: CGRect)
    case composite(overviewRect: CGRect, separatorRect: CGRect, zoomRect: CGRect)

    static func make(outputMode: OutputMode) -> FrameOutputLayout {
        let frameSize = outputMode.size
        let outputSize = CGSize(width: CGFloat(frameSize.width), height: CGFloat(frameSize.height))
        let outputRect = CGRect(origin: .zero, size: outputSize)

        if case .fullFrame = outputMode {
            return .fullFrame(overviewRect: outputRect)
        }

        let zoomHeight = floor(outputSize.height * zoomPaneFraction)
        let overviewHeight = max(outputSize.height - zoomHeight - panelSpacing, 1)

        let overviewRect = CGRect(x: 0, y: 0, width: outputSize.width, height: overviewHeight)
        let separatorRect = CGRect(x: 0, y: overviewRect.maxY, width: outputSize.width, height: panelSpacing)
        let zoomRect = CGRect(x: 0, y: separatorRect.maxY, width: outputSize.width, height: zoomHeight)

        return .composite(
            overviewRect: overviewRect,
            separatorRect: separatorRect,
            zoomRect: zoomRect
        )
    }
}

private final class FrameRenderer {
    private let captureContext: CaptureContext
    private let layout: FrameOutputLayout
    private let sourceSize: CGSize
    private let colorSpace = CGColorSpaceCreateDeviceGray()
    private var grayscalePixels: [UInt8]

    init(captureContext: CaptureContext) {
        self.captureContext = captureContext
        self.layout = FrameOutputLayout.make(outputMode: captureContext.outputMode)
        self.sourceSize = CGSize(
            width: captureContext.sourceWidth,
            height: captureContext.sourceHeight
        )
        self.grayscalePixels = [UInt8](
            repeating: 0,
            count: captureContext.outputWidth * captureContext.outputHeight
        )
    }

    func renderGrayscaleFrame(displayImage: CGImage) throws -> [UInt8] {
        try render(displayImage: displayImage)
        return grayscalePixels
    }

    func renderPackedFrame(displayImage: CGImage, into packedFrame: inout Data) throws {
        try render(displayImage: displayImage)
        FrameQuantizer.pack4BitFrame(grayscalePixels, into: &packedFrame)
    }

    private func render(displayImage: CGImage) throws {
        let bitmapContext = grayscalePixels.withUnsafeMutableBytes { rawBuffer in
            CGContext(
                data: rawBuffer.baseAddress,
                width: captureContext.outputWidth,
                height: captureContext.outputHeight,
                bitsPerComponent: 8,
                bytesPerRow: captureContext.outputWidth,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.none.rawValue
            )
        }

        guard let bitmapContext else {
            throw SenderError.capture("Failed to create grayscale context.")
        }

        bitmapContext.setFillColor(gray: 0.0, alpha: 1.0)
        bitmapContext.fill(
            CGRect(x: 0, y: 0, width: captureContext.outputWidth, height: captureContext.outputHeight)
        )
        bitmapContext.interpolationQuality = .high
        renderContent(in: bitmapContext, displayImage: displayImage)
    }

    private func renderContent(in bitmapContext: CGContext, displayImage: CGImage) {
        switch layout {
        case let .fullFrame(overviewRect):
            drawOverviewPane(
                in: bitmapContext,
                displayImage: displayImage,
                overviewRect: overviewRect,
                sourceSize: sourceSize
            )
        case let .composite(overviewRect, separatorRect, zoomRect):
            let focusRect = makeFocusRect(
                captureContext: captureContext,
                zoomRect: zoomRect,
                sourceSize: sourceSize
            )

            bitmapContext.setFillColor(separatorColor)
            bitmapContext.fill(separatorRect)

            drawZoomPane(
                in: bitmapContext,
                displayImage: displayImage,
                zoomRect: zoomRect,
                focusRect: focusRect,
                sourceSize: sourceSize
            )

            drawOverviewPane(
                in: bitmapContext,
                displayImage: displayImage,
                overviewRect: overviewRect,
                sourceSize: sourceSize
            )
        }
    }
}

private func renderFrame(displayImage: CGImage, captureContext: CaptureContext) throws -> [UInt8] {
    try FrameRenderer(captureContext: captureContext).renderGrayscaleFrame(displayImage: displayImage)
}

private func makeFocusRect(
    captureContext: CaptureContext,
    zoomRect: CGRect,
    sourceSize: CGSize
) -> CGRect {
    let cursorPoint = currentCursorPoint(in: captureContext.displayFrame)
    let displayWidth = max(captureContext.displayFrame.width, 1)
    let displayHeight = max(captureContext.displayFrame.height, 1)
    let sourceCursorX = (cursorPoint.x / displayWidth) * sourceSize.width
    let sourceCursorY = (cursorPoint.y / displayHeight) * sourceSize.height

    let cropWidth = min(sourceSize.width, max(sourceSize.width / captureContext.zoomMagnification, 48))
    let cropHeight = min(sourceSize.height, max(cropWidth * (zoomRect.height / zoomRect.width), 48))

    let maxX = max(sourceSize.width - cropWidth, 0)
    let maxY = max(sourceSize.height - cropHeight, 0)
    let originX = clamp(sourceCursorX - (cropWidth * 0.5), min: 0, max: maxX)
    let originY = clamp(sourceCursorY - (cropHeight * 0.5), min: 0, max: maxY)

    return CGRect(x: originX, y: originY, width: cropWidth, height: cropHeight)
}

private func drawZoomPane(
    in bitmapContext: CGContext,
    displayImage: CGImage,
    zoomRect: CGRect,
    focusRect: CGRect,
    sourceSize: CGSize
) {
    bitmapContext.saveGState()
    bitmapContext.clip(to: zoomRect)

    let scaleX = zoomRect.width / focusRect.width
    let scaleY = zoomRect.height / focusRect.height
    let imageRect = CGRect(
        x: zoomRect.minX - (focusRect.minX * scaleX),
        y: zoomRect.minY - (focusRect.minY * scaleY),
        width: sourceSize.width * scaleX,
        height: sourceSize.height * scaleY
    )

    bitmapContext.draw(displayImage, in: imageRect)
    bitmapContext.restoreGState()
}

private func drawOverviewPane(
    in bitmapContext: CGContext,
    displayImage: CGImage,
    overviewRect: CGRect,
    sourceSize: CGSize
) {
    let overviewScale = min(overviewRect.width / sourceSize.width, overviewRect.height / sourceSize.height)
    let imageWidth = sourceSize.width * overviewScale
    let imageHeight = sourceSize.height * overviewScale
    let imageRect = CGRect(
        x: overviewRect.minX + ((overviewRect.width - imageWidth) * 0.5),
        y: overviewRect.minY + ((overviewRect.height - imageHeight) * 0.5),
        width: imageWidth,
        height: imageHeight
    )

    bitmapContext.draw(displayImage, in: imageRect)
}

private func currentCursorPoint(in displayFrame: CGRect) -> CGPoint {
    let mouseLocation = NSEvent.mouseLocation
    let localX = clamp(mouseLocation.x - displayFrame.minX, min: 0, max: displayFrame.width)
    let localY = clamp(mouseLocation.y - displayFrame.minY, min: 0, max: displayFrame.height)
    return CGPoint(x: localX, y: localY)
}

private func clamp(_ value: CGFloat, min minimum: CGFloat, max maximum: CGFloat) -> CGFloat {
    Swift.max(minimum, Swift.min(maximum, value))
}

// MARK: - Frame Encoding

private struct EncodedFramePayload {
    let payload: Data
    let flags: UInt8
}

private struct FramePayloadCandidate {
    let payload: Data
    let flags: UInt8

    var encodedPayload: EncodedFramePayload {
        EncodedFramePayload(payload: payload, flags: flags)
    }
}

private enum FrameQuantizer {
    static func pack4BitFrame(_ grayscalePixels: [UInt8]) -> Data {
        var packed = Data()
        pack4BitFrame(grayscalePixels, into: &packed)
        return packed
    }

    static func pack4BitFrame(_ grayscalePixels: [UInt8], into packed: inout Data) {
        let packedByteCount = (grayscalePixels.count + 1) / 2
        if packed.count != packedByteCount {
            packed = Data(count: packedByteCount)
        }
        if grayscalePixels.isEmpty {
            return
        }

        packed.withUnsafeMutableBytes { rawBuffer in
            let destination = rawBuffer.bindMemory(to: UInt8.self)
            var sourceIndex = 0
            var destinationIndex = 0

            while sourceIndex < grayscalePixels.count {
                let high = grayscalePixels[sourceIndex] >> 4
                let low: UInt8
                if sourceIndex + 1 < grayscalePixels.count {
                    low = grayscalePixels[sourceIndex + 1] >> 4
                } else {
                    low = 0
                }

                destination[destinationIndex] = (high << 4) | (low & 0x0F)
                sourceIndex += 2
                destinationIndex += 1
            }
        }
    }

    static func unpack4BitFrame(_ packedFrame: Data, pixelCount: Int) -> [UInt8] {
        var unpackedPixels = [UInt8](repeating: 0, count: pixelCount)

        packedFrame.withUnsafeBytes { rawBuffer in
            let source = rawBuffer.bindMemory(to: UInt8.self)
            var sourceIndex = 0
            var destinationIndex = 0

            while sourceIndex < source.count && destinationIndex < unpackedPixels.count {
                let byte = source[sourceIndex]
                unpackedPixels[destinationIndex] = (byte >> 4) * 17
                destinationIndex += 1

                if destinationIndex < unpackedPixels.count {
                    unpackedPixels[destinationIndex] = (byte & 0x0F) * 17
                    destinationIndex += 1
                }

                sourceIndex += 1
            }
        }

        return unpackedPixels
    }
}

private enum QuantizedFramePNGWriter {
    static func writeQuantizedFramePNG(
        packedFrame: Data,
        width: Int,
        height: Int,
        outputURL: URL
    ) throws {
        let pixelCount = width * height
        let unpackedPixels = FrameQuantizer.unpack4BitFrame(packedFrame, pixelCount: pixelCount)
        let imageData = Data(unpackedPixels)
        let colorSpace = CGColorSpaceCreateDeviceGray()

        guard let provider = CGDataProvider(data: imageData as CFData) else {
            throw SenderError.io("Failed to create image provider for \(outputURL.path).")
        }

        guard let image = CGImage(
            width: width,
            height: height,
            bitsPerComponent: 8,
            bitsPerPixel: 8,
            bytesPerRow: width,
            space: colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
            provider: provider,
            decode: nil,
            shouldInterpolate: false,
            intent: .defaultIntent
        ) else {
            throw SenderError.io("Failed to create image for \(outputURL.path).")
        }

        let directoryURL = outputURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(
            at: directoryURL,
            withIntermediateDirectories: true
        )

        guard let destination = CGImageDestinationCreateWithURL(
            outputURL as CFURL,
            UTType.png.identifier as CFString,
            1,
            nil
        ) else {
            throw SenderError.io("Failed to create PNG destination for \(outputURL.path).")
        }

        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else {
            throw SenderError.io("Failed to write PNG to \(outputURL.path).")
        }
    }
}

private struct FramePayloadEncoder {
    private var deltaBuffer = Data()
    private var fullCompressionBuffer = Data()
    private var deltaCompressionBuffer = Data()

    mutating func makeFramePayload(
        packedFrame: Data,
        previousPackedFrame: Data,
        transportKind: TransportKind
    ) -> EncodedFramePayload {
        if transportKind == .tcp {
            return EncodedFramePayload(payload: packedFrame, flags: 0)
        }

        let fullCandidate = makeFullCandidate(packedFrame)
        guard !previousPackedFrame.isEmpty else {
            return fullCandidate.encodedPayload
        }

        let deltaCandidate = makeDeltaCandidate(packedFrame, previousPackedFrame: previousPackedFrame)
        if deltaCandidate.payload.count < fullCandidate.payload.count {
            return deltaCandidate.encodedPayload
        }

        return fullCandidate.encodedPayload
    }

    private mutating func makeFullCandidate(_ packedFrame: Data) -> FramePayloadCandidate {
        if let compressed = Self.zlibCompress(packedFrame, into: &fullCompressionBuffer) {
            return FramePayloadCandidate(payload: compressed, flags: FrameProtocol.flagDeflate)
        }

        return FramePayloadCandidate(payload: packedFrame, flags: 0)
    }

    private mutating func makeDeltaCandidate(
        _ packedFrame: Data,
        previousPackedFrame: Data
    ) -> FramePayloadCandidate {
        let delta = xorBytes(packedFrame, previousPackedFrame)
        if let compressed = Self.zlibCompress(delta, into: &deltaCompressionBuffer) {
            return FramePayloadCandidate(
                payload: compressed,
                flags: FrameProtocol.flagDelta | FrameProtocol.flagDeflate
            )
        }

        return FramePayloadCandidate(payload: delta, flags: FrameProtocol.flagDelta)
    }

    private static func zlibCompress(_ data: Data, into compressed: inout Data) -> Data? {
        if data.isEmpty {
            return nil
        }

        let scratchSize = compression_encode_scratch_buffer_size(COMPRESSION_ZLIB)
        let destinationCapacity = data.count + (data.count / 8) + 64
        if compressed.count != destinationCapacity {
            compressed.count = destinationCapacity
        }

        let written = compressed.withUnsafeMutableBytes { destinationBuffer in
            data.withUnsafeBytes { sourceBuffer in
                guard
                    let destination = destinationBuffer.bindMemory(to: UInt8.self).baseAddress,
                    let source = sourceBuffer.bindMemory(to: UInt8.self).baseAddress
                else {
                    return 0
                }

                let scratch = UnsafeMutablePointer<UInt8>.allocate(capacity: scratchSize)
                defer { scratch.deallocate() }

                return compression_encode_buffer(
                    destination,
                    destinationCapacity,
                    source,
                    data.count,
                    scratch,
                    COMPRESSION_ZLIB
                )
            }
        }

        guard written > 0, written < data.count else {
            return nil
        }

        compressed.count = written
        return compressed
    }

    private mutating func xorBytes(_ a: Data, _ b: Data) -> Data {
        precondition(a.count == b.count)
        if deltaBuffer.count != a.count {
            deltaBuffer = Data(count: a.count)
        }
        a.withUnsafeBytes { aBuf in
            b.withUnsafeBytes { bBuf in
                deltaBuffer.withUnsafeMutableBytes { rBuf in
                    guard
                        let aPtr = aBuf.bindMemory(to: UInt8.self).baseAddress,
                        let bPtr = bBuf.bindMemory(to: UInt8.self).baseAddress,
                        let rPtr = rBuf.bindMemory(to: UInt8.self).baseAddress
                    else { return }
                    for index in 0..<a.count {
                        rPtr[index] = aPtr[index] ^ bPtr[index]
                    }
                }
            }
        }
        return deltaBuffer
    }
}

private enum FramePacketCodec {
    static func makePacket(
        frameSize: FrameSize,
        frameId: UInt32,
        payload: Data,
        payloadFlags: UInt8,
        streamKey: SymmetricKey
    ) throws -> Data {
        let encodedWidth = try encodedUInt16(frameSize.width, field: "frame width")
        let encodedHeight = try encodedUInt16(frameSize.height, field: "frame height")
        let flags = payloadFlags | FrameProtocol.flagAesGcm
        let encryptedPayloadLength = try encryptedPayloadByteCount(for: payload.count)
        let encodedPayloadLength = UInt32(encryptedPayloadLength)
        var header = Data(capacity: FrameProtocol.headerBytes)
        header.appendUInt32BE(FrameProtocol.magic)
        header.append(FrameProtocol.protocolVersion)
        header.append(flags)
        header.appendUInt16BE(encodedWidth)
        header.appendUInt16BE(encodedHeight)
        header.appendUInt32BE(encodedPayloadLength)
        header.appendUInt32BE(frameId)

        let nonceData = try randomData(count: FrameProtocol.aesGcmNonceBytes)
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let sealedBox = try AES.GCM.seal(payload, using: streamKey, nonce: nonce, authenticating: header)

        var packet = Data(capacity: FrameProtocol.headerBytes + encryptedPayloadLength)
        packet.append(header)
        packet.append(nonceData)
        packet.append(sealedBox.ciphertext)
        packet.append(sealedBox.tag)
        return packet
    }

    static func makeHostStatusPacket(
        title: String,
        detail: String,
        frameId: UInt32,
        streamKey: SymmetricKey
    ) throws -> Data {
        let payload = Data("\(title)\n\(detail)".utf8)
        let flags = FrameProtocol.flagAesGcm | FrameProtocol.flagHostStatus
        let encryptedPayloadLength = try encryptedPayloadByteCount(for: payload.count)
        let encodedPayloadLength = UInt32(encryptedPayloadLength)
        var header = Data(capacity: FrameProtocol.headerBytes)
        header.appendUInt32BE(FrameProtocol.magic)
        header.append(FrameProtocol.protocolVersion)
        header.append(flags)
        header.appendUInt16BE(1)
        header.appendUInt16BE(1)
        header.appendUInt32BE(encodedPayloadLength)
        header.appendUInt32BE(frameId)

        let nonceData = try randomData(count: FrameProtocol.aesGcmNonceBytes)
        let nonce = try AES.GCM.Nonce(data: nonceData)
        let sealedBox = try AES.GCM.seal(payload, using: streamKey, nonce: nonce, authenticating: header)

        var packet = Data(capacity: FrameProtocol.headerBytes + encryptedPayloadLength)
        packet.append(header)
        packet.append(nonceData)
        packet.append(sealedBox.ciphertext)
        packet.append(sealedBox.tag)
        return packet
    }

    private static func encodedUInt16(_ value: Int, field: String) throws -> UInt16 {
        guard value > 0, value <= Int(UInt16.max) else {
            throw SenderError.usage("Invalid \(field): \(value).")
        }
        return UInt16(value)
    }

    private static func encryptedPayloadByteCount(for payloadByteCount: Int) throws -> Int {
        let overheadBytes = FrameProtocol.encryptedPayloadOverheadBytes
        guard payloadByteCount <= Int(UInt32.max) - overheadBytes else {
            throw SenderError.io("Frame payload too large for protocol header.")
        }
        let encryptedByteCount = overheadBytes + payloadByteCount
        guard encryptedByteCount <= FrameProtocol.maxEncryptedPayloadBytes else {
            throw SenderError.io("Frame payload too large for receiver.")
        }
        return encryptedByteCount
    }
}

private struct FrameTransmissionPipeline {
    private let frameSize: FrameSize
    private let transportKind: TransportKind
    private let streamKey: SymmetricKey
    private var payloadEncoder = FramePayloadEncoder()

    init(frameSize: FrameSize, transportKind: TransportKind, streamKey: SymmetricKey) {
        self.frameSize = frameSize
        self.transportKind = transportKind
        self.streamKey = streamKey
    }

    mutating func makePacket(
        currentPackedFrame: Data,
        previousPackedFrame: Data,
        frameId: UInt32
    ) throws -> Data {
        let framePayload = payloadEncoder.makeFramePayload(
            packedFrame: currentPackedFrame,
            previousPackedFrame: previousPackedFrame,
            transportKind: transportKind
        )

        return try FramePacketCodec.makePacket(
            frameSize: frameSize,
            frameId: frameId,
            payload: framePayload.payload,
            payloadFlags: framePayload.flags,
            streamKey: streamKey
        )
    }
}

// MARK: - Sender Session

private struct FrameStatsLogger {
    private let fpsLimitDescription: String
    private var sentFrames = 0
    private var skippedFrames = 0
    private var lastLogTime = CFAbsoluteTimeGetCurrent()

    init(fpsLimit: Double?) {
        self.fpsLimitDescription = fpsLimit.map { String(format: "%.1f", $0) } ?? "none"
    }

    mutating func recordSentFrame() {
        sentFrames += 1
    }

    mutating func recordSkippedFrame() {
        skippedFrames += 1
    }

    mutating func logIfNeeded() {
        let now = CFAbsoluteTimeGetCurrent()
        guard now - lastLogTime >= 1.0 else {
            return
        }

        let elapsed = now - lastLogTime
        let capturedFrames = sentFrames + skippedFrames
        let measuredFps = elapsed > 0 ? Double(capturedFrames) / elapsed : 0.0
        let logLine = String(
            format: "sent=%d skipped=%d fps=%.1f limit=%@\n",
            sentFrames,
            skippedFrames,
            measuredFps,
            fpsLimitDescription
        )
        fputs(logLine, stderr)
        sentFrames = 0
        skippedFrames = 0
        lastLogTime = now
    }
}

private struct FrameThrottle {
    private let frameInterval: TimeInterval?
    private var nextDeadline = CFAbsoluteTimeGetCurrent()

    init(fpsLimit: Double?) {
        self.frameInterval = fpsLimit.map { 1.0 / $0 }
    }

    mutating func waitIfNeeded() async throws {
        guard let frameInterval else {
            return
        }

        nextDeadline += frameInterval
        let remaining = nextDeadline - CFAbsoluteTimeGetCurrent()
        if remaining > 0 {
            try await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000.0))
        } else {
            nextDeadline = CFAbsoluteTimeGetCurrent()
        }
    }
}

private struct SenderSession {
    let transport: Transport
    let options: Options
    let captureContext: CaptureContext

    private let frameRenderer: FrameRenderer
    private let hostCommandRunner = HostCommandRunner()
    private let streamKey: SymmetricKey
    private var transmissionPipeline: FrameTransmissionPipeline
    private var statsLogger: FrameStatsLogger
    private var frameThrottle: FrameThrottle
    private var currentPackedFrame: Data
    private var previousPackedFrame = Data()
    private var frameId: UInt32 = 0

    init(
        transport: Transport,
        options: Options,
        captureContext: CaptureContext,
        streamKey: SymmetricKey
    ) {
        self.transport = transport
        self.options = options
        self.captureContext = captureContext
        self.streamKey = streamKey
        self.frameRenderer = FrameRenderer(captureContext: captureContext)
        self.transmissionPipeline = FrameTransmissionPipeline(
            frameSize: captureContext.outputSize,
            transportKind: options.transport,
            streamKey: streamKey
        )
        self.statsLogger = FrameStatsLogger(fpsLimit: options.fps)
        self.frameThrottle = FrameThrottle(fpsLimit: options.fps)
        self.currentPackedFrame = Data(count: captureContext.outputSize.packedByteCount)
    }

    mutating func run() async throws {
        let frameCapture = StreamingFrameCapture(options: options, captureContext: captureContext)
        let displayFrames = try await frameCapture.start()

        do {
            for try await displayImage in displayFrames {
                try await processFrame(displayImage)
            }

            throw SenderError.capture("capture stream ended.")
        } catch {
            await frameCapture.stop()
            throw error
        }
    }

    private mutating func processFrame(_ displayImage: CGImage) async throws {
        try frameRenderer.renderPackedFrame(displayImage: displayImage, into: &currentPackedFrame)

        try await sendCurrentPackedFrameIfNeeded()
        statsLogger.logIfNeeded()
        try await frameThrottle.waitIfNeeded()
    }

    private mutating func sendCurrentPackedFrameIfNeeded() async throws {
        let shouldSendFrame = options.transport == .tcp || currentPackedFrame != previousPackedFrame
        if shouldSendFrame {
            try await sendCurrentPackedFrame()
        } else {
            if let hostCommand = try await transport.pollCommand() {
                try await handleHostCommand(hostCommand)
            }
            statsLogger.recordSkippedFrame()
        }
    }

    private mutating func sendCurrentPackedFrame() async throws {
        let packet = try transmissionPipeline.makePacket(
            currentPackedFrame: currentPackedFrame,
            previousPackedFrame: previousPackedFrame,
            frameId: frameId
        )
        let hostCommand = try await transport.send(packet: packet, frameId: frameId)
        if let hostCommand {
            try await handleHostCommand(hostCommand)
        }

        swap(&currentPackedFrame, &previousPackedFrame)
        frameId &+= 1
        statsLogger.recordSentFrame()
    }

    private mutating func handleHostCommand(_ hostCommand: HostCommand) async throws {
        let result = hostCommandRunner.run(hostCommand)
        if result.succeeded {
            throw SenderError.capture("host command applied; restarting capture.")
        }

        let statusPacket = try FramePacketCodec.makeHostStatusPacket(
            title: result.title,
            detail: result.detail,
            frameId: frameId,
            streamKey: streamKey
        )
        _ = try await transport.send(packet: statusPacket, frameId: frameId)
    }
}

// MARK: - App Runtime

private func streamFrames(
    transport: Transport,
    options: Options,
    captureContext: CaptureContext,
    streamKey: SymmetricKey
) async throws {
    var session = SenderSession(
        transport: transport,
        options: options,
        captureContext: captureContext,
        streamKey: streamKey
    )
    try await session.run()
}

private func saveSingleFrame(options: Options, outputPath: String) async throws {
    let captureContext = try await makeCaptureContext(options: options)
    let grayscalePixels = try await captureScreenshotFrame(captureContext: captureContext)
    let packedFrame = FrameQuantizer.pack4BitFrame(grayscalePixels)
    let outputURL = URL(fileURLWithPath: outputPath).standardizedFileURL

    try QuantizedFramePNGWriter.writeQuantizedFramePNG(
        packedFrame: packedFrame,
        width: captureContext.outputWidth,
        height: captureContext.outputHeight,
        outputURL: outputURL
    )

    fputs("saved frame to \(outputURL.path)\n", stderr)
}

private func makeSyntheticPackedFrame(frameSize: FrameSize, frameIndex: Int) -> Data {
    var packedFrame = Data(count: frameSize.packedByteCount)
    packedFrame.withUnsafeMutableBytes { rawBuffer in
        guard let bytes = rawBuffer.bindMemory(to: UInt8.self).baseAddress else {
            return
        }

        for index in 0..<frameSize.packedByteCount {
            let high = UInt8((index + frameIndex) & 0x0F)
            let low = UInt8(((index / max(1, frameSize.width / 2)) + frameIndex) & 0x0F)
            bytes[index] = (high << 4) | low
        }
    }
    return packedFrame
}

private func sendSyntheticFrames(options: Options, frameCount: Int) async throws {
    guard options.transport == .tcp else {
        throw SenderError.usage("--synthetic-frames supports --transport tcp only.")
    }

    let frameSize = try FrameSize.validated(width: options.width, height: options.height)
    let streamKey = try loadStreamKey(options: options)
    let transport = try await makeTransport(options: options, streamKey: streamKey)
    defer {
        transport.close()
    }

    var pipeline = FrameTransmissionPipeline(
        frameSize: frameSize,
        transportKind: options.transport,
        streamKey: streamKey
    )
    var previousPackedFrame = Data()

    for frameIndex in 0..<frameCount {
        let currentPackedFrame = makeSyntheticPackedFrame(frameSize: frameSize, frameIndex: frameIndex)
        let frameId = UInt32(frameIndex)
        let packet = try pipeline.makePacket(
            currentPackedFrame: currentPackedFrame,
            previousPackedFrame: previousPackedFrame,
            frameId: frameId
        )
        _ = try await transport.send(packet: packet, frameId: frameId)
        previousPackedFrame = currentPackedFrame
    }

    fputs("synthetic frames sent: \(frameCount)\n", stderr)
}

private func makeTransport(options: Options, streamKey: SymmetricKey) async throws -> Transport {
    switch options.transport {
    case .tcp:
        return try TCPTransport(host: options.host, port: options.port)
    case .ble:
        guard let bleHostID = options.bleHostID else {
            throw SenderError.usage("--ble-host-id-hex is required for --transport ble.")
        }
        let transport = BLETransport(
            targetName: options.bleName,
            targetDeviceID: options.bleDeviceID,
            hostID: bleHostID,
            scanTimeout: options.bleScanTimeout,
            streamKey: streamKey
        )
        do {
            try await transport.connect()
        } catch {
            transport.close()
            throw error
        }
        return transport
    }
}

private struct RuntimeSupervisor {
    private let options: Options
    private var captureContext: CaptureContext?
    private var streamKey: SymmetricKey?
    private var lastWaitMessage: String?
    private var hadConnection = false

    init(options: Options) {
        self.options = options
    }

    mutating func run() async -> Never {
        while true {
            do {
                try await streamOnce()
            } catch {
                handleFailure(error)
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private mutating func streamOnce() async throws {
        let activeStreamKey = try cachedStreamKey()
        let activeCaptureContext = try await cachedCaptureContext()
        let activeTransport = try await makeTransport(options: options, streamKey: activeStreamKey)
        defer {
            activeTransport.close()
        }

        fputs("connected via \(activeTransport.transportDescription)\n", stderr)
        lastWaitMessage = nil
        hadConnection = true
        try await streamFrames(
            transport: activeTransport,
            options: options,
            captureContext: activeCaptureContext,
            streamKey: activeStreamKey
        )
    }

    private mutating func cachedStreamKey() throws -> SymmetricKey {
        if let streamKey {
            return streamKey
        }

        let loadedStreamKey = try loadStreamKey(options: options)
        streamKey = loadedStreamKey
        return loadedStreamKey
    }

    private mutating func cachedCaptureContext() async throws -> CaptureContext {
        if let captureContext {
            return captureContext
        }

        let loadedCaptureContext = try await makeCaptureContext(options: options)
        captureContext = loadedCaptureContext
        return loadedCaptureContext
    }

    private mutating func handleFailure(_ error: Error) {
        if let senderError = error as? SenderError, case .capture = senderError {
            captureContext = nil
        }

        let waitMessage: String
        if hadConnection {
            waitMessage = "stream paused: \(error)"
        } else {
            waitMessage = "waiting for receiver: \(error)"
        }

        if waitMessage != lastWaitMessage {
            fputs("\(waitMessage)\n", stderr)
            lastWaitMessage = waitMessage
        }

        hadConnection = false
    }
}

private func run(options: Options) async -> Never {
    var supervisor = RuntimeSupervisor(options: options)
    await supervisor.run()
}

// MARK: - Data Extensions

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    func readUInt32BE(at offset: Int) -> UInt32 {
        return (UInt32(self[offset]) << 24) |
            (UInt32(self[offset + 1]) << 16) |
            (UInt32(self[offset + 2]) << 8) |
            UInt32(self[offset + 3])
    }

    mutating func appendUInt16BE(_ value: UInt16) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { buffer in
            append(buffer.bindMemory(to: UInt8.self))
        }
    }

    mutating func appendUInt32BE(_ value: UInt32) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { buffer in
            append(buffer.bindMemory(to: UInt8.self))
        }
    }
}

// MARK: - Main

do {
    let arguments = Array(CommandLine.arguments.dropFirst())
    if arguments.contains("--help") || arguments.contains("-h") {
        print(usageText)
        exit(0)
    }

    let options = try parseOptions(arguments: arguments)
    if let syntheticFrameCount = options.syntheticFrameCount {
        Task {
            do {
                try await sendSyntheticFrames(options: options, frameCount: syntheticFrameCount)
                exit(0)
            } catch {
                fputs("\(error)\n", stderr)
                exit(1)
            }
        }
        dispatchMain()
    }

    try ensureScreenCapturePermission()

    if let saveImagePath = options.saveImagePath {
        Task {
            do {
                try await saveSingleFrame(options: options, outputPath: saveImagePath)
                exit(0)
            } catch {
                fputs("\(error)\n", stderr)
                exit(1)
            }
        }
    } else {
        Task {
            await run(options: options)
        }
    }
    dispatchMain()
} catch {
    fputs("\(error)\n", stderr)
    exit(1)
}
