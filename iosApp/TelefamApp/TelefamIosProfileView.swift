import SwiftUI
import UIKit

enum TelefamIosProfilePostFilter: String, CaseIterable, Identifiable {
    case all
    case videos
    case photos
    case text

    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: return "All posts"
        case .videos: return "Videos"
        case .photos: return "Photos"
        case .text: return "Text"
        }
    }
}

enum TelefamIosProfilePostSort: String, CaseIterable, Identifiable {
    case latest
    case mostViewed
    case mostLiked

    var id: String { rawValue }

    var label: String {
        switch self {
        case .latest: return "Latest"
        case .mostViewed: return "Most viewed"
        case .mostLiked: return "Most liked"
        }
    }
}

enum TelefamIosProfileReportOptions {
    static let categories: [String] = [
        "Spam or misleading",
        "Harassment or bullying",
        "Impersonation",
        "Hate or abusive content",
        "Scam or fraud",
        "Inappropriate content",
        "Other"
    ]
}

struct TelefamIosUserProfile: Codable, Equatable {
    var userId: String
    var fullName: String
    var username: String
    var avatarUrl: String
    var bio: String
    var location: String
    var countryIso: String
    var followingCount: Int64
    var followersCount: Int64
    var likesCount: Int64
    var isFollowing: Bool
    var followsYou: Bool
    var isOwner: Bool
    var isBlocked: Bool
    var riskScore: Double
    var posts: [TelefamIosFeedItem]

    var relationship: TelefamRelationshipState {
        TelefamRelationshipState.compute(isFollowing: isFollowing, followsYou: followsYou)
    }

    static func == (lhs: TelefamIosUserProfile, rhs: TelefamIosUserProfile) -> Bool {
        lhs.userId == rhs.userId &&
            lhs.fullName == rhs.fullName &&
            lhs.username == rhs.username &&
            lhs.avatarUrl == rhs.avatarUrl &&
            lhs.bio == rhs.bio &&
            lhs.location == rhs.location &&
            lhs.followingCount == rhs.followingCount &&
            lhs.followersCount == rhs.followersCount &&
            lhs.likesCount == rhs.likesCount &&
            lhs.isFollowing == rhs.isFollowing &&
            lhs.followsYou == rhs.followsYou &&
            lhs.isOwner == rhs.isOwner &&
            lhs.isBlocked == rhs.isBlocked &&
            lhs.posts.count == rhs.posts.count
    }

    func filteredAndSortedPosts(
        filter: TelefamIosProfilePostFilter,
        sort: TelefamIosProfilePostSort
    ) -> [TelefamIosFeedItem] {
        let filtered: [TelefamIosFeedItem]
        switch filter {
        case .all: filtered = posts
        case .videos: filtered = posts.filter { $0.mode == "video" }
        case .photos: filtered = posts.filter { $0.mode == "photos" }
        case .text: filtered = posts.filter { $0.mode == "text" }
        }
        switch sort {
        case .latest:
            return filtered.sorted { $0.createdAtMs > $1.createdAtMs }
        case .mostViewed:
            return filtered.sorted { $0.viewsCount > $1.viewsCount }
        case .mostLiked:
            return filtered.sorted { $0.likesCount > $1.likesCount }
        }
    }
}

private struct UserProfileAPIResponse: Codable {
    let success: Bool
    let userId: String?
    let fullName: String?
    let username: String?
    let avatarUrl: String?
    let bio: String?
    let location: String?
    let countryIso: String?
    let followingCount: Int64?
    let followersCount: Int64?
    let likesCount: Int64?
    let isFollowing: Bool?
    let followsYou: Bool?
    let isOwner: Bool?
    let isBlocked: Bool?
    let riskScore: Double?
    let posts: [TelefamIosFeedItem]?

    enum CodingKeys: String, CodingKey {
        case success
        case userId = "user_id"
        case fullName = "full_name"
        case username
        case avatarUrl = "avatar_url"
        case bio
        case location
        case countryIso = "country_iso"
        case followingCount = "following_count"
        case followersCount = "followers_count"
        case likesCount = "likes_count"
        case isFollowing = "is_following"
        case followsYou = "follows_you"
        case isOwner = "is_owner"
        case isBlocked = "is_blocked"
        case riskScore = "risk_score"
        case posts
    }
}

private struct UserConnectionsAPIResponse: Codable {
    let success: Bool
    let items: [TelefamContactRecord]
    let page: Int
    let hasMore: Bool

    enum CodingKeys: String, CodingKey {
        case success, items, page
        case hasMore = "has_more"
    }
}

private struct UserBlockAPIResponse: Codable {
    let success: Bool
    let isBlocked: Bool?

    enum CodingKeys: String, CodingKey {
        case success
        case isBlocked = "is_blocked"
    }
}

func formatTelefamProfileMetricCount(_ value: Int64) -> String {
    if value < 1_000 { return "\(max(0, value))" }
    if value < 1_000_000 {
        let tenths = value / 100
        let whole = tenths / 10
        let rem = tenths % 10
        return rem == 0 ? "\(whole)K" : "\(whole).\(rem)K"
    }
    let tenths = value / 100_000
    let whole = tenths / 10
    let rem = tenths % 10
    return rem == 0 ? "\(whole)M" : "\(whole).\(rem)M"
}

@MainActor
final class TelefamIosProfileStore: ObservableObject {
    @Published var profile: TelefamIosUserProfile
    @Published var isLoading: Bool = true
    @Published var connections: [TelefamContactRecord] = []
    @Published var connectionsIsFollowers: Bool? = nil
    @Published var isLoadingConnections: Bool = false
    @Published var connectionsPage: Int = 1
    @Published var connectionsHasMore: Bool = false
    @Published var toastMessage: String? = nil

    private let baseURL: URL
    private let sessionStore: TelefamIosSessionStore
    private let contactsStore: TelefamContactsStore
    private let feedStore: TelefamIosFeedStore

