import SwiftUI

private let iosStarRed = Color(red: 0.839, green: 0.098, blue: 0.129)
private let iosStarRedSoft = Color(red: 0.992, green: 0.949, blue: 0.953)
private let iosStarGoldBright = Color(red: 1.0, green: 0.847, blue: 0.290)
private let iosStarGoldMid = Color(red: 0.976, green: 0.694, blue: 0.082)
private let iosStarGoldDeep = Color(red: 0.80, green: 0.47, blue: 0.0)
private let iosStarOnlineGreen = Color(red: 0.114, green: 0.725, blue: 0.329)

private func iosStarIntensity(_ amount: Int64) -> CGFloat {
    let clamped = Double(min(50_000, max(5, amount)))
    let norm = (log10(clamped) - log10(5.0)) / (log10(50_000.0) - log10(5.0))
    return CGFloat(min(1.0, max(0.0, norm)))
}

// MARK: - 3D Animated Gold Star + Orbiting Sparkles

struct TelefamIos3DAnimatedStar: View {
    let size: CGFloat
    var starAmount: Int64 = 5
    var animate3D: Bool = true
    var showSparkles: Bool = true

    var body: some View {
        let intensity = iosStarIntensity(starAmount)
        if animate3D {
            TimelineView(.animation(minimumInterval: 1.0 / 60.0)) { context in
                let t = context.date.timeIntervalSinceReferenceDate
                let speed = 2.6 + Double(intensity) * 1.4
                let yaw = sin(t * speed) * (18.0 + Double(intensity) * 14.0)
                let pulse = 0.96 + (0.04 + Double(intensity) * 0.08) * (0.5 + 0.5 * sin(t * 3.8))
                let sparkleCount = 5 + Int(intensity * 7.0)
                starBody(intensity: intensity, t: t, yaw: yaw, pulse: pulse, sparkleCount: sparkleCount)
            }
            .frame(width: size, height: size)
        } else {
            starBody(intensity: intensity, t: 0, yaw: 0, pulse: 1.0, sparkleCount: 0)
                .frame(width: size, height: size)
        }
    }

    @ViewBuilder
    private func starBody(intensity: CGFloat, t: TimeInterval, yaw: Double, pulse: Double, sparkleCount: Int) -> some View {
        ZStack {
            if showSparkles && sparkleCount > 0 {
                Canvas { ctx, sz in
                    let cx = sz.width / 2
                    let cy = sz.height / 2
                    let baseRadius = min(sz.width, sz.height) * 0.44
                    for i in 0..<sparkleCount {
                        let a = t * (2.2 + Double(intensity) * 1.1) + Double(i) * (2.0 * .pi / Double(sparkleCount))
                        let orbit = baseRadius * CGFloat(0.86 + 0.14 * sin(a * 2.0))
                        let sx = cx + CGFloat(cos(a)) * orbit
                        let sy = cy + CGFloat(sin(a)) * orbit
                        let spSize = min(sz.width, sz.height) * (0.045 + intensity * 0.03) * CGFloat(0.65 + 0.35 * abs(sin(a * 1.5)))
                        var p = Path()
                        p.move(to: CGPoint(x: sx, y: sy - spSize))
                        p.addLine(to: CGPoint(x: sx + spSize * 0.28, y: sy - spSize * 0.28))
                        p.addLine(to: CGPoint(x: sx + spSize, y: sy))
                        p.addLine(to: CGPoint(x: sx + spSize * 0.28, y: sy + spSize * 0.28))
                        p.addLine(to: CGPoint(x: sx, y: sy + spSize))
                        p.addLine(to: CGPoint(x: sx - spSize * 0.28, y: sy + spSize * 0.28))
                        p.addLine(to: CGPoint(x: sx - spSize, y: sy))
                        p.addLine(to: CGPoint(x: sx - spSize * 0.28, y: sy - spSize * 0.28))
                        p.closeSubpath()
                        let col: Color = (i % 3 == 0) ? .white : (i % 2 == 0 ? iosStarGoldBright : iosStarRed.opacity(0.75))
                        ctx.fill(p, with: .color(col))
                    }
                }
            }

            Canvas { ctx, sz in
                let cx = sz.width / 2
                let cy = sz.height / 2
                let outerR = min(sz.width, sz.height) * 0.46
                let innerR = outerR * 0.45
                var pts: [CGPoint] = []
                for i in 0..<10 {
                    let rad = (-.pi / 2.0) + Double(i) * (.pi / 5.0)
                    let r = (i % 2 == 0) ? outerR : innerR
                    pts.append(CGPoint(x: cx + CGFloat(cos(rad)) * r, y: cy + CGFloat(sin(rad)) * r))
                }
                var body = Path()
                for (idx, p) in pts.enumerated() {
                    if idx == 0 { body.move(to: p) } else { body.addLine(to: p) }
                }
                body.closeSubpath()
                ctx.fill(
                    body,
                    with: .linearGradient(
                        Gradient(colors: [iosStarGoldBright, iosStarGoldMid, iosStarGoldDeep]),
                        startPoint: .zero,
                        endPoint: CGPoint(x: sz.width, y: sz.height)
                    )
                )
                let center = CGPoint(x: cx, y: cy)
                for i in 0..<10 {
                    var facet = Path()
                    facet.move(to: center)
                    facet.addLine(to: pts[i])
                    facet.addLine(to: pts[(i + 1) % 10])
                    facet.closeSubpath()
                    let fColor: Color = (i < 3 || i == 9)
                        ? .white.opacity(0.24)
                        : ((i >= 4 && i <= 7) ? iosStarGoldDeep.opacity(0.28) : iosStarGoldMid.opacity(0.14))
                    ctx.fill(facet, with: .color(fColor))
                }
                ctx.stroke(
                    body,
                    with: .color(Color(red: 0.70, green: 0.38, blue: 0.0).opacity(0.42)),
                    lineWidth: max(1.2, min(sz.width, sz.height) * 0.03)
                )
            }
            .frame(width: size * 0.72, height: size * 0.72)
            .scaleEffect(pulse)
            .rotation3DEffect(.degrees(yaw), axis: (x: 0, y: 1, z: 0))
        }
    }
}

