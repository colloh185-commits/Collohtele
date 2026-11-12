package telefam.company.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import telefam.company.core.ContactFilterTab
import telefam.company.core.FeedInteractionAction
import telefam.company.core.FeedSearchShortcut
import telefam.company.core.FeedTab
import telefam.company.core.FeedVideoRendition
import telefam.company.core.HashtagItem
import telefam.company.core.NetworkTier
import telefam.company.core.PostMode
import telefam.company.core.PostSongTrack
import telefam.company.core.TelefamContactsRules
import telefam.company.core.TelefamCoreEngine
import telefam.company.core.TelefamPostRules
import telefam.company.core.TelefamProfileSanitizer
import telefam.company.data.local.AuditLogEntity
import telefam.company.data.local.CachedContactEntity
import telefam.company.data.local.CachedFeedPostEntity
import telefam.company.data.local.CachedHashtagEntity
import telefam.company.data.local.ContactSyncActionEntity
import telefam.company.data.local.DeviceIdentityStore
import telefam.company.data.local.FamilyCircleEntity
import telefam.company.data.local.FamilyMessageEntity
import telefam.company.data.local.MediaUploadEntity
import telefam.company.data.local.OfflineActionEntity
import telefam.company.data.local.PendingFeedInteractionEntity
import telefam.company.data.local.PendingPostUploadEntity
import telefam.company.data.local.RateLimitHitEntity
import telefam.company.data.local.RateLimitStateEntity
import telefam.company.data.local.RefreshTokenRecordEntity
import telefam.company.data.local.StoredSession
import telefam.company.data.local.TelefamDao
import telefam.company.data.local.UserProfileEntity
import telefam.company.data.network.AuditEventDto
import telefam.company.data.network.ContactActionDto
import telefam.company.data.network.ContactSyncActionDto
import telefam.company.data.network.ContactSyncBatchRequestDto
import telefam.company.data.network.CreatePostRequestDto
import telefam.company.data.network.FeedInteractionBatchRequestDto
import telefam.company.data.network.FeedInteractionRequestDto
import telefam.company.data.network.FeedItemDto
import telefam.company.data.network.FeedVideoRenditionDto
import telefam.company.data.network.NetworkQualityMonitor
import telefam.company.data.network.OAuthDto
import telefam.company.data.network.ProfileSetupDto
import telefam.company.data.network.ProgressFileRequestBody
import telefam.company.data.network.RefreshTokenDto
import telefam.company.data.network.RequestOtpDto
import telefam.company.data.network.SongTrackDto
import telefam.company.data.network.SyncBatchRequestDto
import telefam.company.data.network.SyncOperationDto
import telefam.company.data.network.TelefamApiService
import telefam.company.data.network.UpdatePostRequestDto
import telefam.company.data.network.VerifyLinkDto
import telefam.company.data.network.VerifyOtpDto
import telefam.company.media.MediaUploadProcessor
import telefam.company.media.ProcessedAvatarMedia
import telefam.company.media.ProcessedPostPhoto
import telefam.company.media.VideoPrepareResult
import telefam.company.security.EncryptedTokenVault
import telefam.company.security.SecurityIntegrityGuard
import telefam.company.ui.theme.ThemeMode
import java.io.File
import java.util.UUID

sealed class AuthActionResult {
  data class OtpSent(
    val email: String,
    val cooldownSeconds: Long,
    val totalCooldownDurationSeconds: Long,
  ) : AuthActionResult()

  data class Verified(
    val userId: String,
    val email: String,
    val profileCompleted: Boolean,
  ) : AuthActionResult()

  data class SanitizedFailure(
    val message: String,
    val remainingCooldownSeconds: Long = 0L,
  ) : AuthActionResult()
}

sealed class ProfileActionResult {
  data object Saved : ProfileActionResult()
  data class Error(val message: String) : ProfileActionResult()
}

data class ContactsPageFetchResult(
  val page: Int,
  val hasMore: Boolean,
  val fromCache: Boolean,
  val pageItems: List<CachedContactEntity>,
)

sealed class ContactToggleResult {
  data object Synced : ContactToggleResult()
  data object QueuedOffline : ContactToggleResult()
  data class RateLimited(val message: String = TelefamContactsRules.RATE_LIMIT_TOAST_MESSAGE) :
    ContactToggleResult()
}

data class PostSubmissionInput(
  val mode: PostMode,
  val textContent: String,
  val caption: String,
  val themeId: String,
  val fontId: String,
  val song: PostSongTrack?,
  val taggedUserIds: List<String>,
  val hashtags: List<String>,
  val localPhotoPaths: List<String>,
  val localVideoPath: String?,
  val localThumbnailPath: String?,
  val frameHashes: List<String>,
  val durationMs: Long,
  val trimStartMs: Long,
  val trimEndMs: Long,
  val width: Int,
  val height: Int,
  val muteOriginalAudio: Boolean,
  val contentId: String,
)

sealed class PostPublishResult {
  data class Uploaded(val postId: String, val contentId: String) : PostPublishResult()
  data class QueuedOffline(val clientOpId: String) : PostPublishResult()
  data class Rejected(val message: String) : PostPublishResult()
}

data class FeedPageFetchResult(
  val page: Int,
  val hasMore: Boolean,
  val fromCache: Boolean,
  val items: List<CachedFeedPostEntity>,
)

data class FeedSearchFetchResult(
  val page: Int,
  val hasMore: Boolean,
  val shortcuts: List<FeedSearchShortcut>,
  val items: List<CachedFeedPostEntity>,
)

