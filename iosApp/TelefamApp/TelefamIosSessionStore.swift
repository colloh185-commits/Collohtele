import CryptoKit
import Darwin
import Foundation
import Security
import SwiftUI
import UIKit

enum TelefamIosSecurityGuard {
    static func isDebuggerAttached() -> Bool {
        var info = kinfo_proc()
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        var size = MemoryLayout<kinfo_proc>.stride
        let result = sysctl(&mib, UInt32(mib.count), &info, &size, nil, 0)
        guard result == 0 else { return false }
        return (info.kp_proc.p_flag & P_TRACED) != 0
    }

    static func hasJailbreakIndicators() -> Bool {
        #if targetEnvironment(simulator)
        return false
        #else
        let paths = [
            "/Applications/Cydia.app",
            "/Library/MobileSubstrate/MobileSubstrate.dylib",
            "/bin/bash",
            "/usr/sbin/sshd",
            "/etc/apt",
            "/private/var/lib/apt/",
            "/var/jb"
        ]
        for p in paths where FileManager.default.fileExists(atPath: p) {
            return true
        }
        return false
        #endif
    }

    static func isAllowedBaseURL(_ raw: String, enforceHTTPS: Bool) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(),
              let host = url.host?.lowercased(),
              !host.isEmpty else {
            return false
        }
        if scheme == "https" { return true }
        if scheme == "http" {
            let isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
            return !enforceHTTPS || isLoopback
        }
        return false
    }

    static func normalizedBaseURL(_ raw: String, enforceHTTPS: Bool) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard isAllowedBaseURL(trimmed, enforceHTTPS: enforceHTTPS) else { return nil }
        return URL(string: trimmed)
    }

    static func isReleaseEnforced() -> Bool {
        #if DEBUG
        return false
        #else
        return true
        #endif
    }

    static func integrityFingerprintSuffix() -> String {
        let dbg = isDebuggerAttached() ? "1" : "0"
        let jb = hasJailbreakIndicators() ? "1" : "0"
        return "ios:\(dbg):\(jb)"
    }
}

private enum TelefamIosKeychainVault {
    private static let service = Bundle.main.bundleIdentifier ?? "com.telefam.company.ios"

    static func save(key: String, value: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)

        var insert = query
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(insert as CFDictionary, nil)
    }

    static func load(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}

struct TelefamIosCountryOption: Identifiable, Hashable {
    let isoCode: String
    let name: String
    let dialCode: String
    let flag: String

    var id: String { isoCode }
}

