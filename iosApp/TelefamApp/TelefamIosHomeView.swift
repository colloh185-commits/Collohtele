import SwiftUI

enum TelefamIosHomeTab: String {
    case chats
    case contacts
    case post
    case feeds
    case profile
}

private enum TelefamIosPostLaunchTarget: Identifiable {
    case createNew
    case prefillHashtag(tag: String, mode: TelefamIosPostMode)
    case prefillSong(song: TelefamIosSongTrack, mode: TelefamIosPostMode)
    case edit(item: TelefamIosFeedItem)

    var id: String {
        switch self {
        case .createNew: return "new"
        case .prefillHashtag(let tag, _): return "tag_\(tag)"
        case .prefillSong(let song, _): return "song_\(song.trackId)"
        case .edit(let item): return "edit_\(item.postId)"
        }
    }
}

struct TelefamIosHomeView: View {
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var sessionStore: TelefamIosSessionStore
    @StateObject private var contactsStore: TelefamContactsStore
    @StateObject private var feedStore: TelefamIosFeedStore
    @Binding var themeMode: TelefamThemeMode

    private let serverURL: URL
    @State private var activeTab: TelefamIosHomeTab = .chats
    @State private var isBottomBarVisible: Bool = true
    @State private var postLaunchTarget: TelefamIosPostLaunchTarget? = nil
    @State private var showFeedSearch: Bool = false
    @State private var comingSoonBanner: String? = nil
    @State private var chatFilterQuery: String = ""

    init(sessionStore: TelefamIosSessionStore, serverURL: URL, themeMode: Binding<TelefamThemeMode>) {
        self.sessionStore = sessionStore
        self.serverURL = serverURL
        _contactsStore = StateObject(
            wrappedValue: TelefamContactsStore(
                baseUrl: serverURL.absoluteString,
                sessionTokenProvider: { sessionStore.sessionToken },
                deviceFingerprintProvider: { sessionStore.deviceFingerprint }
            )
        )
        _feedStore = StateObject(
            wrappedValue: TelefamIosFeedStore(
                baseURL: serverURL,
                tokenProvider: { sessionStore.sessionToken },
                deviceFingerprint: sessionStore.deviceFingerprint
            )
        )
        _themeMode = themeMode
    }

