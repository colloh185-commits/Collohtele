import SwiftUI
import AVFoundation

struct TelefamIosSearchView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: TelefamIosFeedStore
    @ObservedObject var contactsStore: TelefamContactsStore

    var onEditOwnerPost: (TelefamIosFeedItem) -> Void
    var onTapHashtag: (String, TelefamIosPostMode) -> Void
    var onTapAudio: (TelefamIosSongTrack, TelefamIosPostMode) -> Void
    var onCommentComingSoon: () -> Void
    var onOpenAuthorProfile: (String) -> Void = { _ in }

    @State private var viewerIndex: Int? = nil

    var body: some View {
        ZStack {
            if let startIdx = viewerIndex {
                TelefamIosFeedView(
                    store: store,
                    contactsStore: contactsStore,
                    items: store.searchResults,
                    initialIndex: startIdx,
                    isStandaloneViewer: true,
                    bottomChromePadding: 0,
                    onLiveClick: {},
                    onSearchClick: { viewerIndex = nil },
                    onBackClick: { viewerIndex = nil },
                    onCommentClick: onCommentComingSoon,
                    onEditOwnerPost: { item in
                        viewerIndex = nil
                        dismiss()
                        onEditOwnerPost(item)
                    },
                    onTapHashtag: { tag, mode in
                        viewerIndex = nil
                        dismiss()
                        onTapHashtag(tag, mode)
                    },
                    onTapAudio: { song, mode in
                        viewerIndex = nil
                        dismiss()
                        onTapAudio(song, mode)
                    },
                    onOpenAuthorProfile: { uid in
                        viewerIndex = nil
                        dismiss()
                        onOpenAuthorProfile(uid)
                    }
                )
            } else {
                searchGridScreen
            }
        }
        .task {
            await store.searchFeeds()
        }
    }

    private var searchGridScreen: some View {
        GeometryReader { geo in
            let cols = geo.size.width >= 960 ? 4 : (geo.size.width >= 600 ? 3 : 2)
            let gridItems = Array(repeating: GridItem(.flexible(), spacing: 10), count: cols)

            VStack(spacing: 0) {
                VStack(spacing: 10) {
                    HStack(spacing: 8) {
                        Button { dismiss() } label: {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 19, weight: .bold))
                                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                .frame(width: 40, height: 40)
                        }

                        HStack(spacing: 8) {
                            Image(systemName: "magnifyingglass")
                                .foregroundColor(TelefamPalette.textMuted(colorScheme))
                            TextField("Search videos, hashtags, audio...", text: $store.searchQuery)
                                .font(.system(size: 14.5))
                                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                .onChange(of: store.searchQuery) { newVal in
                                    Task { await store.searchFeeds(query: newVal) }
                                }
                            if !store.searchQuery.isEmpty {
                                Button {
                                    Task { await store.searchFeeds(query: "") }
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundColor(TelefamPalette.textMuted(colorScheme))
                                }
                            }
                        }
                        .padding(.horizontal, 12)
                        .frame(height: 44)
                        .background(TelefamPalette.searchFieldBackground(colorScheme))
                        .clipShape(Capsule())
                    }

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            shortcutChip(label: "All", selected: store.activeShortcut.isEmpty) {
                                Task { await store.searchFeeds(shortcut: "") }
                            }
                            ForEach(store.searchShortcuts) { sc in
                                shortcutChip(label: "\(sc.label) (\(formatTelefamCompactCount(sc.count)))", selected: store.activeShortcut == sc.key) {
                                    let next = store.activeShortcut == sc.key ? "" : sc.key
                                    Task { await store.searchFeeds(shortcut: next) }
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(TelefamPalette.surface(colorScheme))

                Divider().background(TelefamPalette.border(colorScheme))

                if store.isSearching && store.searchResults.isEmpty {
                    Spacer()
                    TelefamIosTricolorSpinner(size: 40)
                    Spacer()
                } else if store.searchResults.isEmpty {
                    Spacer()
                    Text("No videos found")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(TelefamPalette.textMuted(colorScheme))
                    Spacer()
                } else {
                    ScrollView {
                        LazyVGrid(columns: gridItems, spacing: 10) {
                            ForEach(Array(store.searchResults.enumerated()), id: \.element.postId) { idx, item in
                                TelefamIosSearchVideoCard(
                                    store: store,
                                    item: item,
                                    onTap: { viewerIndex = idx },
                                    onOpenAuthorProfile: {
                                        if !item.authorUserId.isEmpty {
                                            dismiss()
                                            onOpenAuthorProfile(item.authorUserId)
                                        }
                                    }
                                )
                            }
                        }
                        .padding(12)
                    }
                }
            }
            .background(TelefamPalette.background(colorScheme))
        }
    }

    private func shortcutChip(label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: 13, weight: selected ? .bold : .medium))
                .foregroundColor(selected ? .white : TelefamPalette.textPrimary(colorScheme))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(selected ? TelefamPalette.crimson : TelefamPalette.surface(colorScheme))
                .clipShape(Capsule())
                .overlay(Capsule().stroke(selected ? Color.clear : TelefamPalette.border(colorScheme), lineWidth: 1))
        }
    }
}