enum TelefamIosCountryCatalog {
    private static let dialMap: [String: String] = [
        "AF": "+93", "AL": "+355", "DZ": "+213", "AD": "+376", "AO": "+244",
        "AG": "+1268", "AR": "+54", "AM": "+374", "AU": "+61", "AT": "+43",
        "AZ": "+994", "BS": "+1242", "BH": "+973", "BD": "+880", "BB": "+1246",
        "BY": "+375", "BE": "+32", "BZ": "+501", "BJ": "+229", "BT": "+975",
        "BO": "+591", "BA": "+387", "BW": "+267", "BR": "+55", "BN": "+673",
        "BG": "+359", "BF": "+226", "BI": "+257", "CV": "+238", "KH": "+855",
        "CM": "+237", "CA": "+1", "CF": "+236", "TD": "+235", "CL": "+56",
        "CN": "+86", "CO": "+57", "KM": "+269", "CG": "+242", "CD": "+243",
        "CR": "+506", "CI": "+225", "HR": "+385", "CU": "+53", "CY": "+357",
        "CZ": "+420", "DK": "+45", "DJ": "+253", "DM": "+1767", "DO": "+1809",
        "EC": "+593", "EG": "+20", "SV": "+503", "GQ": "+240", "ER": "+291",
        "EE": "+372", "SZ": "+268", "ET": "+251", "FJ": "+679", "FI": "+358",
        "FR": "+33", "GA": "+241", "GM": "+220", "GE": "+995", "DE": "+49",
        "GH": "+233", "GR": "+30", "GD": "+1473", "GT": "+502", "GN": "+224",
        "GW": "+245", "GY": "+592", "HT": "+509", "HN": "+504", "HK": "+852",
        "HU": "+36", "IS": "+354", "IN": "+91", "ID": "+62", "IR": "+98",
        "IQ": "+964", "IE": "+353", "IL": "+972", "IT": "+39", "JM": "+1876",
        "JP": "+81", "JO": "+962", "KZ": "+7", "KE": "+254", "KI": "+686",
        "KW": "+965", "KG": "+996", "LA": "+856", "LV": "+371", "LB": "+961",
        "LS": "+266", "LR": "+231", "LY": "+218", "LI": "+423", "LT": "+370",
        "LU": "+352", "MO": "+853", "MG": "+261", "MW": "+265", "MY": "+60",
        "MV": "+960", "ML": "+223", "MT": "+356", "MR": "+222", "MU": "+230",
        "MX": "+52", "MD": "+373", "MC": "+377", "MN": "+976", "ME": "+382",
        "MA": "+212", "MZ": "+258", "MM": "+95", "NA": "+264", "NR": "+674",
        "NP": "+977", "NL": "+31", "NZ": "+64", "NI": "+505", "NE": "+227",
        "NG": "+234", "KP": "+850", "MK": "+389", "NO": "+47", "OM": "+968",
        "PK": "+92", "PW": "+680", "PA": "+507", "PG": "+675", "PY": "+595",
        "PE": "+51", "PH": "+63", "PL": "+48", "PT": "+351", "PR": "+1787",
        "QA": "+974", "RO": "+40", "RU": "+7", "RW": "+250", "KN": "+1869",
        "LC": "+1758", "VC": "+1784", "WS": "+685", "SM": "+378", "ST": "+239",
        "SA": "+966", "SN": "+221", "RS": "+381", "SC": "+248", "SL": "+232",
        "SG": "+65", "SK": "+421", "SI": "+386", "SB": "+677", "SO": "+252",
        "ZA": "+27", "KR": "+82", "SS": "+211", "ES": "+34", "LK": "+94",
        "SD": "+249", "SR": "+597", "SE": "+46", "CH": "+41", "SY": "+963",
        "TW": "+886", "TJ": "+992", "TZ": "+255", "TH": "+66", "TL": "+670",
        "TG": "+228", "TO": "+676", "TT": "+1868", "TN": "+216", "TR": "+90",
        "TM": "+993", "UG": "+256", "UA": "+380", "AE": "+971", "GB": "+44",
        "US": "+1", "UY": "+598", "UZ": "+998", "VU": "+678", "VE": "+58",
        "VN": "+84", "YE": "+967", "ZM": "+260", "ZW": "+263"
    ]

    static let all: [TelefamIosCountryOption] = {
        let locale = Locale(identifier: "en_US")
        return Locale.isoRegionCodes.compactMap { code in
            let upper = code.uppercased()
            guard let dial = dialMap[upper],
                  let name = locale.localizedString(forRegionCode: upper),
                  !name.isEmpty else {
                return nil
            }
            return TelefamIosCountryOption(
                isoCode: upper,
                name: name,
                dialCode: dial,
                flag: flagEmoji(for: upper)
            )
        }.sorted { $0.name < $1.name }
    }()

    static func defaultOption() -> TelefamIosCountryOption {
        let region = Locale.current.region?.identifier.uppercased() ?? "US"
        return all.first { $0.isoCode == region }
            ?? all.first { $0.isoCode == "US" }
            ?? TelefamIosCountryOption(isoCode: "US", name: "United States", dialCode: "+1", flag: "🇺🇸")
    }

    private static func flagEmoji(for isoCode: String) -> String {
        let base: UInt32 = 127397
        return isoCode.unicodeScalars.compactMap { scalar in
            UnicodeScalar(base + scalar.value).map { String($0) }
        }.joined()
    }
}

struct TelefamIosProfileBackgroundItem: Identifiable, Codable, Equatable {
    let mediaId: String
    let url: String
    let sha256Fingerprint: String
    let dHashFingerprint: String
    let width: Int
    let height: Int
    let byteSize: Int
    let createdAtMs: Int64