    init(
        targetUserId: String,
        isOwnHint: Bool,
        baseURL: URL,
        sessionStore: TelefamIosSessionStore,
        contactsStore: TelefamContactsStore,
        feedStore: TelefamIosFeedStore
    ) {
        self.baseURL = baseURL
        self.sessionStore = sessionStore
        self.contactsStore = contactsStore
        self.feedStore = feedStore

        let resolvedId = targetUserId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? (sessionStore.userId.isEmpty ? "me" : sessionStore.userId)
            : targetUserId.trimmingCharacters(in: .whitespacesAndNewlines)

        let ownerFlag = isOwnHint || resolvedId == "me" || (!sessionStore.userId.isEmpty && resolvedId == sessionStore.userId)
        let contactMatch = contactsStore.allCached.first { $0.userId == resolvedId }

        self.profile = TelefamIosUserProfile(
            userId: resolvedId,
            fullName: contactMatch?.fullName ?? (ownerFlag ? sessionStore.fullName : ""),
            username: contactMatch?.username ?? (ownerFlag ? sessionStore.username : ""),
            avatarUrl: contactMatch?.avatarUrl ?? "",
            bio: ownerFlag ? sessionStore.bio : "",
            location: contactMatch?.location ?? (ownerFlag ? sessionStore.location : ""),
            countryIso: contactMatch?.countryIso ?? "",
            followingCount: 0,
            followersCount: 0,
            likesCount: 0,
            isFollowing: contactMatch?.isFollowing ?? false,
            followsYou: contactMatch?.followsYou ?? false,
            isOwner: ownerFlag,
            isBlocked: false,
            riskScore: contactMatch?.riskScore ?? 0,
            posts: []
        )

        if let cached = Self.loadCachedProfile(userId: resolvedId) {
            self.profile = cached
            self.isLoading = false
        }
    }