    var body: some View {
        GeometryReader { geo in
            let isWide = geo.size.width >= 680

            ZStack(alignment: .bottom) {
                VStack(spacing: 0) {
                    if activeTab == .contacts {
                        TelefamContactsTopHeaderView(store: contactsStore, themeMode: $themeMode)
                    } else if activeTab != .feeds {
                        chatsTopHeader
                    }

                    Group {
                        if isWide {
                            HStack(spacing: 0) {
                                sideRail
                                Divider().background(TelefamPalette.border(colorScheme))
                                tabBody(isCompactWidth: false, safeBottomInset: geo.safeAreaInsets.bottom)
                            }
                        } else {
                            ZStack(alignment: .bottom) {
                                tabBody(isCompactWidth: true, safeBottomInset: geo.safeAreaInsets.bottom)
                                if activeTab != .contacts || isBottomBarVisible {
                                    bottomBar
                                        .transition(.move(edge: .bottom).combined(with: .opacity))
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(activeTab == .feeds ? Color(red: 0.027, green: 0.031, blue: 0.043) : TelefamPalette.background(colorScheme))
                    .clipShape(
                        RoundedCornerShapeShim(radius: (activeTab == .contacts || activeTab == .feeds) ? 0 : 24)
                    )
                }
                .background((activeTab == .feeds ? Color(red: 0.027, green: 0.031, blue: 0.043) : TelefamPalette.crimson).ignoresSafeArea(edges: .top))

                if let toast = feedStore.toastMessage ?? contactsStore.toastMessage ?? comingSoonBanner {
                    Text(toast)
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(Color.black.opacity(0.84))
                        .clipShape(Capsule())
                        .padding(.bottom, 84)
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: isBottomBarVisible)
            .onChange(of: contactsStore.toastMessage) { msg in
                guard msg != nil else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) {
                    contactsStore.toastMessage = nil
                }
            }
        }
        .fullScreenCover(isPresented: $showFeedSearch) {
            TelefamIosSearchView(
                store: feedStore,
                contactsStore: contactsStore,
                onEditOwnerPost: { item in
                    postLaunchTarget = .edit(item: item)
                },
                onTapHashtag: { tag, mode in
                    postLaunchTarget = .prefillHashtag(tag: tag, mode: mode)
                },
                onTapAudio: { song, mode in
                    postLaunchTarget = .prefillSong(song: song, mode: mode)
                },
                onCommentComingSoon: {
                    showComingSoon("Comments — Coming soon")
                }
            )
            .preferredColorScheme(themeMode.preferredColorScheme)
        }
        .fullScreenCover(item: $postLaunchTarget) { target in
            buildPostView(for: target)
                .preferredColorScheme(themeMode.preferredColorScheme)
                .onDisappear {
                    Task { await feedStore.refresh() }
                }
        }
        .preferredColorScheme(themeMode.preferredColorScheme)
    }

    @ViewBuilder
    private func buildPostView(for target: TelefamIosPostLaunchTarget) -> some View {
        let store: TelefamIosPostStore = {
            switch target {
            case .createNew:
                return TelefamIosPostStore(
                    baseURL: serverURL,
                    tokenProvider: { sessionStore.sessionToken },
                    deviceFingerprint: sessionStore.deviceFingerprint
                )
            case .prefillHashtag(let tag, let mode):
                return TelefamIosPostStore(
                    baseURL: serverURL,
                    tokenProvider: { sessionStore.sessionToken },
                    deviceFingerprint: sessionStore.deviceFingerprint,
                    initialMode: mode,
                    initialHashtags: [tag]
                )
            case .prefillSong(let song, let mode):
                return TelefamIosPostStore(
                    baseURL: serverURL,
                    tokenProvider: { sessionStore.sessionToken },
                    deviceFingerprint: sessionStore.deviceFingerprint,
                    initialMode: mode,
                    initialSong: song
                )
            case .edit(let item):
                return TelefamIosPostStore(
                    baseURL: serverURL,
                    tokenProvider: { sessionStore.sessionToken },
                    deviceFingerprint: sessionStore.deviceFingerprint,
                    editingPostId: item.postId,
                    initialMode: TelefamIosPostMode(rawValue: item.mode) ?? .video,
                    initialText: item.textContent,
                    initialCaption: item.caption,
                    initialThemeId: item.themeId,
                    initialFont: TelefamIosPostFont(rawValue: item.fontId) ?? .classic,
                    initialSong: item.song,
                    initialHashtags: item.hashtags,
                    initialMuteOriginalAudio: item.muteOriginalAudio
                )
            }
        }()
        TelefamIosPostView(store: store, contactsStore: contactsStore)
    }

    private var chatsTopHeader: some View {
        HStack(spacing: 8) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15))
                    .foregroundColor(TelefamPalette.textMuted(colorScheme))
                TextField("Search people or chats", text: $chatFilterQuery)
                    .font(.system(size: 14))
                    .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                    .onSubmit {
                        contactsStore.searchQuery = chatFilterQuery
                        activeTab = .contacts
                    }
                if !chatFilterQuery.isEmpty {
                    Button { chatFilterQuery = "" } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(TelefamPalette.textMuted(colorScheme))
                    }
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 42)
            .background(TelefamPalette.searchFieldBackground(colorScheme))
            .clipShape(Capsule())

            Button {
                themeMode = themeMode.next
            } label: {
                Image(systemName: themeMode == .dark ? "moon.fill" : (themeMode == .light ? "sun.max.fill" : "circle.lefthalf.filled"))
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 36, height: 36)
                    .background(Color.white.opacity(0.18))
                    .clipShape(Circle())
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(TelefamPalette.crimson)
    }

    @ViewBuilder
    private func tabBody(isCompactWidth: Bool, safeBottomInset: CGFloat) -> some View {
        switch activeTab {
        case .chats:
            VStack(spacing: 14) {
                Spacer()
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 28))
                    .foregroundColor(TelefamPalette.crimson)
                    .frame(width: 62, height: 62)
                    .background(TelefamPalette.blush(colorScheme))
                    .clipShape(Circle())
                Text("Start a conversation")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                Text("Select someone from your contacts to connect.")
                    .font(.system(size: 13))
                    .foregroundColor(TelefamPalette.textMuted(colorScheme))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                Button {
                    selectTab(.contacts)
                } label: {
                    Label("Browse contacts", systemImage: "person.2")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 22)
                        .frame(height: 44)
                        .background(TelefamPalette.crimson)
                        .clipShape(Capsule())
                }
                Spacer()
            }
            .frame(maxWidth: .infinity)
        case .contacts:
            TelefamIosContactsView(
                store: contactsStore,
                isCompactWidth: isCompactWidth,
                onBottomBarVisibilityChange: { visible in
                    isBottomBarVisible = visible
                }
            )
            .onAppear {
                Task { await contactsStore.refresh() }
            }
        case .feeds:
            TelefamIosFeedView(
                store: feedStore,
                contactsStore: contactsStore,
                items: feedStore.activeItems,
                initialIndex: 0,
                isStandaloneViewer: false,
                bottomChromePadding: isCompactWidth ? (66 + safeBottomInset) : 0,
                onLiveClick: { showComingSoon("LIVE — Coming soon") },
                onSearchClick: { showFeedSearch = true },
                onBackClick: {},
                onCommentClick: { showComingSoon("Comments — Coming soon") },
                onEditOwnerPost: { item in postLaunchTarget = .edit(item: item) },
                onTapHashtag: { tag, mode in postLaunchTarget = .prefillHashtag(tag: tag, mode: mode) },
                onTapAudio: { song, mode in postLaunchTarget = .prefillSong(song: song, mode: mode) }
            )
            .onAppear {
                Task { await feedStore.refresh() }
            }
        case .post:
            EmptyView()
        case .profile:
            profileTabView(isCompactWidth: isCompactWidth)
        }
    }

    private func profileTabView(isCompactWidth: Bool) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                Circle()
                    .fill(TelefamPalette.blush(colorScheme))
                    .frame(width: 82, height: 82)
                    .overlay(
                        Text(String((sessionStore.fullName.isEmpty ? sessionStore.username : sessionStore.fullName).prefix(2)).uppercased())
                            .font(.system(size: 26, weight: .bold))
                            .foregroundColor(TelefamPalette.crimson)
                    )

                Text(sessionStore.fullName.isEmpty ? "Telefam Member" : sessionStore.fullName)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(TelefamPalette.textPrimary(colorScheme))

                if !sessionStore.username.isEmpty {
                    Text("@\(sessionStore.username)")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(TelefamPalette.crimson)
                }

                VStack(spacing: 0) {
                    profileInfoRow(icon: "envelope", label: "Email", value: sessionStore.email)
                    if !sessionStore.phoneDisplay.isEmpty {
                        Divider()
                        profileInfoRow(icon: "phone", label: "Phone", value: sessionStore.phoneDisplay)
                    }
                    if !sessionStore.location.isEmpty {
                        Divider()
                        profileInfoRow(icon: "mappin.and.ellipse", label: "Location", value: sessionStore.location)
                    }
                    if !sessionStore.bio.isEmpty {
                        Divider()
                        profileInfoRow(icon: "text.bubble", label: "Bio", value: sessionStore.bio)
                    }
                    Divider()
                    HStack {
                        Image(systemName: "paintbrush")
                            .foregroundColor(TelefamPalette.crimson)
                            .frame(width: 24)
                        Text("Theme")
                            .font(.system(size: 14, weight: .medium))
                        Spacer()
                        Picker("Theme", selection: $themeMode) {
                            ForEach(TelefamThemeMode.allCases) { m in
                                Text(m.label).tag(m)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(TelefamPalette.crimson)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                }
                .background(TelefamPalette.surface(colorScheme))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(TelefamPalette.border(colorScheme)))

                Button {
                    sessionStore.signOut()
                } label: {
                    Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(TelefamPalette.crimson)
                        .clipShape(Capsule())
                }
            }
            .padding(20)
            .padding(.bottom, isCompactWidth ? 90 : 24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
    }

    private func profileInfoRow(icon: String, label: String, value: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .foregroundColor(TelefamPalette.crimson)
                .frame(width: 24)
            Text(label)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
            Spacer()
            Text(value)
                .font(.system(size: 13.5))
                .foregroundColor(TelefamPalette.textMuted(colorScheme))
                .multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }

    private var bottomBar: some View {
        VStack(spacing: 0) {
            Divider().background(TelefamPalette.border(colorScheme).opacity(0.6))
            HStack {
                navItem(.chats, title: "Chats", icon: activeTab == .chats ? "bubble.left.fill" : "bubble.left")
                Spacer()
                navItem(.contacts, title: "Contacts", icon: activeTab == .contacts ? "person.2.fill" : "person.2")
                Spacer()
                Button { selectTab(.post) } label: {
                    VStack(spacing: 2) {
                        Image(systemName: "plus")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 40, height: 40)
                            .background(TelefamPalette.crimson)
                            .clipShape(Circle())
                        Text("Post")
                            .font(.system(size: 10.5, weight: .medium))
                            .foregroundColor(activeTab == .post ? TelefamPalette.crimson : TelefamPalette.textMuted(colorScheme))
                    }
                }
                Spacer()
                navItem(.feeds, title: "Feeds", icon: activeTab == .feeds ? "square.stack.fill" : "square.stack")
                Spacer()
                navItem(.profile, title: "Profile", icon: activeTab == .profile ? "person.circle.fill" : "person.circle")
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 10)
            .background(TelefamPalette.surface(colorScheme))
        }
    }

    private var sideRail: some View {
        VStack(spacing: 22) {
            navItem(.chats, title: "Chats", icon: activeTab == .chats ? "bubble.left.fill" : "bubble.left")
            navItem(.contacts, title: "Contacts", icon: activeTab == .contacts ? "person.2.fill" : "person.2")
            Button { selectTab(.post) } label: {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .background(TelefamPalette.crimson)
                    .clipShape(Circle())
            }
            navItem(.feeds, title: "Feeds", icon: activeTab == .feeds ? "square.stack.fill" : "square.stack")
            navItem(.profile, title: "Profile", icon: activeTab == .profile ? "person.circle.fill" : "person.circle")
            Spacer()
        }
        .padding(.vertical, 20)
        .frame(width: 86)
        .background(TelefamPalette.surface(colorScheme))
    }

    private func navItem(_ tab: TelefamIosHomeTab, title: String, icon: String) -> some View {
        let selected = activeTab == tab
        let tint = selected ? TelefamPalette.crimson : TelefamPalette.textMuted(colorScheme)
        return Button {
            selectTab(tab)
        } label: {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 19))
                Text(title)
                    .font(.system(size: 10.5, weight: selected ? .semibold : .medium))
            }
            .foregroundColor(tint)
        }
    }

    private func selectTab(_ tab: TelefamIosHomeTab) {
        if tab == .post {
            postLaunchTarget = .createNew
            return
        }
        activeTab = tab
        isBottomBarVisible = true
        if tab == .feeds {
            Task { await feedStore.refresh() }
        }
    }

    private func showComingSoon(_ msg: String) {
        comingSoonBanner = msg
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            if comingSoonBanner == msg { comingSoonBanner = nil }
        }
    }
}

private struct RoundedCornerShapeShim: Shape {
    let radius: CGFloat
    func path(in rect: CGRect) -> Path {
        let path = UIBezierPath(
            roundedRect: rect,
            byRoundingCorners: [.topLeft, .topRight],
            cornerRadii: CGSize(width: radius, height: radius)
        )
        return Path(path.cgPath)
    }
}