private struct TelefamIosSearchVideoCard: View {
    @ObservedObject var store: TelefamIosFeedStore
    let item: TelefamIosFeedItem
    let onTap: () -> Void
    var onOpenAuthorProfile: () -> Void = {}
    @State private var mutedPlayer: AVPlayer? = nil

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .bottomLeading) {
                Color(red: 0.06, green: 0.07, blue: 0.10)

                if !item.thumbnailUrl.isEmpty {
                    TelefamIosRemoteImage(
                        urlString: store.authorizedURLString(item.thumbnailUrl),
                        bearerToken: store.bearerToken,
                        deviceFingerprint: store.deviceFingerprint,
                        fallbackInitials: ""
                    )
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
                    .blur(radius: 18)
                    .overlay(Color.black.opacity(0.40))

                    TelefamIosRemoteImage(
                        urlString: store.authorizedURLString(item.thumbnailUrl),
                        bearerToken: store.bearerToken,
                        deviceFingerprint: store.deviceFingerprint,
                        fallbackInitials: String(item.authorFullName.prefix(1)).uppercased(),
                        contentMode: .fit
                    )
                }
                if let p = mutedPlayer {
                    TelefamIosVideoSurfaceView(player: p)
                } else if item.thumbnailUrl.isEmpty && item.mode == "photos", let firstPhoto = item.photoUrls.first {
                    TelefamIosRemoteImage(
                        urlString: store.authorizedURLString(firstPhoto),
                        bearerToken: store.bearerToken,
                        deviceFingerprint: store.deviceFingerprint,
                        fallbackInitials: "",
                        contentMode: .fit
                    )
                } else if item.thumbnailUrl.isEmpty {
                    Text(item.textContent)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(12)
                }

                LinearGradient(colors: [.clear, Color.black.opacity(0.75)], startPoint: .center, endPoint: .bottom)

                VStack(alignment: .leading, spacing: 4) {
                    if !item.caption.isEmpty {
                        Text(item.caption)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.white)
                            .lineLimit(2)
                    }
                    HStack {
                        Text("@\(item.authorUsername)")
                            .font(.system(size: 11.5, weight: .bold))
                            .foregroundColor(.white)
                            .lineLimit(1)
                            .padding(.vertical, 2)
                            .contentShape(Rectangle())
                            .onTapGesture { onOpenAuthorProfile() }
                        Spacer()
                        HStack(spacing: 3) {
                            Image(systemName: "heart")
                                .font(.system(size: 10.5))
                            Text(formatTelefamCompactCount(item.likesCount))
                                .font(.system(size: 11))
                        }
                        .foregroundColor(Color.white.opacity(0.9))
                    }
                }
                .padding(10)
            }
            .aspectRatio(9.0 / 16.0, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .onAppear {
            guard item.mode == "video", let url = store.selectRenditionURL(for: item) else { return }
            let asset = store.makeAuthorizedAsset(for: url)
            let pItem = AVPlayerItem(asset: asset)
            pItem.preferredForwardBufferDuration = 4
            let p = AVPlayer(playerItem: pItem)
            p.isMuted = true
            p.play()
            mutedPlayer = p
        }
        .onDisappear {
            mutedPlayer?.pause()
            mutedPlayer = nil
        }
    }
}