    var id: String { mediaId }

    enum CodingKeys: String, CodingKey {
        case mediaId = "media_id"
        case url
        case sha256Fingerprint = "sha256_fingerprint"
        case dHashFingerprint = "dhash_fingerprint"
        case width, height
        case byteSize = "byte_size"
        case createdAtMs = "created_at_ms"
    }

    init(
        mediaId: String,
        url: String = "",
        sha256Fingerprint: String = "",
        dHashFingerprint: String = "",
        width: Int = 0,
        height: Int = 0,
        byteSize: Int = 0,
        createdAtMs: Int64 = 0
    ) {
        self.mediaId = mediaId
        self.url = url
        self.sha256Fingerprint = sha256Fingerprint
        self.dHashFingerprint = dHashFingerprint
        self.width = width
        self.height = height
        self.byteSize = byteSize
        self.createdAtMs = createdAtMs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mediaId = try c.decodeIfPresent(String.self, forKey: .mediaId) ?? ""
        url = try c.decodeIfPresent(String.self, forKey: .url) ?? ""
        sha256Fingerprint = try c.decodeIfPresent(String.self, forKey: .sha256Fingerprint) ?? ""
        dHashFingerprint = try c.decodeIfPresent(String.self, forKey: .dHashFingerprint) ?? ""
        width = try c.decodeIfPresent(Int.self, forKey: .width) ?? 0
        height = try c.decodeIfPresent(Int.self, forKey: .height) ?? 0
        byteSize = try c.decodeIfPresent(Int.self, forKey: .byteSize) ?? 0
        createdAtMs = try c.decodeIfPresent(Int64.self, forKey: .createdAtMs) ?? 0
    }
}

struct TelefamIosAuthResponse: Codable {
    let success: Bool
    let userId: String?
    let sessionToken: String
    let refreshToken: String
    let accessExpiresInSec: Int64
    let email: String
    let suggestedFullName: String
    let suggestedUsername: String
    let profileCompleted: Bool

    enum CodingKeys: String, CodingKey {
        case success
        case userId = "user_id"
        case sessionToken = "session_token"
        case refreshToken = "refresh_token"
        case accessExpiresInSec = "access_expires_in_seconds"
        case email
        case suggestedFullName = "suggested_full_name"
        case suggestedUsername = "suggested_username"
        case profileCompleted = "profile_completed"
    }
}

@MainActor
final class TelefamIosSessionStore: ObservableObject {
    @Published var isAuthenticated: Bool = false
    @Published var profileCompleted: Bool = false
    @Published var userId: String = ""
    @Published var sessionToken: String = ""
    @Published var refreshToken: String = ""
    @Published var email: String = ""
    @Published var fullName: String = ""
    @Published var username: String = ""
    @Published var phoneDisplay: String = ""
    @Published var location: String = ""
    @Published var bio: String = ""
    @Published var baseUrlString: String = ""
    @Published var isBusy: Bool = false
    @Published var authError: String? = nil
    @Published var otpSent: Bool = false
    @Published var cooldownRemainingSec: Int = 0
    @Published var cooldownTotalSec: Int = 60

    private var cooldownTimer: Timer? = nil

    private enum Keys {
        static let userId = "telefam_user_id"
        static let sessionToken = "telefam_session_token"
        static let refreshToken = "telefam_refresh_token"
        static let email = "telefam_email"
        static let fullName = "telefam_full_name"
        static let username = "telefam_username"
        static let profileCompleted = "telefam_profile_completed"
        static let baseUrl = "telefam_api_base_url"
        static let deviceSecret = "telefam_device_secret"
        static let phoneDisplay = "telefam_phone_display"
        static let location = "telefam_location"
        static let bio = "telefam_bio"
    }