    private static func profileCacheURL(userId: String) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        let safe = userId.replacingOccurrences(of: "/", with: "_")
        return dir.appendingPathComponent("telefam_ios_profile_\(safe).json")
    }

    private static func connectionsCacheURL(userId: String, isFollowers: Bool) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        let safe = userId.replacingOccurrences(of: "/", with: "_")
        let kind = isFollowers ? "followers" : "following"
        return dir.appendingPathComponent("telefam_ios_conn_\(safe)_\(kind).json")
    }

    private static func loadCachedProfile(userId: String) -> TelefamIosUserProfile? {
        guard let data = try? Data(contentsOf: profileCacheURL(userId: userId)),
              let decoded = try? JSONDecoder().decode(TelefamIosUserProfile.self, from: data) else {
            return nil
        }
        return decoded
    }

    private func persistProfileCache() {
        if let data = try? JSONEncoder().encode(profile) {
            try? data.write(to: Self.profileCacheURL(userId: profile.userId))
        }
    }

    func fetchProfile(forceLoading: Bool = false) async {
        if forceLoading || profile.fullName.isEmpty {
            isLoading = true
        }
        defer { isLoading = false }

        let targetId = profile.userId.isEmpty ? "me" : profile.userId
        let url = baseURL
            .appendingPathComponent("v1/users")
            .appendingPathComponent(targetId)
            .appendingPathComponent("profile")

        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        req.setValue("Bearer \(sessionStore.sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(sessionStore.deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")

        if let (data, resp) = try? await URLSession.shared.data(for: req),
           let http = resp as? HTTPURLResponse,
           (200...299).contains(http.statusCode),
           let decoded = try? JSONDecoder().decode(UserProfileAPIResponse.self, from: data),
           decoded.success {
            let finalId = (decoded.userId?.isEmpty == false) ? decoded.userId! : targetId
            profile = TelefamIosUserProfile(
                userId: finalId,
                fullName: decoded.fullName ?? profile.fullName,
                username: decoded.username ?? profile.username,
                avatarUrl: decoded.avatarUrl ?? profile.avatarUrl,
                bio: decoded.bio ?? profile.bio,
                location: decoded.location ?? profile.location,
                countryIso: decoded.countryIso ?? profile.countryIso,
                followingCount: decoded.followingCount ?? 0,
                followersCount: decoded.followersCount ?? 0,
                likesCount: decoded.likesCount ?? 0,
                isFollowing: decoded.isFollowing ?? false,
                followsYou: decoded.followsYou ?? false,
                isOwner: decoded.isOwner ?? profile.isOwner,
                isBlocked: decoded.isBlocked ?? false,
                riskScore: decoded.riskScore ?? 0,
                posts: decoded.posts ?? []
            )
            persistProfileCache()
        } else if let cached = Self.loadCachedProfile(userId: targetId) {
            profile = cached
        }
    }

    func toggleFollow() {
        let wasFollowing = profile.isFollowing
        let nextFollowing = !wasFollowing
        profile.isFollowing = nextFollowing
        profile.followersCount = nextFollowing
            ? profile.followersCount + 1
            : max(0, profile.followersCount - 1)
        persistProfileCache()
        feedStore.markAuthorFollowing(userId: profile.userId, isFollowing: nextFollowing)

        // Sync via contacts store if present, or call contacts/follow|unfollow directly.
        if contactsStore.allCached.contains(where: { $0.userId == profile.userId }) {
            contactsStore.toggleFollow(for: profile.userId)
            if let msg = contactsStore.toastMessage {
                showToast(msg)
            }
            return
        }

        Task {
            let endpoint = nextFollowing ? "v1/contacts/follow" : "v1/contacts/unfollow"
            var req = URLRequest(url: baseURL.appendingPathComponent(endpoint))
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.setValue("Bearer \(sessionStore.sessionToken)", forHTTPHeaderField: "Authorization")
            req.setValue(sessionStore.deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
            req.httpBody = try? JSONSerialization.data(withJSONObject: ["target_user_id": profile.userId])

            if let (_, resp) = try? await URLSession.shared.data(for: req),
               let http = resp as? HTTPURLResponse {
                if http.statusCode == 429 {
                    profile.isFollowing = wasFollowing
                    profile.followersCount = wasFollowing ? profile.followersCount + 1 : max(0, profile.followersCount - 1)
                    persistProfileCache()
                    showToast("Try again later")
                } else if !(200...299).contains(http.statusCode) {
                    showToast("Saved offline — will sync when online")
                }
            }
        }
    }

    func openConnections(isFollowers: Bool) async {
        connectionsIsFollowers = isFollowers
        connectionsPage = 1
        connectionsHasMore = false
        if let data = try? Data(contentsOf: Self.connectionsCacheURL(userId: profile.userId, isFollowers: isFollowers)),
           let cached = try? JSONDecoder().decode([TelefamContactRecord].self, from: data) {
            connections = cached
        } else {
            connections = []
        }
        await loadConnectionsPage(page: 1, append: false, isFollowers: isFollowers)
    }

    func loadMoreConnectionsIfNeeded() async {
        guard let isFollowers = connectionsIsFollowers,
              connectionsHasMore,
              !isLoadingConnections else { return }
        await loadConnectionsPage(page: connectionsPage + 1, append: true, isFollowers: isFollowers)
    }

    private func loadConnectionsPage(page: Int, append: Bool, isFollowers: Bool) async {
        isLoadingConnections = true
        defer { isLoadingConnections = false }

        let targetId = profile.userId.isEmpty ? "me" : profile.userId
        let pathSuffix = isFollowers ? "followers" : "following"
        let baseEndpoint = baseURL
            .appendingPathComponent("v1/users")
            .appendingPathComponent(targetId)
            .appendingPathComponent(pathSuffix)
        var comps = URLComponents(url: baseEndpoint, resolvingAgainstBaseURL: false)
        comps?.queryItems = [
            URLQueryItem(name: "page", value: "\(page)"),
            URLQueryItem(name: "page_size", value: "30")
        ]
        guard let url = comps?.url else { return }

        var req = URLRequest(url: url)
        req.setValue("Bearer \(sessionStore.sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(sessionStore.deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")

        if let (data, resp) = try? await URLSession.shared.data(for: req),
           let http = resp as? HTTPURLResponse,
           (200...299).contains(http.statusCode),
           let parsed = try? JSONDecoder().decode(UserConnectionsAPIResponse.self, from: data),
           parsed.success {
            connectionsPage = parsed.page
            connectionsHasMore = parsed.hasMore
            if append {
                var merged = connections
                for item in parsed.items where !merged.contains(where: { $0.userId == item.userId }) {
                    merged.append(item)
                }
                connections = merged
            } else {
                connections = parsed.items
            }
            if let encoded = try? JSONEncoder().encode(connections) {
                try? encoded.write(to: Self.connectionsCacheURL(userId: targetId, isFollowers: isFollowers))
            }
        }
    }

    func toggleConnectionFollow(targetUserId: String) {
        if let idx = connections.firstIndex(where: { $0.userId == targetUserId }) {
            connections[idx].isFollowing.toggle()
        }
        if contactsStore.allCached.contains(where: { $0.userId == targetUserId }) {
            contactsStore.toggleFollow(for: targetUserId)
            return
        }
        let wantFollow = connections.first(where: { $0.userId == targetUserId })?.isFollowing ?? true
        Task {
            let endpoint = wantFollow ? "v1/contacts/follow" : "v1/contacts/unfollow"
            var req = URLRequest(url: baseURL.appendingPathComponent(endpoint))
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.setValue("Bearer \(sessionStore.sessionToken)", forHTTPHeaderField: "Authorization")
            req.setValue(sessionStore.deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
            req.httpBody = try? JSONSerialization.data(withJSONObject: ["target_user_id": targetUserId])
            _ = try? await URLSession.shared.data(for: req)
        }
    }

    func blockUser() async {
        let targetId = profile.userId
        guard !targetId.isEmpty else { return }
        profile.isBlocked = true
        profile.isFollowing = false
        persistProfileCache()

        var req = URLRequest(
            url: baseURL.appendingPathComponent("v1/users").appendingPathComponent(targetId).appendingPathComponent("block")
        )
        req.httpMethod = "POST"
        req.setValue("Bearer \(sessionStore.sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(sessionStore.deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        if let (data, resp) = try? await URLSession.shared.data(for: req),
           let http = resp as? HTTPURLResponse,
           (200...299).contains(http.statusCode),
           let parsed = try? JSONDecoder().decode(UserBlockAPIResponse.self, from: data),
           parsed.success {
            profile.isBlocked = parsed.isBlocked ?? true
            persistProfileCache()
            showToast("Account blocked")
        } else {
            showToast("Unable to update block status right now")
        }
    }

    func unblockUser() async {
        let targetId = profile.userId
        guard !targetId.isEmpty else { return }
        profile.isBlocked = false
        persistProfileCache()

        var req = URLRequest(
            url: baseURL.appendingPathComponent("v1/users").appendingPathComponent(targetId).appendingPathComponent("unblock")
        )
        req.httpMethod = "POST"
        req.setValue("Bearer \(sessionStore.sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(sessionStore.deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        if let (data, resp) = try? await URLSession.shared.data(for: req),
           let http = resp as? HTTPURLResponse,
           (200...299).contains(http.statusCode),
           let parsed = try? JSONDecoder().decode(UserBlockAPIResponse.self, from: data),
           parsed.success {
            profile.isBlocked = parsed.isBlocked ?? false
            persistProfileCache()
            showToast("Account unblocked")
            await fetchProfile()
        } else {
            showToast("Unable to unblock right now")
        }
    }

    func reportUser(reason: String, details: String) async {
        let targetId = profile.userId
        guard !targetId.isEmpty else { return }

        var req = URLRequest(
            url: baseURL.appendingPathComponent("v1/users").appendingPathComponent(targetId).appendingPathComponent("report")
        )
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(sessionStore.sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue(sessionStore.deviceFingerprint, forHTTPHeaderField: "X-Telefam-Device-FP")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["reason": reason, "details": details])

        if let (_, resp) = try? await URLSession.shared.data(for: req),
           let http = resp as? HTTPURLResponse,
           (200...299).contains(http.statusCode) {
            showToast("Report submitted")
        } else {
            showToast("Unable to submit report right now")
        }
    }

    func incrementLocalPostView(postId: String) {
        if let idx = profile.posts.firstIndex(where: { $0.postId == postId }) {
            profile.posts[idx].viewsCount += 1
            persistProfileCache()
        }
        feedStore.recordView(postId: postId)
    }

    func shareProfileURL() -> URL {
        let handle = profile.username.isEmpty ? profile.userId : profile.username
        if let host = baseURL.host?.lowercased(),
           host != "127.0.0.1", host != "localhost", host != "::1" {
            return baseURL.appendingPathComponent("u").appendingPathComponent(handle)
        }
        return URL(string: "telefam://u/\(handle)")!
    }

    func showToast(_ message: String) {
        toastMessage = message
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) { [weak self] in
            if self?.toastMessage == message { self?.toastMessage = nil }
        }
    }
}

struct TelefamIosProfileView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss

    @ObservedObject var sessionStore: TelefamIosSessionStore
    @ObservedObject var contactsStore: TelefamContactsStore
    @ObservedObject var feedStore: TelefamIosFeedStore
    @StateObject private var profileStore: TelefamIosProfileStore

    let isEmbeddedOwnProfileTab: Bool
    let bottomChromePadding: CGFloat
    @Binding var themeMode: TelefamThemeMode
    var onBack: () -> Void
    var onOpenUserProfile: (String) -> Void
    var onEditOwnerPost: (TelefamIosFeedItem) -> Void
    var onTapHashtag: (String, TelefamIosPostMode) -> Void
    var onTapAudio: (TelefamIosSongTrack, TelefamIosPostMode) -> Void

    @State private var selectedFilter: TelefamIosProfilePostFilter = .all
    @State private var selectedSort: TelefamIosProfilePostSort = .latest
    @State private var viewerPosts: [TelefamIosFeedItem]? = nil
    @State private var viewerStartIndex: Int = 0
    @State private var showConnectionsSheet: Bool = false
    @State private var showReportCategorySheet: Bool = false
    @State private var selectedReportCategory: String? = nil
    @State private var reportDetails: String = ""

    init(
        targetUserId: String,
        isEmbeddedOwnProfileTab: Bool,
        bottomChromePadding: CGFloat,
        baseURL: URL,
        sessionStore: TelefamIosSessionStore,
        contactsStore: TelefamContactsStore,
        feedStore: TelefamIosFeedStore,
        themeMode: Binding<TelefamThemeMode>,
        onBack: @escaping () -> Void,
        onOpenUserProfile: @escaping (String) -> Void,
        onEditOwnerPost: @escaping (TelefamIosFeedItem) -> Void,
        onTapHashtag: @escaping (String, TelefamIosPostMode) -> Void,
        onTapAudio: @escaping (TelefamIosSongTrack, TelefamIosPostMode) -> Void
    ) {
        self.isEmbeddedOwnProfileTab = isEmbeddedOwnProfileTab
        self.bottomChromePadding = bottomChromePadding
        self.sessionStore = sessionStore
        self.contactsStore = contactsStore
        self.feedStore = feedStore
        self._themeMode = themeMode
        self.onBack = onBack
        self.onOpenUserProfile = onOpenUserProfile
        self.onEditOwnerPost = onEditOwnerPost
        self.onTapHashtag = onTapHashtag
        self.onTapAudio = onTapAudio
        _profileStore = StateObject(
            wrappedValue: TelefamIosProfileStore(
                targetUserId: targetUserId,
                isOwnHint: isEmbeddedOwnProfileTab,
                baseURL: baseURL,
                sessionStore: sessionStore,
                contactsStore: contactsStore,
                feedStore: feedStore
            )
        )
    }

    private var accentCrimson: Color {
        colorScheme == .dark ? TelefamPalette.crimsonBright : TelefamPalette.crimson
    }

    var body: some View {
        let p = profileStore.profile
        let filteredPosts = p.filteredAndSortedPosts(filter: selectedFilter, sort: selectedSort)

        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                topBar(profile: p)

                GeometryReader { geo in
                    let gridColumnsCount = geo.size.width >= 960 ? 5 : (geo.size.width >= 680 ? 4 : 3)
                    let gridCols = Array(repeating: GridItem(.flexible(), spacing: 2), count: gridColumnsCount)

                    ScrollView {
                        VStack(spacing: 0) {
                            profileHeader(profile: p)

                            // Posts Tab Strip matching Alex Mwangi screenshot
                            VStack(spacing: 0) {
                                HStack {
                                    Spacer()
                                    VStack(spacing: 10) {
                                        HStack(spacing: 6) {
                                            PostsGrid2x2SvgIcon(color: TelefamPalette.textPrimary(colorScheme))
                                                .frame(width: 17, height: 17)
                                            Text("Posts")
                                                .font(.system(size: 14, weight: .bold))
                                                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                        }
                                        Rectangle()
                                            .fill(accentCrimson)
                                            .frame(width: 96, height: 2.5)
                                    }
                                    Spacer()
                                    Spacer()
                                    Spacer()
                                }
                                .padding(.top, 18)

                                Divider().background(TelefamPalette.border(colorScheme))
                            }

                            // Filter & Sort Toolbar ("All posts v" | Sort Sliders)
                            HStack {
                                Menu {
                                    ForEach(TelefamIosProfilePostFilter.allCases) { f in
                                        Button {
                                            selectedFilter = f
                                        } label: {
                                            if selectedFilter == f {
                                                Label(f.label, systemImage: "checkmark")
                                            } else {
                                                Text(f.label)
                                            }
                                        }
                                    }
                                } label: {
                                    HStack(spacing: 4) {
                                        Text(selectedFilter.label)
                                            .font(.system(size: 14, weight: .bold))
                                            .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                        Image(systemName: "chevron.down")
                                            .font(.system(size: 11.5, weight: .bold))
                                            .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                    }
                                    .frame(minHeight: 44)
                                }

                                Spacer()

                                Menu {
                                    ForEach(TelefamIosProfilePostSort.allCases) { s in
                                        Button {
                                            selectedSort = s
                                        } label: {
                                            if selectedSort == s {
                                                Label(s.label, systemImage: "checkmark")
                                            } else {
                                                Text(s.label)
                                            }
                                        }
                                    }
                                } label: {
                                    FilterSlidersSvgIcon(color: TelefamPalette.textPrimary(colorScheme))
                                        .frame(width: 44, height: 44)
                                }
                            }
                            .padding(.horizontal, 14)

                            if profileStore.isLoading && filteredPosts.isEmpty {
                                HStack {
                                    Spacer()
                                    TelefamIosTricolorSpinner(size: 38)
                                        .padding(.vertical, 32)
                                    Spacer()
                                }
                            } else if filteredPosts.isEmpty {
                                VStack(spacing: 8) {
                                    Text("No posts yet")
                                        .font(.system(size: 15.5, weight: .bold))
                                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                    Text(p.isBlocked ? "Unblock this account to view their posts." : "Published photos, text cards, and videos appear here.")
                                        .font(.system(size: 13))
                                        .foregroundColor(TelefamPalette.textMuted(colorScheme))
                                        .multilineTextAlignment(.center)
                                }
                                .padding(.horizontal, 28)
                                .padding(.vertical, 36)
                            } else {
                                LazyVGrid(columns: gridCols, spacing: 2) {
                                    ForEach(Array(filteredPosts.enumerated()), id: \.element.postId) { idx, post in
                                        ProfilePostGridCell(
                                            post: post,
                                            feedStore: feedStore,
                                            accentCrimson: accentCrimson
                                        ) {
                                            profileStore.incrementLocalPostView(postId: post.postId)
                                            viewerPosts = filteredPosts
                                            viewerStartIndex = idx
                                        }
                                    }
                                }
                            }
                        }
                        .frame(maxWidth: 920)
                        .frame(maxWidth: .infinity)
                        .padding(.bottom, 24 + bottomChromePadding)
                    }
                }
            }
            .background(TelefamPalette.background(colorScheme).ignoresSafeArea())

            if let toast = profileStore.toastMessage {
                Text(toast)
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(Color.black.opacity(0.84))
                    .clipShape(Capsule())
                    .padding(.bottom, 28 + bottomChromePadding)
                    .transition(.opacity)
            }
        }
        .task {
            await profileStore.fetchProfile()
        }
        .sheet(isPresented: $showConnectionsSheet) {
            ProfileConnectionsSheetView(
                title: (profileStore.connectionsIsFollowers ?? true) ? "Followers" : "Following",
                profileStore: profileStore,
                contactsStore: contactsStore
            ) { clickedUserId in
                showConnectionsSheet = false
                onOpenUserProfile(clickedUserId)
            }
            .preferredColorScheme(themeMode.preferredColorScheme)
        }
        .confirmationDialog("Report account", isPresented: $showReportCategorySheet) {
            ForEach(TelefamIosProfileReportOptions.categories, id: \.self) { cat in
                Button(cat) {
                    selectedReportCategory = cat
                    reportDetails = ""
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: Binding(get: { selectedReportCategory != nil }, set: { if !$0 { selectedReportCategory = nil } })) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 14) {
                    Text(selectedReportCategory ?? "")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))

                    TextEditor(text: $reportDetails)
                        .frame(minHeight: 110)
                        .padding(8)
                        .background(TelefamPalette.surface(colorScheme))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(TelefamPalette.border(colorScheme)))

                    Spacer()

                    Button {
                        let cat = selectedReportCategory ?? "Other"
                        let det = reportDetails
                        selectedReportCategory = nil
                        Task { await profileStore.reportUser(reason: cat, details: det) }
                    } label: {
                        Text("Submit")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(accentCrimson)
                            .clipShape(Capsule())
                    }
                }
                .padding(20)
                .background(TelefamPalette.background(colorScheme))
                .navigationTitle("Report")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { selectedReportCategory = nil }
                    }
                }
            }
            .preferredColorScheme(themeMode.preferredColorScheme)
        }
        .fullScreenCover(isPresented: Binding(get: { viewerPosts != nil }, set: { if !$0 { viewerPosts = nil } })) {
            if let vPosts = viewerPosts {
                TelefamIosFeedView(
                    store: feedStore,
                    contactsStore: contactsStore,
                    items: vPosts,
                    initialIndex: viewerStartIndex,
                    isStandaloneViewer: true,
                    bottomChromePadding: 0,
                    onLiveClick: {},
                    onSearchClick: { viewerPosts = nil },
                    onBackClick: { viewerPosts = nil },
                    onCommentClick: { profileStore.showToast("Comments — Coming soon") },
                    onEditOwnerPost: { item in
                        viewerPosts = nil
                        onEditOwnerPost(item)
                    },
                    onTapHashtag: { tag, mode in
                        viewerPosts = nil
                        onTapHashtag(tag, mode)
                    },
                    onTapAudio: { song, mode in
                        viewerPosts = nil
                        onTapAudio(song, mode)
                    },
                    onOpenAuthorProfile: { uid in
                        viewerPosts = nil
                        if uid != profileStore.profile.userId {
                            onOpenUserProfile(uid)
                        }
                    }
                )
                .preferredColorScheme(themeMode.preferredColorScheme)
            }
        }
    }

    private func topBar(profile: TelefamIosUserProfile) -> some View {
        HStack(spacing: 4) {
            if !isEmbeddedOwnProfileTab {
                Button {
                    onBack()
                } label: {
                    Image(systemName: "arrow.left")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                        .frame(width: 44, height: 44)
                }
            } else {
                Spacer().frame(width: 44, height: 44)
            }

            Spacer()

            Text(profile.fullName.isEmpty ? "@\(profile.username.isEmpty ? profile.userId : profile.username)" : profile.fullName)
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                .lineLimit(1)

            Spacer()

            Button {
                presentInviteShare()
            } label: {
                InviteTBadgeSvgIcon(badgeColor: accentCrimson)
                    .frame(width: 44, height: 44)
            }

            Menu {
                Button("Copy link") {
                    UIPasteboard.general.string = profileStore.shareProfileURL().absoluteString
                    profileStore.showToast("Link copied")
                }
                Button("Invite friends") {
                    presentInviteShare()
                }
                if profile.isOwner {
                    Menu("Theme: \(themeMode.label)") {
                        ForEach(TelefamThemeMode.allCases) { m in
                            Button(m.label) { themeMode = m }
                        }
                    }
                    Button("Sign out", role: .destructive) {
                        sessionStore.signOut()
                    }
                } else {
                    if profile.isBlocked {
                        Button("Unblock") { Task { await profileStore.unblockUser() } }
                    } else {
                        Button("Block", role: .destructive) { Task { await profileStore.blockUser() } }
                    }
                    Button("Report", role: .destructive) { showReportCategorySheet = true }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                    .frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, 6)
        .frame(height: 52)
    }

    private func profileHeader(profile: TelefamIosUserProfile) -> some View {
        VStack(spacing: 0) {
            // Gradient-ringed Avatar matching Alex Mwangi reference
            ZStack {
                Circle()
                    .stroke(
                        LinearGradient(
                            colors: [
                                Color(hex: 0xFF7A00),
                                Color(hex: 0xE61E62),
                                Color(hex: 0x9C27B0)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 2.5
                    )
                    .frame(width: 98, height: 98)

                if !profile.avatarUrl.isEmpty {
                    TelefamIosRemoteImage(
                        urlString: feedStore.authorizedURLString(profile.avatarUrl),
                        bearerToken: sessionStore.sessionToken,
                        deviceFingerprint: sessionStore.deviceFingerprint,
                        fallbackInitials: initials(from: profile)
                    )
                    .frame(width: 90, height: 90)
                    .clipShape(Circle())
                } else {
                    Circle()
                        .fill(TelefamPalette.blush(colorScheme))
                        .frame(width: 90, height: 90)
                        .overlay(
                            Text(initials(from: profile))
                                .font(.system(size: 28, weight: .bold))
                                .foregroundColor(accentCrimson)
                        )
                }
            }
            .padding(.top, 6)

            Text("@\(profile.username.isEmpty ? profile.userId : profile.username)")
                .font(.system(size: 15.5, weight: .bold))
                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                .lineLimit(1)
                .padding(.top, 10)

            // Stats Row: Following | Followers | Likes
            HStack(spacing: 0) {
                statButton(
                    count: formatTelefamProfileMetricCount(profile.followingCount),
                    label: "Following"
                ) {
                    showConnectionsSheet = true
                    Task { await profileStore.openConnections(isFollowers: false) }
                }

                Rectangle()
                    .fill(TelefamPalette.border(colorScheme))
                    .frame(width: 1, height: 26)

                statButton(
                    count: formatTelefamProfileMetricCount(profile.followersCount),
                    label: "Followers"
                ) {
                    showConnectionsSheet = true
                    Task { await profileStore.openConnections(isFollowers: true) }
                }

                Rectangle()
                    .fill(TelefamPalette.border(colorScheme))
                    .frame(width: 1, height: 26)

                VStack(spacing: 2) {
                    Text(formatTelefamProfileMetricCount(profile.likesCount))
                        .font(.system(size: 19, weight: .bold))
                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                    Text("Likes")
                        .font(.system(size: 12.5))
                        .foregroundColor(TelefamPalette.textMuted(colorScheme))
                }
                .frame(maxWidth: .infinity)
            }
            .frame(maxWidth: 360)
            .padding(.horizontal, 24)
            .padding(.top, 14)

            // Action Buttons Row
            if profile.isBlocked {
                VStack(spacing: 8) {
                    Text("You blocked this account.")
                        .font(.system(size: 13.5))
                        .foregroundColor(TelefamPalette.textMuted(colorScheme))
                    Button {
                        Task { await profileStore.unblockUser() }
                    } label: {
                        Text("Unblock")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 40)
                            .background(accentCrimson)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
                .frame(maxWidth: 380)
                .padding(.horizontal, 20)
                .padding(.top, 16)
            } else if !profile.isOwner {
                HStack(spacing: 8) {
                    followActionButton(state: profile.relationship)

                    Button {
                        profileStore.showToast("Message — Coming soon")
                    } label: {
                        HStack(spacing: 6) {
                            PaperPlaneProfileSvgIcon(color: TelefamPalette.textPrimary(colorScheme))
                                .frame(width: 15, height: 15)
                            Text("Message")
                                .font(.system(size: 13.5, weight: .semibold))
                                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 40)
                        .background(TelefamPalette.surface(colorScheme))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(TelefamPalette.border(colorScheme), lineWidth: 1))
                    }

                    Button {
                        profileStore.showToast("Subscribe — Coming soon")
                    } label: {
                        HStack(spacing: 6) {
                            SubscribeCrownSvgIcon(color: .white)
                                .frame(width: 15, height: 15)
                            Text("Subscribe")
                                .font(.system(size: 13.5, weight: .semibold))
                                .foregroundColor(.white)
                                .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 40)
                        .background(accentCrimson)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
                .frame(maxWidth: 460)
                .padding(.horizontal, 18)
                .padding(.top, 16)
            } else {
                HStack(spacing: 10) {
                    Button { presentInviteShare() } label: {
                        Text("Invite friends")
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 40)
                            .background(accentCrimson)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    Button {
                        UIPasteboard.general.string = profileStore.shareProfileURL().absoluteString
                        profileStore.showToast("Link copied")
                    } label: {
                        Text("Copy link")
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                            .frame(maxWidth: .infinity)
                            .frame(height: 40)
                            .background(TelefamPalette.surface(colorScheme))
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(TelefamPalette.border(colorScheme), lineWidth: 1))
                    }
                }
                .frame(maxWidth: 380)
                .padding(.horizontal, 24)
                .padding(.top, 16)
            }

            if !profile.bio.isEmpty {
                Text(profile.bio)
                    .font(.system(size: 13.5))
                    .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .frame(maxWidth: 420)
                    .padding(.horizontal, 28)
                    .padding(.top, 14)
            }

            if !profile.location.isEmpty {
                HStack(spacing: 4) {
                    LocationPinSvgIcon(color: accentCrimson)
                        .frame(width: 14, height: 14)
                    Text(profile.location)
                        .font(.system(size: 13))
                        .foregroundColor(TelefamPalette.textMuted(colorScheme))
                        .lineLimit(1)
                }
                .padding(.top, 6)
            }
        }
    }

    private func statButton(count: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text(count)
                    .font(.system(size: 19, weight: .bold))
                    .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                Text(label)
                    .font(.system(size: 12.5))
                    .foregroundColor(TelefamPalette.textMuted(colorScheme))
            }
            .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.plain)
    }

    private func followActionButton(state: TelefamRelationshipState) -> some View {
        let filled = state == .follow || state == .followBack
        return Button {
            profileStore.toggleFollow()
        } label: {
            HStack(spacing: 6) {
                if filled {
                    PersonAddSvgIcon(color: .white)
                        .frame(width: 15, height: 15)
                } else {
                    Image(systemName: state.systemIcon)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                }
                Text(state.label)
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundColor(filled ? .white : TelefamPalette.textPrimary(colorScheme))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 40)
            .background(filled ? accentCrimson : TelefamPalette.surface(colorScheme))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(filled ? Color.clear : TelefamPalette.border(colorScheme), lineWidth: 1)
            )
        }
    }

    private func initials(from p: TelefamIosUserProfile) -> String {
        let words = p.fullName.split(separator: " ")
        if words.count >= 2, let a = words.first?.first, let b = words.last?.first {
            return "\(a)\(b)".uppercased()
        }
        let source = p.fullName.isEmpty ? p.username : p.fullName
        return String(source.prefix(2)).uppercased()
    }

    private func presentInviteShare() {
        let link = profileStore.shareProfileURL().absoluteString
        let text = "Join me on Telefam! \(link)"
        let av = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow?.rootViewController }
            .first?
            .present(av, animated: true)
    }
}

