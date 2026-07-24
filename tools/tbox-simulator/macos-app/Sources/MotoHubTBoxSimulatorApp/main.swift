import Foundation
import SwiftUI
import AppKit
import CoreImage.CIFilterBuiltins
import CoreWLAN

@main
struct MotoHubTBoxSimulatorApp: App {
    @NSApplicationDelegateAdaptor(SimulatorAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup("MOTO-HUB T-Box Simulator") {
            ContentView(model: appDelegate.model)
                .frame(minWidth: 780, minHeight: 700)
                .padding(22)
        }
    }
}

@MainActor
final class SimulatorAppDelegate: NSObject, NSApplicationDelegate {
    let model = SimulatorModel()

    func applicationWillTerminate(_ notification: Notification) {
        model.stop()
    }
}

@MainActor
final class SimulatorModel: ObservableObject {
    struct DisplayGeometry: Equatable {
        let displayWidth: Int
        let displayHeight: Int
        let safeX: Int
        let safeY: Int
        let safeWidth: Int
        let safeHeight: Int
    }

    enum DisplayProfile: String, CaseIterable, Identifiable {
        case automatic = "automatic"
        case landscapeSD = "landscape_sd"
        case landscapeHD = "landscape_hd"
        case portraitSD = "portrait_sd"
        case portraitHD = "portrait_hd"
        case manual = "manual"

        var id: String { rawValue }

        var label: String {
            switch self {
            case .automatic: return "Auto · TFT 800 x 480 / app 800 x 384"
            case .landscapeSD: return "Full canvas · Landscape 800 x 480"
            case .landscapeHD: return "Full canvas · Landscape 1280 x 720"
            case .portraitSD: return "Full canvas · Portrait 720 x 1280"
            case .portraitHD: return "Full canvas · Portrait 1080 x 1920"
            case .manual: return "Manuale"
            }
        }

        var geometry: DisplayGeometry? {
            switch self {
            case .automatic:
                return DisplayGeometry(
                    displayWidth: 800,
                    displayHeight: 480,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 800,
                    safeHeight: 384
                )
            case .landscapeSD:
                return DisplayGeometry(
                    displayWidth: 800,
                    displayHeight: 480,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 800,
                    safeHeight: 480
                )
            case .landscapeHD:
                return DisplayGeometry(
                    displayWidth: 1280,
                    displayHeight: 720,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 1280,
                    safeHeight: 720
                )
            case .portraitSD:
                return DisplayGeometry(
                    displayWidth: 720,
                    displayHeight: 1280,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 720,
                    safeHeight: 1280
                )
            case .portraitHD:
                return DisplayGeometry(
                    displayWidth: 1080,
                    displayHeight: 1920,
                    safeX: 0,
                    safeY: 0,
                    safeWidth: 1080,
                    safeHeight: 1920
                )
            case .manual: return nil
            }
        }
    }

    enum CompatibilityProfile: String, CaseIterable, Identifiable {
        case motohub = "motohub"
        case cfdl16 = "cfdl16"
        case cfdl26Portrait = "cfdl26-portrait"
        case cfdl26Landscape = "cfdl26-landscape"
        case nk800Crcp = "800nk-crcp"
        case nk800Touch = "800nk-touch"
        case model66660742 = "66660742"

        var id: String { rawValue }

        var label: String {
            switch self {
            case .motohub: return "MOTO-HUB Simulator"
            case .cfdl16: return "CFDL16 legacy · 37416"
            case .cfdl26Portrait: return "CFDL26 portrait · 37426"
            case .cfdl26Landscape: return "CFDL26 landscape · 37426"
            case .nk800Crcp: return "800NK CRCP · 66660703"
            case .nk800Touch: return "800NK touch · 37426"
            case .model66660742: return "CFDL16 MotoPlay · 66660742"
            }
        }

        var modelId: String {
            switch self {
            case .motohub: return "MOTO-HUB-SIMULATOR"
            case .cfdl16: return "37416"
            case .cfdl26Portrait, .cfdl26Landscape, .nk800Touch: return "37426"
            case .nk800Crcp: return "66660703"
            case .model66660742: return "66660742"
            }
        }