    init() {
        let infoURL = (Bundle.main.object(forInfoDictionaryKey: "TELEFAM_API_BASE_URL") as? String) ?? ""
        let envURL = ProcessInfo.processInfo.environment["TELEFAM_API_BASE_URL"] ?? ""
        let savedURL = TelefamIosKeychainVault.load(key: Keys.baseUrl) ?? ""

        let candidateURL = [savedURL, envURL, infoURL].first {
            TelefamIosSecurityGuard.isAllowedBaseURL($0, enforceHTTPS: TelefamIosSecurityGuard.isReleaseEnforced())
        } ?? ""

        baseUrlString = candidateURL
        userId = TelefamIosKeychainVault.load(key: Keys.userId) ?? ""
        sessionToken = TelefamIosKeychainVault.load(key: Keys.sessionToken) ?? ""
        refreshToken = TelefamIosKeychainVault.load(key: Keys.refreshToken) ?? ""
        email = TelefamIosKeychainVault.load(key: Keys.email) ?? ""
        fullName = TelefamIosKeychainVault.load(key: Keys.fullName) ?? ""
        username = TelefamIosKeychainVault.load(key: Keys.username) ?? ""
        phoneDisplay = TelefamIosKeychainVault.load(key: Keys.phoneDisplay) ?? ""
        location = TelefamIosKeychainVault.load(key: Keys.location) ?? ""
        bio = TelefamIosKeychainVault.load(key: Keys.bio) ?? ""
        profileCompleted = (TelefamIosKeychainVault.load(key: Keys.profileCompleted) == "1")
        isAuthenticated = !sessionToken.isEmpty && serverURL != nil

        Task {
            if isAuthenticated && !refreshToken.isEmpty {
                await refreshAccessTokenIfNeeded()
            }
        }
    }

    var serverURL: URL? {
        TelefamIosSecurityGuard.normalizedBaseURL(
            baseUrlString,
            enforceHTTPS: TelefamIosSecurityGuard.isReleaseEnforced()
        )
    }

    var deviceFingerprint: String {
        let secret: String
        if let existing = TelefamIosKeychainVault.load(key: Keys.deviceSecret), !existing.isEmpty {
            secret = existing
        } else {
            let created = UUID().uuidString + "-" + UUID().uuidString
            TelefamIosKeychainVault.save(key: Keys.deviceSecret, value: created)
            secret = created
        }
        let raw = "\(secret)|\(UIDevice.current.systemName)|\(UIDevice.current.systemVersion)|\(TelefamIosSecurityGuard.integrityFingerprintSuffix())"
        let digest = SHA256.hash(data: Data(raw.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    func updateServerEndpoint(_ raw: String) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard TelefamIosSecurityGuard.isAllowedBaseURL(trimmed, enforceHTTPS: TelefamIosSecurityGuard.isReleaseEnforced()) else {
            authError = TelefamIosSecurityGuard.isReleaseEnforced()
                ? "Release builds require an HTTPS server endpoint."
                : "Enter a valid server URL."
            return false
        }
        baseUrlString = trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        TelefamIosKeychainVault.save(key: Keys.baseUrl, value: baseUrlString)
        authError = nil
        return true
    }

    func googleOAuthClientID() -> String {
        let envVal = ProcessInfo.processInfo.environment["TELEFAM_GOOGLE_CLIENT_ID"] ?? ""
        if !envVal.isEmpty { return envVal }
        return (Bundle.main.object(forInfoDictionaryKey: "TELEFAM_GOOGLE_CLIENT_ID") as? String) ?? ""
    }

    func requestOtp(for rawEmail: String) async {
        let normalized = rawEmail.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard normalized.contains("@"), normalized.contains(".") else {
            authError = "Please enter a valid email address."
            return
        }
        guard let url = serverURL?.appendingPathComponent("v1/auth/otp/request") else {
            authError = "Configure your Telefam server endpoint to sign in."
            return
        }
        isBusy = true
        authError = nil
        defer { isBusy = false }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["email": normalized])

        struct Resp: Codable {
            let success: Bool
            let cooldownSeconds: Int?
            enum CodingKeys: String, CodingKey {
                case success
                case cooldownSeconds = "cooldown_seconds"
            }
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                authError = "An error has occurred. Please try again."
                return
            }
            if (200...299).contains(http.statusCode),
               let parsed = try? JSONDecoder().decode(Resp.self, from: data), parsed.success {
                email = normalized
                otpSent = true
                startCooldown(seconds: max(30, parsed.cooldownSeconds ?? 60))
            } else if http.statusCode == 429 {
                authError = "Too many requests. Try again shortly."
            } else {
                authError = "Unable to send OTP. Please check your connection."
            }
        } catch {
            authError = "Unable to reach server. Please try again."
        }
    }

