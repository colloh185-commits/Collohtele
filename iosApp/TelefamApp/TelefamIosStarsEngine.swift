import Foundation
import SwiftUI
import UIKit

extension Notification.Name {
    static let telefamStarsCheckoutCallback = Notification.Name("TelefamStarsCheckoutCallback")
}

// MARK: - Stars Catalog & Client-Side Country Currency Conversion

enum IosStarsCatalog {
    static let tiers: [Int64] = [
        5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 50_000
    ]

    struct CurrencyRule {
        let currencyCode: String
        let symbol: String
        let ratePerStar: Double
        let decimals: Int
    }

    private static let rulesByCurrency: [String: CurrencyRule] = [
        "KES": CurrencyRule(currencyCode: "KES", symbol: "Ksh", ratePerStar: 1.00, decimals: 0),
        "NGN": CurrencyRule(currencyCode: "NGN", symbol: "₦", ratePerStar: 12.00, decimals: 0),
        "GHS": CurrencyRule(currencyCode: "GHS", symbol: "GH₵", ratePerStar: 0.12, decimals: 2),
        "ZAR": CurrencyRule(currencyCode: "ZAR", symbol: "R", ratePerStar: 0.15, decimals: 2),
        "UGX": CurrencyRule(currencyCode: "UGX", symbol: "UGX", ratePerStar: 28.00, decimals: 0),
        "TZS": CurrencyRule(currencyCode: "TZS", symbol: "TZS", ratePerStar: 20.00, decimals: 0),
        "RWF": CurrencyRule(currencyCode: "RWF", symbol: "RWF", ratePerStar: 10.00, decimals: 0),
        "USD": CurrencyRule(currencyCode: "USD", symbol: "$", ratePerStar: 0.01, decimals: 2),
        "EUR": CurrencyRule(currencyCode: "EUR", symbol: "€", ratePerStar: 0.009, decimals: 2),
        "GBP": CurrencyRule(currencyCode: "GBP", symbol: "£", ratePerStar: 0.008, decimals: 2),
        "CAD": CurrencyRule(currencyCode: "CAD", symbol: "CA$", ratePerStar: 0.014, decimals: 2),
        "AUD": CurrencyRule(currencyCode: "AUD", symbol: "A$", ratePerStar: 0.015, decimals: 2),
        "JPY": CurrencyRule(currencyCode: "JPY", symbol: "¥", ratePerStar: 1.50, decimals: 0),
        "INR": CurrencyRule(currencyCode: "INR", symbol: "₹", ratePerStar: 0.85, decimals: 2),
        "AED": CurrencyRule(currencyCode: "AED", symbol: "AED", ratePerStar: 0.037, decimals: 2),
        "SAR": CurrencyRule(currencyCode: "SAR", symbol: "SAR", ratePerStar: 0.038, decimals: 2),
        "BRL": CurrencyRule(currencyCode: "BRL", symbol: "R$", ratePerStar: 0.055, decimals: 2),
        "MXN": CurrencyRule(currencyCode: "MXN", symbol: "MX$", ratePerStar: 0.20, decimals: 2)
    ]

    private static let countryToCurrency: [String: String] = [
        "KE": "KES", "NG": "NGN", "GH": "GHS", "ZA": "ZAR",
        "UG": "UGX", "TZ": "TZS", "RW": "RWF",
        "US": "USD", "GB": "GBP", "UK": "GBP", "CA": "CAD", "AU": "AUD",
        "JP": "JPY", "IN": "INR", "AE": "AED", "SA": "SAR", "BR": "BRL", "MX": "MXN",
        "DE": "EUR", "FR": "EUR", "IT": "EUR", "ES": "EUR", "NL": "EUR", "BE": "EUR",
        "IE": "EUR", "PT": "EUR", "AT": "EUR", "FI": "EUR", "GR": "EUR", "LU": "EUR"
    ]

    private static let paypalDirectCurrencies: Set<String> = ["USD", "EUR", "GBP", "CAD", "AUD", "JPY", "BRL", "MXN"]
    private static let paystackDirectCurrencies: Set<String> = ["KES", "NGN", "GHS", "ZAR", "USD"]

    static func currentDeviceCountryIso() -> String {
        let code = ((Locale.current as NSLocale).object(forKey: .countryCode) as? String ?? "US")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        return code.count == 2 ? code : "US"
    }