        var qrName: String {
            switch self {
            case .motohub: return "MOTO-HUB Simulator"
            case .cfdl16: return "CFDL16-6GUV"
            case .cfdl26Portrait: return "CFMOTO-805120"
            case .cfdl26Landscape: return "CFMOTO1565"
            case .nk800Crcp, .nk800Touch: return "CFMOTO-800NK"
            case .model66660742: return "CFMOTO-60742"
            }
        }

        var serial: String {
            switch self {
            case .motohub: return "MOTO-HUB-SIM"
            case .cfdl16: return "peTz"
            case .cfdl26Portrait, .cfdl26Landscape: return "0rLs"
            case .nk800Crcp: return "800NK"
            case .nk800Touch: return "800NKT"
            case .model66660742: return "60742"
            }
        }
    }

    private struct CoreConfiguration: Equatable {
        let compatibilityProfile: CompatibilityProfile
        let geometry: DisplayGeometry
        let heartbeatSeconds: Double
    }

    private enum PreferenceKey {
        // Keep the old keys only to migrate existing projection dimensions.
        static let width = "simulator.width"
        static let height = "simulator.height"
        static let displayWidth = "simulator.displayWidth"
        static let displayHeight = "simulator.displayHeight"
        static let safeX = "simulator.safeX"
        static let safeY = "simulator.safeY"
        static let safeWidth = "simulator.safeWidth"
        static let safeHeight = "simulator.safeHeight"
        static let displayProfile = "simulator.displayProfile"
        static let compatibilityProfile = "simulator.compatibilityProfile"
        static let heartbeat = "simulator.heartbeat"
        static let networkSSID = "simulator.networkSSID"
        static let networkPassword = "simulator.networkPassword"
    }

    @Published var displayWidth: String {
        didSet {
            UserDefaults.standard.set(displayWidth, forKey: PreferenceKey.displayWidth)
            markProfileManualIfNeeded()
        }
    }
    @Published var displayHeight: String {
        didSet {
            UserDefaults.standard.set(displayHeight, forKey: PreferenceKey.displayHeight)
            markProfileManualIfNeeded()
        }
    }
    @Published var safeX: String {
        didSet {
            UserDefaults.standard.set(safeX, forKey: PreferenceKey.safeX)
            markProfileManualIfNeeded()
        }
    }
    @Published var safeY: String {
        didSet {
            UserDefaults.standard.set(safeY, forKey: PreferenceKey.safeY)
            markProfileManualIfNeeded()
        }
    }
    @Published var safeWidth: String {
        didSet {
            UserDefaults.standard.set(safeWidth, forKey: PreferenceKey.safeWidth)
            markProfileManualIfNeeded()
        }
    }
    @Published var safeHeight: String {
        didSet {
            UserDefaults.standard.set(safeHeight, forKey: PreferenceKey.safeHeight)
            markProfileManualIfNeeded()
        }
    }
    @Published var displayProfile: DisplayProfile {
        didSet {
            UserDefaults.standard.set(displayProfile.rawValue, forKey: PreferenceKey.displayProfile)
            guard !applyingDisplayProfile, let geometry = displayProfile.geometry else { return }
            applyGeometry(geometry)
        }
    }
    @Published var compatibilityProfile: CompatibilityProfile {
        didSet { UserDefaults.standard.set(compatibilityProfile.rawValue, forKey: PreferenceKey.compatibilityProfile) }
    }
    @Published var heartbeat: String {
        didSet { UserDefaults.standard.set(heartbeat, forKey: PreferenceKey.heartbeat) }
    }
    @Published var networkSSID: String {
        didSet { UserDefaults.standard.set(networkSSID, forKey: PreferenceKey.networkSSID) }
    }
    @Published var networkPassword: String {
        didSet { UserDefaults.standard.set(networkPassword, forKey: PreferenceKey.networkPassword) }
    }
    @Published var controlPort = 0
    @Published var coreStarted = false
    @Published var running = false
    @Published var phoneIP = ""
    @Published var frames = 0
    @Published var logLines: [String] = []
    @Published var errorMessage: String?

