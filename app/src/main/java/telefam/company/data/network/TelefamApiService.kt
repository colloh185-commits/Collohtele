package telefam.company.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import com.telefam.company.app.BuildConfig
import java.net.URI
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class RequestOtpDto(val email: String)

@JsonClass(generateAdapter = true)
data class RequestOtpResponseDto(
  val success: Boolean,
  @Json(name = "cooldown_seconds") val cooldownSeconds: Long = 60L,
)

@JsonClass(generateAdapter = true)
data class VerifyOtpDto(val email: String, val otp: String)

@JsonClass(generateAdapter = true)
data class VerifyLinkDto(val token: String)

@JsonClass(generateAdapter = true)
data class RefreshTokenDto(@Json(name = "refresh_token") val refreshToken: String)

@JsonClass(generateAdapter = true)
data class OAuthDto(
  val provider: String,
  @Json(name = "identity_token") val identityToken: String,
  val nonce: String? = null,
)

@JsonClass(generateAdapter = true)
data class AuthSessionResponseDto(
  val success: Boolean,
  @Json(name = "session_token") val sessionToken: String = "",
  @Json(name = "refresh_token") val refreshToken: String = "",
  @Json(name = "access_expires_in_seconds") val accessExpiresInSec: Long = 900L,
  @Json(name = "refresh_expires_in_seconds") val refreshExpiresInSec: Long = 2_592_000L,
  val email: String = "",
  @Json(name = "suggested_full_name") val suggestedFullName: String = "",
  @Json(name = "suggested_username") val suggestedUsername: String = "",
  @Json(name = "profile_completed") val profileCompleted: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class UsernameCheckResponseDto(val available: Boolean = false)

@JsonClass(generateAdapter = true)
data class ProfileSetupDto(
  @Json(name = "full_name") val fullName: String,
  val username: String,
  @Json(name = "phone_number") val phoneNumber: String,
  @Json(name = "country_iso") val countryIso: String,
  val gender: String,
  @Json(name = "date_of_birth") val dateOfBirth: String,
  val location: String,
  val bio: String,
  @Json(name = "avatar_media_id") val avatarMediaId: String? = null,
)

@JsonClass(generateAdapter = true)
data class VideoRenditionDto(
  @Json(name = "quality_label") val qualityLabel: String = "",
  val width: Int = 0,
  val height: Int = 0,
  @Json(name = "bitrate_kbps") val bitrateKbps: Int = 0,
  @Json(name = "storage_object_key") val storageObjectKey: String = "",
  @Json(name = "byte_size") val byteSize: Int = 0,
)

@JsonClass(generateAdapter = true)
data class MediaUploadResponseDto(
  val success: Boolean = false,
  @Json(name = "media_id") val mediaId: String = "",
  @Json(name = "sha256_fingerprint") val sha256Fingerprint: String = "",
  @Json(name = "dhash_fingerprint") val dHashFingerprint: String = "",
  @Json(name = "content_id") val contentId: String = "",
  @Json(name = "thumbnail_media_id") val thumbnailMediaId: String = "",
  @Json(name = "per_frame_hashes") val perFrameHashes: List<String> = emptyList(),
  val renditions: List<VideoRenditionDto> = emptyList(),
  @Json(name = "mime_type") val mimeType: String = "",
  @Json(name = "byte_size") val byteSize: Int = 0,
)

@JsonClass(generateAdapter = true)
data class SongTrackDto(
  @Json(name = "track_id") val trackId: String = "",
  val title: String = "",
  val artist: String = "",
  val album: String = "",
  @Json(name = "artwork_url") val artworkUrl: String = "",
  @Json(name = "preview_url") val previewUrl: String = "",
  @Json(name = "duration_ms") val durationMs: Long = 0L,
  val source: String = "",
)

@JsonClass(generateAdapter = true)
data class SongsSearchResponseDto(
  val success: Boolean = false,
  val items: List<SongTrackDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class HashtagItemDto(
  val tag: String = "",
  @Json(name = "post_count") val postCount: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class HashtagsSearchResponseDto(
  val success: Boolean = false,
  val items: List<HashtagItemDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CreatePostRequestDto(
  @Json(name = "client_op_id") val clientOpId: String,
  val mode: String,
  @Json(name = "text_content") val textContent: String = "",
  val caption: String = "",
  @Json(name = "theme_id") val themeId: String = "crimson",
  @Json(name = "font_id") val fontId: String = "classic",
  val song: SongTrackDto? = null,
  @Json(name = "tagged_user_ids") val taggedUserIds: List<String> = emptyList(),
  val hashtags: List<String> = emptyList(),
  @Json(name = "photo_media_ids") val photoMediaIds: List<String> = emptyList(),
  @Json(name = "video_media_id") val videoMediaId: String = "",
  @Json(name = "thumbnail_media_id") val thumbnailMediaId: String = "",
  @Json(name = "trim_start_ms") val trimStartMs: Long = 0L,
  @Json(name = "trim_end_ms") val trimEndMs: Long = 0L,
  @Json(name = "mute_original_audio") val muteOriginalAudio: Boolean = false,
  @Json(name = "content_id") val contentId: String = "",
)

@JsonClass(generateAdapter = true)
data class CreatedPostDto(
  @Json(name = "post_id") val postId: String = "",
  @Json(name = "client_op_id") val clientOpId: String = "",
  val mode: String = "",
  @Json(name = "content_id") val contentId: String = "",
  @Json(name = "created_at_ms") val createdAtMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class CreatePostResponseDto(
  val success: Boolean = false,
  val post: CreatedPostDto? = null,
)

@JsonClass(generateAdapter = true)
data class AuditEventDto(
  val action: String,
  val metadata: Map<String, String> = emptyMap(),
)

@JsonClass(generateAdapter = true)
data class GenericSuccessDto(val success: Boolean = false)

@JsonClass(generateAdapter = true)
data class SyncOperationDto(
  @Json(name = "client_op_id") val clientOpId: String,
  @Json(name = "room_id") val roomId: String,
  val kind: String,
  val payload: String,
  @Json(name = "created_at") val createdAt: Long,
)

@JsonClass(generateAdapter = true)
data class SyncBatchRequestDto(val operations: List<SyncOperationDto>)

@JsonClass(generateAdapter = true)
data class SyncBatchResponseDto(
  val success: Boolean,
  @Json(name = "acked_op_ids") val ackedOpIds: List<String> = emptyList(),
  @Json(name = "server_epoch_ms") val serverEpochMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class ContactItemDto(
  @Json(name = "user_id") val userId: String,
  @Json(name = "full_name") val fullName: String,
  val username: String,
  val location: String = "",
  @Json(name = "country_iso") val countryIso: String = "",
  @Json(name = "avatar_url") val avatarUrl: String? = null,
  @Json(name = "is_following") val isFollowing: Boolean = false,
  @Json(name = "follows_you") val followsYou: Boolean = false,
  @Json(name = "relationship_state") val relationshipState: String = "FOLLOW",
  @Json(name = "risk_score") val riskScore: Double = 0.0,
  @Json(name = "updated_at_ms") val updatedAtMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class ContactsPageResponseDto(
  val success: Boolean = false,
  val items: List<ContactItemDto> = emptyList(),
  val page: Int = 1,
  @Json(name = "page_size") val pageSize: Int = 20,
  @Json(name = "total_count") val totalCount: Int = 0,
  @Json(name = "has_more") val hasMore: Boolean = false,
  @Json(name = "server_epoch_ms") val serverEpochMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class ContactActionDto(
  @Json(name = "target_user_id") val targetUserId: String,
)

@JsonClass(generateAdapter = true)
data class FollowMutationResponseDto(
  val success: Boolean = false,
  @Json(name = "target_user_id") val targetUserId: String = "",
  @Json(name = "is_following") val isFollowing: Boolean = false,
  @Json(name = "follows_you") val followsYou: Boolean = false,
  @Json(name = "relationship_state") val relationshipState: String = "FOLLOW",
  @Json(name = "caller_risk_score") val callerRiskScore: Double = 0.0,
  @Json(name = "server_epoch_ms") val serverEpochMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class ContactSyncActionDto(
  @Json(name = "client_op_id") val clientOpId: String,
  @Json(name = "target_user_id") val targetUserId: String,
  val action: String,
  @Json(name = "created_at_ms") val createdAtMs: Long,
)

@JsonClass(generateAdapter = true)
data class UserProfileVideoDto(
  @Json(name = "post_id") val postId: String = "",
  @Json(name = "thumbnail_url") val thumbnailUrl: String = "",
  @Json(name = "video_url") val videoUrl: String = "",
  @Json(name = "views_count") val viewsCount: Long = 0L,
  @Json(name = "duration_ms") val durationMs: Long = 0L,
  val caption: String = "",
  @Json(name = "created_at_ms") val createdAtMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class UserProfileResponseDto(
  val success: Boolean = false,
  @Json(name = "user_id") val userId: String = "",
  @Json(name = "full_name") val fullName: String = "",
  val username: String = "",
  @Json(name = "avatar_url") val avatarUrl: String = "",
  val bio: String = "",
  val location: String = "",
  @Json(name = "country_iso") val countryIso: String = "",
  @Json(name = "following_count") val followingCount: Long = 0L,
  @Json(name = "followers_count") val followersCount: Long = 0L,
  @Json(name = "likes_count") val likesCount: Long = 0L,
  @Json(name = "is_following") val isFollowing: Boolean = false,
  @Json(name = "follows_you") val followsYou: Boolean = false,
  @Json(name = "is_owner") val isOwner: Boolean = false,
  @Json(name = "relationship_state") val relationshipState: String = "FOLLOW",
  val videos: List<UserProfileVideoDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class UserConnectionsResponseDto(
  val success: Boolean = false,
  val items: List<ContactItemDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ContactSyncBatchRequestDto(
  val actions: List<ContactSyncActionDto>,
)

@JsonClass(generateAdapter = true)
data class UpdatePostRequestDto(
  @Json(name = "text_content") val textContent: String = "",
  val caption: String = "",
  @Json(name = "theme_id") val themeId: String = "crimson",
  @Json(name = "font_id") val fontId: String = "classic",
  val song: SongTrackDto? = null,
  @Json(name = "tagged_user_ids") val taggedUserIds: List<String> = emptyList(),
  val hashtags: List<String> = emptyList(),
  @Json(name = "mute_original_audio") val muteOriginalAudio: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class FeedVideoRenditionDto(
  @Json(name = "quality_label") val qualityLabel: String = "",
  val width: Int = 0,
  val height: Int = 0,
  @Json(name = "bitrate_kbps") val bitrateKbps: Int = 0,
  val url: String = "",
  @Json(name = "byte_size") val byteSize: Int = 0,
)

@JsonClass(generateAdapter = true)
data class FeedItemDto(
  @Json(name = "post_id") val postId: String = "",
  @Json(name = "author_user_id") val authorUserId: String = "",
  @Json(name = "author_username") val authorUsername: String = "",
  @Json(name = "author_full_name") val authorFullName: String = "",
  @Json(name = "author_avatar_url") val authorAvatarUrl: String = "",
  @Json(name = "is_following_author") val isFollowingAuthor: Boolean = false,
  @Json(name = "is_owner") val isOwner: Boolean = false,
  val mode: String = "video",
  @Json(name = "text_content") val textContent: String = "",
  val caption: String = "",
  @Json(name = "theme_id") val themeId: String = "",
  @Json(name = "font_id") val fontId: String = "",
  val song: SongTrackDto? = null,
  val hashtags: List<String> = emptyList(),
  @Json(name = "photo_urls") val photoUrls: List<String> = emptyList(),
  @Json(name = "video_url") val videoUrl: String = "",
  @Json(name = "thumbnail_url") val thumbnailUrl: String = "",
  val renditions: List<FeedVideoRenditionDto> = emptyList(),
  val width: Int = 0,
  val height: Int = 0,
  @Json(name = "duration_ms") val durationMs: Long = 0L,
  @Json(name = "mute_original_audio") val muteOriginalAudio: Boolean = false,
  @Json(name = "content_id") val contentId: String = "",
  @Json(name = "likes_count") val likesCount: Long = 0L,
  @Json(name = "comments_count") val commentsCount: Long = 0L,
  @Json(name = "shares_count") val sharesCount: Long = 0L,
  @Json(name = "favourites_count") val favouritesCount: Long = 0L,
  @Json(name = "reposts_count") val repostsCount: Long = 0L,
  @Json(name = "is_liked") val isLiked: Boolean = false,
  @Json(name = "is_favourited") val isFavourited: Boolean = false,
  @Json(name = "is_reposted") val isReposted: Boolean = false,
  @Json(name = "reposted_by_username") val repostedByUsername: String = "",
  @Json(name = "recommendation_label") val recommendationLabel: String = "",
  @Json(name = "relevance_score") val relevanceScore: Double = 0.0,
  @Json(name = "created_at_ms") val createdAtMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class FeedPageResponseDto(
  val success: Boolean = false,
  val tab: String = "for_you",
  val items: List<FeedItemDto> = emptyList(),
  val page: Int = 1,
  @Json(name = "page_size") val pageSize: Int = 15,
  @Json(name = "has_more") val hasMore: Boolean = false,
  @Json(name = "server_epoch_ms") val serverEpochMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class SearchShortcutDto(
  val key: String = "",
  val label: String = "",
  val count: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class FeedSearchResponseDto(
  val success: Boolean = false,
  val query: String = "",
  val shortcut: String = "",
  val shortcuts: List<SearchShortcutDto> = emptyList(),
  val items: List<FeedItemDto> = emptyList(),
  val page: Int = 1,
  @Json(name = "page_size") val pageSize: Int = 24,
  @Json(name = "has_more") val hasMore: Boolean = false,
  @Json(name = "server_epoch_ms") val serverEpochMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class FeedInteractionRequestDto(
  @Json(name = "client_op_id") val clientOpId: String = "",
  @Json(name = "post_id") val postId: String,
  val action: String,
  @Json(name = "watch_ms") val watchMs: Long = 0L,
  @Json(name = "duration_ms") val durationMs: Long = 0L,
  val reason: String = "",
  val details: String = "",
)

@JsonClass(generateAdapter = true)
data class FeedInteractionResponseDto(
  val success: Boolean = false,
  @Json(name = "post_id") val postId: String = "",
  val action: String = "",
  @Json(name = "likes_count") val likesCount: Long = 0L,
  @Json(name = "favourites_count") val favouritesCount: Long = 0L,
  @Json(name = "reposts_count") val repostsCount: Long = 0L,
  @Json(name = "shares_count") val sharesCount: Long = 0L,
  @Json(name = "is_liked") val isLiked: Boolean = false,
  @Json(name = "is_favourited") val isFavourited: Boolean = false,
  @Json(name = "is_reposted") val isReposted: Boolean = false,
  @Json(name = "server_epoch_ms") val serverEpochMs: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class FeedInteractionBatchRequestDto(
  val operations: List<FeedInteractionRequestDto>,
)

interface TelefamApiService {
  @POST("v1/auth/otp/request")
  suspend fun requestOtp(
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Header("X-Telefam-Client-IP-Hint") ipFingerprint: String,
    @Body body: RequestOtpDto,
  ): Response<RequestOtpResponseDto>

  @POST("v1/auth/otp/verify")
  suspend fun verifyOtp(
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Header("X-Telefam-Client-IP-Hint") ipFingerprint: String,
    @Body body: VerifyOtpDto,
  ): Response<AuthSessionResponseDto>

  @POST("v1/auth/verify-link")
  suspend fun verifyLink(
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Header("X-Telefam-Client-IP-Hint") ipFingerprint: String,
    @Body body: VerifyLinkDto,
  ): Response<AuthSessionResponseDto>

  @POST("v1/auth/token/refresh")
  suspend fun refreshToken(
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: RefreshTokenDto,
  ): Response<AuthSessionResponseDto>

  @POST("v1/auth/oauth/google")
  suspend fun verifyGoogleOAuth(
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Header("X-Telefam-Client-IP-Hint") ipFingerprint: String,
    @Body body: OAuthDto,
  ): Response<AuthSessionResponseDto>

  @GET("v1/profile/check-username")
  suspend fun checkUsername(
    @Query("u") username: String,
  ): Response<UsernameCheckResponseDto>

  @POST("v1/media/avatar")
  suspend fun uploadAvatar(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Query("cx") cropCenterX: Float,
    @Query("cy") cropCenterY: Float,
    @Query("scale") cropScale: Float,
    @Body imageBody: RequestBody,
  ): Response<MediaUploadResponseDto>

  @POST("v1/media/photo")
  suspend fun uploadPostPhoto(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body imageBody: RequestBody,
  ): Response<MediaUploadResponseDto>

  @Multipart
  @POST("v1/media/video")
  suspend fun uploadPostVideo(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Header("X-Telefam-Frame-Hashes") frameHashesCsv: String,
    @Query("duration_ms") durationMs: Long,
    @Query("trim_start_ms") trimStartMs: Long,
    @Query("trim_end_ms") trimEndMs: Long,
    @Query("width") width: Int,
    @Query("height") height: Int,
    @Part videoPart: MultipartBody.Part,
    @Part thumbnailPart: MultipartBody.Part? = null,
  ): Response<MediaUploadResponseDto>

  @GET("v1/songs/search")
  suspend fun searchSongs(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Query("q") query: String,
  ): Response<SongsSearchResponseDto>

  @GET("v1/hashtags/search")
  suspend fun searchHashtags(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Query("q") query: String,
  ): Response<HashtagsSearchResponseDto>

  @POST("v1/posts")
  suspend fun createPost(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: CreatePostRequestDto,
  ): Response<CreatePostResponseDto>

  @PATCH("v1/posts/{id}")
  suspend fun updatePost(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Path("id") postId: String,
    @Body body: UpdatePostRequestDto,
  ): Response<CreatePostResponseDto>

  @DELETE("v1/posts/{id}")
  suspend fun deletePost(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Path("id") postId: String,
  ): Response<GenericSuccessDto>

  @GET("v1/feeds")
  suspend fun getFeeds(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Query("tab") tab: String,
    @Query("page") page: Int,
    @Query("page_size") pageSize: Int,
  ): Response<FeedPageResponseDto>

  @GET("v1/feeds/search")
  suspend fun searchFeeds(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Query("q") query: String,
    @Query("shortcut") shortcut: String,
    @Query("page") page: Int,
    @Query("page_size") pageSize: Int,
  ): Response<FeedSearchResponseDto>

  @POST("v1/feeds/interact")
  suspend fun interactFeed(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: FeedInteractionRequestDto,
  ): Response<FeedInteractionResponseDto>

  @POST("v1/feeds/interact/batch")
  suspend fun interactFeedBatch(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: FeedInteractionBatchRequestDto,
  ): Response<SyncBatchResponseDto>

  @POST("v1/profile/setup")
  suspend fun setupProfile(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: ProfileSetupDto,
  ): Response<GenericSuccessDto>

  @POST("v1/audit/event")
  suspend fun recordAuditEvent(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: AuditEventDto,
  ): Response<GenericSuccessDto>

  @POST("v1/sync/batch")
  suspend fun syncBatch(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: SyncBatchRequestDto,
  ): Response<SyncBatchResponseDto>

  @GET("v1/contacts")
  suspend fun listContacts(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Query("filter") filter: String,
    @Query("q") query: String,
    @Query("page") page: Int,
    @Query("page_size") pageSize: Int,
  ): Response<ContactsPageResponseDto>

  @POST("v1/contacts/follow")
  suspend fun followContact(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: ContactActionDto,
  ): Response<FollowMutationResponseDto>

  @POST("v1/contacts/unfollow")
  suspend fun unfollowContact(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: ContactActionDto,
  ): Response<FollowMutationResponseDto>

  @POST("v1/contacts/sync")
  suspend fun syncContactActions(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Body body: ContactSyncBatchRequestDto,
  ): Response<SyncBatchResponseDto>

  @GET("v1/users/{id}/profile")
  suspend fun getUserProfile(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Path("id") userId: String,
  ): Response<UserProfileResponseDto>

  @GET("v1/users/{id}/connections")
  suspend fun getUserConnections(
    @Header("Authorization") bearer: String,
    @Header("X-Telefam-Device-FP") deviceFingerprint: String,
    @Path("id") userId: String,
    @Query("type") type: String, // "followers" or "following"
  ): Response<UserConnectionsResponseDto>

  @GET
  suspend fun downloadMedia(@Url url: String): Response<ResponseBody>

  companion object {
    fun isAllowedBaseUrl(baseUrl: String, enforceHttps: Boolean): Boolean {
      val trimmed = baseUrl.trim()
      if (trimmed.isBlank()) return false
      val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return false
      val scheme = parsed.scheme?.lowercase() ?: return false
      val host = parsed.host?.lowercase() ?: return false
      if (scheme == "https") return true
      if (scheme == "http") {
        val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2" || host == "::1"
        return !enforceHttps || isLoopback
      }
      return false
    }

    fun create(baseUrl: String, enforceHttps: Boolean = !BuildConfig.DEBUG): TelefamApiService? {
      val trimmed = baseUrl.trim()
      if (!isAllowedBaseUrl(trimmed, enforceHttps)) return null
      val normalizedUrl = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
      val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
      val okHttp = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

      return Retrofit.Builder()
        .baseUrl(normalizedUrl)
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(TelefamApiService::class.java)
    }
  }
}