    func verifyOtp(code: String) async {
        let clean = code.filter(\.isNumber)
        guard clean.count == 6 else {
            authError = "Enter the 6-digit code."
            return
        }
        guard let url = serverURL?.appendingPathComponent("v1/auth/otp/verify") else {
            authError = "Configure your Telefam server endpoint to sign in."
            return
        }
        await performAuthRequest(url: url, body: ["email": email, "otp": clean])
    }

    func verifyMagicLink(token: String) async {
        let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let url = serverURL?.appendingPathComponent("v1/auth/verify-link") else {
            authError = "Invalid verification link."
            return
        }
        await performAuthRequest(url: url, body: ["token": trimmed])
    }

    func verifyAppleOAuth(identityToken: String, nonce: String) async {
        guard let url = serverURL?.appendingPathComponent("v1/auth/oauth/apple") else {
            authError = "Configure your Telefam server endpoint to sign in."
            return
        }
        await performAuthRequest(
            url: url,
            body: ["provider": "apple", "identity_token": identityToken, "nonce": nonce]
        )
    }

    func verifyGoogleOAuth(identityToken: String) async {
        guard let url = serverURL?.appendingPathComponent("v1/auth/oauth/google") else {
            authError = "Configure your Telefam server endpoint to sign in."
            return
        }
        await performAuthRequest(
            url: url,
            body: ["provider": "google", "identity_token": identityToken]
        )
    }

    func refreshAccessTokenIfNeeded() async {
        guard !refreshToken.isEmpty,
              let url = serverURL?.appendingPathComponent("v1/auth/token/refresh") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["refresh_token": refreshToken])