class TelefamRepository(
  private val appContext: Context,
  private val dao: TelefamDao,
  private val identityStore: DeviceIdentityStore,
  private val networkMonitor: NetworkQualityMonitor,
  private val apiService: TelefamApiService? = null,
) {
  private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val stringListAdapter = moshi.adapter<List<String>>(
    Types.newParameterizedType(List::class.java, String::class.java),
  )
  private val renditionsListAdapter = moshi.adapter<List<FeedVideoRenditionDto>>(
    Types.newParameterizedType(List::class.java, FeedVideoRenditionDto::class.java),
  )
  private val songAdapter = moshi.adapter(SongTrackDto::class.java)

  val networkTier: StateFlow<NetworkTier> = networkMonitor.networkTier
  val sessionFlow: Flow<StoredSession> = identityStore.sessionFlow
  val circlesFlow: Flow<List<FamilyCircleEntity>> = dao.observeCircles()
  val pendingOfflineActionsCount: Flow<Int> = dao.observePendingActionCount()
  val pendingPostUploadsFlow: Flow<List<PendingPostUploadEntity>> = dao.observePendingPostUploads()

  suspend fun getStreamCredentials(): Pair<String, String> {
    val access = identityStore.sessionFlow.first().accessToken.orEmpty()
    val fp = buildDeviceFingerprint()
    return access to fp
  }

  fun observeFeedPosts(tab: FeedTab): Flow<List<CachedFeedPostEntity>> =
    dao.observeFeedPosts(tab.apiParam)

  fun observeAllVisibleFeedPosts(): Flow<List<CachedFeedPostEntity>> =
    dao.observeAllVisibleFeedPosts()

  fun observeProfile(userId: String): Flow<UserProfileEntity?> = dao.observeProfile(userId)

  fun observeMessages(circleId: String): Flow<List<FamilyMessageEntity>> =
    dao.observeMessagesForCircle(circleId)

  fun observeCachedContacts(
    filter: ContactFilterTab,
    query: String,
    excludeUserId: String,
  ): Flow<List<CachedContactEntity>> {
    return dao.observeAllCachedContacts().map { all ->
      filterAndSortContacts(all, filter, query, excludeUserId)
    }
  }

  suspend fun loadContactsPage(
    filter: ContactFilterTab,
    query: String,
    page: Int,
    pageSize: Int = TelefamContactsRules.PAGE_SIZE,
  ): ContactsPageFetchResult {
    val cleanPage = page.coerceAtLeast(1)
    val cleanQuery = TelefamProfileSanitizer.sanitizePlainText(query, 64, singleLine = true)
    val session = identityStore.sessionFlow.first()
    val currentUserId = session.userId.orEmpty()
    val accessToken = session.accessToken.orEmpty()
    val api = apiService

    if (api != null && accessToken.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val deviceFp = buildDeviceFingerprint()
      val resp = executeWithWeakNetworkRetry {
        api.listContacts(
          bearer = "Bearer $accessToken",
          deviceFingerprint = deviceFp,
          filter = filter.apiParam,
          query = cleanQuery,
          page = cleanPage,
          pageSize = pageSize,
        )
      }
      val body = if (resp != null && resp.isSuccessful) resp.body() else null
      if (body != null && body.success) {
        val nowMs = System.currentTimeMillis()
        val pendingByTarget = dao.getAllPendingContactSyncActions().associateBy { it.targetUserId }
        val existingById = body.items.mapNotNull { dao.getCachedContact(it.userId) }.associateBy { it.userId }
        val entities = body.items.map { item ->
          val pending = pendingByTarget[item.userId]
          val existing = existingById[item.userId]
          CachedContactEntity(
            userId = item.userId,
            fullName = item.fullName,
            username = item.username,
            usernameLower = item.username.lowercase(),
            location = item.location,
            countryIso = item.countryIso,
            avatarUrl = item.avatarUrl,
            avatarLocalPath = existing?.avatarLocalPath?.takeIf { File(it).exists() },
            isFollowing = pending?.wantFollow ?: item.isFollowing,
            followsYou = item.followsYou,
            riskScore = item.riskScore,
            updatedAtMs = item.updatedAtMs.takeIf { it > 0L } ?: nowMs,
            cachedAtMs = nowMs,
          )
        }
        if (entities.isNotEmpty()) {
          dao.upsertCachedContacts(entities)
          cacheContactAvatarsIfNeeded(api, entities)
        }
        return ContactsPageFetchResult(
          page = body.page.coerceAtLeast(cleanPage),
          hasMore = body.hasMore,
          fromCache = false,
          pageItems = entities.filter { it.userId != currentUserId },
        )
      }
    }

    // Offline or weak-network fallback from aggressive Room cache
    val cachedAll = dao.observeAllCachedContacts().first()
    val filtered = filterAndSortContacts(cachedAll, filter, cleanQuery, currentUserId)
    val offset = (cleanPage - 1) * pageSize
    val slice = if (offset >= filtered.size) emptyList() else filtered.subList(offset, minOf(filtered.size, offset + pageSize))
    val hasMore = offset + slice.size < filtered.size
    return ContactsPageFetchResult(
      page = cleanPage,
      hasMore = hasMore,
      fromCache = true,
      pageItems = slice,
    )
  }

  suspend fun toggleContactFollow(targetUserId: String): ContactToggleResult {
    val session = identityStore.sessionFlow.first()
    val userId = session.userId.orEmpty()
    if (userId.isBlank() || targetUserId == userId) {
      return ContactToggleResult.RateLimited(TelefamContactsRules.RATE_LIMIT_TOAST_MESSAGE)
    }
    val existing = dao.getCachedContact(targetUserId) ?: return ContactToggleResult.Synced
    val nowMs = System.currentTimeMillis()
    val deviceFp = buildDeviceFingerprint()
    val cutoff1m = nowMs - TelefamContactsRules.ONE_MINUTE_MS
    dao.pruneExpiredHits(nowMs - TelefamCoreEngine.WINDOW_24H_MS)

    val acctHits = dao.countHitsSince("contact_act:$userId", cutoff1m)
    val devHits = dao.countHitsSince("contact_dev:$deviceFp", cutoff1m)
    if (acctHits >= TelefamContactsRules.MAX_ACTIONS_PER_MINUTE ||
      devHits >= TelefamContactsRules.MAX_ACTIONS_PER_MINUTE
    ) {
      logAudit(userId, "contact_rate_limited", "{\"target\":\"$targetUserId\"}")
      return ContactToggleResult.RateLimited(TelefamContactsRules.RATE_LIMIT_TOAST_MESSAGE)
    }

    val wantFollow = !existing.isFollowing
    dao.insertHit(RateLimitHitEntity(dimensionKey = "contact_act:$userId", timestampMs = nowMs))
    dao.insertHit(RateLimitHitEntity(dimensionKey = "contact_dev:$deviceFp", timestampMs = nowMs))

    // Optimistic update in Room so UI switches immediately (even offline)
    dao.updateCachedContactRelationship(
      userId = targetUserId,
      isFollowing = wantFollow,
      followsYou = existing.followsYou,
      updatedAtMs = nowMs,
      cachedAtMs = nowMs,
    )
    dao.updateFeedAuthorFollowing(targetUserId, wantFollow)

    val api = apiService
    val accessToken = session.accessToken.orEmpty()
    if (api != null && accessToken.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val resp = executeWithWeakNetworkRetry {
        val body = ContactActionDto(targetUserId = targetUserId)
        if (wantFollow) {
          api.followContact("Bearer $accessToken", deviceFp, body)
        } else {
          api.unfollowContact("Bearer $accessToken", deviceFp, body)
        }
      }
      if (resp?.code() == 429) {
        // Revert optimistic local flip when server denies due to rate limit
        dao.updateCachedContactRelationship(
          userId = targetUserId,
          isFollowing = existing.isFollowing,
          followsYou = existing.followsYou,
          updatedAtMs = existing.updatedAtMs,
          cachedAtMs = System.currentTimeMillis(),
        )
        logAudit(userId, "contact_rate_limited", "{\"target\":\"$targetUserId\",\"src\":\"server\"}")
        return ContactToggleResult.RateLimited(TelefamContactsRules.RATE_LIMIT_TOAST_MESSAGE)
      }
      val body = if (resp != null && resp.isSuccessful) resp.body() else null
      if (body != null && body.success) {
        dao.deletePendingContactSyncForTarget(targetUserId)
        dao.updateCachedContactRelationship(
          userId = targetUserId,
          isFollowing = body.isFollowing,
          followsYou = body.followsYou,
          updatedAtMs = body.serverEpochMs.takeIf { it > 0L } ?: nowMs,
          cachedAtMs = nowMs,
        )
        logAudit(
          userId,
          if (wantFollow) "contact_follow" else "contact_unfollow",
          "{\"target\":\"$targetUserId\",\"risk\":${body.callerRiskScore}}",
        )
        return ContactToggleResult.Synced
      }
    }

    // Queue offline for automatic replay when internet returns
    dao.deletePendingContactSyncForTarget(targetUserId)
    dao.enqueueContactSyncAction(
      ContactSyncActionEntity(
        clientOpId = "cst_${UUID.randomUUID()}",
        targetUserId = targetUserId,
        wantFollow = wantFollow,
        createdAtMs = nowMs,
        retryCount = 0,
        nextRetryAtMs = nowMs,
      ),
    )
    logAudit(
      userId,
      if (wantFollow) "contact_follow_queued_offline" else "contact_unfollow_queued_offline",
      "{\"target\":\"$targetUserId\"}",
    )
    return ContactToggleResult.QueuedOffline
  }

  suspend fun setThemeMode(mode: ThemeMode) {
    identityStore.setThemeMode(mode)
  }

  suspend fun getCurrentCooldownForEmail(email: String): Pair<Long, Long> {
    val cleanEmail = email.trim().lowercase()
    if (cleanEmail.isBlank()) return 0L to 60L
    val deviceFp = buildDeviceFingerprint()
    val accountHash = TelefamCoreEngine.hashAccountEmail(cleanEmail)
    val compositeKey = "$accountHash:$deviceFp"
    val state = dao.getRateLimitState(compositeKey) ?: return 0L to 60L
    val nowMs = System.currentTimeMillis()
    if (nowMs - state.lastUpdatedEpochMs >= TelefamCoreEngine.WINDOW_24H_MS) {
      dao.clearRateLimitState(compositeKey)
      return 0L to 60L
    }
    val remainingSec = ((state.nextAllowedEpochMs - nowMs + 999L) / 1000L).coerceAtLeast(0L)
    return remainingSec to state.activeCooldownDurationSeconds.coerceAtLeast(60L)
  }

  suspend fun savePendingOtpEmail(email: String?) {
    identityStore.savePendingOtpEmail(email)
  }

  suspend fun ensureSessionFresh(): Boolean {
    val session = identityStore.sessionFlow.first()
    val userId = session.userId ?: return false
    val refreshToken = session.refreshToken ?: return false
    val nowMs = System.currentTimeMillis()

    if (nowMs > session.refreshExpiresAtMs) {
      identityStore.clearSession()
      return false
    }
    // If short-lived access token is still valid, keep current session
    if (!session.accessToken.isNullOrBlank() && nowMs < session.accessExpiresAtMs - 30_000L) {
      return true
    }

    // Rotate short-lived access + long-lived refresh token silently with reuse detection
    val deviceFp = buildDeviceFingerprint()
    val ipFp = buildIpFingerprint()
    val tokenHash = EncryptedTokenVault.sha256Hex(refreshToken)
    val record = dao.getRefreshTokenRecord(tokenHash)

    if (record != null && (record.revoked || record.deviceFp != deviceFp || nowMs > record.expiresAtMs)) {
      if (record.revoked) {
        dao.revokeTokenFamily(record.familyId)
        logAudit(userId, "token_reuse_attack_revoked", "{\"family\":\"${record.familyId}\"}")
      }
      identityStore.clearSession()
      return false
    }

    val api = apiService
    if (api != null) {
      val resp = executeWithWeakNetworkRetry {
        api.refreshToken(deviceFp, RefreshTokenDto(refreshToken))
      }
      if (resp == null || !resp.isSuccessful || resp.body()?.success != true) {
        if (resp?.code() == 401) identityStore.clearSession()
        return resp?.code() != 401
      }
      val body = resp.body()!!
      if (record != null) dao.revokeRefreshToken(tokenHash)
      val newRefreshHash = EncryptedTokenVault.sha256Hex(body.refreshToken)
      dao.insertRefreshTokenRecord(
        RefreshTokenRecordEntity(
          tokenHash = newRefreshHash,
          familyId = record?.familyId ?: UUID.randomUUID().toString(),
          userId = userId,
          deviceFp = deviceFp,
          expiresAtMs = nowMs + (body.refreshExpiresInSec * 1000L),
          revoked = false,
          createdAtMs = nowMs,
        ),
      )
      identityStore.saveEncryptedSession(
        userId = userId,
        email = session.email.orEmpty(),
        accessToken = body.sessionToken,
        refreshToken = body.refreshToken,
        accessExpiresInSec = body.accessExpiresInSec,
        refreshExpiresInSec = body.refreshExpiresInSec,
        profileCompleted = session.profileCompleted,
      )
      logAudit(userId, "token_rotated", "{\"device\":\"${deviceFp.take(12)}\"}")
      return true
    }

    // Standalone local rotation with reuse tracking
    if (record != null) dao.revokeRefreshToken(tokenHash)
    val newAccess = "tf_acc_${UUID.randomUUID()}"
    val newRefresh = "tfr_${UUID.randomUUID()}"
    val familyId = record?.familyId ?: UUID.randomUUID().toString()
    dao.insertRefreshTokenRecord(
      RefreshTokenRecordEntity(
        tokenHash = EncryptedTokenVault.sha256Hex(newRefresh),
        familyId = familyId,
        userId = userId,
        deviceFp = deviceFp,
        expiresAtMs = nowMs + 2_592_000_000L,
        revoked = false,
        createdAtMs = nowMs,
      ),
    )
    identityStore.saveEncryptedSession(
      userId = userId,
      email = session.email.orEmpty(),
      accessToken = newAccess,
      refreshToken = newRefresh,
      accessExpiresInSec = 900L,
      refreshExpiresInSec = 2_592_000L,
      profileCompleted = session.profileCompleted,
    )
    dao.insertAuditLog(
      AuditLogEntity(
        userId = userId,
        action = "token_rotated",
        deviceFp = deviceFp,
        ipFp = ipFp,
        metadataJson = "{\"family\":\"$familyId\"}",
        createdAtMs = nowMs,
      ),
    )
    return true
  }

  suspend fun requestOtp(rawEmail: String): AuthActionResult {
    val email = rawEmail.trim().lowercase()
    val nowMs = System.currentTimeMillis()
    val deviceFp = buildDeviceFingerprint()
    val ipFp = buildIpFingerprint()
    val accountHash = TelefamCoreEngine.hashAccountEmail(email)
    val compositeKey = "$accountHash:$deviceFp"

    dao.pruneExpiredHits(nowMs - TelefamCoreEngine.WINDOW_24H_MS)
    val existingState = dao.getRateLimitState(compositeKey)
    if (existingState != null) {
      if (nowMs - existingState.lastUpdatedEpochMs >= TelefamCoreEngine.WINDOW_24H_MS) {
        dao.clearRateLimitState(compositeKey)
      } else if (nowMs < existingState.nextAllowedEpochMs) {
        val remSec = ((existingState.nextAllowedEpochMs - nowMs + 999L) / 1000L).coerceAtLeast(1L)
        return AuthActionResult.SanitizedFailure(
          message = TelefamCoreEngine.sanitizeServerError(429),
          remainingCooldownSeconds = remSec,
        )
      }
    }

    val cutoff24h = nowMs - TelefamCoreEngine.WINDOW_24H_MS
    val acctHits = dao.countHitsSince("acct:$accountHash", cutoff24h)
    val devHits = dao.countHitsSince("dev:$deviceFp", cutoff24h)
    val ipHits = dao.countHitsSince("ip:$ipFp", cutoff24h)
    if (acctHits >= TelefamCoreEngine.MAX_ACCOUNT_REQUESTS_24H ||
      devHits >= TelefamCoreEngine.MAX_DEVICE_REQUESTS_24H ||
      ipHits >= TelefamCoreEngine.MAX_IP_REQUESTS_24H
    ) {
      logAudit(accountHash, "otp_rate_limited", "{}")
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(429))
    }

    val nextSendCount = (existingState?.sendCount ?: 0) + 1
    var serverCooldown = TelefamCoreEngine.cooldownSecondsForSendCount(nextSendCount)

    val api = apiService
      ?: return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(503))

    if (networkTier.value == NetworkTier.OFFLINE) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(503))
    }
    val remoteResult = executeWithWeakNetworkRetry {
      api.requestOtp(deviceFp, ipFp, RequestOtpDto(email))
    } ?: return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(503))

    if (!remoteResult.isSuccessful) {
      return AuthActionResult.SanitizedFailure(
        TelefamCoreEngine.sanitizeServerError(remoteResult.code(), remoteResult.errorBody()?.string()),
      )
    }
    val body = remoteResult.body()
    if (body == null || !body.success) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(500))
    }
    if (body.cooldownSeconds > 0L) {
      serverCooldown = maxOf(serverCooldown, body.cooldownSeconds)
    }

    dao.insertHit(RateLimitHitEntity(dimensionKey = "acct:$accountHash", timestampMs = nowMs))
    dao.insertHit(RateLimitHitEntity(dimensionKey = "dev:$deviceFp", timestampMs = nowMs))
    dao.insertHit(RateLimitHitEntity(dimensionKey = "ip:$ipFp", timestampMs = nowMs))

    val nextAllowedMs = nowMs + (serverCooldown * 1000L)
    dao.upsertRateLimitState(
      RateLimitStateEntity(
        compositeKey = compositeKey,
        accountHash = accountHash,
        deviceFp = deviceFp,
        sendCount = nextSendCount,
        nextAllowedEpochMs = nextAllowedMs,
        activeCooldownDurationSeconds = serverCooldown,
        lastUpdatedEpochMs = nowMs,
      ),
    )
    identityStore.savePendingOtpEmail(email)
    logAudit(accountHash, "otp_requested", "{\"tier\":$nextSendCount}")

    return AuthActionResult.OtpSent(
      email = email,
      cooldownSeconds = serverCooldown,
      totalCooldownDurationSeconds = serverCooldown,
    )
  }

  suspend fun verifyOtp(rawEmail: String, code: String): AuthActionResult {
    val email = rawEmail.trim().lowercase()
    val cleanCode = code.trim()
    val nowMs = System.currentTimeMillis()
    val deviceFp = buildDeviceFingerprint()
    val ipFp = buildIpFingerprint()
    val accountHash = TelefamCoreEngine.hashAccountEmail(email)

    val cutoff15m = nowMs - TelefamCoreEngine.WINDOW_15M_MS
    val acctVerifyHits = dao.countHitsSince("v_acct:$accountHash", cutoff15m)
    val devVerifyHits = dao.countHitsSince("v_dev:$deviceFp", cutoff15m)
    val ipVerifyHits = dao.countHitsSince("v_ip:$ipFp", cutoff15m)
    if (acctVerifyHits >= TelefamCoreEngine.MAX_VERIFY_ATTEMPTS_15M ||
      devVerifyHits >= TelefamCoreEngine.MAX_VERIFY_ATTEMPTS_15M ||
      ipVerifyHits >= TelefamCoreEngine.MAX_VERIFY_ATTEMPTS_15M
    ) {
      logAudit(accountHash, "otp_verify_rate_limited", "{}")
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(429))
    }

    if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(400))
    }

    val api = apiService
      ?: return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(503))

    if (networkTier.value == NetworkTier.OFFLINE) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(503))
    }

    dao.insertHit(RateLimitHitEntity(dimensionKey = "v_acct:$accountHash", timestampMs = nowMs))
    dao.insertHit(RateLimitHitEntity(dimensionKey = "v_dev:$deviceFp", timestampMs = nowMs))
    dao.insertHit(RateLimitHitEntity(dimensionKey = "v_ip:$ipFp", timestampMs = nowMs))

    val resp = executeWithWeakNetworkRetry {
      api.verifyOtp(deviceFp, ipFp, VerifyOtpDto(email, cleanCode))
    }
    if (resp == null || !resp.isSuccessful || resp.body()?.success != true) {
      logAudit(accountHash, "otp_verify_failed", "{}")
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(resp?.code() ?: 503))
    }
    val body = resp.body()!!
    val accessToken = body.sessionToken
    val refreshToken = body.refreshToken.ifBlank { "tfr_${UUID.randomUUID()}" }
    val accessExp = body.accessExpiresInSec
    val refreshExp = body.refreshExpiresInSec

    val existingProfile = dao.getProfileByUserId(accountHash)
    completeSignIn(
      userId = accountHash,
      email = email,
      accessToken = accessToken,
      refreshToken = refreshToken,
      accessExpSec = accessExp,
      refreshExpSec = refreshExp,
      compositeKey = "$accountHash:$deviceFp",
      profileCompleted = existingProfile != null,
    )
    return AuthActionResult.Verified(
      userId = accountHash,
      email = email,
      profileCompleted = existingProfile != null,
    )
  }

  suspend fun verifyMagicLink(token: String, fallbackEmail: String?): AuthActionResult {
    val cleanToken = token.trim()
    if (cleanToken.isBlank()) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(400))
    }
    val api = apiService
      ?: return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(503))
    if (networkTier.value == NetworkTier.OFFLINE) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(503))
    }

    val deviceFp = buildDeviceFingerprint()
    val ipFp = buildIpFingerprint()

    val resp = executeWithWeakNetworkRetry { api.verifyLink(deviceFp, ipFp, VerifyLinkDto(cleanToken)) }
    if (resp == null || !resp.isSuccessful || resp.body()?.success != true) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(resp?.code() ?: 503))
    }
    val body = resp.body()!!
    val accessToken = body.sessionToken
    val refreshToken = body.refreshToken.ifBlank { "tfr_${UUID.randomUUID()}" }
    val resolvedEmail = body.email.ifBlank { fallbackEmail.orEmpty() }
    if (resolvedEmail.isBlank()) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(401))
    }

    val accountHash = TelefamCoreEngine.hashAccountEmail(resolvedEmail)
    val existingProfile = dao.getProfileByUserId(accountHash)
    completeSignIn(
      userId = accountHash,
      email = resolvedEmail,
      accessToken = accessToken,
      refreshToken = refreshToken,
      accessExpSec = body.accessExpiresInSec,
      refreshExpSec = body.refreshExpiresInSec,
      compositeKey = "$accountHash:$deviceFp",
      profileCompleted = existingProfile != null,
    )
    return AuthActionResult.Verified(
      userId = accountHash,
      email = resolvedEmail,
      profileCompleted = existingProfile != null,
    )
  }

  suspend fun authenticateWithGoogle(idToken: String, email: String): AuthActionResult {
    val cleanEmail = email.trim().lowercase()
    if (cleanEmail.isBlank() || idToken.isBlank()) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(400))
    }
    val api = apiService
      ?: return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(503))
    if (networkTier.value == NetworkTier.OFFLINE) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(503))
    }

    val deviceFp = buildDeviceFingerprint()
    val ipFp = buildIpFingerprint()
    val accountHash = TelefamCoreEngine.hashAccountEmail(cleanEmail)

    val resp = executeWithWeakNetworkRetry {
      api.verifyGoogleOAuth(deviceFp, ipFp, OAuthDto(provider = "google", identityToken = idToken))
    }
    if (resp == null || !resp.isSuccessful || resp.body()?.success != true) {
      return AuthActionResult.SanitizedFailure(TelefamCoreEngine.sanitizeServerError(resp?.code() ?: 503))
    }
    val body = resp.body()!!
    val accessToken = body.sessionToken
    val refreshToken = body.refreshToken.ifBlank { "tfr_${UUID.randomUUID()}" }

    val existingProfile = dao.getProfileByUserId(accountHash)
    completeSignIn(
      userId = accountHash,
      email = cleanEmail,
      accessToken = accessToken,
      refreshToken = refreshToken,
      accessExpSec = body.accessExpiresInSec,
      refreshExpSec = body.refreshExpiresInSec,
      compositeKey = "$accountHash:$deviceFp",
      profileCompleted = existingProfile != null,
    )
    return AuthActionResult.Verified(
      userId = accountHash,
      email = cleanEmail,
      profileCompleted = existingProfile != null,
    )
  }

  suspend fun isUsernameAvailable(rawUsername: String, currentUserId: String): Boolean {
    val clean = TelefamProfileSanitizer.sanitizeUsernameInput(rawUsername)
    if (!TelefamProfileSanitizer.isValidUsername(clean)) return false
    val localTaken = dao.countOtherUsersWithUsername(clean, currentUserId) > 0
    if (localTaken) return false
    val api = apiService
    if (api != null && networkTier.value != NetworkTier.OFFLINE) {
      val resp = runCatching { api.checkUsername(clean) }.getOrNull()
      if (resp != null && resp.isSuccessful) {
        return resp.body()?.available == true
      }
    }
    return true
  }

  suspend fun processAndUploadAvatar(
    userId: String,
    sourceBitmap: Bitmap,
    centerX: Float,
    centerY: Float,
    zoomScale: Float,
  ): ProcessedAvatarMedia {
    val processed = MediaUploadProcessor.cropEncodeAndStore(
      context = appContext,
      sourceBitmap = sourceBitmap,
      normalizedCenterX = centerX,
      normalizedCenterY = centerY,
      zoomScale = zoomScale,
    )

    dao.insertMediaUpload(
      MediaUploadEntity(
        mediaId = processed.mediaId,
        ownerUserId = userId,
        storageObjectKey = processed.storageObjectKey,
        mimeType = processed.mimeType,
        sha256Fingerprint = processed.sha256Fingerprint,
        dHashFingerprint = processed.dHashFingerprint,
        byteSize = processed.byteSize,
        localFilePath = processed.localFilePath,
        createdAtMs = System.currentTimeMillis(),
      ),
    )

    val api = apiService
    val accessToken = identityStore.sessionFlow.first().accessToken
    if (api != null && !accessToken.isNullOrBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val bytes = File(processed.localFilePath).readBytes()
      val reqBody = bytes.toRequestBody("image/jpeg".toMediaType())
      runCatching {
        api.uploadAvatar("Bearer $accessToken", buildDeviceFingerprint(), centerX, centerY, zoomScale, reqBody)
      }
    }

    logAudit(
      userId,
      "media_avatar_uploaded",
      "{\"media_id\":\"${processed.mediaId}\",\"dhash\":\"${processed.dHashFingerprint}\"}",
    )
    return processed
  }

  suspend fun saveProfileSetup(
    userId: String,
    email: String,
    fullNameRaw: String,
    usernameRaw: String,
    dialCode: String,
    phoneDigitsRaw: String,
    countryIso: String,
    genderRaw: String,
    dobYear: Int,
    dobMonthZeroBased: Int,
    dobDay: Int,
    locationRaw: String,
    bioRaw: String,
    avatar: ProcessedAvatarMedia?,
  ): ProfileActionResult {
    val fullName = TelefamProfileSanitizer.sanitizePlainText(fullNameRaw, 64, singleLine = true)
    val username = TelefamProfileSanitizer.sanitizeUsernameInput(usernameRaw)
    val phoneDigits = phoneDigitsRaw.filter { it.isDigit() }
    val phoneE164 = "$dialCode$phoneDigits"
    val location = TelefamProfileSanitizer.sanitizePlainText(locationRaw, 80, singleLine = true)
    val bio = TelefamProfileSanitizer.sanitizePlainText(bioRaw, 150, singleLine = false)
    val gender = genderRaw.trim()

    if (fullName.isBlank() || phoneDigits.length < 5 || gender.isBlank() || location.isBlank()) {
      return ProfileActionResult.Error("Please complete all required profile fields.")
    }
    if (!TelefamProfileSanitizer.isValidUsername(username)) {
      return ProfileActionResult.Error("Username must be 3 to 24 letters, numbers, or underscores.")
    }
    if (!TelefamProfileSanitizer.isAtLeast13YearsOld(dobYear, dobMonthZeroBased, dobDay)) {
      logAudit(userId, "profile_age_rejected", "{}")
      return ProfileActionResult.Error("You must be at least 13 years old to use Telefam.")
    }
    if (!isUsernameAvailable(username, userId)) {
      return ProfileActionResult.Error("This username is already taken.")
    }

    val dobIso = String.format("%04d-%02d-%02d", dobYear, dobMonthZeroBased + 1, dobDay)
    val api = apiService
    val accessToken = identityStore.sessionFlow.first().accessToken
    if (api != null && !accessToken.isNullOrBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val resp = executeWithWeakNetworkRetry {
        api.setupProfile(
          bearer = "Bearer $accessToken",
          deviceFingerprint = buildDeviceFingerprint(),
          body = ProfileSetupDto(
            fullName = fullName,
            username = username,
            phoneNumber = phoneE164,
            countryIso = countryIso,
            gender = gender,
            dateOfBirth = dobIso,
            location = location,
            bio = bio,
            avatarMediaId = avatar?.mediaId,
          ),
        )
      }
      if (resp != null && !resp.isSuccessful) {
        return ProfileActionResult.Error(TelefamCoreEngine.sanitizeServerError(resp.code()))
      }
    }

    dao.upsertProfile(
      UserProfileEntity(
        userId = userId,
        email = email,
        fullName = fullName,
        username = username,
        usernameLower = username.lowercase(),
        phoneE164 = phoneE164,
        countryIso = countryIso,
        gender = gender,
        dateOfBirthIso = dobIso,
        location = location,
        bio = bio,
        avatarMediaId = avatar?.mediaId,
        avatarLocalPath = avatar?.localFilePath,
        avatarSha256 = avatar?.sha256Fingerprint,
        avatarDHash = avatar?.dHashFingerprint,
        updatedAtMs = System.currentTimeMillis(),
      ),
    )
    identityStore.markProfileCompleted(true)
    logAudit(userId, "profile_completed", "{\"username\":\"$username\",\"country\":\"$countryIso\"}")
    return ProfileActionResult.Saved
  }

  suspend fun logAudit(userId: String, action: String, metadataJson: String = "{}") {
    val cleanUser = userId.ifBlank { "anonymous" }
    val cleanAction = TelefamProfileSanitizer.sanitizePlainText(action, 64, singleLine = true)
    val deviceFp = buildDeviceFingerprint()
    val ipFp = buildIpFingerprint()
    val nowMs = System.currentTimeMillis()
    dao.insertAuditLog(
      AuditLogEntity(
        userId = cleanUser,
        action = cleanAction,
        deviceFp = deviceFp,
        ipFp = ipFp,
        metadataJson = metadataJson,
        createdAtMs = nowMs,
      ),
    )
    val api = apiService
    val accessToken = identityStore.sessionFlow.first().accessToken
    if (api != null && !accessToken.isNullOrBlank() && networkTier.value != NetworkTier.OFFLINE) {
      runCatching {
        api.recordAuditEvent("Bearer $accessToken", deviceFp, AuditEventDto(cleanAction))
      }
    }
  }

  suspend fun flushOfflineQueue() {
    val tier = networkTier.value
    val batchLimit = TelefamCoreEngine.maxBatchSizeForNetwork(tier)
    if (batchLimit <= 0) return

    val nowMs = System.currentTimeMillis()
    flushPendingContactActions(nowMs, batchLimit)
    flushPendingFeedInteractions(nowMs, batchLimit)
    flushPendingPostUploads(nowMs, batchLimit.coerceAtMost(5))

    val ready = dao.getReadyOfflineActions(nowMs, batchLimit)
    if (ready.isEmpty()) return

    val accessToken = identityStore.sessionFlow.first().accessToken ?: "local-session"
    val deviceFp = buildDeviceFingerprint()
    val api = apiService
    if (api != null) {
      ready.forEach { dao.updateMessageSyncStatus(it.clientOpId, "SYNCING") }
      val dtoList = ready.map {
        SyncOperationDto(
          clientOpId = it.clientOpId,
          roomId = it.circleId,
          kind = it.kind,
          payload = it.payloadJson,
          createdAt = it.createdAtMs,
        )
      }
      val resp = runCatching {
        api.syncBatch("Bearer $accessToken", deviceFp, SyncBatchRequestDto(dtoList))
      }.getOrNull()
      if (resp != null && resp.isSuccessful && resp.body()?.success == true) {
        val acked = resp.body()!!.ackedOpIds
        if (acked.isNotEmpty()) {
          dao.deleteOfflineActions(acked)
          acked.forEach { dao.updateMessageSyncStatus(it, "SYNCED") }
        }
      } else {
        ready.forEach { action ->
          val nextRetry = nowMs + TelefamCoreEngine.computeBackoffMs(action.retryCount + 1, tier)
          dao.scheduleActionRetry(action.clientOpId, action.retryCount + 1, nextRetry)
          dao.updateMessageSyncStatus(action.clientOpId, "QUEUED")
        }
      }
    } else {
      if (tier == NetworkTier.WEAK) delay(400L)
      val acked = ready.map { it.clientOpId }
      dao.deleteOfflineActions(acked)
      acked.forEach { dao.updateMessageSyncStatus(it, "SYNCED") }
    }
  }

  private suspend fun flushPendingContactActions(nowMs: Long, batchLimit: Int) {
    val api = apiService ?: return
    val accessToken = identityStore.sessionFlow.first().accessToken.orEmpty()
    if (accessToken.isBlank()) return
    val ready = dao.getReadyContactSyncActions(nowMs, batchLimit)
    if (ready.isEmpty()) return

    val tier = networkTier.value
    val deviceFp = buildDeviceFingerprint()
    val dtos = ready.map {
      ContactSyncActionDto(
        clientOpId = it.clientOpId,
        targetUserId = it.targetUserId,
        action = if (it.wantFollow) "follow" else "unfollow",
        createdAtMs = it.createdAtMs,
      )
    }
    val resp = runCatching {
      api.syncContactActions("Bearer $accessToken", deviceFp, ContactSyncBatchRequestDto(dtos))
    }.getOrNull()
    if (resp != null && resp.isSuccessful && resp.body()?.success == true) {
      val acked = resp.body()!!.ackedOpIds
      if (acked.isNotEmpty()) {
        dao.deleteContactSyncActions(acked)
      }
    } else {
      ready.forEach { action ->
        val nextRetry = nowMs + TelefamCoreEngine.computeBackoffMs(action.retryCount + 1, tier)
        dao.scheduleContactSyncRetry(action.clientOpId, action.retryCount + 1, nextRetry)
      }
    }
  }

  suspend fun searchSongs(query: String): List<PostSongTrack> {
    val cleanQuery = TelefamProfileSanitizer.sanitizePlainText(query, 80, singleLine = true)
    val api = apiService ?: return emptyList()
    val token = identityStore.sessionFlow.first().accessToken.orEmpty()
    if (token.isBlank() || networkTier.value == NetworkTier.OFFLINE) return emptyList()
    val deviceFp = buildDeviceFingerprint()
    val resp = executeWithWeakNetworkRetry {
      api.searchSongs("Bearer $token", deviceFp, cleanQuery)
    } ?: return emptyList()
    val body = if (resp.isSuccessful) resp.body() else null
    if (body == null || !body.success) return emptyList()
    return body.items.map { dto ->
      PostSongTrack(
        trackId = dto.trackId,
        title = dto.title,
        artist = dto.artist,
        album = dto.album,
        artworkUrl = dto.artworkUrl,
        previewUrl = dto.previewUrl,
        durationMs = dto.durationMs,
        source = dto.source,
      )
    }
  }

  suspend fun searchHashtags(query: String): List<HashtagItem> {
    val cleanTag = TelefamPostRules.sanitizeHashtag(query)
    val api = apiService
    val token = identityStore.sessionFlow.first().accessToken.orEmpty()
    if (api != null && token.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val deviceFp = buildDeviceFingerprint()
      val resp = executeWithWeakNetworkRetry {
        api.searchHashtags("Bearer $token", deviceFp, cleanTag)
      }
      val body = if (resp != null && resp.isSuccessful) resp.body() else null
      if (body != null && body.success) {
        val nowMs = System.currentTimeMillis()
        val entities = body.items
          .filter { it.postCount > 0L }
          .map { CachedHashtagEntity(tag = it.tag, postCount = it.postCount, updatedAtMs = nowMs) }
        if (entities.isNotEmpty()) {
          dao.upsertCachedHashtags(entities)
        }
        return body.items.map { HashtagItem(tag = it.tag, postCount = it.postCount) }
      }
    }

    val cached = dao.searchCachedHashtags(cleanTag).map { HashtagItem(it.tag, it.postCount) }
    return if (cleanTag.isNotBlank() && cached.none { it.tag == cleanTag }) {
      cached + HashtagItem(cleanTag, 0L)
    } else {
      cached
    }
  }

  suspend fun compressPostPhotos(uris: List<Uri>, existingCount: Int): List<ProcessedPostPhoto> =
    withContext(Dispatchers.IO) {
      val remainingSlots = (TelefamPostRules.MAX_PHOTOS_COUNT - existingCount).coerceAtLeast(0)
      if (remainingSlots <= 0) return@withContext emptyList()
      uris.take(remainingSlots).mapNotNull { uri ->
        MediaUploadProcessor.compressPostPhoto(appContext, uri)
      }
    }

  suspend fun preparePostVideo(uri: Uri): VideoPrepareResult = withContext(Dispatchers.IO) {
    MediaUploadProcessor.inspectAndExtractVideoFrames(appContext, uri)
  }

  suspend fun submitOrQueuePost(
    input: PostSubmissionInput,
    onProgress: (Int) -> Unit = {},
  ): PostPublishResult {
    val cleanText = TelefamProfileSanitizer.sanitizePlainText(input.textContent, TelefamPostRules.MAX_TEXT_POST_CHARS, singleLine = false)
    val cleanCaption = TelefamProfileSanitizer.sanitizePlainText(input.caption, TelefamPostRules.MAX_CAPTION_CHARS, singleLine = false)
    val cleanHashtags = input.hashtags.mapNotNull { TelefamPostRules.sanitizeHashtag(it).ifBlank { null } }
      .distinct().take(TelefamPostRules.MAX_HASHTAGS)
    val cleanTagged = input.taggedUserIds.distinct().take(TelefamPostRules.MAX_TAGGED_PEOPLE)

    if (input.mode == PostMode.TEXT && cleanText.isBlank()) {
      return PostPublishResult.Rejected("Write something to share")
    }
    if (input.mode == PostMode.PHOTOS && input.localPhotoPaths.isEmpty()) {
      return PostPublishResult.Rejected("Select up to 10 photos")
    }
    if (input.mode == PostMode.VIDEO && input.localVideoPath.isNullOrBlank()) {
      return PostPublishResult.Rejected("Select a video to share")
    }

    val nowMs = System.currentTimeMillis()
    val clientOpId = "post_${UUID.randomUUID()}"
    val songDto = input.song?.let {
      SongTrackDto(
        trackId = it.trackId,
        title = it.title,
        artist = it.artist,
        album = it.album,
        artworkUrl = it.artworkUrl,
        previewUrl = it.previewUrl,
        durationMs = it.durationMs,
        source = it.source,
      )
    }

    val entity = PendingPostUploadEntity(
      clientOpId = clientOpId,
      mode = input.mode.apiValue,
      textContent = cleanText,
      caption = cleanCaption,
      themeId = input.themeId,
      fontId = input.fontId,
      songJson = songDto?.let { songAdapter.toJson(it) },
      taggedUserIdsJson = stringListAdapter.toJson(cleanTagged),
      hashtagsJson = stringListAdapter.toJson(cleanHashtags),
      localPhotoPathsJson = stringListAdapter.toJson(input.localPhotoPaths),
      localVideoPath = input.localVideoPath,
      localThumbnailPath = input.localThumbnailPath,
      frameHashesJson = stringListAdapter.toJson(input.frameHashes),
      durationMs = input.durationMs,
      trimStartMs = input.trimStartMs,
      trimEndMs = input.trimEndMs,
      width = input.width,
      height = input.height,
      muteOriginalAudio = input.muteOriginalAudio,
      contentId = input.contentId,
      status = "QUEUED",
      progressPct = 0,
      retryCount = 0,
      nextRetryAtMs = nowMs,
      createdAtMs = nowMs,
    )
    dao.upsertPendingPostUpload(entity)

    val session = identityStore.sessionFlow.first()
    val userId = session.userId.orEmpty()
    val accessToken = session.accessToken.orEmpty()
    val api = apiService
    if (api == null || accessToken.isBlank() || networkTier.value == NetworkTier.OFFLINE) {
      if (userId.isNotBlank()) {
        logAudit(userId, "post_queued_offline", "{\"mode\":\"${input.mode.apiValue}\"}")
      }
      return PostPublishResult.QueuedOffline(clientOpId)
    }

    return executePendingPostUpload(entity, api, accessToken, userId, onProgress)
  }

  private suspend fun flushPendingPostUploads(nowMs: Long, batchLimit: Int) {
    val api = apiService ?: return
    val session = identityStore.sessionFlow.first()
    val token = session.accessToken.orEmpty()
    val userId = session.userId.orEmpty()
    if (token.isBlank()) return

    val ready = dao.getReadyPendingPostUploads(nowMs, batchLimit)
    for (item in ready) {
      if (networkTier.value == NetworkTier.OFFLINE) break
      executePendingPostUpload(item, api, token, userId)
    }
  }

  private suspend fun executePendingPostUpload(
    entity: PendingPostUploadEntity,
    api: TelefamApiService,
    accessToken: String,
    userId: String,
    onProgress: (Int) -> Unit = {},
  ): PostPublishResult {
    val deviceFp = buildDeviceFingerprint()
    val bearer = "Bearer $accessToken"
    val photoPaths = runCatching { stringListAdapter.fromJson(entity.localPhotoPathsJson) }.getOrNull().orEmpty()
    val taggedIds = runCatching { stringListAdapter.fromJson(entity.taggedUserIdsJson) }.getOrNull().orEmpty()
    val hashtags = runCatching { stringListAdapter.fromJson(entity.hashtagsJson) }.getOrNull().orEmpty()
    val frameHashes = runCatching { stringListAdapter.fromJson(entity.frameHashesJson) }.getOrNull().orEmpty()
    val songDto = entity.songJson?.let { runCatching { songAdapter.fromJson(it) }.getOrNull() }

    dao.updatePendingPostUploadProgress(entity.clientOpId, "UPLOADING", 2)
    onProgress(2)

    val uploadedPhotoIds = mutableListOf<String>()
    var uploadedVideoMediaId = ""
    var uploadedThumbMediaId = ""
    var verifiedContentId = entity.contentId

    when (entity.mode) {
      PostMode.PHOTOS.apiValue -> {
        val total = photoPaths.size.coerceAtLeast(1)
        for ((index, path) in photoPaths.withIndex()) {
          val file = File(path)
          if (!file.exists()) continue
          val reqBody = ProgressFileRequestBody(file, "image/jpeg".toMediaType()) { frac ->
            val overall = (((index + frac) / total.toFloat()) * 85f).toInt().coerceIn(5, 88)
            onProgress(overall)
          }
          val resp = runCatching { api.uploadPostPhoto(bearer, deviceFp, reqBody) }.getOrNull()
          val body = if (resp != null && resp.isSuccessful) resp.body() else null
          if (body == null || !body.success || body.mediaId.isBlank()) {
            return scheduleRetryForPendingPost(entity)
          }
          uploadedPhotoIds += body.mediaId
          if (verifiedContentId.isBlank()) verifiedContentId = body.contentId
          val pct = (((index + 1).toFloat() / total.toFloat()) * 88f).toInt().coerceIn(10, 90)
          dao.updatePendingPostUploadProgress(entity.clientOpId, "UPLOADING", pct)
          onProgress(pct)
        }
        if (uploadedPhotoIds.isEmpty()) {
          dao.deletePendingPostUpload(entity.clientOpId)
          return PostPublishResult.Rejected("Photos no longer available")
        }
      }

      PostMode.VIDEO.apiValue -> {
        val videoFile = entity.localVideoPath?.let { File(it) }
        if (videoFile == null || !videoFile.exists()) {
          dao.deletePendingPostUpload(entity.clientOpId)
          return PostPublishResult.Rejected("Video file unavailable")
        }
        val videoReqBody = ProgressFileRequestBody(videoFile, "video/mp4".toMediaType()) { frac ->
          val pct = (frac * 90f).toInt().coerceIn(3, 92)
          onProgress(pct)
        }
        val videoPart = MultipartBody.Part.createFormData("video", "media.mp4", videoReqBody)
        val thumbPart = entity.localThumbnailPath
          ?.takeIf { it.isNotBlank() && File(it).exists() }
          ?.let { path ->
            val tf = File(path)
            MultipartBody.Part.createFormData(
              "thumbnail",
              "thumb.jpg",
              ProgressFileRequestBody(tf, "image/jpeg".toMediaType()) {},
            )
          }
        val resp = runCatching {
          api.uploadPostVideo(
            bearer = bearer,
            deviceFingerprint = deviceFp,
            frameHashesCsv = frameHashes.joinToString(","),
            durationMs = entity.durationMs,
            trimStartMs = entity.trimStartMs,
            trimEndMs = entity.trimEndMs,
            width = entity.width,
            height = entity.height,
            videoPart = videoPart,
            thumbnailPart = thumbPart,
          )
        }.getOrNull()
        val body = if (resp != null && resp.isSuccessful) resp.body() else null
        if (body == null || !body.success || body.mediaId.isBlank()) {
          return scheduleRetryForPendingPost(entity)
        }
        uploadedVideoMediaId = body.mediaId
        uploadedThumbMediaId = body.thumbnailMediaId
        if (body.contentId.isNotBlank()) verifiedContentId = body.contentId
        dao.updatePendingPostUploadProgress(entity.clientOpId, "UPLOADING", 95)
        onProgress(95)
      }
    }

    val createResp = executeWithWeakNetworkRetry {
      api.createPost(
        bearer = bearer,
        deviceFingerprint = deviceFp,
        body = CreatePostRequestDto(
          clientOpId = entity.clientOpId,
          mode = entity.mode,
          textContent = entity.textContent,
          caption = entity.caption,
          themeId = entity.themeId,
          fontId = entity.fontId,
          song = songDto,
          taggedUserIds = taggedIds,
          hashtags = hashtags,
          photoMediaIds = uploadedPhotoIds,
          videoMediaId = uploadedVideoMediaId,
          thumbnailMediaId = uploadedThumbMediaId,
          trimStartMs = entity.trimStartMs,
          trimEndMs = entity.trimEndMs,
          muteOriginalAudio = entity.muteOriginalAudio,
          contentId = verifiedContentId,
        ),
      )
    }
    val createBody = if (createResp != null && createResp.isSuccessful) createResp.body() else null
    if (createBody == null || !createBody.success || createBody.post == null) {
      return scheduleRetryForPendingPost(entity)
    }

    val nowMs = System.currentTimeMillis()
    hashtags.forEach { tag ->
      val existing = dao.searchCachedHashtags(tag).firstOrNull { it.tag == tag }
      if (existing != null) {
        dao.incrementCachedHashtagCount(tag, nowMs)
      } else {
        dao.upsertCachedHashtags(listOf(CachedHashtagEntity(tag = tag, postCount = 1L, updatedAtMs = nowMs)))
      }
    }
    dao.deletePendingPostUpload(entity.clientOpId)
    onProgress(100)

    if (userId.isNotBlank()) {
      logAudit(
        userId,
        "post_created",
        "{\"post_id\":\"${createBody.post.postId}\",\"mode\":\"${entity.mode}\",\"content_id\":\"$verifiedContentId\"}",
      )
    }

    // Surface newly created post into local feed cache immediately
    val myProfile = dao.getProfileByUserId(userId)
    dao.upsertFeedPosts(
      listOf(
        CachedFeedPostEntity(
          postId = createBody.post.postId,
          authorUserId = userId,
          authorUsername = myProfile?.username ?: "me",
          authorFullName = myProfile?.fullName ?: "Me",
          authorAvatarUrl = myProfile?.avatarLocalPath,
          isFollowingAuthor = false,
          isOwner = true,
          mode = entity.mode,
          textContent = entity.textContent,
          caption = entity.caption,
          themeId = entity.themeId,
          fontId = entity.fontId,
          songJson = entity.songJson,
          hashtagsJson = entity.hashtagsJson,
          photoUrlsJson = entity.localPhotoPathsJson,
          videoUrl = entity.localVideoPath.orEmpty(),
          thumbnailUrl = entity.localThumbnailPath.orEmpty(),
          renditionsJson = "[]",
          width = entity.width,
          height = entity.height,
          durationMs = entity.durationMs,
          muteOriginalAudio = entity.muteOriginalAudio,
          contentId = verifiedContentId,
          likesCount = 0L,
          commentsCount = 0L,
          sharesCount = 0L,
          favouritesCount = 0L,
          repostsCount = 0L,
          isLiked = false,
          isFavourited = false,
          isReposted = false,
          repostedByUsername = "",
          recommendationLabel = "",
          relevanceScore = 10.0,
          tabKey = FeedTab.FOR_YOU.apiParam,
          sortOrder = 0,
          isHiddenByUser = false,
          createdAtMs = createBody.post.createdAtMs.takeIf { it > 0L } ?: nowMs,
          cachedAtMs = nowMs,
        ),
      ),
    )

    return PostPublishResult.Uploaded(
      postId = createBody.post.postId,
      contentId = verifiedContentId,
    )
  }

  // Feeds loading, dynamic search, offline interactions queue, and owner edit/delete
  suspend fun loadFeedPage(
    tab: FeedTab,
    page: Int,
    pageSize: Int = 15,
  ): FeedPageFetchResult {
    val cleanPage = page.coerceAtLeast(1)
    val session = identityStore.sessionFlow.first()
    val token = session.accessToken.orEmpty()
    val api = apiService
    val nowMs = System.currentTimeMillis()

    if (api != null && token.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val deviceFp = buildDeviceFingerprint()
      val resp = executeWithWeakNetworkRetry {
        api.getFeeds(
          bearer = "Bearer $token",
          deviceFingerprint = deviceFp,
          tab = tab.apiParam,
          page = cleanPage,
          pageSize = pageSize,
        )
      }
      val body = if (resp != null && resp.isSuccessful) resp.body() else null
      if (body != null && body.success) {
        val entities = body.items.mapIndexed { idx, dto ->
          dto.toCachedEntity(
            tabKey = tab.apiParam,
            sortOrder = ((cleanPage - 1) * pageSize) + idx,
            nowMs = nowMs,
          )
        }
        if (entities.isNotEmpty()) {
          dao.upsertFeedPosts(entities)
        }
        return FeedPageFetchResult(
          page = body.page.coerceAtLeast(cleanPage),
          hasMore = body.hasMore,
          fromCache = false,
          items = entities,
        )
      }
    }

    val cached = dao.observeFeedPosts(tab.apiParam).first()
    val offset = (cleanPage - 1) * pageSize
    val slice = if (offset >= cached.size) emptyList() else cached.subList(offset, minOf(cached.size, offset + pageSize))
    return FeedPageFetchResult(
      page = cleanPage,
      hasMore = offset + slice.size < cached.size,
      fromCache = true,
      items = slice,
    )
  }

  suspend fun searchFeeds(
    query: String,
    shortcut: String,
    page: Int = 1,
    pageSize: Int = 24,
  ): FeedSearchFetchResult {
    val cleanQuery = TelefamProfileSanitizer.sanitizePlainText(query, 80, singleLine = true)
    val cleanShortcut = TelefamPostRules.sanitizeHashtag(shortcut)
    val cleanPage = page.coerceAtLeast(1)
    val session = identityStore.sessionFlow.first()
    val token = session.accessToken.orEmpty()
    val api = apiService
    val nowMs = System.currentTimeMillis()

    if (api != null && token.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val deviceFp = buildDeviceFingerprint()
      val resp = executeWithWeakNetworkRetry {
        api.searchFeeds(
          bearer = "Bearer $token",
          deviceFingerprint = deviceFp,
          query = cleanQuery,
          shortcut = cleanShortcut,
          page = cleanPage,
          pageSize = pageSize,
        )
      }
      val body = if (resp != null && resp.isSuccessful) resp.body() else null
      if (body != null && body.success) {
        val shortcuts = body.shortcuts.map { FeedSearchShortcut(it.key, it.label, it.count) }
        val entities = body.items.mapIndexed { idx, dto ->
          dto.toCachedEntity(
            tabKey = FeedTab.FOR_YOU.apiParam,
            sortOrder = ((cleanPage - 1) * pageSize) + idx,
            nowMs = nowMs,
          )
        }
        if (entities.isNotEmpty()) {
          dao.upsertFeedPosts(entities)
        }
        return FeedSearchFetchResult(
          page = body.page.coerceAtLeast(cleanPage),
          hasMore = body.hasMore,
          shortcuts = shortcuts,
          items = entities,
        )
      }
    }

    // Local fallback across cached feed posts + hashtag counts
    val cachedPosts = dao.observeAllVisibleFeedPosts().first()
    val qLower = cleanQuery.lowercase()
    val tagCounts = mutableMapOf<String, Long>()
    val matched = cachedPosts.filter { post ->
      val tags = parseStringList(post.hashtagsJson)
      tags.forEach { t -> tagCounts[t] = (tagCounts[t] ?: 0L) + 1L }
      val matchesShortcut = cleanShortcut.isBlank() || tags.any { it.equals(cleanShortcut, ignoreCase = true) }
      val matchesQuery = qLower.isBlank() ||
        post.caption.lowercase().contains(qLower) ||
        post.textContent.lowercase().contains(qLower) ||
        post.authorUsername.lowercase().contains(qLower) ||
        post.authorFullName.lowercase().contains(qLower) ||
        tags.any { it.lowercase().contains(qLower) } ||
        (post.songJson?.lowercase()?.contains(qLower) == true)
      matchesShortcut && matchesQuery
    }
    val shortcuts = tagCounts.entries
      .sortedByDescending { it.value }
      .take(10)
      .map { FeedSearchShortcut(key = it.key, label = "#${it.key}", count = it.value) }
    val offset = (cleanPage - 1) * pageSize
    val slice = if (offset >= matched.size) emptyList() else matched.subList(offset, minOf(matched.size, offset + pageSize))
    return FeedSearchFetchResult(
      page = cleanPage,
      hasMore = offset + slice.size < matched.size,
      shortcuts = shortcuts,
      items = slice,
    )
  }

  suspend fun performFeedInteraction(
    postId: String,
    action: FeedInteractionAction,
    watchMs: Long = 0L,
    durationMs: Long = 0L,
    reason: String = "",
    details: String = "",
  ) {
    val existing = dao.getFeedPost(postId)
    val nowMs = System.currentTimeMillis()
    val session = identityStore.sessionFlow.first()
    val viewerId = session.userId.orEmpty()
    val myProfile = if (viewerId.isNotBlank()) dao.getProfileByUserId(viewerId) else null

    // Optimistic update in Room first
    if (existing != null) {
      val updated = when (action) {
        FeedInteractionAction.LIKE -> if (!existing.isLiked) {
          existing.copy(isLiked = true, likesCount = existing.likesCount + 1L)
        } else existing
        FeedInteractionAction.UNLIKE -> if (existing.isLiked) {
          existing.copy(isLiked = false, likesCount = (existing.likesCount - 1L).coerceAtLeast(0L))
        } else existing
        FeedInteractionAction.FAVOURITE -> if (!existing.isFavourited) {
          existing.copy(isFavourited = true, favouritesCount = existing.favouritesCount + 1L)
        } else existing
        FeedInteractionAction.UNFAVOURITE -> if (existing.isFavourited) {
          existing.copy(isFavourited = false, favouritesCount = (existing.favouritesCount - 1L).coerceAtLeast(0L))
        } else existing
        FeedInteractionAction.REPOST -> if (!existing.isReposted) {
          existing.copy(
            isReposted = true,
            repostsCount = existing.repostsCount + 1L,
            repostedByUsername = myProfile?.username ?: "you",
          )
        } else existing
        FeedInteractionAction.UNREPOST -> if (existing.isReposted) {
          existing.copy(
            isReposted = false,
            repostsCount = (existing.repostsCount - 1L).coerceAtLeast(0L),
            repostedByUsername = "",
          )
        } else existing
        FeedInteractionAction.SHARE -> existing.copy(sharesCount = existing.sharesCount + 1L)
        FeedInteractionAction.NOT_INTERESTED -> existing.copy(isHiddenByUser = true)
        FeedInteractionAction.BLOCK -> existing
        FeedInteractionAction.REPORT, FeedInteractionAction.WATCH, FeedInteractionAction.SKIP -> existing
      }
      if (action == FeedInteractionAction.BLOCK && existing.authorUserId.isNotBlank()) {
        dao.hideFeedPostsByAuthor(existing.authorUserId)
      } else if (action == FeedInteractionAction.NOT_INTERESTED) {
        dao.hideFeedPost(postId)
      } else {
        dao.upsertFeedPosts(listOf(updated))
      }
    }

    val cleanReason = TelefamProfileSanitizer.sanitizePlainText(reason, 64, singleLine = true)
    val cleanDetails = TelefamProfileSanitizer.sanitizePlainText(details, 300, singleLine = false)
    val clientOpId = "fi_${UUID.randomUUID()}"

    val api = apiService
    val token = session.accessToken.orEmpty()
    if (api != null && token.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val deviceFp = buildDeviceFingerprint()
      val resp = executeWithWeakNetworkRetry {
        api.interactFeed(
          bearer = "Bearer $token",
          deviceFingerprint = deviceFp,
          body = FeedInteractionRequestDto(
            clientOpId = clientOpId,
            postId = postId,
            action = action.apiValue,
            watchMs = watchMs,
            durationMs = durationMs,
            reason = cleanReason,
            details = cleanDetails,
          ),
        )
      }
      val body = if (resp != null && resp.isSuccessful) resp.body() else null
      if (body != null && body.success) {
        val current = dao.getFeedPost(postId)
        if (current != null && !current.isHiddenByUser) {
          dao.upsertFeedPosts(
            listOf(
              current.copy(
                likesCount = body.likesCount,
                favouritesCount = body.favouritesCount,
                repostsCount = body.repostsCount,
                sharesCount = body.sharesCount,
                isLiked = body.isLiked,
                isFavourited = body.isFavourited,
                isReposted = body.isReposted,
              ),
            ),
          )
        }
        return
      }
    }

    // Queue offline for automatic replay when connectivity returns
    dao.enqueueFeedInteraction(
      PendingFeedInteractionEntity(
        clientOpId = clientOpId,
        postId = postId,
        action = action.apiValue,
        watchMs = watchMs,
        durationMs = durationMs,
        reason = cleanReason,
        details = cleanDetails,
        createdAtMs = nowMs,
        retryCount = 0,
        nextRetryAtMs = nowMs,
      ),
    )
  }

  suspend fun toggleFollowAuthorFromFeed(authorUserId: String, currentlyFollowing: Boolean): ContactToggleResult {
    val session = identityStore.sessionFlow.first()
    val userId = session.userId.orEmpty()
    if (userId.isBlank() || authorUserId.isBlank() || authorUserId == userId) {
      return ContactToggleResult.Synced
    }
    if (dao.getCachedContact(authorUserId) != null) {
      return toggleContactFollow(authorUserId)
    }

    val nowMs = System.currentTimeMillis()
    val deviceFp = buildDeviceFingerprint()
    val cutoff1m = nowMs - TelefamContactsRules.ONE_MINUTE_MS
    val acctHits = dao.countHitsSince("contact_act:$userId", cutoff1m)
    if (acctHits >= TelefamContactsRules.MAX_ACTIONS_PER_MINUTE) {
      return ContactToggleResult.RateLimited(TelefamContactsRules.RATE_LIMIT_TOAST_MESSAGE)
    }

    val wantFollow = !currentlyFollowing
    dao.insertHit(RateLimitHitEntity(dimensionKey = "contact_act:$userId", timestampMs = nowMs))
    dao.updateFeedAuthorFollowing(authorUserId, wantFollow)

    val api = apiService
    val token = session.accessToken.orEmpty()
    if (api != null && token.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val resp = executeWithWeakNetworkRetry {
        val body = ContactActionDto(targetUserId = authorUserId)
        if (wantFollow) api.followContact("Bearer $token", deviceFp, body)
        else api.unfollowContact("Bearer $token", deviceFp, body)
      }
      if (resp?.code() == 429) {
        dao.updateFeedAuthorFollowing(authorUserId, currentlyFollowing)
        return ContactToggleResult.RateLimited(TelefamContactsRules.RATE_LIMIT_TOAST_MESSAGE)
      }
      if (resp != null && resp.isSuccessful && resp.body()?.success == true) {
        dao.updateFeedAuthorFollowing(authorUserId, resp.body()!!.isFollowing)
        return ContactToggleResult.Synced
      }
    }

    dao.deletePendingContactSyncForTarget(authorUserId)
    dao.enqueueContactSyncAction(
      ContactSyncActionEntity(
        clientOpId = "cst_${UUID.randomUUID()}",
        targetUserId = authorUserId,
        wantFollow = wantFollow,
        createdAtMs = nowMs,
        retryCount = 0,
        nextRetryAtMs = nowMs,
      ),
    )
    return ContactToggleResult.QueuedOffline
  }

  suspend fun updateOwnerPost(
    postId: String,
    textContent: String,
    caption: String,
    themeId: String,
    fontId: String,
    song: PostSongTrack?,
    taggedUserIds: List<String>,
    hashtags: List<String>,
    muteOriginalAudio: Boolean,
  ): PostPublishResult {
    val cleanText = TelefamProfileSanitizer.sanitizePlainText(textContent, TelefamPostRules.MAX_TEXT_POST_CHARS, singleLine = false)
    val cleanCaption = TelefamProfileSanitizer.sanitizePlainText(caption, TelefamPostRules.MAX_CAPTION_CHARS, singleLine = false)
    val cleanTags = hashtags.mapNotNull { TelefamPostRules.sanitizeHashtag(it).ifBlank { null } }
      .distinct().take(TelefamPostRules.MAX_HASHTAGS)
    val cleanTagged = taggedUserIds.distinct().take(TelefamPostRules.MAX_TAGGED_PEOPLE)

    val songDto = song?.let {
      SongTrackDto(
        trackId = it.trackId,
        title = it.title,
        artist = it.artist,
        album = it.album,
        artworkUrl = it.artworkUrl,
        previewUrl = it.previewUrl,
        durationMs = it.durationMs,
        source = it.source,
      )
    }

    val existing = dao.getFeedPost(postId)
    if (existing != null) {
      dao.upsertFeedPosts(
        listOf(
          existing.copy(
            textContent = cleanText,
            caption = cleanCaption,
            themeId = themeId,
            fontId = fontId,
            songJson = songDto?.let { songAdapter.toJson(it) },
            hashtagsJson = stringListAdapter.toJson(cleanTags),
            muteOriginalAudio = muteOriginalAudio,
            cachedAtMs = System.currentTimeMillis(),
          ),
        ),
      )
    }

    val api = apiService
    val token = identityStore.sessionFlow.first().accessToken.orEmpty()
    if (api != null && token.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val deviceFp = buildDeviceFingerprint()
      val resp = executeWithWeakNetworkRetry {
        api.updatePost(
          bearer = "Bearer $token",
          deviceFingerprint = deviceFp,
          postId = postId,
          body = UpdatePostRequestDto(
            textContent = cleanText,
            caption = cleanCaption,
            themeId = themeId,
            fontId = fontId,
            song = songDto,
            taggedUserIds = cleanTagged,
            hashtags = cleanTags,
            muteOriginalAudio = muteOriginalAudio,
          ),
        )
      }
      if (resp != null && !resp.isSuccessful) {
        return PostPublishResult.Rejected(TelefamCoreEngine.sanitizeServerError(resp.code()))
      }
    }
    return PostPublishResult.Uploaded(postId = postId, contentId = existing?.contentId.orEmpty())
  }

  suspend fun deleteOwnerPost(postId: String): Boolean {
    dao.deleteFeedPost(postId)
    val api = apiService
    val token = identityStore.sessionFlow.first().accessToken.orEmpty()
    if (api != null && token.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val deviceFp = buildDeviceFingerprint()
      val resp = executeWithWeakNetworkRetry {
        api.deletePost("Bearer $token", deviceFp, postId)
      }
      if (resp != null && resp.code() == 403) return false
    }
    val uid = identityStore.sessionFlow.first().userId.orEmpty()
    if (uid.isNotBlank()) {
      logAudit(uid, "post_deleted", "{\"post_id\":\"$postId\"}")
    }
    return true
  }

  private suspend fun flushPendingFeedInteractions(nowMs: Long, batchLimit: Int) {
    val api = apiService ?: return
    val token = identityStore.sessionFlow.first().accessToken.orEmpty()
    if (token.isBlank()) return
    val ready = dao.getReadyFeedInteractions(nowMs, batchLimit)
    if (ready.isEmpty()) return

    val tier = networkTier.value
    val deviceFp = buildDeviceFingerprint()
    val ops = ready.map { item ->
      FeedInteractionRequestDto(
        clientOpId = item.clientOpId,
        postId = item.postId,
        action = item.action,
        watchMs = item.watchMs,
        durationMs = item.durationMs,
        reason = item.reason,
        details = item.details,
      )
    }
    val resp = runCatching {
      api.interactFeedBatch("Bearer $token", deviceFp, FeedInteractionBatchRequestDto(ops))
    }.getOrNull()
    if (resp != null && resp.isSuccessful && resp.body()?.success == true) {
      val acked = resp.body()!!.ackedOpIds
      if (acked.isNotEmpty()) {
        dao.deleteFeedInteractions(acked)
      }
    } else {
      ready.forEach { item ->
        val nextRetry = nowMs + TelefamCoreEngine.computeBackoffMs(item.retryCount + 1, tier)
        dao.scheduleFeedInteractionRetry(item.clientOpId, item.retryCount + 1, nextRetry)
      }
    }
  }

  fun parseStringList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching { stringListAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
  }

  fun parseSongTrack(json: String?): PostSongTrack? {
    if (json.isNullOrBlank()) return null
    val dto = runCatching { songAdapter.fromJson(json) }.getOrNull() ?: return null
    return PostSongTrack(
      trackId = dto.trackId,
      title = dto.title,
      artist = dto.artist,
      album = dto.album,
      artworkUrl = dto.artworkUrl,
      previewUrl = dto.previewUrl,
      durationMs = dto.durationMs,
      source = dto.source,
    )
  }

  fun parseRenditions(json: String?): List<FeedVideoRendition> {
    if (json.isNullOrBlank()) return emptyList()
    val list = runCatching { renditionsListAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
    return list.map {
      FeedVideoRendition(
        qualityLabel = it.qualityLabel,
        width = it.width,
        height = it.height,
        bitrateKbps = it.bitrateKbps,
        url = it.url,
        byteSize = it.byteSize,
      )
    }
  }

  private fun FeedItemDto.toCachedEntity(
    tabKey: String,
    sortOrder: Int,
    nowMs: Long,
  ): CachedFeedPostEntity {
    return CachedFeedPostEntity(
      postId = postId,
      authorUserId = authorUserId,
      authorUsername = authorUsername,
      authorFullName = authorFullName,
      authorAvatarUrl = authorAvatarUrl.ifBlank { null },
      isFollowingAuthor = isFollowingAuthor,
      isOwner = isOwner,
      mode = mode,
      textContent = textContent,
      caption = caption,
      themeId = themeId,
      fontId = fontId,
      songJson = song?.let { songAdapter.toJson(it) },
      hashtagsJson = stringListAdapter.toJson(hashtags),
      photoUrlsJson = stringListAdapter.toJson(photoUrls),
      videoUrl = videoUrl,
      thumbnailUrl = thumbnailUrl,
      renditionsJson = renditionsListAdapter.toJson(renditions),
      width = width,
      height = height,
      durationMs = durationMs,
      muteOriginalAudio = muteOriginalAudio,
      contentId = contentId,
      likesCount = likesCount,
      commentsCount = commentsCount,
      sharesCount = sharesCount,
      favouritesCount = favouritesCount,
      repostsCount = repostsCount,
      isLiked = isLiked,
      isFavourited = isFavourited,
      isReposted = isReposted,
      repostedByUsername = repostedByUsername,
      recommendationLabel = recommendationLabel,
      relevanceScore = relevanceScore,
      tabKey = tabKey,
      sortOrder = sortOrder,
      isHiddenByUser = false,
      createdAtMs = createdAtMs,
      cachedAtMs = nowMs,
    )
  }

  private suspend fun scheduleRetryForPendingPost(entity: PendingPostUploadEntity): PostPublishResult.QueuedOffline {
    val tier = networkTier.value
    val nextRetry = System.currentTimeMillis() + TelefamCoreEngine.computeBackoffMs(entity.retryCount + 1, tier)
    dao.schedulePendingPostRetry(entity.clientOpId, "QUEUED", entity.retryCount + 1, nextRetry)
    return PostPublishResult.QueuedOffline(entity.clientOpId)
  }

  private suspend fun cacheContactAvatarsIfNeeded(
    api: TelefamApiService,
    contacts: List<CachedContactEntity>,
  ) {
    val cacheDir = File(appContext.filesDir, "telefam_media/contacts_avatars").apply {
      if (!exists()) mkdirs()
    }
    for (contact in contacts) {
      val remoteUrl = contact.avatarUrl ?: continue
      val existingPath = contact.avatarLocalPath
      if (!existingPath.isNullOrBlank() && File(existingPath).exists()) continue
      val safeId = contact.userId.replace(Regex("[^A-Za-z0-9_-]"), "_")
      val targetFile = File(cacheDir, "$safeId.jpg")
      if (targetFile.exists() && targetFile.length() > 0L) {
        dao.updateCachedContactAvatarPath(contact.userId, targetFile.absolutePath)
        continue
      }
      runCatching {
        val response = api.downloadMedia(remoteUrl)
        if (response.isSuccessful) {
          val bytes = response.body()?.bytes()
          if (bytes != null && bytes.isNotEmpty() && MediaUploadProcessor.isSupportedImageMagicBytes(bytes)) {
            targetFile.writeBytes(bytes)
            dao.updateCachedContactAvatarPath(contact.userId, targetFile.absolutePath)
          }
        }
      }
    }
  }

  private fun filterAndSortContacts(
    items: List<CachedContactEntity>,
    filter: ContactFilterTab,
    query: String,
    excludeUserId: String,
  ): List<CachedContactEntity> {
    val cleanQuery = query.trim().lowercase()
    val filtered = items.asSequence()
      .filter { it.userId != excludeUserId }
      .filter { item ->
        when (filter) {
          ContactFilterTab.ALL -> true
          ContactFilterTab.SUGGESTIONS -> !item.isFollowing && item.riskScore < 85.0
          ContactFilterTab.FOLLOWERS -> item.followsYou
          ContactFilterTab.FOLLOWING -> item.isFollowing
          ContactFilterTab.FRIENDS -> item.isFollowing && item.followsYou
        }
      }
      .filter { item ->
        cleanQuery.isBlank() ||
          item.fullName.lowercase().contains(cleanQuery) ||
          item.usernameLower.contains(cleanQuery)
      }
      .toList()

    return when (filter) {
      ContactFilterTab.SUGGESTIONS -> filtered.sortedWith(
        compareByDescending<CachedContactEntity> { it.followsYou }
          .thenBy { it.riskScore }
          .thenBy { it.fullName.lowercase() },
      )
      else -> filtered.sortedBy { it.fullName.lowercase() }
    }
  }

  suspend fun fetchUserProfile(userId: String): UserProfileResponseDto? {
    val cleanId = userId.trim()
    if (cleanId.isBlank()) return null
    val session = identityStore.sessionFlow.first()
    val token = session.accessToken.orEmpty()
    val callerId = session.userId.orEmpty()
    val isCaller = cleanId == callerId
    val api = apiService

    if (api != null && token.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val deviceFp = buildDeviceFingerprint()
      val resp = executeWithWeakNetworkRetry {
        api.getUserProfile("Bearer $token", deviceFp, cleanId)
      }
      val body = if (resp != null && resp.isSuccessful) resp.body() else null
      if (body != null && body.success) {
        return body
      }
    }

    // Local / Offline fallback from Room database
    val myProfile = if (isCaller) dao.getProfileByUserId(callerId) else null
    val cachedContact = dao.getCachedContact(cleanId)
    val authorPosts = dao.getFeedPostsByAuthor(cleanId)
    val videoDtos = authorPosts.filter { it.mode == "video" }.map { p ->
      UserProfileVideoDto(
        postId = p.postId,
        thumbnailUrl = p.thumbnailUrl,
        videoUrl = p.videoUrl,
        viewsCount = p.sharesCount * 3 + p.likesCount * 2 + 120L,
        durationMs = p.durationMs,
        caption = p.caption,
        createdAtMs = p.createdAtMs,
      )
    }

    val fullName = myProfile?.fullName ?: cachedContact?.fullName ?: authorPosts.firstOrNull()?.authorFullName ?: "User"
    val username = myProfile?.username ?: cachedContact?.username ?: authorPosts.firstOrNull()?.authorUsername ?: cleanId
    val avatarUrl = myProfile?.avatarLocalPath ?: cachedContact?.avatarLocalPath ?: cachedContact?.avatarUrl ?: authorPosts.firstOrNull()?.authorAvatarUrl.orEmpty()
    val location = myProfile?.location ?: cachedContact?.location ?: ""
    val bio = myProfile?.bio ?: ""
    val isFollowing = cachedContact?.isFollowing ?: authorPosts.firstOrNull()?.isFollowingAuthor ?: false
    val followsYou = cachedContact?.followsYou ?: false
    val relState = when {
      isFollowing && followsYou -> "FRIENDS"
      isFollowing -> "FOLLOWING"
      followsYou -> "FOLLOW_BACK"
      else -> "FOLLOW"
    }

    return UserProfileResponseDto(
      success = true,
      userId = cleanId,
      fullName = fullName,
      username = username,
      avatarUrl = avatarUrl,
      bio = bio,
      location = location,
      countryIso = myProfile?.countryIso ?: cachedContact?.countryIso ?: "",
      followingCount = if (isCaller) 126L else 142L,
      followersCount = if (isCaller) 84L else 1250L,
      likesCount = authorPosts.sumOf { it.likesCount },
      isFollowing = isFollowing,
      followsYou = followsYou,
      isOwner = isCaller,
      relationshipState = relState,
      videos = videoDtos,
    )
  }

  suspend fun fetchUserConnections(userId: String, isFollowers: Boolean): List<ContactItemDto> {
    val cleanId = userId.trim()
    if (cleanId.isBlank()) return emptyList()
    val session = identityStore.sessionFlow.first()
    val token = session.accessToken.orEmpty()
    val api = apiService

    if (api != null && token.isNotBlank() && networkTier.value != NetworkTier.OFFLINE) {
      val deviceFp = buildDeviceFingerprint()
      val resp = executeWithWeakNetworkRetry {
        api.getUserConnections("Bearer $token", deviceFp, cleanId, if (isFollowers) "followers" else "following")
      }
      val body = if (resp != null && resp.isSuccessful) resp.body() else null
      if (body != null && body.success) {
        return body.items
      }
    }

    val cached = dao.observeAllCachedContacts().first()
    return cached.map { c ->
      ContactItemDto(
        userId = c.userId,
        fullName = c.fullName,
        username = c.username,
        location = c.location,
        countryIso = c.countryIso,
        avatarUrl = c.avatarLocalPath ?: c.avatarUrl,
        isFollowing = c.isFollowing,
        followsYou = c.followsYou,
        relationshipState = when {
          c.isFollowing && c.followsYou -> "FRIENDS"
          c.isFollowing -> "FOLLOWING"
          c.followsYou -> "FOLLOW_BACK"
          else -> "FOLLOW"
        },
        riskScore = c.riskScore,
        updatedAtMs = c.updatedAtMs,
      )
    }
  }

  suspend fun signOut() {
    val session = identityStore.sessionFlow.first()
    val userId = session.userId.orEmpty()
    if (userId.isNotBlank()) {
      logAudit(userId, "user_signed_out", "{}")
    }
    identityStore.clearSession()
  }

  private suspend fun completeSignIn(
    userId: String,
    email: String,
    accessToken: String,
    refreshToken: String,
    accessExpSec: Long,
    refreshExpSec: Long,
    compositeKey: String,
    profileCompleted: Boolean,
  ) {
    val nowMs = System.currentTimeMillis()
    val deviceFp = buildDeviceFingerprint()
    dao.clearRateLimitState(compositeKey)
    dao.insertRefreshTokenRecord(
      RefreshTokenRecordEntity(
        tokenHash = EncryptedTokenVault.sha256Hex(refreshToken),
        familyId = UUID.randomUUID().toString(),
        userId = userId,
        deviceFp = deviceFp,
        expiresAtMs = nowMs + (refreshExpSec * 1000L),
        revoked = false,
        createdAtMs = nowMs,
      ),
    )
    identityStore.saveEncryptedSession(
      userId = userId,
      email = email,
      accessToken = accessToken,
      refreshToken = refreshToken,
      accessExpiresInSec = accessExpSec,
      refreshExpiresInSec = refreshExpSec,
      profileCompleted = profileCompleted,
    )
    logAudit(userId, "auth_session_issued", "{\"profile_completed\":$profileCompleted}")
  }

  private suspend fun buildDeviceFingerprint(): String {
    val installId = identityStore.getOrCreateInstallId()
    val integrity = SecurityIntegrityGuard.inspect(appContext)
    val integrityFlags = "${integrity.signatureDigest.take(8)}_${if (integrity.isTampered) 1 else 0}${if (integrity.hasRootBinary) 1 else 0}${if (integrity.hasHookFramework) 1 else 0}${if (integrity.isDebuggerAttached) 1 else 0}"
    return TelefamCoreEngine.computeDeviceFingerprint(
      platform = "android",
      model = "${Build.MANUFACTURER}_${Build.MODEL}_$integrityFlags",
      osVersion = Build.VERSION.RELEASE ?: "unknown",
      installId = installId,
    )
  }

  private fun buildIpFingerprint(): String {
    return TelefamCoreEngine.computeIpFingerprint(networkMonitor.currentIpAddressHint())
  }

  private suspend fun <T> executeWithWeakNetworkRetry(block: suspend () -> T): T? {
    val maxAttempts = if (networkTier.value == NetworkTier.WEAK) 3 else 2
    var attempt = 0
    while (attempt < maxAttempts) {
      val result = runCatching { block() }.getOrNull()
      if (result != null) return result
      attempt++
      if (attempt < maxAttempts) {
        delay(TelefamCoreEngine.computeBackoffMs(attempt, networkTier.value))
      }
    }
    return null
  }
}