private struct IosFlatStarBadge: View {
    let size: CGFloat
    var body: some View {
        ZStack {
            Circle().fill(Color(red: 1.0, green: 0.96, blue: 0.84))
            Canvas { ctx, sz in
                let cx = sz.width / 2
                let cy = sz.height / 2
                let outerR = min(sz.width, sz.height) * 0.48
                let innerR = outerR * 0.45
                var star = Path()
                for i in 0..<10 {
                    let rad = (-.pi / 2.0) + Double(i) * (.pi / 5.0)
                    let r = (i % 2 == 0) ? outerR : innerR
                    let pt = CGPoint(x: cx + CGFloat(cos(rad)) * r, y: cy + CGFloat(sin(rad)) * r)
                    if i == 0 { star.move(to: pt) } else { star.addLine(to: pt) }
                }
                star.closeSubpath()
                ctx.fill(
                    star,
                    with: .linearGradient(
                        Gradient(colors: [iosStarGoldBright, iosStarGoldMid]),
                        startPoint: .zero,
                        endPoint: CGPoint(x: sz.width, y: sz.height)
                    )
                )
            }
            .frame(width: size * 0.62, height: size * 0.62)
        }
        .frame(width: size, height: size)
    }
}

private struct IosPaystackVectorIcon: View {
    let size: CGFloat
    var body: some View {
        Canvas { ctx, sz in
            let cyan = Color(red: 0.0, green: 0.765, blue: 0.969)
            let w = sz.width
            let h = sz.height
            let barH = h * 0.13
            let r = barH * 0.45
            ctx.fill(Path(roundedRect: CGRect(x: w * 0.12, y: h * 0.15, width: w * 0.76, height: barH), cornerRadius: r), with: .color(cyan))
            ctx.fill(Path(roundedRect: CGRect(x: w * 0.12, y: h * 0.35, width: w * 0.76, height: barH), cornerRadius: r), with: .color(cyan))
            ctx.fill(Path(roundedRect: CGRect(x: w * 0.12, y: h * 0.55, width: w * 0.56, height: barH), cornerRadius: r), with: .color(cyan))
            ctx.fill(Path(roundedRect: CGRect(x: w * 0.12, y: h * 0.75, width: w * 0.34, height: barH), cornerRadius: r), with: .color(cyan))
        }
        .frame(width: size, height: size)
    }
}

private struct IosPayPalVectorIcon: View {
    let size: CGFloat
    var body: some View {
        Canvas { ctx, sz in
            let w = sz.width
            let h = sz.height
            var back = Path()
            back.move(to: CGPoint(x: w * 0.24, y: h * 0.12))
            back.addLine(to: CGPoint(x: w * 0.58, y: h * 0.12))
            back.addCurve(
                to: CGPoint(x: w * 0.58, y: h * 0.48),
                control1: CGPoint(x: w * 0.78, y: h * 0.12),
                control2: CGPoint(x: w * 0.80, y: h * 0.42)
            )
            back.addLine(to: CGPoint(x: w * 0.38, y: h * 0.48))
            back.addLine(to: CGPoint(x: w * 0.32, y: h * 0.78))
            back.addLine(to: CGPoint(x: w * 0.16, y: h * 0.78))
            back.closeSubpath()
            ctx.fill(back, with: .color(Color(red: 0.0, green: 0.188, blue: 0.529)))

            var front = Path()
            front.move(to: CGPoint(x: w * 0.36, y: h * 0.26))
            front.addLine(to: CGPoint(x: w * 0.68, y: h * 0.26))
            front.addCurve(
                to: CGPoint(x: w * 0.66, y: h * 0.60),
                control1: CGPoint(x: w * 0.88, y: h * 0.26),
                control2: CGPoint(x: w * 0.88, y: h * 0.56)
            )
            front.addLine(to: CGPoint(x: w * 0.46, y: h * 0.60))
            front.addLine(to: CGPoint(x: w * 0.40, y: h * 0.88))
            front.addLine(to: CGPoint(x: w * 0.25, y: h * 0.88))
            front.closeSubpath()
            ctx.fill(front, with: .color(Color(red: 0.0, green: 0.475, blue: 0.757)))
        }
        .frame(width: size, height: size)
    }
}