private struct ProfilePostGridCell: View {
    let post: TelefamIosFeedItem
    @ObservedObject var feedStore: TelefamIosFeedStore
    let accentCrimson: Color
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .bottomLeading) {
                Color(red: 0.08, green: 0.09, blue: 0.13)

                if !post.thumbnailUrl.isEmpty {
                    TelefamIosRemoteImage(
                        urlString: feedStore.authorizedURLString(post.thumbnailUrl),
                        bearerToken: feedStore.bearerToken,
                        deviceFingerprint: feedStore.deviceFingerprint,
                        fallbackInitials: ""
                    )
                } else if !post.photoUrls.isEmpty {
                    TelefamIosRemoteImage(
                        urlString: feedStore.authorizedURLString(post.photoUrls[0]),
                        bearerToken: feedStore.bearerToken,
                        deviceFingerprint: feedStore.deviceFingerprint,
                        fallbackInitials: ""
                    )
                } else {
                    Text(post.textContent.isEmpty ? post.caption : post.textContent)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(4)
                        .padding(10)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(accentCrimson.opacity(0.82))
                }

                LinearGradient(
                    colors: [.clear, Color.black.opacity(0.62)],
                    startPoint: .center,
                    endPoint: .bottom
                )

                HStack(spacing: 4) {
                    PlayOutlineSvgIcon()
                        .frame(width: 13, height: 13)
                    Text(formatTelefamProfileMetricCount(post.viewsCount))
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.white)
                }
                .padding(.horizontal, 7)
                .padding(.vertical, 6)
            }
            .aspectRatio(3.0 / 4.0, contentMode: .fit)
            .clipped()
        }
        .buttonStyle(.plain)
    }
}