    private var process: Process?
    private var statusTask: Task<Void, Never>?
    private var applyingDisplayProfile = false
    private var activeCoreConfiguration: CoreConfiguration?
    private var restartRequested = false

    init() {
        let defaults = UserDefaults.standard
        let savedProfile = defaults.string(forKey: PreferenceKey.displayProfile)
            .flatMap(DisplayProfile.init(rawValue:))
        let fallbackGeometry = savedProfile?.geometry ?? DisplayProfile.automatic.geometry!
        let legacySafeWidth = defaults.string(forKey: PreferenceKey.width)
            ?? String(fallbackGeometry.safeWidth)
        let legacySafeHeight = defaults.string(forKey: PreferenceKey.height)
            ?? String(fallbackGeometry.safeHeight)
        let restoredDisplayWidth = defaults.string(forKey: PreferenceKey.displayWidth)
            ?? String(fallbackGeometry.displayWidth)
        let restoredDisplayHeight = defaults.string(forKey: PreferenceKey.displayHeight)
            ?? String(fallbackGeometry.displayHeight)
        let restoredSafeX = defaults.string(forKey: PreferenceKey.safeX)
            ?? String(fallbackGeometry.safeX)
        let restoredSafeY = defaults.string(forKey: PreferenceKey.safeY)
            ?? String(fallbackGeometry.safeY)
        let restoredSafeWidth = defaults.string(forKey: PreferenceKey.safeWidth) ?? legacySafeWidth
        let restoredSafeHeight = defaults.string(forKey: PreferenceKey.safeHeight) ?? legacySafeHeight
        let restoredGeometry = DisplayGeometry(
            displayWidth: Int(restoredDisplayWidth) ?? 0,
            displayHeight: Int(restoredDisplayHeight) ?? 0,
            safeX: Int(restoredSafeX) ?? -1,
            safeY: Int(restoredSafeY) ?? -1,
            safeWidth: Int(restoredSafeWidth) ?? 0,
            safeHeight: Int(restoredSafeHeight) ?? 0
        )
        displayWidth = restoredDisplayWidth
        displayHeight = restoredDisplayHeight
        safeX = restoredSafeX
        safeY = restoredSafeY
        safeWidth = restoredSafeWidth
        safeHeight = restoredSafeHeight
        displayProfile = savedProfile ?? Self.profile(for: restoredGeometry)
        compatibilityProfile = defaults.string(forKey: PreferenceKey.compatibilityProfile)
            .flatMap(CompatibilityProfile.init(rawValue:))
            ?? .motohub
        heartbeat = defaults.string(forKey: PreferenceKey.heartbeat) ?? "1"
        networkSSID = defaults.string(forKey: PreferenceKey.networkSSID)
            ?? CWWiFiClient.shared().interface()?.ssid()
            ?? ""
        networkPassword = defaults.string(forKey: PreferenceKey.networkPassword) ?? ""
    }

    private func markProfileManualIfNeeded() {
        guard !applyingDisplayProfile,
              let profileGeometry = displayProfile.geometry,
              currentGeometry != profileGeometry else { return }
        displayProfile = .manual
    }

    private func applyGeometry(_ geometry: DisplayGeometry) {
        applyingDisplayProfile = true
        displayWidth = String(geometry.displayWidth)
        displayHeight = String(geometry.displayHeight)
        safeX = String(geometry.safeX)
        safeY = String(geometry.safeY)
        safeWidth = String(geometry.safeWidth)
        safeHeight = String(geometry.safeHeight)
        applyingDisplayProfile = false
    }

    private var currentGeometry: DisplayGeometry? {
        guard let displayWidth = Int(displayWidth),
              let displayHeight = Int(displayHeight),
              let safeX = Int(safeX),
              let safeY = Int(safeY),
              let safeWidth = Int(safeWidth),
              let safeHeight = Int(safeHeight) else { return nil }
        return DisplayGeometry(
            displayWidth: displayWidth,
            displayHeight: displayHeight,
            safeX: safeX,
            safeY: safeY,
            safeWidth: safeWidth,
            safeHeight: safeHeight
        )
    }