    static func ruleForCountry(_ countryIso: String) -> CurrencyRule {
        let clean = countryIso.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let code = countryToCurrency[clean] ?? "USD"
        return rulesByCurrency[code] ?? rulesByCurrency["USD"]!
    }

    static func formatConvertedPrice(countryIso: String, stars: Int64) -> String {
        let rule = ruleForCountry(countryIso)
        return formatRuleAmount(rule, major: Double(stars) * rule.ratePerStar)
    }

    static func gatewaySettlementNote(countryIso: String, provider: String, stars: Int64) -> String? {
        let local = ruleForCountry(countryIso)
        let prov = provider.lowercased()
        let supported = (prov == "paypal") ? paypalDirectCurrencies : paystackDirectCurrencies
        if supported.contains(local.currencyCode) { return nil }
        let usd = rulesByCurrency["USD"]!
        let usdText = formatRuleAmount(usd, major: Double(stars) * usd.ratePerStar)
        return "Settled as \(usdText) USD via \(prov == "paypal" ? "PayPal" : "Paystack")"
    }

    static func formatComma(_ value: Int64) -> String {
        let fmt = NumberFormatter()
        fmt.numberStyle = .decimal
        fmt.locale = Locale(identifier: "en_US")
        return fmt.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    private static func formatRuleAmount(_ rule: CurrencyRule, major: Double) -> String {
        let rounded = (major * 100.0).rounded() / 100.0
        let intRounded = rounded.rounded()
        let fmt = NumberFormatter()
        fmt.numberStyle = .decimal
        fmt.locale = Locale(identifier: "en_US")
        let str: String
        if rule.decimals == 0 || abs(rounded - intRounded) < 0.001 {
            fmt.maximumFractionDigits = 0
            str = fmt.string(from: NSNumber(value: Int64(intRounded))) ?? "\(Int64(intRounded))"
        } else {
            str = String(format: "%.2f", locale: Locale(identifier: "en_US"), rounded)
        }
        if rule.symbol.count > 1 && !rule.symbol.hasSuffix("$") {
            return "\(rule.symbol) \(str)"
        }
        return "\(rule.symbol)\(str)"
    }
}

// MARK: - Stars Models

enum IosStarScreenRoute: Equatable {
    case starsHub
    case sendStars
    case buyStars
    case checkout
}

struct IosStarWallet: Equatable {
    var userId: String = ""
    var availableBalance: Int64 = 0
    var totalReceived: Int64 = 0
    var totalSent: Int64 = 0
    var updatedAtMs: Int64 = 0
}

struct IosStarSender: Identifiable, Equatable {
    var id: String { senderUserId }
    var senderUserId: String
    var senderName: String
    var senderUsername: String
    var senderAvatar: String
    var totalStars: Int64
    var giftsCount: Int
    var lastGiftedAtMs: Int64
}

struct IosStarTx: Identifiable, Equatable {
    var id: String { txId }
    var txId: String
    var direction: String
    var senderUserId: String
    var senderName: String
    var senderUsername: String
    var senderAvatar: String
    var recipientUserId: String
    var recipientName: String
    var recipientUsername: String
    var starAmount: Int64
    var currency: String
    var amountMinor: Int64
    var provider: String
    var providerReference: String
    var status: String
    var createdAtMs: Int64
}

struct IosStarPaymentIntent: Identifiable, Equatable {
    var id: String { intentId.isEmpty ? providerReference : intentId }
    var intentId: String
    var idempotencyKey: String
    var starAmount: Int64
    var provider: String
    var providerReference: String
    var localCurrency: String
    var localAmountMinor: Int64
    var settlementCurrency: String
    var settlementAmountMinor: Int64
    var gatewayAmountStr: String
    var status: String
    var checkoutUrl: String
    var credited: Bool
    var createdAtMs: Int64
    var verifiedAtMs: Int64
}

struct IosStarRateLimit: Equatable {
    var hourlySentCount: Int = 0
    var hourlySentLimit: Int = 20
    var dailySentStars: Int64 = 0
    var dailySentLimit: Int64 = 100_000
    var dailyPurchaseStars: Int64 = 0
    var dailyPurchaseLimit: Int64 = 250_000
    var isRestricted: Bool = false
}

struct IosStarGiftRecipientTarget: Equatable {
    var userId: String
    var fullName: String
    var username: String
    var avatarUrl: String
    var isOnline: Bool
    var conversationId: String
}

struct IosStarCelebrationState: Equatable {
    var eventKey: String
    var starAmount: Int64
    var senderName: String
    var isIncoming: Bool
}

// MARK: - Stars Store (API + Idempotency + Gateway Verification + WS Live Sync)

@MainActor
final class TelefamIosStarsStore: ObservableObject {
    @Published var routeStack: [IosStarScreenRoute] = []
    @Published var wallet: IosStarWallet = IosStarWallet()
    @Published var countryIso: String = IosStarsCatalog.currentDeviceCountryIso()
    @Published var senders: [IosStarSender] = []
    @Published var transactions: [IosStarTx] = []
    @Published var pendingIntents: [IosStarPaymentIntent] = []
    @Published var rateLimitStatus: IosStarRateLimit = IosStarRateLimit()
    @Published var isLoadingSummary: Bool = false
    @Published var isSubmittingSend: Bool = false
    @Published var isCreatingCheckout: Bool = false
    @Published var isVerifyingPayment: Bool = false
    @Published var recipientTarget: IosStarGiftRecipientTarget? = nil
    @Published var selectedPackageStars: Int64 = 100
    @Published var selectedProvider: String = "paystack"
    @Published var pendingCheckoutIntent: IosStarPaymentIntent? = nil
    @Published var currentCheckoutIdempKey: String = ""
    @Published var launchCheckoutUrl: URL? = nil
    @Published var errorBanner: String? = nil
    @Published var statusMessage: String? = nil
    @Published var activeCelebration: IosStarCelebrationState? = nil