private struct IosSecurityShieldVector: View {
    let size: CGFloat
    var body: some View {
        Canvas { ctx, sz in
            let w = sz.width
            let h = sz.height
            var shield = Path()
            shield.move(to: CGPoint(x: w * 0.5, y: h * 0.08))
            shield.addLine(to: CGPoint(x: w * 0.86, y: h * 0.22))
            shield.addLine(to: CGPoint(x: w * 0.84, y: h * 0.54))
            shield.addCurve(
                to: CGPoint(x: w * 0.5, y: h * 0.94),
                control1: CGPoint(x: w * 0.82, y: h * 0.74),
                control2: CGPoint(x: w * 0.66, y: h * 0.86)
            )
            shield.addCurve(
                to: CGPoint(x: w * 0.16, y: h * 0.54),
                control1: CGPoint(x: w * 0.34, y: h * 0.86),
                control2: CGPoint(x: w * 0.18, y: h * 0.74)
            )
            shield.addLine(to: CGPoint(x: w * 0.14, y: h * 0.22))
            shield.closeSubpath()
            ctx.fill(shield, with: .color(Color(red: 0.718, green: 0.110, blue: 0.137)))

            var check = Path()
            check.move(to: CGPoint(x: w * 0.34, y: h * 0.51))
            check.addLine(to: CGPoint(x: w * 0.46, y: h * 0.63))
            check.addLine(to: CGPoint(x: w * 0.68, y: h * 0.38))
            ctx.stroke(check, with: .color(.white), style: StrokeStyle(lineWidth: w * 0.10, lineCap: .round, lineJoin: .round))
        }
        .frame(width: size, height: size)
    }
}

// MARK: - Chat Star Gift Bubble & Realtime Celebration Overlay

struct TelefamIosStarGiftBubble: View {
    @Environment(\.colorScheme) private var colorScheme
    let msg: TelefamIosChatMessage
    let isOwn: Bool
    let peerAvatarUrl: String
    let peerName: String
    let showReadReceipts: Bool
    let chatStore: TelefamIosChatStore

    var body: some View {
        let amt = max(5, msg.starGiftAmount)
        let intensity = iosStarIntensity(amt)
        let starSize = 64 + intensity * 46
        let topCol = colorScheme == .dark ? Color(red: 0.16, green: 0.10, blue: 0.11) : Color(red: 1.0, green: 0.973, blue: 0.965)
        let botCol = colorScheme == .dark ? Color(red: 0.10, green: 0.08, blue: 0.09) : Color(red: 1.0, green: 0.949, blue: 0.918)
        let borderCol = colorScheme == .dark ? iosStarGoldMid.opacity(0.42) : iosStarRed.opacity(0.22)
        let metaInk = colorScheme == .dark ? Color.white.opacity(0.65) : Color.black.opacity(0.55)

        VStack(spacing: 4) {
            TelefamIos3DAnimatedStar(size: starSize, starAmount: amt, animate3D: true, showSparkles: true)

            Text("Telefam")
                .font(.system(size: 15 + intensity * 5, weight: .heavy))
                .foregroundColor(iosStarRed)
                .tracking(0.3)

            Text("\(IosStarsCatalog.formatComma(amt)) Stars")
                .font(.system(size: 16 + intensity * 4, weight: .bold))
                .foregroundColor(colorScheme == .dark ? .white : Color(red: 0.10, green: 0.11, blue: 0.13))

            if msg.starGiftVerified {
                Text("Verified Gift")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(colorScheme == .dark ? iosStarGoldBright : iosStarGoldDeep)
            }

            HStack(spacing: 4) {
                Spacer()
                Text(formatCompactChatTime(msg.createdAtMs))
                    .font(.system(size: 10.5))
                    .foregroundColor(metaInk)
                if isOwn {
                    DeliveryTickBadge(
                        status: msg.deliveryStatus,
                        peerAvatarUrl: peerAvatarUrl,
                        peerName: peerName,
                        chatStore: chatStore,
                        showReadReceipts: showReadReceipts,
                        tintColor: metaInk
                    )
                }
            }
            .padding(.top, 2)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(minWidth: 190, maxWidth: 270)
        .background(LinearGradient(colors: [topCol, botCol], startPoint: .top, endPoint: .bottom))
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(borderCol, lineWidth: 1.2))
    }
}

struct TelefamIosStarCelebrationOverlay: View {
    let celebration: IosStarCelebrationState
    let onDismiss: () -> Void

    @State private var appeared: Bool = false