    private var currentCoreConfiguration: CoreConfiguration? {
        guard let geometry = currentGeometry,
              let heartbeatSeconds = Double(heartbeat), heartbeatSeconds > 0 else { return nil }
        return CoreConfiguration(
            compatibilityProfile: compatibilityProfile,
            geometry: geometry,
            heartbeatSeconds: heartbeatSeconds
        )
    }

    var hasPendingCoreConfiguration: Bool {
        guard coreStarted, let activeCoreConfiguration else { return false }
        return currentCoreConfiguration != activeCoreConfiguration
    }

    private static func profile(for geometry: DisplayGeometry) -> DisplayProfile {
        DisplayProfile.allCases.first { $0.geometry == geometry } ?? .manual
    }

    var controlURL: URL? {
        guard controlPort > 0 else { return nil }
        return URL(string: "http://127.0.0.1:\(controlPort)")
    }

    var pairingPayload: String {
        var components = URLComponents(string: "https://carbit.com/tbox")!
        components.queryItems = [
            URLQueryItem(name: "ssid", value: networkSSID),
            URLQueryItem(name: "pwd", value: networkPassword),
            URLQueryItem(name: "auth", value: "WPA2"),
            URLQueryItem(name: "name", value: compatibilityProfile.qrName),
            URLQueryItem(name: "modelid", value: compatibilityProfile.modelId),
            URLQueryItem(name: "sn", value: compatibilityProfile.serial),
            URLQueryItem(name: "action", value: "9")
        ]
        return components.url?.absoluteString ?? ""
    }