    var currentRoute: IosStarScreenRoute? { routeStack.last }

    private let baseURL: URL
    private let tokenProvider: () -> String
    private let deviceFingerprint: String
    private let chatStore: TelefamIosChatStore
    private let soundsEnabledProvider: () -> Bool

    private var attachedUserId: String = ""
    private var autoVerifyTask: Task<Void, Never>? = nil
    private var callbackObserver: NSObjectProtocol? = nil
    private var foregroundObserver: NSObjectProtocol? = nil

    init(
        baseURL: URL,
        tokenProvider: @escaping () -> String,
        deviceFingerprint: String,
        chatStore: TelefamIosChatStore,
        soundsEnabledProvider: @escaping () -> Bool
    ) {
        self.baseURL = baseURL
        self.tokenProvider = tokenProvider
        self.deviceFingerprint = deviceFingerprint
        self.chatStore = chatStore
        self.soundsEnabledProvider = soundsEnabledProvider

        chatStore.onStarWsEvent = { [weak self] obj in
            self?.handleStarWsEvent(obj)
        }

        callbackObserver = NotificationCenter.default.addObserver(
            forName: .telefamStarsCheckoutCallback,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let url = note.object as? URL else { return }
            Task { @MainActor [weak self] in
                self?.handleCheckoutCallbackURL(url)
            }
        }

        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let pending = self.pendingCheckoutIntent, !pending.credited {
                    self.verifyPendingPayment(silent: true)
                }
            }
        }
    }

    deinit {
        if let obs = callbackObserver { NotificationCenter.default.removeObserver(obs) }
        if let obs = foregroundObserver { NotificationCenter.default.removeObserver(obs) }
        autoVerifyTask?.cancel()
    }

    func attachIfNeeded(userId: String) {
        let clean = userId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        if attachedUserId != clean {
            attachedUserId = clean
            countryIso = IosStarsCatalog.currentDeviceCountryIso()
        }
        refreshSummary(silent: true)
    }

    func openStarsHub() {
        errorBanner = nil
        statusMessage = nil
        routeStack = [.starsHub]
        refreshSummary(silent: false)
    }

    func openSendStars(target: IosStarGiftRecipientTarget) {
        recipientTarget = target
        errorBanner = nil
        statusMessage = nil
        routeStack = [.sendStars]
        refreshSummary(silent: true)
    }

    func openBuyStars(preselectedTier: Int64 = 5) {
        selectedPackageStars = preselectedTier
        errorBanner = nil
        statusMessage = nil
        if routeStack.last != .buyStars {
            routeStack.append(.buyStars)
        }
        refreshSummary(silent: true)
    }

    func openCheckoutForPackage(_ starsAmount: Int64) {
        selectedPackageStars = starsAmount
        currentCheckoutIdempKey = UUID().uuidString
        pendingCheckoutIntent = nil
        errorBanner = nil
        statusMessage = nil
        if routeStack.last != .checkout {
            routeStack.append(.checkout)
        }
    }

    func navigateBack() {
        autoVerifyTask?.cancel()
        autoVerifyTask = nil
        errorBanner = nil
        statusMessage = nil
        if !routeStack.isEmpty {
            routeStack.removeLast()
        }
    }

    func closeAll() {
        autoVerifyTask?.cancel()
        autoVerifyTask = nil
        errorBanner = nil
        statusMessage = nil
        routeStack.removeAll()
    }

    func dismissCelebration() {
        activeCelebration = nil
    }

    func refreshSummary(silent: Bool = false) {
        let iso = IosStarsCatalog.currentDeviceCountryIso()
        if !silent {
            isLoadingSummary = true
            errorBanner = nil
        }
        Task {
            let enc = iso.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "US"
            let req = authorizedRequest(path: "v1/stars/summary", method: "GET", query: "country=\(enc)")
            guard let (data, http) = await perform(req) else {
                isLoadingSummary = false
                if !silent { errorBanner = "Connection unavailable. Check your network." }
                return
            }
            if (200...299).contains(http.statusCode),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                if let wDict = obj["wallet"] as? [String: Any] {
                    wallet = parseWallet(wDict)
                }
                senders = (obj["senders"] as? [[String: Any]] ?? []).map(parseSender)
                transactions = (obj["transactions"] as? [[String: Any]] ?? []).map(parseTx)
                pendingIntents = (obj["pending_intents"] as? [[String: Any]] ?? []).map(parseIntent)
                if let rlDict = obj["rate_limit_status"] as? [String: Any] {
                    rateLimitStatus = parseRateLimit(rlDict)
                }
                let serverIso = (obj["country_iso"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                countryIso = serverIso.count == 2 ? serverIso.uppercased() : iso
                isLoadingSummary = false
            } else {
                isLoadingSummary = false
                if !silent {
                    errorBanner = parseError(status: http.statusCode, data: data)
                }
            }
        }
    }

    func sendStarsFromSheet(_ starsAmount: Int64) {
        guard let target = recipientTarget, !isSubmittingSend else { return }
        if wallet.availableBalance < starsAmount {
            openBuyStars(preselectedTier: starsAmount)
            return
        }

        let idempKey = UUID().uuidString
        isSubmittingSend = true
        errorBanner = nil
        statusMessage = nil

        Task {
            let payload: [String: Any] = [
                "idempotency_key": idempKey,
                "recipient_user_id": target.userId,
                "conversation_id": target.conversationId,
                "star_amount": starsAmount
            ]
            guard let body = try? JSONSerialization.data(withJSONObject: payload) else {
                isSubmittingSend = false
                return
            }
            let req = authorizedRequest(
                path: "v1/stars/send",
                method: "POST",
                body: body,
                extraHeaders: ["Idempotency-Key": idempKey]
            )
            guard let (data, http) = await perform(req) else {
                isSubmittingSend = false
                errorBanner = "Connection unavailable. Check your network."
                return
            }
            let obj = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
            let insufficient = obj["insufficient_balance"] as? Bool ?? false
            let ok = (200...299).contains(http.statusCode) && (obj["success"] as? Bool ?? false)
            if ok {
                if let wDict = obj["wallet"] as? [String: Any] { wallet = parseWallet(wDict) }
                let msgDict = obj["message"] as? [String: Any]
                let cid = (msgDict?["conversation_id"] as? String ?? "").isEmpty ? target.conversationId : (msgDict?["conversation_id"] as? String ?? "")
                if !cid.isEmpty {
                    await chatStore.syncMessages(convId: cid)
                    await chatStore.refreshConversations(archived: false)
                }
                let txDict = obj["transaction"] as? [String: Any]
                let txId = txDict?["tx_id"] as? String ?? idempKey
                isSubmittingSend = false
                routeStack.removeAll()
                statusMessage = "Sent \(IosStarsCatalog.formatComma(starsAmount)) Stars"
                activeCelebration = IosStarCelebrationState(
                    eventKey: txId,
                    starAmount: starsAmount,
                    senderName: target.fullName,
                    isIncoming: false
                )
                chatStore.audio.playStarGiftChime(enabled: soundsEnabledProvider())
                refreshSummary(silent: true)
            } else if insufficient {
                isSubmittingSend = false
                openBuyStars(preselectedTier: starsAmount)
            } else {
                isSubmittingSend = false
                errorBanner = parseError(status: http.statusCode, data: data)
            }
        }
    }

    func proceedToPay() {
        guard !isCreatingCheckout && !isVerifyingPayment else { return }
        let idempKey = currentCheckoutIdempKey.isEmpty ? UUID().uuidString : currentCheckoutIdempKey
        currentCheckoutIdempKey = idempKey
        let iso = IosStarsCatalog.currentDeviceCountryIso()
        isCreatingCheckout = true
        errorBanner = nil
        statusMessage = nil

        Task {
            let payload: [String: Any] = [
                "idempotency_key": idempKey,
                "star_amount": selectedPackageStars,
                "provider": selectedProvider,
                "country_iso": iso
            ]
            guard let body = try? JSONSerialization.data(withJSONObject: payload) else {
                isCreatingCheckout = false
                return
            }
            let req = authorizedRequest(
                path: "v1/stars/checkout",
                method: "POST",
                body: body,
                extraHeaders: ["Idempotency-Key": idempKey]
            )
            guard let (data, http) = await perform(req) else {
                isCreatingCheckout = false
                errorBanner = "Connection unavailable. Check your network."
                return
            }
            let obj = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
            let ok = (200...299).contains(http.statusCode) && (obj["success"] as? Bool ?? false)
            if ok {
                let intent = parseIntent(obj["intent"] as? [String: Any] ?? [:])
                if let wDict = obj["wallet"] as? [String: Any] { wallet = parseWallet(wDict) }
                pendingCheckoutIntent = intent
                isCreatingCheckout = false
                if intent.credited {
                    statusMessage = "\(IosStarsCatalog.formatComma(intent.starAmount)) Stars credited"
                    routeStack = (recipientTarget != nil) ? [.sendStars] : [.starsHub]
                    refreshSummary(silent: true)
                } else {
                    if let url = URL(string: intent.checkoutUrl), !intent.checkoutUrl.isEmpty {
                        launchCheckoutUrl = url
                    }
                    statusMessage = "Complete payment in browser, then return to verify."
                    startAutoVerifyPolling(idempKey: idempKey, providerRef: intent.providerReference)
                }
            } else {
                isCreatingCheckout = false
                errorBanner = parseError(status: http.statusCode, data: data)
            }
        }
    }

    func verifyPendingPayment(silent: Bool = false) {
        let pending = pendingCheckoutIntent ?? pendingIntents.first(where: { !$0.credited })
        guard let target = pending else { return }
        let idemp = target.idempotencyKey.isEmpty ? currentCheckoutIdempKey : target.idempotencyKey
        if !silent {
            isVerifyingPayment = true
            errorBanner = nil
        }
        Task {
            _ = await verifyInternal(idempKey: idemp, providerRef: target.providerReference, silent: silent)
        }
    }

    func handleCheckoutCallbackURL(_ url: URL) {
        guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        let status = comps.queryItems?.first(where: { $0.name == "status" })?.value ?? ""
        let ref = comps.queryItems?.first(where: { $0.name == "reference" })?.value
            ?? comps.queryItems?.first(where: { $0.name == "trxref" })?.value
            ?? comps.queryItems?.first(where: { $0.name == "token" })?.value
            ?? pendingCheckoutIntent?.providerReference
            ?? ""
        if status.lowercased() == "cancelled" {
            autoVerifyTask?.cancel()
            errorBanner = "Payment was cancelled"
            return
        }
        isVerifyingPayment = true
        Task {
            _ = await verifyInternal(idempKey: currentCheckoutIdempKey, providerRef: ref, silent: false)
        }
    }

    private func startAutoVerifyPolling(idempKey: String, providerRef: String) {
        autoVerifyTask?.cancel()
        autoVerifyTask = Task {
            for _ in 0..<18 {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if Task.isCancelled { return }
                guard let pending = pendingCheckoutIntent, !pending.credited, currentRoute == .checkout else { return }
                if await verifyInternal(idempKey: idempKey, providerRef: providerRef, silent: true) {
                    return
                }
            }
        }
    }

    private func verifyInternal(idempKey: String, providerRef: String, silent: Bool) async -> Bool {
        let payload: [String: Any] = [
            "idempotency_key": idempKey,
            "provider_reference": providerRef
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else {
            if !silent { isVerifyingPayment = false }
            return false
        }
        let req = authorizedRequest(path: "v1/stars/payments/verify", method: "POST", body: body)
        guard let (data, http) = await perform(req) else {
            if !silent {
                isVerifyingPayment = false
                errorBanner = "Connection unavailable. Check your network."
            }
            return false
        }
        let obj = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        let ok = (200...299).contains(http.statusCode) && (obj["success"] as? Bool ?? false)
        if ok {
            let intent = parseIntent(obj["intent"] as? [String: Any] ?? [:])
            if let wDict = obj["wallet"] as? [String: Any] { wallet = parseWallet(wDict) }
            pendingCheckoutIntent = intent
            isVerifyingPayment = false
            if intent.credited {
                autoVerifyTask?.cancel()
                statusMessage = "Payment verified. \(IosStarsCatalog.formatComma(intent.starAmount)) Stars added!"
                routeStack = (recipientTarget != nil) ? [.sendStars] : [.starsHub]
                chatStore.audio.playStarGiftChime(enabled: soundsEnabledProvider())
                refreshSummary(silent: true)
                return true
            } else if !silent {
                statusMessage = "Payment status: \(intent.status.isEmpty ? "pending" : intent.status)"
            }
        } else if !silent {
            isVerifyingPayment = false
            errorBanner = parseError(status: http.statusCode, data: data)
        }
        return false
    }

    private func handleStarWsEvent(_ obj: [String: Any]) {
        let ev = obj["event"] as? String ?? ""
        let cid = obj["conversation_id"] as? String ?? ""
        let uid = obj["user_id"] as? String ?? ""
        let mid = obj["message_id"] as? String ?? ""
        let starAmt = (obj["star_gift_amount"] as? NSNumber)?.int64Value ?? 0
        let tsMs = (obj["timestamp_ms"] as? NSNumber)?.int64Value ?? Int64(Date().timeIntervalSince1970 * 1000)

        if ev == "star_balance_updated" {
            if let avail = (obj["star_balance_available"] as? NSNumber)?.int64Value {
                wallet.availableBalance = avail
            }
            if let rec = (obj["star_balance_received"] as? NSNumber)?.int64Value {
                wallet.totalReceived = rec
            }
            refreshSummary(silent: true)
            return
        }

        if ev == "star_gift_received" || ((ev == "new_message" || ev == "message_new") && starAmt > 0 && uid != attachedUserId) {
            let senderName = chatStore.conversations
                .first(where: { $0.conversationId == cid || $0.peerUserId == uid })?
                .peerFullName ?? ""
            activeCelebration = IosStarCelebrationState(
                eventKey: mid.isEmpty ? "\(cid)_\(tsMs)" : mid,
                starAmount: max(5, starAmt),
                senderName: senderName.isEmpty ? "Supporter" : senderName,
                isIncoming: true
            )
            chatStore.audio.playStarGiftChime(enabled: soundsEnabledProvider())
            refreshSummary(silent: true)
        }
    }

    private func authorizedRequest(
        path: String,
        method: String,
        query: String? = nil,
        body: Data? = nil,
        extraHeaders: [String: String] = [:]
    ) -> URLRequest {
        var url = baseURL.appendingPathComponent(path)
        if let query, !query.isEmpty, var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            comps.percentEncodedQuery = query
            if let u = comps.url { url = u }
        }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
        req.httpMethod = method
        req.setValue("gzip", forHTTPHeaderField: "Accept-Encoding")
        let tok = tokenProvider()
        if !tok.isEmpty { req.setValue("Bearer \(tok)", forHTTPHeaderField: "Authorization") }
        req.setValue(deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        for (k, v) in extraHeaders { req.setValue(v, forHTTPHeaderField: k) }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = body
        }
        return req
    }

    private func perform(_ req: URLRequest) async -> (Data, HTTPURLResponse)? {
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse else { return nil }
        return (data, http)
    }

    private func parseWallet(_ m: [String: Any]) -> IosStarWallet {
        IosStarWallet(
            userId: m["user_id"] as? String ?? "",
            availableBalance: (m["available_balance"] as? NSNumber)?.int64Value ?? 0,
            totalReceived: (m["total_received"] as? NSNumber)?.int64Value ?? 0,
            totalSent: (m["total_sent"] as? NSNumber)?.int64Value ?? 0,
            updatedAtMs: (m["updated_at_ms"] as? NSNumber)?.int64Value ?? 0
        )
    }

    private func parseSender(_ m: [String: Any]) -> IosStarSender {
        IosStarSender(
            senderUserId: m["sender_user_id"] as? String ?? "",
            senderName: m["sender_name"] as? String ?? "",
            senderUsername: m["sender_username"] as? String ?? "",
            senderAvatar: m["sender_avatar"] as? String ?? "",
            totalStars: (m["total_stars"] as? NSNumber)?.int64Value ?? 0,
            giftsCount: (m["gifts_count"] as? NSNumber)?.intValue ?? 0,
            lastGiftedAtMs: (m["last_gifted_at_ms"] as? NSNumber)?.int64Value ?? 0
        )
    }

    private func parseTx(_ m: [String: Any]) -> IosStarTx {
        IosStarTx(
            txId: m["tx_id"] as? String ?? "",
            direction: m["direction"] as? String ?? "",
            senderUserId: m["sender_user_id"] as? String ?? "",
            senderName: m["sender_name"] as? String ?? "",
            senderUsername: m["sender_username"] as? String ?? "",
            senderAvatar: m["sender_avatar"] as? String ?? "",
            recipientUserId: m["recipient_user_id"] as? String ?? "",
            recipientName: m["recipient_name"] as? String ?? "",
            recipientUsername: m["recipient_username"] as? String ?? "",
            starAmount: (m["star_amount"] as? NSNumber)?.int64Value ?? 0,
            currency: m["currency"] as? String ?? "",
            amountMinor: (m["amount_minor"] as? NSNumber)?.int64Value ?? 0,
            provider: m["provider"] as? String ?? "",
            providerReference: m["provider_reference"] as? String ?? "",
            status: m["status"] as? String ?? "",
            createdAtMs: (m["created_at_ms"] as? NSNumber)?.int64Value ?? 0
        )
    }

    private func parseIntent(_ m: [String: Any]) -> IosStarPaymentIntent {
        IosStarPaymentIntent(
            intentId: m["intent_id"] as? String ?? "",
            idempotencyKey: m["idempotency_key"] as? String ?? "",
            starAmount: (m["star_amount"] as? NSNumber)?.int64Value ?? 0,
            provider: m["provider"] as? String ?? "",
            providerReference: m["provider_reference"] as? String ?? "",
            localCurrency: m["local_currency"] as? String ?? "",
            localAmountMinor: (m["local_amount_minor"] as? NSNumber)?.int64Value ?? 0,
            settlementCurrency: m["settlement_currency"] as? String ?? "",
            settlementAmountMinor: (m["settlement_amount_minor"] as? NSNumber)?.int64Value ?? 0,
            gatewayAmountStr: m["gateway_amount_str"] as? String ?? "",
            status: m["status"] as? String ?? "",
            checkoutUrl: m["checkout_url"] as? String ?? "",
            credited: m["credited"] as? Bool ?? false,
            createdAtMs: (m["created_at_ms"] as? NSNumber)?.int64Value ?? 0,
            verifiedAtMs: (m["verified_at_ms"] as? NSNumber)?.int64Value ?? 0
        )
    }

    private func parseRateLimit(_ m: [String: Any]) -> IosStarRateLimit {
        IosStarRateLimit(
            hourlySentCount: (m["hourly_sent_count"] as? NSNumber)?.intValue ?? 0,
            hourlySentLimit: (m["hourly_sent_limit"] as? NSNumber)?.intValue ?? 20,
            dailySentStars: (m["daily_sent_stars"] as? NSNumber)?.int64Value ?? 0,
            dailySentLimit: (m["daily_sent_limit"] as? NSNumber)?.int64Value ?? 100_000,
            dailyPurchaseStars: (m["daily_purchase_stars"] as? NSNumber)?.int64Value ?? 0,
            dailyPurchaseLimit: (m["daily_purchase_limit"] as? NSNumber)?.int64Value ?? 250_000,
            isRestricted: m["is_restricted"] as? Bool ?? false
        )
    }

    private func parseError(status: Int, data: Data) -> String {
        if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let err = obj["error"] as? String, !err.isEmpty {
            return err
        }
        return status == 429 ? "Rate limit reached. Please try again later." : "Request failed (\(status))"
    }
}