private struct ProfileConnectionsSheetView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss

    let title: String
    @ObservedObject var profileStore: TelefamIosProfileStore
    @ObservedObject var contactsStore: TelefamContactsStore
    let onSelectUser: (String) -> Void

    private var accentCrimson: Color {
        colorScheme == .dark ? TelefamPalette.crimsonBright : TelefamPalette.crimson
    }

    var body: some View {
        NavigationStack {
            Group {
                if profileStore.isLoadingConnections && profileStore.connections.isEmpty {
                    VStack {
                        Spacer()
                        ProgressView().tint(accentCrimson)
                        Spacer()
                    }
                } else if profileStore.connections.isEmpty {
                    VStack(spacing: 8) {
                        Spacer()
                        Text("No people found")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                        Spacer()
                    }
                } else {
                    List(profileStore.connections) { c in
                        HStack(spacing: 12) {
                            HStack(spacing: 12) {
                                if let av = c.avatarUrl, !av.isEmpty {
                                    TelefamIosRemoteImage(
                                        urlString: contactsStore.resolvedURLString(av),
                                        bearerToken: contactsStore.currentToken,
                                        deviceFingerprint: contactsStore.currentDeviceFingerprint,
                                        fallbackInitials: c.initials
                                    )
                                    .frame(width: 44, height: 44)
                                    .clipShape(Circle())
                                } else {
                                    Circle()
                                        .fill(TelefamPalette.blush(colorScheme))
                                        .frame(width: 44, height: 44)
                                        .overlay(
                                            Text(c.initials)
                                                .font(.system(size: 14, weight: .bold))
                                                .foregroundColor(accentCrimson)
                                        )
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(c.fullName.isEmpty ? "@\(c.username)" : c.fullName)
                                        .font(.system(size: 14.5, weight: .bold))
                                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                        .lineLimit(1)
                                    Text("@\(c.username)")
                                        .font(.system(size: 12.5))
                                        .foregroundColor(TelefamPalette.textMuted(colorScheme))
                                        .lineLimit(1)
                                }
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { onSelectUser(c.userId) }

                            Spacer()

                            let rel = c.relationship
                            let filled = rel == .follow || rel == .followBack
                            Button {
                                profileStore.toggleConnectionFollow(targetUserId: c.userId)
                            } label: {
                                Text(rel.label)
                                    .font(.system(size: 12.5, weight: .semibold))
                                    .foregroundColor(filled ? .white : TelefamPalette.textPrimary(colorScheme))
                                    .padding(.horizontal, 14)
                                    .frame(height: 34)
                                    .background(filled ? accentCrimson : TelefamPalette.surface(colorScheme))
                                    .clipShape(Capsule())
                                    .overlay(Capsule().stroke(filled ? Color.clear : TelefamPalette.border(colorScheme), lineWidth: 1))
                            }
                            .buttonStyle(.plain)
                        }
                        .listRowBackground(TelefamPalette.background(colorScheme))
                        .onAppear {
                            if c.userId == profileStore.connections.last?.userId {
                                Task { await profileStore.loadMoreConnectionsIfNeeded() }
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .background(TelefamPalette.background(colorScheme))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Custom SVG Vector Icons Matching Reference Screenshot (Zero Emojis)

private struct InviteTBadgeSvgIcon: View {
    let badgeColor: Color
    var body: some View {
        ZStack {
            Canvas { ctx, size in
                let w = size.width
                let h = size.height
                let box = CGRect(x: w * 0.18, y: h * 0.22, width: w * 0.64, height: h * 0.50)
                ctx.fill(Path(roundedRect: box, cornerRadius: w * 0.14), with: .color(badgeColor))
                var tail = Path()
                tail.move(to: CGPoint(x: w * 0.42, y: h * 0.70))
                tail.addLine(to: CGPoint(x: w * 0.50, y: h * 0.84))
                tail.addLine(to: CGPoint(x: w * 0.58, y: h * 0.70))
                tail.closeSubpath()
                ctx.fill(tail, with: .color(badgeColor))
            }
            Text("T")
                .font(.system(size: 12.5, weight: .heavy))
                .foregroundColor(.white)
                .offset(y: -1)
        }
        .frame(width: 34, height: 34)
    }
}

private struct PersonAddSvgIcon: View {
    let color: Color
    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let stroke = StrokeStyle(lineWidth: 1.7, lineCap: .round, lineJoin: .round)
            ctx.stroke(Path(ellipseIn: CGRect(x: w * 0.20, y: h * 0.14, width: w * 0.34, height: h * 0.34)), with: .color(color), style: stroke)
            var arc = Path()
            arc.move(to: CGPoint(x: w * 0.10, y: h * 0.86))
            arc.addQuadCurve(to: CGPoint(x: w * 0.64, y: h * 0.86), control: CGPoint(x: w * 0.37, y: h * 0.56))
            ctx.stroke(arc, with: .color(color), style: stroke)
            var plus = Path()
            plus.move(to: CGPoint(x: w * 0.76, y: h * 0.30))
            plus.addLine(to: CGPoint(x: w * 0.76, y: h * 0.58))
            plus.move(to: CGPoint(x: w * 0.62, y: h * 0.44))
            plus.addLine(to: CGPoint(x: w * 0.90, y: h * 0.44))
            ctx.stroke(plus, with: .color(color), style: stroke)
        }
    }
}

private struct PaperPlaneProfileSvgIcon: View {
    let color: Color
    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let stroke = StrokeStyle(lineWidth: 1.7, lineCap: .round, lineJoin: .round)
            var p = Path()
            p.move(to: CGPoint(x: w * 0.90, y: h * 0.12))
            p.addLine(to: CGPoint(x: w * 0.10, y: h * 0.44))
            p.addLine(to: CGPoint(x: w * 0.44, y: h * 0.58))
            p.addLine(to: CGPoint(x: w * 0.60, y: h * 0.90))
            p.closeSubpath()
            ctx.stroke(p, with: .color(color), style: stroke)
            var mid = Path()
            mid.move(to: CGPoint(x: w * 0.44, y: h * 0.58))
            mid.addLine(to: CGPoint(x: w * 0.90, y: h * 0.12))
            ctx.stroke(mid, with: .color(color), style: stroke)
        }
    }
}

private struct SubscribeCrownSvgIcon: View {
    let color: Color
    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let stroke = StrokeStyle(lineWidth: 1.7, lineCap: .round, lineJoin: .round)
            var p = Path()
            p.move(to: CGPoint(x: w * 0.12, y: h * 0.30))
            p.addLine(to: CGPoint(x: w * 0.32, y: h * 0.52))
            p.addLine(to: CGPoint(x: w * 0.50, y: h * 0.22))
            p.addLine(to: CGPoint(x: w * 0.68, y: h * 0.52))
            p.addLine(to: CGPoint(x: w * 0.88, y: h * 0.30))
            p.addLine(to: CGPoint(x: w * 0.82, y: h * 0.80))
            p.addLine(to: CGPoint(x: w * 0.18, y: h * 0.80))
            p.closeSubpath()
            ctx.stroke(p, with: .color(color), style: stroke)
        }
    }
}

private struct LocationPinSvgIcon: View {
    let color: Color
    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let stroke = StrokeStyle(lineWidth: 1.7, lineCap: .round, lineJoin: .round)
            var p = Path()
            p.move(to: CGPoint(x: w * 0.50, y: h * 0.92))
            p.addCurve(to: CGPoint(x: w * 0.16, y: h * 0.40), control1: CGPoint(x: w * 0.24, y: h * 0.66), control2: CGPoint(x: w * 0.16, y: h * 0.52))
            p.addCurve(to: CGPoint(x: w * 0.50, y: h * 0.10), control1: CGPoint(x: w * 0.16, y: h * 0.20), control2: CGPoint(x: w * 0.30, y: h * 0.10))
            p.addCurve(to: CGPoint(x: w * 0.84, y: h * 0.40), control1: CGPoint(x: w * 0.70, y: h * 0.10), control2: CGPoint(x: w * 0.84, y: h * 0.20))
            p.addCurve(to: CGPoint(x: w * 0.50, y: h * 0.92), control1: CGPoint(x: w * 0.84, y: h * 0.52), control2: CGPoint(x: w * 0.76, y: h * 0.66))
            p.closeSubpath()
            ctx.stroke(p, with: .color(color), style: stroke)
            ctx.stroke(Path(ellipseIn: CGRect(x: w * 0.38, y: h * 0.28, width: w * 0.24, height: h * 0.24)), with: .color(color), style: stroke)
        }
    }
}