        if let (data, resp) = try? await URLSession.shared.data(for: req),
           let http = resp as? HTTPURLResponse {
            if (200...299).contains(http.statusCode),
               let parsed = try? JSONDecoder().decode(TelefamIosAuthResponse.self, from: data),
               parsed.success, !parsed.sessionToken.isEmpty {
                applyAuthSession(parsed)
            } else if http.statusCode == 401 || http.statusCode == 403 {
                signOut()
            }
        }
    }

    func checkUsernameAvailability(_ candidate: String) async -> Bool? {
        let trimmed = candidate.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard trimmed.count >= 3,
              let base = serverURL else { return nil }
        var comps = URLComponents(url: base.appendingPathComponent("v1/profile/check-username"), resolvingAgainstBaseURL: false)
        comps?.queryItems = [URLQueryItem(name: "u", value: trimmed)]
        guard let url = comps?.url else { return nil }

        struct Resp: Codable { let available: Bool }
        if let (data, resp) = try? await URLSession.shared.data(from: url),
           let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode),
           let parsed = try? JSONDecoder().decode(Resp.self, from: data) {
            return parsed.available
        }
        return nil
    }

    func uploadAvatar(jpegData: Data, cx: Float, cy: Float, scale: Float) async -> String? {
        guard let base = serverURL else { return nil }
        var comps = URLComponents(url: base.appendingPathComponent("v1/media/avatar"), resolvingAgainstBaseURL: false)
        comps?.queryItems = [
            URLQueryItem(name: "cx", value: String(format: "%.3f", cx)),
            URLQueryItem(name: "cy", value: String(format: "%.3f", cy)),
            URLQueryItem(name: "scale", value: String(format: "%.3f", scale))
        ]
        guard let url = comps?.url else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        req.httpBody = jpegData

        struct Resp: Codable {
            let success: Bool
            let mediaId: String?
            enum CodingKeys: String, CodingKey {
                case success
                case mediaId = "media_id"
            }
        }
        if let (data, resp) = try? await URLSession.shared.data(for: req),
           let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode),
           let parsed = try? JSONDecoder().decode(Resp.self, from: data), parsed.success {
            return parsed.mediaId
        }
        return nil
    }

    func completeProfileSetup(
        fullName: String,
        username: String,
        phoneE164: String,
        countryIso: String,
        gender: String,
        dateOfBirthIso: String,
        location: String,
        bio: String,
        avatarMediaId: String?
    ) async -> Bool {
        guard let url = serverURL?.appendingPathComponent("v1/profile/setup") else {
            authError = "Server endpoint is not configured."
            return false
        }
        isBusy = true
        authError = nil
        defer { isBusy = false }

        var payload: [String: Any] = [
            "full_name": fullName,
            "username": username,
            "phone_number": phoneE164,
            "country_iso": countryIso,
            "gender": gender,
            "date_of_birth": dateOfBirthIso,
            "location": location,
            "bio": bio
        ]
        if let avatarMediaId, !avatarMediaId.isEmpty {
            payload["avatar_media_id"] = avatarMediaId
        }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)

        do {
            let (_, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                authError = "Could not save profile. Check your details and try again."
                return false
            }
            self.fullName = fullName
            self.username = username
            self.phoneDisplay = phoneE164
            self.location = location
            self.bio = bio
            self.profileCompleted = true
            TelefamIosKeychainVault.save(key: Keys.fullName, value: fullName)
            TelefamIosKeychainVault.save(key: Keys.username, value: username)
            TelefamIosKeychainVault.save(key: Keys.phoneDisplay, value: phoneE164)
            TelefamIosKeychainVault.save(key: Keys.location, value: location)
            TelefamIosKeychainVault.save(key: Keys.bio, value: bio)
            TelefamIosKeychainVault.save(key: Keys.profileCompleted, value: "1")
            return true
        } catch {
            authError = "Network error while saving profile."
            return false
        }
    }

    func resetToEmailStep() {
        otpSent = false
        authError = nil
        cooldownTimer?.invalidate()
        cooldownRemainingSec = 0
    }

    func logout(allDevices: Bool) async {
        if let url = serverURL?.appendingPathComponent("v1/auth/logout"), !sessionToken.isEmpty {
            var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
            req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
            req.httpBody = try? JSONSerialization.data(withJSONObject: ["all_devices": allDevices])
            _ = try? await URLSession.shared.data(for: req)
        }
        signOut()
    }

    func signOut() {
        cooldownTimer?.invalidate()
        userId = ""
        sessionToken = ""
        refreshToken = ""
        isAuthenticated = false
        profileCompleted = false
        otpSent = false
        authError = nil
        for key in [Keys.userId, Keys.sessionToken, Keys.refreshToken, Keys.profileCompleted, Keys.email, Keys.fullName, Keys.username, Keys.phoneDisplay, Keys.location, Keys.bio] {
            TelefamIosKeychainVault.delete(key: key)
        }
    }

    static func sha256Hex(data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    func uploadProfileBackground(jpegData: Data) async -> (ok: Bool, error: String?, backgrounds: [TelefamIosProfileBackgroundItem]) {
        guard let url = serverURL?.appendingPathComponent("v1/media/background") else {
            return (false, "Server endpoint is not configured.", [])
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        req.setValue(Self.sha256Hex(data: jpegData), forHTTPHeaderField: "X-Telefam-Content-SHA256")
        req.httpBody = jpegData

        struct Resp: Codable {
            let success: Bool
            let error: String?
            let backgrounds: [TelefamIosProfileBackgroundItem]?
        }

        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let parsed = try? JSONDecoder().decode(Resp.self, from: data)
            if (200...299).contains(code), parsed?.success == true {
                return (true, nil, parsed?.backgrounds ?? [])
            }
            if code == 409 {
                return (false, parsed?.error ?? "Maximum 5 background photos allowed.", parsed?.backgrounds ?? [])
            }
            return (false, parsed?.error ?? "Unable to upload background photo.", [])
        } catch {
            return (false, "Network error while uploading background.", [])
        }
    }

    func deleteProfileBackground(mediaId: String) async -> (ok: Bool, error: String?, backgrounds: [TelefamIosProfileBackgroundItem]) {
        guard let url = serverURL?.appendingPathComponent("v1/profile/backgrounds").appendingPathComponent(mediaId) else {
            return (false, "Server endpoint is not configured.", [])
        }
        var req = URLRequest(url: url)
        req.httpMethod = "DELETE"
        req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")

        struct Resp: Codable {
            let success: Bool
            let error: String?
            let backgrounds: [TelefamIosProfileBackgroundItem]?
        }
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let parsed = try? JSONDecoder().decode(Resp.self, from: data)
            if (200...299).contains(code), parsed?.success == true {
                return (true, nil, parsed?.backgrounds ?? [])
            }
            return (false, parsed?.error ?? "Unable to remove background photo.", [])
        } catch {
            return (false, "Network error while removing background.", [])
        }
    }

    func reorderProfileBackgrounds(mediaIds: [String]) async -> (ok: Bool, error: String?, backgrounds: [TelefamIosProfileBackgroundItem]) {
        guard let url = serverURL?.appendingPathComponent("v1/profile/backgrounds") else {
            return (false, "Server endpoint is not configured.", [])
        }
        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["media_ids": mediaIds])

        struct Resp: Codable {
            let success: Bool
            let error: String?
            let backgrounds: [TelefamIosProfileBackgroundItem]?
        }
        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let parsed = try? JSONDecoder().decode(Resp.self, from: data)
            if (200...299).contains(code), parsed?.success == true {
                return (true, nil, parsed?.backgrounds ?? [])
            }
            return (false, parsed?.error ?? "Unable to reorder backgrounds.", [])
        } catch {
            return (false, "Network error while reordering backgrounds.", [])
        }
    }

    func updateProfile(
        fullName: String,
        username: String,
        phoneE164: String,
        countryIso: String,
        gender: String,
        dateOfBirthIso: String,
        location: String,
        bio: String,
        avatarMediaId: String?
    ) async -> (ok: Bool, error: String?, usernameChangedAtMs: Int64, nextUsernameChangeMs: Int64) {
        guard let url = serverURL?.appendingPathComponent("v1/profile") else {
            return (false, "Server endpoint is not configured.", 0, 0)
        }
        var payload: [String: Any] = [
            "full_name": fullName,
            "username": username,
            "phone_number": phoneE164,
            "country_iso": countryIso,
            "gender": gender,
            "date_of_birth": dateOfBirthIso,
            "location": location,
            "bio": bio
        ]
        if let avatarMediaId, !avatarMediaId.isEmpty {
            payload["avatar_media_id"] = avatarMediaId
        }

        var req = URLRequest(url: url)
        req.httpMethod = "PATCH"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)

        struct Resp: Codable {
            let success: Bool
            let error: String?
            let username: String?
            let fullName: String?
            let usernameChangedAtMs: Int64?
            let nextUsernameChangeMs: Int64?

            enum CodingKeys: String, CodingKey {
                case success, error, username
                case fullName = "full_name"
                case usernameChangedAtMs = "username_changed_at_ms"
                case nextUsernameChangeMs = "next_username_change_ms"
            }
        }

        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let parsed = try? JSONDecoder().decode(Resp.self, from: data)
            if (200...299).contains(code), parsed?.success == true {
                self.fullName = parsed?.fullName ?? fullName
                self.username = parsed?.username ?? username
                self.phoneDisplay = phoneE164
                self.location = location
                self.bio = bio
                TelefamIosKeychainVault.save(key: Keys.fullName, value: self.fullName)
                TelefamIosKeychainVault.save(key: Keys.username, value: self.username)
                TelefamIosKeychainVault.save(key: Keys.phoneDisplay, value: phoneE164)
                TelefamIosKeychainVault.save(key: Keys.location, value: location)
                TelefamIosKeychainVault.save(key: Keys.bio, value: bio)
                return (
                    true,
                    nil,
                    parsed?.usernameChangedAtMs ?? 0,
                    parsed?.nextUsernameChangeMs ?? 0
                )
            }
            return (
                false,
                parsed?.error ?? (code == 429 ? "Username can only be changed once every 2 weeks." : "Could not update profile."),
                parsed?.usernameChangedAtMs ?? 0,
                parsed?.nextUsernameChangeMs ?? 0
            )
        } catch {
            return (false, "Network error while updating profile.", 0, 0)
        }
    }

    func sharePostURL(postId: String) -> URL {
        if let base = serverURL,
           let host = base.host?.lowercased(),
           host != "127.0.0.1", host != "localhost", host != "::1" {
            return base.appendingPathComponent("v1/feeds/\(postId)/download")
        }
        return URL(string: "telefam://post/\(postId)")!
    }

    private func performAuthRequest(url: URL, body: [String: Any]) async {
        isBusy = true
        authError = nil
        defer { isBusy = false }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)

        do {
            let (data, resp) = try await URLSession.shared.data(for: req)
            guard let http = resp as? HTTPURLResponse,
                  (200...299).contains(http.statusCode),
                  let parsed = try? JSONDecoder().decode(TelefamIosAuthResponse.self, from: data),
                  parsed.success, !parsed.sessionToken.isEmpty else {
                authError = "Verification failed. Please check your code or try again."
                return
            }
            applyAuthSession(parsed)
        } catch {
            authError = "Unable to connect to server. Please try again."
        }
    }

    private func applyAuthSession(_ auth: TelefamIosAuthResponse) {
        sessionToken = auth.sessionToken
        if let uid = auth.userId, !uid.isEmpty {
            userId = uid
            TelefamIosKeychainVault.save(key: Keys.userId, value: uid)
        }
        if !auth.refreshToken.isEmpty {
            refreshToken = auth.refreshToken
            TelefamIosKeychainVault.save(key: Keys.refreshToken, value: auth.refreshToken)
        }
        if !auth.email.isEmpty {
            email = auth.email
            TelefamIosKeychainVault.save(key: Keys.email, value: auth.email)
        }
        if !auth.suggestedFullName.isEmpty && fullName.isEmpty {
            fullName = auth.suggestedFullName
            TelefamIosKeychainVault.save(key: Keys.fullName, value: auth.suggestedFullName)
        }
        if !auth.suggestedUsername.isEmpty && username.isEmpty {
            username = auth.suggestedUsername
            TelefamIosKeychainVault.save(key: Keys.username, value: auth.suggestedUsername)
        }
        profileCompleted = auth.profileCompleted
        isAuthenticated = true
        otpSent = false
        cooldownTimer?.invalidate()

        TelefamIosKeychainVault.save(key: Keys.sessionToken, value: auth.sessionToken)
        TelefamIosKeychainVault.save(key: Keys.profileCompleted, value: auth.profileCompleted ? "1" : "0")
    }

    private func startCooldown(seconds: Int) {
        cooldownTimer?.invalidate()
        cooldownTotalSec = seconds
        cooldownRemainingSec = seconds
        cooldownTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] t in
            Task { @MainActor in
                guard let self else {
                    t.invalidate()
                    return
                }
                if self.cooldownRemainingSec > 0 {
                    self.cooldownRemainingSec -= 1
                } else {
                    t.invalidate()
                }
            }
        }
    }
}

struct TelefamIosRemoteImage: View {
    let urlString: String
    let bearerToken: String
    let deviceFingerprint: String
    let fallbackInitials: String
    var contentMode: ContentMode = .fill

    @State private var loadedImage: UIImage? = nil

    var body: some View {
        Group {
            if let img = loadedImage {
                Image(uiImage: img)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else {
                ZStack {
                    Color(white: 0.22)
                    Text(fallbackInitials)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                }
            }
        }
        .task(id: "\(urlString)|\(bearerToken.suffix(8))") {
            await fetchImage()
        }
    }

    private func fetchImage() async {
        guard let url = URL(string: urlString), !urlString.isEmpty else { return }
        var req = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad, timeoutInterval: 15)
        if !bearerToken.isEmpty {
            req.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }
        if !deviceFingerprint.isEmpty {
            req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        }
        if let (data, resp) = try? await URLSession.shared.data(for: req),
           let http = resp as? HTTPURLResponse,
           (200...299).contains(http.statusCode),
           let ui = UIImage(data: data) {
            loadedImage = ui
        }
    }
}