    func copyPairingPayload() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(pairingPayload, forType: .string)
    }

    func start() {
        guard process == nil else { return }
        guard let geometry = currentGeometry,
              geometry.displayWidth > 15,
              geometry.displayHeight > 15,
              geometry.safeX >= 0,
              geometry.safeY >= 0,
              geometry.safeWidth > 15,
              geometry.safeHeight > 15 else {
            errorMessage = "Le dimensioni del TFT e dell'area app non sono valide."
            return
        }
        guard geometry.safeX + geometry.safeWidth <= geometry.displayWidth,
              geometry.safeY + geometry.safeHeight <= geometry.displayHeight else {
            errorMessage = "L'area app deve essere interamente contenuta nel TFT fisico."
            return
        }
        let playerPath = ffplayPath
        guard playerPath != "ffplay" else {
            errorMessage = "ffplay non è stato trovato. Installa FFmpeg oppure verifica /opt/homebrew/bin/ffplay."
            return
        }
        guard let heartbeatValue = Double(heartbeat), heartbeatValue > 0 else {
            errorMessage = "L'intervallo heartbeat deve essere maggiore di zero."
            return
        }
        let configuration = CoreConfiguration(
            compatibilityProfile: compatibilityProfile,
            geometry: geometry,
            heartbeatSeconds: heartbeatValue
        )

        let process = Process()
        process.executableURL = coreURL
        process.arguments = [
            "-profile", configuration.compatibilityProfile.rawValue,
            "-display-width", String(configuration.geometry.displayWidth),
            "-display-height", String(configuration.geometry.displayHeight),
            "-safe-x", String(configuration.geometry.safeX),
            "-safe-y", String(configuration.geometry.safeY),
            "-width", String(configuration.geometry.safeWidth),
            "-height", String(configuration.geometry.safeHeight),
            "-heartbeat", "\(configuration.heartbeatSeconds)s",
            "-ec-port", "0",
            "-control-port", "0",
            "-player", playerPath
        ]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        process.terminationHandler = { [weak self] process in
            Task { @MainActor in
                guard let self else { return }
                guard self.process === process else { return }
                let shouldRestart = self.restartRequested
                self.restartRequested = false
                self.process = nil
                self.activeCoreConfiguration = nil
                self.coreStarted = false
                self.running = false
                self.phoneIP = ""
                self.controlPort = 0
                self.logLines.append("Core terminato con codice \(process.terminationStatus).")
                if shouldRestart {
                    self.logLines.append("Riavvio il core con la geometria aggiornata.")
                    self.start()
                }
            }
        }
        do {
            try process.run()
        } catch {
            errorMessage = "Impossibile avviare il core: \(error.localizedDescription)"
            return
        }
        self.process = process
        self.activeCoreConfiguration = configuration
        self.coreStarted = true
        self.controlPort = 0
        logLines.removeAll()
        errorMessage = nil
        readOutput(pipe)
        statusTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshStatus()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    func stop() {
        restartRequested = false
        statusTask?.cancel()
        statusTask = nil
        process?.terminate()
        process = nil
        activeCoreConfiguration = nil
        coreStarted = false
        running = false
        phoneIP = ""
        controlPort = 0
    }

    func restartToApplyCoreConfiguration() {
        guard coreStarted, hasPendingCoreConfiguration, !restartRequested else { return }
        guard process != nil else {
            start()
            return
        }
        restartRequested = true
        statusTask?.cancel()
        statusTask = nil
        running = false
        phoneIP = ""
        logLines.append("Configurazione modificata: riavvio il simulatore per applicare TFT e area app.")
        process?.terminate()
    }

    func sendGesture(_ path: String) {
        Task { await post(path: path) }
    }

    func sendHandlebar(_ gesture: String) {
        Task { await post(path: "/handlebar", body: ["gesture": gesture]) }
    }

    func sendTap() {
        guard let width = Int(safeWidth), let height = Int(safeHeight) else { return }
        let x = width / 2
        let y = height / 2
        Task {
            await post(path: "/touch", body: [
                "action": "down", "pointerId": 0, "x": x, "y": y
            ])
            await post(path: "/touch", body: [
                "action": "up", "pointerId": 0, "x": x, "y": y
            ])
        }
    }

    private var coreURL: URL {
        let bundlePath = Bundle.main.bundleURL
        let bundledCore = bundlePath.appendingPathComponent("Contents/MacOS/tbox-simulator-core")
        if FileManager.default.isExecutableFile(atPath: bundledCore.path) { return bundledCore }
        let executableDirectory = bundlePath.deletingLastPathComponent()
        let siblingCore = executableDirectory.appendingPathComponent("tbox-simulator-core")
        if FileManager.default.isExecutableFile(atPath: siblingCore.path) { return siblingCore }
        return URL(fileURLWithPath: "/usr/local/bin/tbox-simulator-core")
    }

    private var ffplayPath: String {
        let candidates = ["/opt/homebrew/bin/ffplay", "/usr/local/bin/ffplay", "/usr/bin/ffplay"]
        return candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) ?? "ffplay"
    }

    var sessionLabel: String {
        if running { return "Telefono collegato: \(phoneIP)" }
        if coreStarted { return "Core avviato: in attesa di MOTO-HUB" }
        return "Simulatore fermo"
    }

    var geometryLabel: String {
        guard let geometry = currentGeometry else { return "Geometria non valida" }
        return "\(compatibilityProfile.label) · TFT \(geometry.displayWidth) x \(geometry.displayHeight) · area app \(geometry.safeWidth) x \(geometry.safeHeight) @(\(geometry.safeX), \(geometry.safeY))"
    }

    private func readOutput(_ pipe: Pipe) {
        // availableData blocks until the core writes or exits. Keep it off the
        // SwiftUI main actor or the window becomes unresponsive immediately.
        DispatchQueue.global(qos: .utility).async { [weak self, pipe] in
            let handle = pipe.fileHandleForReading
            while true {
                let data = handle.availableData
                if data.isEmpty { break }
                let text = String(decoding: data, as: UTF8.self)
                let lines = text.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
                DispatchQueue.main.async {
                    guard let self else { return }
                    lines.forEach(self.consumeLogLine)
                }
            }
        }
    }

    private func refreshStatus() async {
        guard let controlURL else { return }
        do {
            let (data, _) = try await URLSession.shared.data(from: controlURL.appendingPathComponent("status"))
            let response = try JSONDecoder().decode(StatusResponse.self, from: data)
            await MainActor.run {
                running = response.running
                phoneIP = response.phoneIp ?? ""
                frames = response.frames
            }
        } catch {
            await MainActor.run { running = false }
        }
    }

    private func post(path: String, body: [String: Any]? = nil) async {
        guard let controlURL else {
            await MainActor.run { errorMessage = "Il core non ha ancora pubblicato la porta di controllo." }
            return
        }
        var request = URLRequest(url: controlURL.appendingPathComponent(path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))))
        request.httpMethod = "POST"
        if let body {
            request.httpBody = try? JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        do {
            _ = try await URLSession.shared.data(for: request)
        } catch {
            await MainActor.run { errorMessage = "Comando fallito: \(error.localizedDescription)" }
        }
    }

    private func consumeLogLine(_ line: String) {
        logLines.append(line)
        logLines = Array(logLines.suffix(160))
        let marker = "control http://127.0.0.1:"
        guard let start = line.range(of: marker)?.upperBound else { return }
        let portText = line[start...].prefix { $0.isNumber }
        if let port = Int(portText) { controlPort = port }
    }
}