    var body: some View {
        let intensity = iosStarIntensity(celebration.starAmount)
        ZStack {
            Color.black.opacity(0.36)
                .ignoresSafeArea()
                .onTapGesture(perform: onDismiss)

            VStack(spacing: 6) {
                TelefamIos3DAnimatedStar(
                    size: 120 + intensity * 68,
                    starAmount: celebration.starAmount,
                    animate3D: true,
                    showSparkles: true
                )

                Text("Telefam")
                    .font(.system(size: 22 + intensity * 8, weight: .heavy))
                    .foregroundColor(iosStarRed)

                Text("\(IosStarsCatalog.formatComma(celebration.starAmount)) Stars")
                    .font(.system(size: 20 + intensity * 6, weight: .bold))
                    .foregroundColor(Color(red: 0.08, green: 0.09, blue: 0.11))

                Text(celebration.isIncoming ? "From \(celebration.senderName)" : "Sent to \(celebration.senderName)")
                    .font(.system(size: 13.5))
                    .foregroundColor(.gray)
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 22)
            .frame(maxWidth: 340)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 26))
            .overlay(RoundedRectangle(cornerRadius: 26).stroke(iosStarGoldMid.opacity(0.55), lineWidth: 1.5))
            .shadow(radius: 18, y: 6)
            .scaleEffect(appeared ? 1.0 : 0.72)
            .opacity(appeared ? 1.0 : 0.0)
        }
        .task(id: celebration.eventKey) {
            withAnimation(.spring(response: 0.36, dampingFraction: 0.74)) {
                appeared = true
            }
            try? await Task.sleep(nanoseconds: 3_400_000_000)
            onDismiss()
        }
    }
}

// MARK: - Stars Modal Host & Screens

struct TelefamIosStarsModalHost: View {
    @ObservedObject var starsStore: TelefamIosStarsStore
    let chatStore: TelefamIosChatStore
    let onOpenSenderProfile: (String) -> Void

    var body: some View {
        switch starsStore.currentRoute {
        case .sendStars:
            TelefamIosSendStarsView(
                starsStore: starsStore,
                chatStore: chatStore,
                onOpenRecipientProfile: {
                    if let uid = starsStore.recipientTarget?.userId, !uid.isEmpty {
                        starsStore.closeAll()
                        onOpenSenderProfile(uid)
                    }
                }
            )
        case .buyStars:
            TelefamIosBuyStarsView(starsStore: starsStore)
        case .checkout:
            TelefamIosStarCheckoutView(starsStore: starsStore)
        case .starsHub, .none:
            TelefamIosStarsHubView(
                starsStore: starsStore,
                chatStore: chatStore,
                onOpenSenderProfile: { uid in
                    starsStore.closeAll()
                    onOpenSenderProfile(uid)
                }
            )
        }
    }
}

private struct IosStarsCrimsonBar<Trailing: View>: View {
    let title: String
    let onBack: () -> Void
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onBack) {
                Image(systemName: "arrow.left")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Back")

            Text(title)
                .font(.system(size: 19, weight: .bold))
                .foregroundColor(.white)

            Spacer()

            trailing()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(iosStarRed.ignoresSafeArea(edges: .top))
    }
}

private struct TelefamIosSendStarsView: View {
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var starsStore: TelefamIosStarsStore
    let chatStore: TelefamIosChatStore
    let onOpenRecipientProfile: () -> Void

