package telefam.company.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "rate_limit_state")
data class RateLimitStateEntity(
  @PrimaryKey val compositeKey: String,
  val accountHash: String,
  val deviceFp: String,
  val sendCount: Int,
  val nextAllowedEpochMs: Long,
  val activeCooldownDurationSeconds: Long,
  val lastUpdatedEpochMs: Long,
)

@Entity(
  tableName = "rate_limit_hits",
  indices = [Index(value = ["dimensionKey", "timestampMs"])],
)
data class RateLimitHitEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val dimensionKey: String,
  val timestampMs: Long,
)

@Entity(
  tableName = "user_profiles",
  indices = [Index(value = ["usernameLower"], unique = true)],
)
data class UserProfileEntity(
  @PrimaryKey val userId: String,
  val email: String,
  val fullName: String,
  val username: String,
  val usernameLower: String,
  val phoneE164: String,
  val countryIso: String,
  val gender: String,
  val dateOfBirthIso: String,
  val location: String,
  val bio: String,
  val avatarMediaId: String?,
  val avatarLocalPath: String?,
  val avatarSha256: String?,
  val avatarDHash: String?,
  val updatedAtMs: Long,
)

@Entity(tableName = "media_uploads")
data class MediaUploadEntity(
  @PrimaryKey val mediaId: String,
  val ownerUserId: String,
  val storageObjectKey: String,
  val mimeType: String,
  val sha256Fingerprint: String,
  val dHashFingerprint: String,
  val byteSize: Int,
  val localFilePath: String,
  val createdAtMs: Long,
)

@Entity(tableName = "refresh_token_records")
data class RefreshTokenRecordEntity(
  @PrimaryKey val tokenHash: String,
  val familyId: String,
  val userId: String,
  val deviceFp: String,
  val expiresAtMs: Long,
  val revoked: Boolean,
  val createdAtMs: Long,
)

@Entity(
  tableName = "audit_logs",
  indices = [Index(value = ["userId", "createdAtMs"])],
)
data class AuditLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val userId: String,
  val action: String,
  val deviceFp: String,
  val ipFp: String,
  val metadataJson: String,
  val createdAtMs: Long,
)

@Entity(tableName = "family_circles")
data class FamilyCircleEntity(
  @PrimaryKey val id: String,
  val name: String,
  val memberSummary: String,
  val lastMessage: String,
  val lastTimestampMs: Long,
)

@Entity(
  tableName = "family_messages",
  indices = [Index(value = ["circleId", "createdAtMs"])],
)
data class FamilyMessageEntity(
  @PrimaryKey val id: String,
  val circleId: String,
  val senderLabel: String,
  val body: String,
  val createdAtMs: Long,
  val isMine: Boolean,
  val syncStatus: String,
)

@Entity(tableName = "offline_actions")
data class OfflineActionEntity(
  @PrimaryKey val clientOpId: String,
  val circleId: String,
  val kind: String,
  val payloadJson: String,
  val createdAtMs: Long,
  val retryCount: Int,
  val nextRetryAtMs: Long,
)

@Entity(
  tableName = "cached_contacts",
  indices = [Index(value = ["usernameLower"]), Index(value = ["fullName"])],
)
data class CachedContactEntity(
  @PrimaryKey val userId: String,
  val fullName: String,
  val username: String,
  val usernameLower: String,
  val location: String,
  val countryIso: String,
  val avatarUrl: String?,
  val avatarLocalPath: String?,
  val isFollowing: Boolean,
  val followsYou: Boolean,
  val riskScore: Double,
  val updatedAtMs: Long,
  val cachedAtMs: Long,
)

@Entity(tableName = "contact_sync_actions")
data class ContactSyncActionEntity(
  @PrimaryKey val clientOpId: String,
  val targetUserId: String,
  val wantFollow: Boolean,
  val createdAtMs: Long,
  val retryCount: Int,
  val nextRetryAtMs: Long,
)

@Entity(tableName = "cached_hashtags")
data class CachedHashtagEntity(
  @PrimaryKey val tag: String,
  val postCount: Long,
  val updatedAtMs: Long,
)

