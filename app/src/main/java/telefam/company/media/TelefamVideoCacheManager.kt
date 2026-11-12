package telefam.company.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import telefam.company.core.AdaptiveStreamSelector
import telefam.company.core.NetworkTier
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared singleton SimpleCache + chunked next-5 preloader + play-ahead ExoPlayer factory.
 * Guarantees maximum disk caching (512 MB LRU), range-based chunk preloading, and
 * bandwidth/network-tier adaptive bitrate constraints to prevent playback buffering.
 */
@OptIn(UnstableApi::class)
object TelefamVideoCacheManager {
  private const val MAX_CACHE_BYTES: Long = 512L * 1024L * 1024L // 512 MB LRU disk cache

  @Volatile
  private var simpleCache: SimpleCache? = null

  @Volatile
  private var sharedHttpClient: OkHttpClient? = null

  private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activePreloadJob = AtomicReference<Job?>(null)

  fun getHttpClient(): OkHttpClient {
    return sharedHttpClient ?: synchronized(this) {
      sharedHttpClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
        .also { sharedHttpClient = it }
    }
  }

  fun getCache(context: Context): SimpleCache {
    return simpleCache ?: synchronized(this) {
      simpleCache ?: run {
        val cacheDir = File(context.applicationContext.cacheDir, "telefam_video_cache").apply {
          if (!exists()) mkdirs()
        }
        val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES)
        val dbProvider = StandaloneDatabaseProvider(context.applicationContext)
        SimpleCache(cacheDir, evictor, dbProvider)
      }.also { simpleCache = it }
    }
  }

  fun buildCacheDataSourceFactory(
    context: Context,
    bearerToken: String = "",
    deviceFingerprint: String = "",
  ): CacheDataSource.Factory {
    val appCtx = context.applicationContext
    val headers = buildMap {
      if (bearerToken.isNotBlank()) put("Authorization", "Bearer $bearerToken")
      if (deviceFingerprint.isNotBlank()) put("X-Telefam-Device-FP", deviceFingerprint)
    }
    val upstreamOkHttp = OkHttpDataSource.Factory(getHttpClient())
      .setUserAgent("TelefamAndroid/1.0")
      .apply {
        if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
      }
    val upstreamFactory = DefaultDataSource.Factory(appCtx, upstreamOkHttp)
    return CacheDataSource.Factory()
      .setCache(getCache(appCtx))
      .setUpstreamDataSourceFactory(upstreamFactory)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
  }

  fun buildFeedExoPlayer(
    context: Context,
    networkTier: NetworkTier,
    bearerToken: String = "",
    deviceFingerprint: String = "",
    startMuted: Boolean = false,
  ): ExoPlayer {
    val appCtx = context.applicationContext
    val cacheFactory = buildCacheDataSourceFactory(appCtx, bearerToken, deviceFingerprint)

    val trackSelector = DefaultTrackSelector(appCtx).apply {
      setParameters(
        buildUponParameters()
          .setMaxVideoBitrate(AdaptiveStreamSelector.maxVideoBitrateBps(networkTier))
          .setForceLowestBitrate(networkTier == NetworkTier.WEAK)
          .build(),
      )
    }

    // Play-ahead load control: low min-buffer-to-start (750ms) with generous 30s play-ahead
    val loadControl = DefaultLoadControl.Builder()
      .setBufferDurationsMs(
        15_000, // minBufferMs
        35_000, // maxBufferMs
        750, // bufferForPlaybackMs (instant start)
        1_800, // bufferForPlaybackAfterRebufferMs
      )
      .setBackBuffer(6_000, true)
      .build()

    val bandwidthMeter = DefaultBandwidthMeter.Builder(appCtx).build()

    return ExoPlayer.Builder(appCtx)
      .setTrackSelector(trackSelector)
      .setLoadControl(loadControl)
      .setBandwidthMeter(bandwidthMeter)
      .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
      .build()
      .apply {
        repeatMode = Player.REPEAT_MODE_ONE
        videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        volume = if (startMuted) 0f else 1f
      }
  }

  fun applyAdaptiveBitrate(player: ExoPlayer, networkTier: NetworkTier) {
    val selector = player.trackSelector as? DefaultTrackSelector ?: return
    selector.setParameters(
      selector.buildUponParameters()
        .setMaxVideoBitrate(AdaptiveStreamSelector.maxVideoBitrateBps(networkTier))
        .setForceLowestBitrate(networkTier == NetworkTier.WEAK)
        .build(),
    )
  }

  fun createMediaItem(url: String): MediaItem {
    val uri = Uri.parse(url)
    return MediaItem.Builder()
      .setUri(uri)
      .setMediaId(url)
      .build()
  }

  /**
   * Preloads the opening chunks of up to the next 5 upcoming videos into SimpleCache
   * sequentially in the background so swiping to any of the next 5 videos starts instantly.
   */
  fun preloadNextFiveVideos(
    context: Context,
    upcomingUrls: List<String>,
    networkTier: NetworkTier,
    bearerToken: String = "",
    deviceFingerprint: String = "",
  ) {
    val budgetBytes = AdaptiveStreamSelector.preloadBytesBudget(networkTier)
    if (budgetBytes <= 0L) return

    val targets = upcomingUrls
      .asSequence()
      .map { it.trim() }
      .filter { it.startsWith("http://") || it.startsWith("https://") }
      .distinct()
      .take(5)
      .toList()
    if (targets.isEmpty()) return

    val appCtx = context.applicationContext
    val job = preloadScope.launch {
      val cacheDataSource = buildCacheDataSourceFactory(appCtx, bearerToken, deviceFingerprint)
        .createDataSourceForDownloading()
      for (url in targets) {
        runCatching {
          val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setPosition(0L)
            .setLength(budgetBytes)
            .build()
          val writer = CacheWriter(cacheDataSource, dataSpec, null, null)
          writer.cache()
        }
      }
    }
    activePreloadJob.getAndSet(job)?.cancel()
  }
}