private struct StatusResponse: Decodable {
    let running: Bool
    let phoneIp: String?
    let frames: Int
}

struct ContentView: View {
    @ObservedObject var model: SimulatorModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("MOTO-HUB T-Box Simulator")
                        .font(.title2.weight(.semibold))
                    Text("Emula il T-Box e visualizza il video ricevuto da MOTO-HUB.")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Circle()
                    .fill(model.running ? .green : .gray)
                    .frame(width: 12, height: 12)
            }

            GroupBox("TFT, area proiettabile e sessione") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 12) {
                        LabeledContent("Profilo T-Box") {
                            Picker("Profilo T-Box", selection: $model.compatibilityProfile) {
                                ForEach(SimulatorModel.CompatibilityProfile.allCases) { profile in
                                    Text(profile.label).tag(profile)
                                }
                            }
                            .pickerStyle(.menu)
                            .frame(width: 260, alignment: .leading)
                        }
                        LabeledContent("Profilo") {
                            Picker("Profilo display", selection: $model.displayProfile) {
                                ForEach(SimulatorModel.DisplayProfile.allCases) { profile in
                                    Text(profile.label).tag(profile)
                                }
                            }
                            .pickerStyle(.menu)
                            .frame(width: 280, alignment: .leading)
                        }
                        Spacer()
                        LabeledContent("Heartbeat (s)") {
                            TextField("1", text: $model.heartbeat)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 70)
                        }
                    }
                    HStack(spacing: 12) {
                        Text("TFT fisico")
                            .frame(width: 82, alignment: .leading)
                        LabeledContent("W") {
                            TextField("800", text: $model.displayWidth)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 72)
                        }
                        LabeledContent("H") {
                            TextField("480", text: $model.displayHeight)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 72)
                        }
                        Spacer()
                    }
                    HStack(spacing: 12) {
                        Text("Area app")
                            .frame(width: 82, alignment: .leading)
                        LabeledContent("X") {
                            TextField("0", text: $model.safeX)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 72)
                        }
                        LabeledContent("Y") {
                            TextField("0", text: $model.safeY)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 72)
                        }
                        LabeledContent("W") {
                            TextField("800", text: $model.safeWidth)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 72)
                        }
                        LabeledContent("H") {
                            TextField("384", text: $model.safeHeight)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 72)
                        }
                        Spacer()
                        Button(model.processButtonTitle) { model.coreStarted ? model.stop() : model.start() }
                            .keyboardShortcut(.return)
                    }
                    if model.hasPendingCoreConfiguration {
                        HStack(spacing: 10) {
                            Label(
                                "Configurazione display modificata: il core attivo usa ancora la geometria precedente.",
                                systemImage: "exclamationmark.triangle.fill"
                            )
                            .font(.caption)
                            .foregroundStyle(.orange)
                            Spacer()
                            Button("Riavvia e applica") {
                                model.restartToApplyCoreConfiguration()
                            }
                        }
                    }
                    Text("La preview mostra il TFT completo; le zone esterne all'area app rappresentano lo spazio riservato alle informazioni della moto.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            GroupBox("QR di pairing") {
                HStack(spacing: 18) {
                    QRCodeView(value: model.pairingPayload)
                        .frame(width: 150, height: 150)
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Scansiona questo QR dall'app che vuoi collegare al simulatore.")
                            .font(.headline)
                        Text("Mac e telefono possono usare la normale Wi-Fi di casa. Inserisci la password di quella rete.")
                            .foregroundStyle(.secondary)
                        HStack {
                            Text("SSID")
                                .frame(width: 64, alignment: .leading)
                            TextField("SSID Wi-Fi di casa", text: $model.networkSSID)
                                .textFieldStyle(.roundedBorder)
                        }
                        HStack {
                            Text("Password")
                                .frame(width: 64, alignment: .leading)
                            SecureField("Password Wi-Fi", text: $model.networkPassword)
                                .textFieldStyle(.roundedBorder)
                        }
                        Button("Copia contenuto QR") { model.copyPairingPayload() }
                        Text(model.pairingPayload)
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                            .textSelection(.enabled)
                    }
                }
            }

            HStack(spacing: 16) {
                Label(model.sessionLabel, systemImage: model.running ? "network" : "pause.circle")
                Label("Frame \(model.frames)", systemImage: "film")
                Spacer()
                Text("Preview TFT completo in ffplay")
                    .foregroundStyle(.secondary)
            }
            Text(model.geometryLabel)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.secondary)

            GroupBox("Input T-Box") {
                HStack {
                    Button("Tap") { model.sendTap() }
                    Button("Pinch") { model.sendGesture("/gesture/pinch") }
                    Button("Rotate") { model.sendGesture("/gesture/rotate") }
                    Spacer()
                    Text("Le coordinate sono relative all'area app.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            GroupBox("Handlebar controls") {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Button("Up") { model.sendHandlebar("volumeUp") }
                        Button("Up x2") { model.sendHandlebar("volumeUpDouble") }
                        Button("Down") { model.sendHandlebar("volumeDown") }
                        Button("Down x2") { model.sendHandlebar("volumeDownDouble") }
                        Divider().frame(height: 18)
                        Button("Select") { model.sendHandlebar("enter") }
                        Button("Select hold") { model.sendHandlebar("enterLong") }
                        Button("Select x2") { model.sendHandlebar("enterDouble") }
                    }
                    HStack {
                        Button("Backward") { model.sendHandlebar("trackBack") }
                        Button("Backward x2") { model.sendHandlebar("trackBackDouble") }
                        Button("Forward") { model.sendHandlebar("trackForward") }
                        Button("Forward x2") { model.sendHandlebar("trackForwardDouble") }
                        Spacer()
                        Text("Invia gesti logici all'app, non eventi Bluetooth reali.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            GroupBox("Log") {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 3) {
                            ForEach(Array(model.logLines.enumerated()), id: \.offset) { index, line in
                                Text(line).font(.system(.caption, design: .monospaced)).textSelection(.enabled).id(index)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .onChange(of: model.logLines.count) { count in
                        if count > 0 { proxy.scrollTo(count - 1, anchor: .bottom) }
                    }
                }
                .frame(minHeight: 190)
            }
        }
        .alert("Errore", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
    }
}

struct QRCodeView: View {
    let value: String

    var body: some View {
        Group {
            if let image = makeImage(value: value) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .padding(8)
                    .background(.white)
            } else {
                Image(systemName: "qrcode")
                    .font(.system(size: 64))
            }
        }
        .frame(width: 150, height: 150)
    }

    private func makeImage(value: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(value.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = 8.0
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: 150, height: 150))
    }
}

private extension SimulatorModel {
    var processButtonTitle: String { coreStarted ? "Ferma" : "Avvia" }
}