    var body: some View {
        let bg = colorScheme == .dark ? TelefamPalette.darkBackground : Color.white
        let cardBg = colorScheme == .dark ? TelefamPalette.darkSurface : Color.white
        let border = colorScheme == .dark ? TelefamPalette.darkBorder : Color(red: 0.90, green: 0.91, blue: 0.94)
        let bannerBg = colorScheme == .dark ? Color(red: 0.14, green: 0.09, blue: 0.10) : iosStarRedSoft

        VStack(spacing: 0) {
            IosStarsCrimsonBar(title: "Send Stars", onBack: { starsStore.navigateBack() }) {
                Button {
                    starsStore.openBuyStars(preselectedTier: 5)
                } label: {
                    HStack(spacing: 6) {
                        IosFlatStarBadge(size: 18)
                        Text("Balance: \(IosStarsCatalog.formatComma(starsStore.wallet.availableBalance))")
                            .font(.system(size: 12.5, weight: .bold))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color(red: 0.663, green: 0.071, blue: 0.094))
                    .clipShape(Capsule())
                }
            }

            if let err = starsStore.errorBanner {
                Text(err)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundColor(iosStarRed)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(bannerBg)
                    .onTapGesture { starsStore.errorBanner = nil }
            }

            ScrollView {
                VStack(spacing: 10) {
                    if let target = starsStore.recipientTarget {
                        Button(action: onOpenRecipientProfile) {
                            HStack(spacing: 12) {
                                ZStack(alignment: .bottomTrailing) {
                                    let resolved = chatStore.resolvedMediaURL(target.avatarUrl)
                                    let initials = String((target.fullName.isEmpty ? target.username : target.fullName).prefix(2)).uppercased()
                                    Group {
                                        if !resolved.isEmpty {
                                            TelefamIosRemoteImage(
                                                urlString: resolved,
                                                bearerToken: chatStore.authBearerToken,
                                                deviceFingerprint: chatStore.authDeviceFingerprint,
                                                fallbackInitials: initials.isEmpty ? "T" : initials
                                            )
                                        } else {
                                            Circle().fill(iosStarRed.opacity(0.18))
                                                .overlay(Text(initials.isEmpty ? "T" : initials).font(.system(size: 15, weight: .bold)).foregroundColor(iosStarRed))
                                        }
                                    }
                                    .frame(width: 48, height: 48)
                                    .clipShape(Circle())

                                    if target.isOnline {
                                        Circle()
                                            .fill(iosStarOnlineGreen)
                                            .frame(width: 13, height: 13)
                                            .overlay(Circle().stroke(Color.white, lineWidth: 2))
                                    }
                                }

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(target.fullName.isEmpty ? "@\(target.username)" : target.fullName)
                                        .font(.system(size: 15.5, weight: .bold))
                                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                    Text("@\(target.username.trimmingCharacters(in: CharacterSet(charactersIn: "@")))")
                                        .font(.system(size: 13))
                                        .foregroundColor(TelefamPalette.textMuted(colorScheme))
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundColor(iosStarRed)
                            }
                            .padding(14)
                            .background(bannerBg)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(colorScheme == .dark ? Color(red: 0.23, green: 0.14, blue: 0.15) : Color(red: 0.96, green: 0.85, blue: 0.85), lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }

                    HStack(spacing: 12) {
                        TelefamIos3DAnimatedStar(size: 44, starAmount: 200, animate3D: false, showSparkles: false)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Select stars to send")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                            Text("Show your support and help them grow!")
                                .font(.system(size: 12.5))
                                .foregroundColor(TelefamPalette.textMuted(colorScheme))
                            HStack(spacing: 5) {
                                IosFlatStarBadge(size: 14)
                                Text("1 star = 1 appreciation")
                                    .font(.system(size: 11.5, weight: .semibold))
                                    .foregroundColor(colorScheme == .dark ? Color(red: 0.96, green: 0.65, blue: 0.68) : Color(red: 0.62, green: 0.10, blue: 0.13))
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(colorScheme == .dark ? Color(red: 0.26, green: 0.13, blue: 0.14) : Color(red: 0.98, green: 0.84, blue: 0.85))
                            .clipShape(Capsule())
                        }
                        Spacer()
                    }
                    .padding(14)
                    .background(bannerBg)
                    .clipShape(RoundedRectangle(cornerRadius: 14))

                    ForEach(IosStarsCatalog.tiers, id: \.self) { tier in
                        HStack(spacing: 12) {
                            IosFlatStarBadge(size: 38)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(IosStarsCatalog.formatComma(tier))
                                    .font(.system(size: 15.5, weight: .bold))
                                    .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                Text(IosStarsCatalog.formatConvertedPrice(countryIso: starsStore.countryIso, stars: tier))
                                    .font(.system(size: 12.5))
                                    .foregroundColor(TelefamPalette.textMuted(colorScheme))
                            }
                            Spacer()
                            Button {
                                starsStore.sendStarsFromSheet(tier)
                            } label: {
                                Text("Send")
                                    .font(.system(size: 13.5, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 24)
                                    .frame(height: 38)
                                    .background(iosStarRed)
                                    .clipShape(Capsule())
                            }
                            .disabled(starsStore.isSubmittingSend)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(cardBg)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(border, lineWidth: 1))
                    }
                }
                .frame(maxWidth: 620)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity)
            }
        }
        .background(bg.ignoresSafeArea())
    }
}

private struct TelefamIosBuyStarsView: View {
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var starsStore: TelefamIosStarsStore

    var body: some View {
        let bg = colorScheme == .dark ? TelefamPalette.darkBackground : Color.white
        let cardBg = colorScheme == .dark ? TelefamPalette.darkSurface : Color.white
        let border = colorScheme == .dark ? TelefamPalette.darkBorder : Color(red: 0.90, green: 0.91, blue: 0.94)
        let bannerBg = colorScheme == .dark ? Color(red: 0.14, green: 0.09, blue: 0.10) : iosStarRedSoft

        VStack(spacing: 0) {
            IosStarsCrimsonBar(title: "Buy Stars", onBack: { starsStore.navigateBack() }) {
                EmptyView()
            }

            if let err = starsStore.errorBanner {
                Text(err)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundColor(iosStarRed)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(bannerBg)
                    .onTapGesture { starsStore.errorBanner = nil }
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 12) {
                        TelefamIos3DAnimatedStar(size: 46, starAmount: 500, animate3D: false, showSparkles: false)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Support your favorite creators and help them grow!")
                                .font(.system(size: 14.5, weight: .bold))
                                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                            Text("Buy Stars and send them as a gift in chats.")
                                .font(.system(size: 12.5))
                                .foregroundColor(TelefamPalette.textMuted(colorScheme))
                        }
                        Spacer()
                    }
                    .padding(14)
                    .background(bannerBg)
                    .clipShape(RoundedRectangle(cornerRadius: 14))

                    Text("Choose a package")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                        .padding(.top, 4)

                    ForEach(IosStarsCatalog.tiers, id: \.self) { tier in
                        let isPopular = tier == 5
                        let highlighted = isPopular || tier == starsStore.selectedPackageStars
                        HStack(spacing: 12) {
                            IosFlatStarBadge(size: 38)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 8) {
                                    Text(IosStarsCatalog.formatComma(tier))
                                        .font(.system(size: 15.5, weight: .bold))
                                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                    if isPopular {
                                        Text("Popular")
                                            .font(.system(size: 10.5, weight: .bold))
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 2)
                                            .background(iosStarRed)
                                            .clipShape(Capsule())
                                    }
                                }
                                Text(IosStarsCatalog.formatConvertedPrice(countryIso: starsStore.countryIso, stars: tier))
                                    .font(.system(size: 12.5))
                                    .foregroundColor(TelefamPalette.textMuted(colorScheme))
                            }
                            Spacer()
                            Button {
                                starsStore.openCheckoutForPackage(tier)
                            } label: {
                                Text("Buy")
                                    .font(.system(size: 13.5, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 24)
                                    .frame(height: 38)
                                    .background(iosStarRed)
                                    .clipShape(Capsule())
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(cardBg)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(highlighted ? iosStarRed : border, lineWidth: highlighted ? 1.4 : 1))
                    }
                }
                .frame(maxWidth: 620)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity)
            }
        }
        .background(bg.ignoresSafeArea())
    }
}