@Entity(tableName = "pending_post_uploads")
data class PendingPostUploadEntity(
  @PrimaryKey val clientOpId: String,
  val mode: String,
  val textContent: String,
  val caption: String,
  val themeId: String,
  val fontId: String,
  val songJson: String?,
  val taggedUserIdsJson: String,
  val hashtagsJson: String,
  val localPhotoPathsJson: String,
  val localVideoPath: String?,
  val localThumbnailPath: String?,
  val frameHashesJson: String,
  val durationMs: Long,
  val trimStartMs: Long,
  val trimEndMs: Long,
  val width: Int,
  val height: Int,
  val muteOriginalAudio: Boolean,
  val contentId: String,
  val status: String,
  val progressPct: Int,
  val retryCount: Int,
  val nextRetryAtMs: Long,
  val createdAtMs: Long,
)

@Entity(
  tableName = "cached_feed_posts",
  indices = [
    Index(value = ["tabKey", "sortOrder"]),
    Index(value = ["authorUserId"]),
  ],
)
data class CachedFeedPostEntity(
  @PrimaryKey val postId: String,
  val authorUserId: String,
  val authorUsername: String,
  val authorFullName: String,
  val authorAvatarUrl: String?,
  val isFollowingAuthor: Boolean,
  val isOwner: Boolean,
  val mode: String,
  val textContent: String,
  val caption: String,
  val themeId: String,
  val fontId: String,
  val songJson: String?,
  val hashtagsJson: String,
  val photoUrlsJson: String,
  val videoUrl: String,
  val thumbnailUrl: String,
  val renditionsJson: String,
  val width: Int,
  val height: Int,
  val durationMs: Long,
  val muteOriginalAudio: Boolean,
  val contentId: String,
  val likesCount: Long,
  val commentsCount: Long,
  val sharesCount: Long,
  val favouritesCount: Long,
  val repostsCount: Long,
  val isLiked: Boolean,
  val isFavourited: Boolean,
  val isReposted: Boolean,
  val repostedByUsername: String,
  val recommendationLabel: String,
  val relevanceScore: Double,
  val tabKey: String,
  val sortOrder: Int,
  val isHiddenByUser: Boolean,
  val viewsCount: Long = 0L,
  val createdAtMs: Long,
  val cachedAtMs: Long,
)

@Entity(
  tableName = "cached_user_profiles",
  indices = [Index(value = ["username"])],
)
data class CachedUserProfileEntity(
  @PrimaryKey val userId: String,
  val fullName: String,
  val username: String,
  val avatarUrl: String?,
  val avatarLocalPath: String?,
  val bio: String,
  val location: String,
  val countryIso: String,
  val followingCount: Long,
  val followersCount: Long,
  val likesCount: Long,
  val isFollowing: Boolean,
  val followsYou: Boolean,
  val isOwner: Boolean,
  val isBlocked: Boolean,
  val riskScore: Double,
  val relationshipState: String,
  val postsJson: String,
  val cachedAtMs: Long,
)

@Entity(
  tableName = "cached_user_connections",
  indices = [Index(value = ["ownerUserId", "listType", "sortOrder"])],
)
data class CachedUserConnectionEntity(
  @PrimaryKey val compositeKey: String, // "$ownerUserId|$listType|$targetUserId"
  val ownerUserId: String,
  val listType: String, // "followers" or "following"
  val targetUserId: String,
  val fullName: String,
  val username: String,
  val location: String,
  val countryIso: String,
  val avatarUrl: String?,
  val avatarLocalPath: String?,
  val isFollowing: Boolean,
  val followsYou: Boolean,
  val relationshipState: String,
  val sortOrder: Int,
  val cachedAtMs: Long,
)

@Entity(
  tableName = "pending_feed_interactions",
  indices = [Index(value = ["nextRetryAtMs"])],
)
data class PendingFeedInteractionEntity(
  @PrimaryKey val clientOpId: String,
  val postId: String,
  val action: String,
  val watchMs: Long,
  val durationMs: Long,
  val reason: String,
  val details: String,
  val createdAtMs: Long,
  val retryCount: Int,
  val nextRetryAtMs: Long,
)