private struct PostsGrid2x2SvgIcon: View {
    let color: Color
    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let stroke = StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round)
            let boxW = w * 0.34
            let boxH = h * 0.34
            for (rx, ry) in [(0.12, 0.12), (0.54, 0.12), (0.12, 0.54), (0.54, 0.54)] {
                ctx.stroke(Path(roundedRect: CGRect(x: w * rx, y: h * ry, width: boxW, height: boxH), cornerRadius: w * 0.06), with: .color(color), style: stroke)
            }
        }
    }
}

private struct FilterSlidersSvgIcon: View {
    let color: Color
    var body: some View {
        Canvas { ctx, size in
            let w = size.width * 0.48
            let h = size.height * 0.48
            let ox = (size.width - w) / 2
            let oy = (size.height - h) / 2
            let stroke = StrokeStyle(lineWidth: 1.7, lineCap: .round)
            for (yFrac, knobX) in [(0.24, 0.35), (0.50, 0.68), (0.76, 0.42)] {
                var line = Path()
                line.move(to: CGPoint(x: ox + w * 0.10, y: oy + h * yFrac))
                line.addLine(to: CGPoint(x: ox + w * 0.90, y: oy + h * yFrac))
                ctx.stroke(line, with: .color(color), style: stroke)
                ctx.fill(Path(ellipseIn: CGRect(x: ox + w * knobX - 2.6, y: oy + h * yFrac - 2.6, width: 5.2, height: 5.2)), with: .color(color))
            }
        }
    }
}

private struct PlayOutlineSvgIcon: View {
    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            var p = Path()
            p.move(to: CGPoint(x: w * 0.22, y: h * 0.14))
            p.addLine(to: CGPoint(x: w * 0.86, y: h * 0.50))
            p.addLine(to: CGPoint(x: w * 0.22, y: h * 0.86))
            p.closeSubpath()
            ctx.stroke(p, with: .color(.white), style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
        }
    }
}