private struct TelefamIosStarCheckoutView: View {
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var starsStore: TelefamIosStarsStore

    var body: some View {
        let bg = colorScheme == .dark ? TelefamPalette.darkBackground : Color.white
        let cardBg = colorScheme == .dark ? TelefamPalette.darkSurface : Color.white
        let border = colorScheme == .dark ? TelefamPalette.darkBorder : Color(red: 0.90, green: 0.91, blue: 0.94)
        let settlementNote = IosStarsCatalog.gatewaySettlementNote(
            countryIso: starsStore.countryIso,
            provider: starsStore.selectedProvider,
            stars: starsStore.selectedPackageStars
        )

        VStack(spacing: 0) {
            IosStarsCrimsonBar(title: "Checkout", onBack: { starsStore.navigateBack() }) {
                EmptyView()
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 14) {
                        IosFlatStarBadge(size: 48)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(IosStarsCatalog.formatComma(starsStore.selectedPackageStars)) Stars")
                                .font(.system(size: 19, weight: .bold))
                                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                            Text(IosStarsCatalog.formatConvertedPrice(countryIso: starsStore.countryIso, stars: starsStore.selectedPackageStars))
                                .font(.system(size: 14.5, weight: .medium))
                                .foregroundColor(TelefamPalette.textMuted(colorScheme))
                            if let settlementNote {
                                Text(settlementNote)
                                    .font(.system(size: 12))
                                    .foregroundColor(TelefamPalette.textMuted(colorScheme))
                            }
                        }
                        Spacer()
                    }
                    .padding(16)
                    .background(cardBg)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(border, lineWidth: 1))

                    Text("Payment Method")
                        .font(.system(size: 15.5, weight: .bold))
                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))

                    paymentOptionCard(
                        title: "Paystack",
                        subtitle: "Pay securely with M-Pesa, cards and bank transfers",
                        selected: starsStore.selectedProvider == "paystack",
                        cardBg: cardBg,
                        border: border
                    ) {
                        IosPaystackVectorIcon(size: 36)
                    } onSelect: {
                        starsStore.selectedProvider = "paystack"
                        starsStore.errorBanner = nil
                    }

                    paymentOptionCard(
                        title: "PayPal",
                        subtitle: "Pay with your PayPal account or card",
                        selected: starsStore.selectedProvider == "paypal",
                        cardBg: cardBg,
                        border: border
                    ) {
                        IosPayPalVectorIcon(size: 36)
                    } onSelect: {
                        starsStore.selectedProvider = "paypal"
                        starsStore.errorBanner = nil
                    }

                    HStack(spacing: 12) {
                        IosSecurityShieldVector(size: 28)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Your payment is secure and encrypted")
                                .font(.system(size: 13.5, weight: .bold))
                                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                            Text("We use industry-standard encryption to protect your information.")
                                .font(.system(size: 12))
                                .foregroundColor(TelefamPalette.textMuted(colorScheme))
                        }
                        Spacer()
                    }
                    .padding(14)
                    .background(colorScheme == .dark ? Color(red: 0.14, green: 0.09, blue: 0.10) : iosStarRedSoft)
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                    if let st = starsStore.statusMessage {
                        Text(st)
                            .font(.system(size: 12.5))
                            .foregroundColor(colorScheme == .dark ? Color(red: 0.43, green: 0.90, blue: 0.72) : Color(red: 0.02, green: 0.37, blue: 0.27))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(colorScheme == .dark ? Color(red: 0.09, green: 0.15, blue: 0.11) : Color(red: 0.93, green: 0.99, blue: 0.96))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }

                    if let err = starsStore.errorBanner {
                        Text(err)
                            .font(.system(size: 12.5))
                            .foregroundColor(iosStarRed)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(colorScheme == .dark ? Color(red: 0.20, green: 0.09, blue: 0.09) : iosStarRedSoft)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .onTapGesture { starsStore.errorBanner = nil }
                    }

                    Button {
                        starsStore.proceedToPay()
                    } label: {
                        Group {
                            if starsStore.isCreatingCheckout {
                                ProgressView().tint(.white)
                            } else {
                                Text("Proceed to Pay")
                                    .font(.system(size: 15.5, weight: .bold))
                                    .foregroundColor(.white)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(iosStarRed)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    .disabled(starsStore.isCreatingCheckout || starsStore.isVerifyingPayment)

                    if let pending = starsStore.pendingCheckoutIntent, !pending.credited {
                        Button {
                            starsStore.verifyPendingPayment(silent: false)
                        } label: {
                            Group {
                                if starsStore.isVerifyingPayment {
                                    ProgressView().tint(iosStarRed)
                                } else {
                                    Text("Verify Completed Payment")
                                        .font(.system(size: 14.5, weight: .semibold))
                                        .foregroundColor(iosStarRed)
                                }
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 46)
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(iosStarRed, lineWidth: 1))
                        }
                        .disabled(starsStore.isVerifyingPayment)
                    }
                }
                .frame(maxWidth: 580)
                .padding(18)
                .frame(maxWidth: .infinity)
            }
        }
        .background(bg.ignoresSafeArea())
    }

    private func paymentOptionCard<Icon: View>(
        title: String,
        subtitle: String,
        selected: Bool,
        cardBg: Color,
        border: Color,
        @ViewBuilder icon: () -> Icon,
        onSelect: @escaping () -> Void
    ) -> some View {
        Button(action: onSelect) {
            HStack(spacing: 12) {
                icon()
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                    Text(subtitle)
                        .font(.system(size: 12.5))
                        .foregroundColor(TelefamPalette.textMuted(colorScheme))
                        .multilineTextAlignment(.leading)
                }
                Spacer()
                ZStack {
                    Circle()
                        .stroke(selected ? iosStarRed : border, lineWidth: 2)
                        .frame(width: 20, height: 20)
                    if selected {
                        Circle()
                            .fill(iosStarRed)
                            .frame(width: 10, height: 10)
                    }
                }
            }
            .padding(14)
            .background(cardBg)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? iosStarRed : border, lineWidth: selected ? 1.5 : 1))
        }
        .buttonStyle(.plain)
    }
}

