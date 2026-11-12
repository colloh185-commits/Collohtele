package telefam.company.core

import java.util.Locale
import kotlin.math.abs

enum class FeedTab(val apiParam: String, val label: String) {
  FOLLOWING("following", "Following"),
  FOR_YOU("for_you", "For You"),
}

enum class FeedInteractionAction(val apiValue: String) {
  LIKE("like"),
  UNLIKE("unlike"),
  FAVOURITE("favourite"),
  UNFAVOURITE("unfavourite"),
  REPOST("repost"),
  UNREPOST("unrepost"),
  SHARE("share"),
  NOT_INTERESTED("not_interested"),
  REPORT("report"),
  BLOCK("block"),
  WATCH("watch"),
  SKIP("skip"),
}

data class FeedVideoRendition(
  val qualityLabel: String,
  val width: Int,
  val height: Int,
  val bitrateKbps: Int,
  val url: String,
  val byteSize: Int,
)

data class FeedSearchShortcut(
  val key: String,
  val label: String,
  val count: Long,
)

data class FittedMediaBounds(
  val width: Float,
  val height: Float,
)

object FeedAspectRatioCalculator {
  /**
   * Computes non-distorted proportional width/height for any video or photo aspect ratio
   * (e.g., 9:16, 4:5, 1:1, 16:9, 21:9, or extreme custom dimensions) inside available safe viewport.
   */
  fun computeFittedSize(
    containerWidth: Float,
    containerHeight: Float,
    mediaWidth: Int,
    mediaHeight: Int,
  ): FittedMediaBounds {
    if (containerWidth <= 0f || containerHeight <= 0f) {
      return FittedMediaBounds(0f, 0f)
    }
    val safeW = if (mediaWidth > 0) mediaWidth.toFloat() else 9f
    val safeH = if (mediaHeight > 0) mediaHeight.toFloat() else 16f
    val mediaRatio = safeW / safeH
    val containerRatio = containerWidth / containerHeight

    if (abs(mediaRatio - containerRatio) <= 0.04f) {
      return FittedMediaBounds(containerWidth, containerHeight)
    }
    return if (mediaRatio > containerRatio) {
      val fittedW = containerWidth
      val fittedH = (containerWidth / mediaRatio).coerceAtMost(containerHeight)
      FittedMediaBounds(fittedW, fittedH)
    } else {
      val fittedH = containerHeight
      val fittedW = (containerHeight * mediaRatio).coerceAtMost(containerWidth)
      FittedMediaBounds(fittedW, fittedH)
    }
  }
}

object AdaptiveStreamSelector {
  fun resolveMediaUrl(baseUrl: String, pathOrUrl: String): String {
    val clean = pathOrUrl.trim()
    if (clean.isBlank()) return ""
    if (clean.startsWith("http://") || clean.startsWith("https://") ||
      clean.startsWith("content://") || clean.startsWith("file://")
    ) {
      return clean
    }
    if (clean.startsWith("/")) {
      val cleanBase = baseUrl.trim().trimEnd('/')
      return if (cleanBase.isNotBlank()) "$cleanBase$clean" else clean
    }
    return clean
  }

  /**
   * Selects optimal stream rendition based on real-time NetworkTier to minimize rebuffering.
   */
  fun selectRenditionUrl(
    baseUrl: String,
    primaryVideoUrl: String,
    renditions: List<FeedVideoRendition>,
    networkTier: NetworkTier,
  ): String {
    if (renditions.isEmpty()) {
      return resolveMediaUrl(baseUrl, primaryVideoUrl)
    }
    val sorted = renditions.filter { it.url.isNotBlank() }.sortedBy { it.bitrateKbps }
    if (sorted.isEmpty()) return resolveMediaUrl(baseUrl, primaryVideoUrl)

    val picked = when (networkTier) {
      NetworkTier.OFFLINE -> sorted.first()
      NetworkTier.WEAK -> sorted.first()
      NetworkTier.GOOD -> {
        sorted.lastOrNull { it.bitrateKbps <= 2500 } ?: sorted.last()
      }
    }
    return resolveMediaUrl(baseUrl, picked.url)
  }

  fun maxVideoBitrateBps(networkTier: NetworkTier): Int = when (networkTier) {
    NetworkTier.GOOD -> 4_500_000
    NetworkTier.WEAK -> 700_000
    NetworkTier.OFFLINE -> 450_000
  }

  fun preloadBytesBudget(networkTier: NetworkTier): Long = when (networkTier) {
    NetworkTier.GOOD -> 1_536L * 1024L // 1.5 MB opening chunk per upcoming video
    NetworkTier.WEAK -> 512L * 1024L // 512 KB opening chunk on weak connection
    NetworkTier.OFFLINE -> 0L
  }
}

fun formatCompactCount(count: Long): String {
  if (count <= 0L) return "0"
  if (count >= 1_000_000L) {
    val tenths = count / 100_000L
    val whole = tenths / 10L
    val rem = tenths % 10L
    return if (rem == 0L) "${whole}M" else String.format(Locale.US, "%d.%dM", whole, rem)
  }
  if (count >= 10_000L) {
    val tenths = count / 100L
    val whole = tenths / 10L
    val rem = tenths % 10L
    return if (rem == 0L) "${whole}K" else String.format(Locale.US, "%d.%dK", whole, rem)
  }
  return String.format(Locale.US, "%,d", count)
}