private struct TelefamIosStarsHubView: View {
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var starsStore: TelefamIosStarsStore
    let chatStore: TelefamIosChatStore
    let onOpenSenderProfile: (String) -> Void

    @State private var selectedTab: Int = 0

    var body: some View {
        let bg = colorScheme == .dark ? TelefamPalette.darkBackground : Color(red: 0.965, green: 0.969, blue: 0.980)
        let cardBg = colorScheme == .dark ? TelefamPalette.darkSurface : Color.white
        let border = colorScheme == .dark ? TelefamPalette.darkBorder : Color(red: 0.906, green: 0.922, blue: 0.945)

        VStack(spacing: 0) {
            IosStarsCrimsonBar(title: "Stars", onBack: { starsStore.navigateBack() }) {
                Button {
                    starsStore.refreshSummary(silent: false)
                } label: {
                    Text(starsStore.isLoadingSummary ? "Syncing" : "Refresh")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color(red: 0.663, green: 0.071, blue: 0.094))
                        .clipShape(Capsule())
                }
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    VStack(spacing: 14) {
                        HStack(spacing: 12) {
                            TelefamIos3DAnimatedStar(size: 56, starAmount: max(100, starsStore.wallet.availableBalance))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Available Balance")
                                    .font(.system(size: 12.5))
                                    .foregroundColor(.white.opacity(0.85))
                                Text("\(IosStarsCatalog.formatComma(starsStore.wallet.availableBalance)) Stars")
                                    .font(.system(size: 25, weight: .heavy))
                                    .foregroundColor(.white)
                                Text(IosStarsCatalog.formatConvertedPrice(countryIso: starsStore.countryIso, stars: starsStore.wallet.availableBalance))
                                    .font(.system(size: 13))
                                    .foregroundColor(.white.opacity(0.88))
                            }
                            Spacer()
                            Button {
                                starsStore.openBuyStars(preselectedTier: 100)
                            } label: {
                                Text("Buy Stars")
                                    .font(.system(size: 13.5, weight: .bold))
                                    .foregroundColor(iosStarRed)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
                                    .background(Color.white)
                                    .clipShape(Capsule())
                            }
                        }

                        HStack(spacing: 10) {
                            statCard(title: "Stars Received", value: IosStarsCatalog.formatComma(starsStore.wallet.totalReceived))
                            statCard(title: "Stars Sent", value: IosStarsCatalog.formatComma(starsStore.wallet.totalSent))
                        }
                    }
                    .padding(18)
                    .background(LinearGradient(colors: [Color(red: 0.773, green: 0.075, blue: 0.106), iosStarRed], startPoint: .leading, endPoint: .trailing))
                    .clipShape(RoundedRectangle(cornerRadius: 18))

                    if let pending = starsStore.pendingIntents.first(where: { !$0.credited }) {
                        HStack(spacing: 10) {
                            IosFlatStarBadge(size: 34)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Pending \(IosStarsCatalog.formatComma(pending.starAmount)) Stars (\(pending.provider.uppercased()))")
                                    .font(.system(size: 13.5, weight: .semibold))
                                    .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                Text("Ref: \(pending.providerReference)")
                                    .font(.system(size: 11.5))
                                    .foregroundColor(TelefamPalette.textMuted(colorScheme))
                            }
                            Spacer()
                            Button {
                                starsStore.verifyPendingPayment(silent: false)
                            } label: {
                                Text("Verify")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 7)
                                    .background(iosStarRed)
                                    .clipShape(Capsule())
                            }
                        }
                        .padding(14)
                        .background(cardBg)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(iosStarRed.opacity(0.35), lineWidth: 1))
                    }

                    HStack(spacing: 8) {
                        tabChip(idx: 0, label: "Star Senders (\(starsStore.senders.count))", cardBg: cardBg, border: border)
                        tabChip(idx: 1, label: "History (\(starsStore.transactions.count))", cardBg: cardBg, border: border)
                    }

                    if selectedTab == 0 {
                        if starsStore.senders.isEmpty {
                            emptyBox("No star senders yet", cardBg: cardBg, border: border)
                        } else {
                            ForEach(starsStore.senders) { sender in
                                Button {
                                    onOpenSenderProfile(sender.senderUserId)
                                } label: {
                                    HStack(spacing: 12) {
                                        let resolved = chatStore.resolvedMediaURL(sender.senderAvatar)
                                        let initials = String((sender.senderName.isEmpty ? sender.senderUsername : sender.senderName).prefix(2)).uppercased()
                                        Group {
                                            if !resolved.isEmpty {
                                                TelefamIosRemoteImage(
                                                    urlString: resolved,
                                                    bearerToken: chatStore.authBearerToken,
                                                    deviceFingerprint: chatStore.authDeviceFingerprint,
                                                    fallbackInitials: initials.isEmpty ? "T" : initials
                                                )
                                            } else {
                                                Circle().fill(iosStarRed.opacity(0.16))
                                                    .overlay(Text(initials.isEmpty ? "T" : initials).font(.system(size: 14, weight: .bold)).foregroundColor(iosStarRed))
                                            }
                                        }
                                        .frame(width: 44, height: 44)
                                        .clipShape(Circle())

                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(sender.senderName.isEmpty ? "@\(sender.senderUsername)" : sender.senderName)
                                                .font(.system(size: 14.5, weight: .bold))
                                                .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                            Text("@\(sender.senderUsername) · \(sender.giftsCount) \(sender.giftsCount == 1 ? "gift" : "gifts")")
                                                .font(.system(size: 12))
                                                .foregroundColor(TelefamPalette.textMuted(colorScheme))
                                        }
                                        Spacer()
                                        HStack(spacing: 5) {
                                            IosFlatStarBadge(size: 24)
                                            Text(IosStarsCatalog.formatComma(sender.totalStars))
                                                .font(.system(size: 15, weight: .bold))
                                                .foregroundColor(iosStarRed)
                                        }
                                    }
                                    .padding(14)
                                    .background(cardBg)
                                    .clipShape(RoundedRectangle(cornerRadius: 14))
                                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(border, lineWidth: 1))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    } else {
                        if starsStore.transactions.isEmpty {
                            emptyBox("No star transactions yet", cardBg: cardBg, border: border)
                        } else {
                            ForEach(starsStore.transactions) { tx in
                                let isCredit = tx.direction == "received" || tx.direction == "purchased"
                                let title: String = {
                                    switch tx.direction {
                                    case "purchased": return "Purchased Stars (\(tx.provider.capitalized))"
                                    case "received": return "Received from \(tx.senderName.isEmpty ? tx.senderUsername : tx.senderName)"
                                    case "sent": return "Sent to \(tx.recipientName.isEmpty ? tx.recipientUsername : tx.recipientName)"
                                    default: return "Stars"
                                    }
                                }()
                                HStack(spacing: 12) {
                                    IosFlatStarBadge(size: 38)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(title)
                                            .font(.system(size: 14, weight: .semibold))
                                            .foregroundColor(TelefamPalette.textPrimary(colorScheme))
                                        Text("\(formatCompactChatTime(tx.createdAtMs)) · \(tx.status.capitalized)")
                                            .font(.system(size: 12))
                                            .foregroundColor(TelefamPalette.textMuted(colorScheme))
                                    }
                                    Spacer()
                                    Text("\(isCredit ? "+" : "-")\(IosStarsCatalog.formatComma(tx.starAmount))")
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundColor(isCredit ? Color(red: 0.082, green: 0.502, blue: 0.239) : iosStarRed)
                                }
                                .padding(14)
                                .background(cardBg)
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                                .overlay(RoundedRectangle(cornerRadius: 14).stroke(border, lineWidth: 1))
                            }
                        }
                    }
                }
                .frame(maxWidth: 740)
                .padding(16)
                .frame(maxWidth: .infinity)
            }
        }
        .background(bg.ignoresSafeArea())
    }

    private func statCard(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 11.5))
                .foregroundColor(.white.opacity(0.82))
            Text(value)
                .font(.system(size: 17, weight: .bold))
                .foregroundColor(.white)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color.white.opacity(0.14))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func tabChip(idx: Int, label: String, cardBg: Color, border: Color) -> some View {
        let active = selectedTab == idx
        return Button {
            selectedTab = idx
        } label: {
            Text(label)
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(active ? .white : TelefamPalette.textPrimary(colorScheme))
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(active ? iosStarRed : cardBg)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(active ? Color.clear : border, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private func emptyBox(_ text: String, cardBg: Color, border: Color) -> some View {
        Text(text)
            .font(.system(size: 14))
            .foregroundColor(TelefamPalette.textMuted(colorScheme))
            .frame(maxWidth: .infinity)
            .padding(28)
            .background(cardBg)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(border, lineWidth: 1))
    }
}
