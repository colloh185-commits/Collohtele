package telefam.company

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import retrofit2.Response
import telefam.company.core.AdaptiveStreamSelector
import telefam.company.core.FeedAspectRatioCalculator
import telefam.company.core.FeedInteractionAction
import telefam.company.core.FeedTab
import telefam.company.core.FeedVideoRendition
import telefam.company.core.NetworkTier
import telefam.company.core.PostMode
import telefam.company.core.PostSongTrack
import telefam.company.core.formatCompactCount
import telefam.company.data.local.DeviceIdentityStore
import telefam.company.data.local.TelefamDatabase
import telefam.company.data.network.AuditEventDto
import telefam.company.data.network.AuthSessionResponseDto
import telefam.company.data.network.ContactActionDto
import telefam.company.data.network.ContactSyncBatchRequestDto
import telefam.company.data.network.ContactsPageResponseDto
import telefam.company.data.network.CreatePostRequestDto
import telefam.company.data.network.CreatePostResponseDto
import telefam.company.data.network.CreatedPostDto
import telefam.company.data.network.FeedInteractionBatchRequestDto
import telefam.company.data.network.FeedInteractionRequestDto
import telefam.company.data.network.FeedInteractionResponseDto
import telefam.company.data.network.FeedItemDto
import telefam.company.data.network.FeedPageResponseDto
import telefam.company.data.network.FeedSearchResponseDto
import telefam.company.data.network.FeedVideoRenditionDto
import telefam.company.data.network.FollowMutationResponseDto
import telefam.company.data.network.GenericSuccessDto
import telefam.company.data.network.HashtagsSearchResponseDto
import telefam.company.data.network.MediaUploadResponseDto
import telefam.company.data.network.NetworkQualityMonitor
import telefam.company.data.network.OAuthDto
import telefam.company.data.network.ProfileSetupDto
import telefam.company.data.network.RefreshTokenDto
import telefam.company.data.network.RequestOtpDto
import telefam.company.data.network.RequestOtpResponseDto
import telefam.company.data.network.SearchShortcutDto
import telefam.company.data.network.SongsSearchResponseDto
import telefam.company.data.network.SyncBatchRequestDto
import telefam.company.data.network.SyncBatchResponseDto
import telefam.company.data.network.TelefamApiService
import telefam.company.data.network.UpdatePostRequestDto
import telefam.company.data.network.UsernameCheckResponseDto
import telefam.company.data.network.VerifyLinkDto
import telefam.company.data.network.VerifyOtpDto
import telefam.company.data.repository.PostPublishResult
import telefam.company.data.repository.TelefamRepository
import telefam.company.ui.viewmodel.FeedUiItem
import telefam.company.ui.viewmodel.TelefamViewModel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TelefamFeedRobolectricTest {
  private lateinit var context: Context
  private lateinit var db: TelefamDatabase
  private lateinit var identityStore: DeviceIdentityStore
  private lateinit var networkMonitor: NetworkQualityMonitor
  private lateinit var fakeApi: FakeFeedApiService
  private lateinit var repository: TelefamRepository

  @Before
  fun setup() = runTest {
    context = ApplicationProvider.getApplicationContext()
    setNetworkOnline(true)
    db = Room.inMemoryDatabaseBuilder(context, TelefamDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    identityStore = DeviceIdentityStore(context)
    identityStore.saveEncryptedSession(
      userId = "caller_1",
      email = "caller@telefam.test",
      accessToken = "acc_caller_1",
      refreshToken = "ref_caller_1",
      accessExpiresInSec = 900L,
      refreshExpiresInSec = 2_592_000L,
      profileCompleted = true,
    )
    networkMonitor = NetworkQualityMonitor(context)
    fakeApi = FakeFeedApiService()
    repository = TelefamRepository(
      appContext = context,
      dao = db.telefamDao(),
      identityStore = identityStore,
      networkMonitor = networkMonitor,
      apiService = fakeApi,
    )
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `aspect ratio calculator preserves proportions across portrait square landscape and ultrawide without clipping`() {
    val containerW = 1080f
    val containerH = 1920f

    // 9:16 portrait fills container cleanly
    val p916 = FeedAspectRatioCalculator.computeFittedSize(containerW, containerH, 1080, 1920)
    assertEquals(1080f, p916.width, 1f)
    assertEquals(1920f, p916.height, 1f)

    // 1:1 square preserves 1080x1080 centered without stretching
    val p11 = FeedAspectRatioCalculator.computeFittedSize(containerW, containerH, 1080, 1080)
    assertEquals(1080f, p11.width, 1f)
    assertEquals(1080f, p11.height, 1f)

    // 16:9 landscape fits width and scales height proportionally
    val p169 = FeedAspectRatioCalculator.computeFittedSize(containerW, containerH, 1920, 1080)
    assertEquals(1080f, p169.width, 1f)
    assertEquals(607.5f, p169.height, 1f)

    // 21:9 ultrawide fits width and scales height proportionally
    val p219 = FeedAspectRatioCalculator.computeFittedSize(containerW, containerH, 2520, 1080)
    assertEquals(1080f, p219.width, 1f)
    assertEquals(462.8f, p219.height, 2f)

    // Wide desktop container with portrait 9:16 video fits height without cropping
    val desk = FeedAspectRatioCalculator.computeFittedSize(1600f, 900f, 1080, 1920)
    assertEquals(900f, desk.height, 1f)
    assertEquals(506.25f, desk.width, 1f)
  }

  @Test
  fun `adaptive stream selector and compact counter formatting match reference layout`() {
    assertEquals("0", formatCompactCount(0L))
    assertEquals("2,345", formatCompactCount(2_345L))
    assertEquals("32.1K", formatCompactCount(32_140L))
    assertEquals("128.4K", formatCompactCount(128_420L))
    assertEquals("1.2M", formatCompactCount(1_240_000L))

    val renditions = listOf(
      FeedVideoRendition("360p", 360, 640, 450, "/v1/media/f/vid_360.mp4", 400_000),
      FeedVideoRendition("720p", 720, 1280, 1400, "/v1/media/f/vid_720.mp4", 1_200_000),
      FeedVideoRendition("1080p", 1080, 1920, 3400, "/v1/media/f/vid_1080.mp4", 2_800_000),
    )

    val goodUrl = AdaptiveStreamSelector.selectRenditionUrl("https://api.telefam.app", "", renditions, NetworkTier.GOOD)
    val weakUrl = AdaptiveStreamSelector.selectRenditionUrl("https://api.telefam.app", "", renditions, NetworkTier.WEAK)
    val offlineUrl = AdaptiveStreamSelector.selectRenditionUrl("https://api.telefam.app", "", renditions, NetworkTier.OFFLINE)

    assertEquals("https://api.telefam.app/v1/media/f/vid_720.mp4", goodUrl)
    assertEquals("https://api.telefam.app/v1/media/f/vid_360.mp4", weakUrl)
    assertEquals("https://api.telefam.app/v1/media/f/vid_360.mp4", offlineUrl)
  }

  @Test
  fun `offline feed interactions queue optimistically and flush batch on reconnect`() = runTest {
    repository.loadFeedPage(FeedTab.FOR_YOU, page = 1, pageSize = 15)
    val initial = db.telefamDao().observeFeedPosts(FeedTab.FOR_YOU.apiParam).first()
    assertEquals(2, initial.size)
    assertEquals("post_owner", initial[0].postId)

    // Go offline and perform Like + Repost + Not Interested
    setNetworkOnline(false)
    networkMonitor.refreshStatus()

    repository.performFeedInteraction("post_owner", FeedInteractionAction.LIKE)
    repository.performFeedInteraction("post_owner", FeedInteractionAction.REPOST)
    repository.performFeedInteraction("post_other", FeedInteractionAction.NOT_INTERESTED)

    val afterOffline = db.telefamDao().observeFeedPosts(FeedTab.FOR_YOU.apiParam).first()
    assertEquals(1, afterOffline.size) // post_other hidden locally immediately
    assertTrue(afterOffline[0].isLiked)
    assertTrue(afterOffline[0].isReposted)
    assertEquals(128_421L, afterOffline[0].likesCount)
    assertEquals(3, db.telefamDao().getReadyFeedInteractions(Long.MAX_VALUE, 50).size)

    // Reconnect and flush offline queue
    setNetworkOnline(true)
    networkMonitor.refreshStatus()
    repository.flushOfflineQueue()

    assertTrue(db.telefamDao().getReadyFeedInteractions(Long.MAX_VALUE, 50).isEmpty())
    assertTrue(fakeApi.batchedActions.any { it.postId == "post_owner" && it.action == "like" })
    assertTrue(fakeApi.batchedActions.any { it.postId == "post_owner" && it.action == "repost" })
    assertTrue(fakeApi.batchedActions.any { it.postId == "post_other" && it.action == "not_interested" })
  }

  @Test
  fun `owner update delete and viewmodel post autofill from hashtag song edit work properly`() = runTest {
    repository.loadFeedPage(FeedTab.FOR_YOU, page = 1, pageSize = 15)

    // Update owner post
    val updated = repository.updateOwnerPost(
      postId = "post_owner",
      textContent = "",
      caption = "Updated night lights!",
      themeId = "crimson",
      fontId = "classic",
      song = PostSongTrack("s1", "City Lights", "Rohan"),
      taggedUserIds = emptyList(),
      hashtags = listOf("citylights", "nightvibes"),
      muteOriginalAudio = true,
    )
    assertTrue(updated is PostPublishResult.Uploaded)
    assertEquals("Updated night lights!", db.telefamDao().getFeedPost("post_owner")?.caption)

    // ViewModel autofill state transitions
    val vm = TelefamViewModel(repository)
    vm.openPostWithHashtag("#Explore!", PostMode.VIDEO)
    assertTrue(vm.uiState.value.postComposer.isOpen)
    assertNull(vm.uiState.value.postComposer.editingPostId)
    assertEquals(listOf("explore"), vm.uiState.value.postComposer.selectedHashtags)

    val song = PostSongTrack("trk_9", "Midnight", "Rohan")
    vm.openPostWithSong(song, PostMode.VIDEO)
    assertTrue(vm.uiState.value.postComposer.isOpen)
    assertEquals(song, vm.uiState.value.postComposer.selectedSong)

    vm.openPostForEdit(
      FeedUiItem(
        postId = "post_owner",
        authorUserId = "caller_1",
        authorUsername = "caller",
        authorFullName = "Caller",
        authorAvatarUrl = null,
        isFollowingAuthor = false,
        isOwner = true,
        mode = PostMode.VIDEO,
        textContent = "",
        caption = "Updated night lights!",
        themeId = "crimson",
        fontOption = telefam.company.core.PostFontOption.CLASSIC,
        song = song,
        hashtags = listOf("citylights"),
        photoUrls = emptyList(),
        primaryStreamUrl = "/v1/media/f/vid.mp4",
        rawDownloadUrl = "/v1/feeds/post_owner/download",
        thumbnailUrl = "/v1/media/f/thumb.jpg",
        renditions = emptyList(),
        width = 1080,
        height = 1920,
        durationMs = 15_000L,
        muteOriginalAudio = true,
        contentId = "vid_123",
        likesCount = 10L,
        commentsCount = 0L,
        sharesCount = 0L,
        favouritesCount = 0L,
        repostsCount = 0L,
        isLiked = false,
        isFavourited = false,
        isReposted = false,
        repostedByUsername = "",
        recommendationLabel = "You might like this",
        createdAtMs = 1_000L,
      ),
    )
    assertTrue(vm.uiState.value.postComposer.isOpen)
    assertEquals("post_owner", vm.uiState.value.postComposer.editingPostId)
    assertTrue(vm.uiState.value.postComposer.canShare)

    // Delete owner post
    val deleted = repository.deleteOwnerPost("post_owner")
    assertTrue(deleted)
    assertNull(db.telefamDao().getFeedPost("post_owner"))
    assertFalse(db.telefamDao().observeFeedPosts(FeedTab.FOR_YOU.apiParam).first().any { it.postId == "post_owner" })
  }

  private fun setNetworkOnline(online: Boolean) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = cm.activeNetwork ?: return
    val caps = ShadowNetworkCapabilities.newInstance()
    if (online) {
      shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    shadowOf(cm).setNetworkCapabilities(active, caps)
  }

  private class FakeFeedApiService : TelefamApiService {
    val batchedActions = mutableListOf<FeedInteractionRequestDto>()

    private val feedItems = mutableListOf(
      FeedItemDto(
        postId = "post_owner",
        authorUserId = "caller_1",
        authorUsername = "rohan.verma",
        authorFullName = "Rohan Verma",
        isOwner = true,
        mode = "video",
        caption = "Beautiful nights in the city",
        hashtags = listOf("nightvibes", "citylights", "explore"),
        videoUrl = "/v1/media/f/vid1_720.mp4",
        thumbnailUrl = "/v1/media/f/thumb1.jpg",
        renditions = listOf(
          FeedVideoRenditionDto("360p", 360, 640, 450, "/v1/media/f/vid1_360.mp4", 400_000),
          FeedVideoRenditionDto("720p", 720, 1280, 1400, "/v1/media/f/vid1_720.mp4", 1_200_000),
        ),
        width = 1080,
        height = 1920,
        durationMs = 15_000L,
        likesCount = 128_420L,
        commentsCount = 2_345L,
        sharesCount = 8_765L,
        favouritesCount = 32_100L,
        recommendationLabel = "You might like this",
      ),
      FeedItemDto(
        postId = "post_other",
        authorUserId = "u_2",
        authorUsername = "amara_reads",
        authorFullName = "Amara Okafor",
        isOwner = false,
        mode = "video",
        caption = "Sunset horizon",
        hashtags = listOf("sunset"),
        videoUrl = "/v1/media/f/vid2.mp4",
        width = 1920,
        height = 1080,
        durationMs = 12_000L,
        likesCount = 500L,
        recommendationLabel = "You might like this",
      ),
    )

    override suspend fun getFeeds(
      bearer: String,
      deviceFingerprint: String,
      tab: String,
      page: Int,
      pageSize: Int,
    ): Response<FeedPageResponseDto> =
      Response.success(
        FeedPageResponseDto(
          success = true,
          tab = tab,
          items = feedItems,
          page = page,
          pageSize = pageSize,
          hasMore = false,
        ),
      )

    override suspend fun searchFeeds(
      bearer: String,
      deviceFingerprint: String,
      query: String,
      shortcut: String,
      page: Int,
      pageSize: Int,
    ): Response<FeedSearchResponseDto> =
      Response.success(
        FeedSearchResponseDto(
          success = true,
          query = query,
          shortcut = shortcut,
          shortcuts = listOf(SearchShortcutDto("nightvibes", "#nightvibes", 12)),
          items = feedItems,
          page = page,
          pageSize = pageSize,
          hasMore = false,
        ),
      )

    override suspend fun interactFeed(
      bearer: String,
      deviceFingerprint: String,
      body: FeedInteractionRequestDto,
    ): Response<FeedInteractionResponseDto> =
      Response.success(FeedInteractionResponseDto(success = true, postId = body.postId, action = body.action))

    override suspend fun interactFeedBatch(
      bearer: String,
      deviceFingerprint: String,
      body: FeedInteractionBatchRequestDto,
    ): Response<SyncBatchResponseDto> {
      batchedActions.addAll(body.operations)
      return Response.success(SyncBatchResponseDto(success = true, ackedOpIds = body.operations.map { it.clientOpId }))
    }

    override suspend fun updatePost(
      bearer: String,
      deviceFingerprint: String,
      postId: String,
      body: UpdatePostRequestDto,
    ): Response<CreatePostResponseDto> =
      Response.success(CreatePostResponseDto(success = true, post = CreatedPostDto(postId = postId)))

    override suspend fun deletePost(
      bearer: String,
      deviceFingerprint: String,
      postId: String,
    ): Response<GenericSuccessDto> {
      feedItems.removeAll { it.postId == postId }
      return Response.success(GenericSuccessDto(success = true))
    }

    override suspend fun requestOtp(deviceFingerprint: String, ipFingerprint: String, body: RequestOtpDto): Response<RequestOtpResponseDto> =
      Response.success(RequestOtpResponseDto(success = true))

    override suspend fun verifyOtp(deviceFingerprint: String, ipFingerprint: String, body: VerifyOtpDto): Response<AuthSessionResponseDto> =
      Response.success(AuthSessionResponseDto(success = true))

    override suspend fun verifyLink(deviceFingerprint: String, ipFingerprint: String, body: VerifyLinkDto): Response<AuthSessionResponseDto> =
      Response.success(AuthSessionResponseDto(success = true))

    override suspend fun refreshToken(deviceFingerprint: String, body: RefreshTokenDto): Response<AuthSessionResponseDto> =
      Response.success(AuthSessionResponseDto(success = true))

    override suspend fun verifyGoogleOAuth(deviceFingerprint: String, ipFingerprint: String, body: OAuthDto): Response<AuthSessionResponseDto> =
      Response.success(AuthSessionResponseDto(success = true))

    override suspend fun checkUsername(username: String): Response<UsernameCheckResponseDto> =
      Response.success(UsernameCheckResponseDto(available = true))

    override suspend fun uploadAvatar(bearer: String, deviceFingerprint: String, cropCenterX: Float, cropCenterY: Float, cropScale: Float, imageBody: RequestBody): Response<MediaUploadResponseDto> =
      Response.success(MediaUploadResponseDto(success = true))

    override suspend fun uploadPostPhoto(bearer: String, deviceFingerprint: String, imageBody: RequestBody): Response<MediaUploadResponseDto> =
      Response.success(MediaUploadResponseDto(success = true))

    override suspend fun uploadPostVideo(bearer: String, deviceFingerprint: String, frameHashesCsv: String, durationMs: Long, trimStartMs: Long, trimEndMs: Long, width: Int, height: Int, videoPart: MultipartBody.Part, thumbnailPart: MultipartBody.Part?): Response<MediaUploadResponseDto> =
      Response.success(MediaUploadResponseDto(success = true))

    override suspend fun searchSongs(bearer: String, deviceFingerprint: String, query: String): Response<SongsSearchResponseDto> =
      Response.success(SongsSearchResponseDto(success = true))

    override suspend fun searchHashtags(bearer: String, deviceFingerprint: String, query: String): Response<HashtagsSearchResponseDto> =
      Response.success(HashtagsSearchResponseDto(success = true))

    override suspend fun createPost(bearer: String, deviceFingerprint: String, body: CreatePostRequestDto): Response<CreatePostResponseDto> =
      Response.success(CreatePostResponseDto(success = true, post = CreatedPostDto(postId = "p1", clientOpId = body.clientOpId)))

    override suspend fun setupProfile(bearer: String, deviceFingerprint: String, body: ProfileSetupDto): Response<GenericSuccessDto> =
      Response.success(GenericSuccessDto(success = true))

    override suspend fun recordAuditEvent(bearer: String, deviceFingerprint: String, body: AuditEventDto): Response<GenericSuccessDto> =
      Response.success(GenericSuccessDto(success = true))

    override suspend fun syncBatch(bearer: String, deviceFingerprint: String, body: SyncBatchRequestDto): Response<SyncBatchResponseDto> =
      Response.success(SyncBatchResponseDto(success = true))

    override suspend fun listContacts(bearer: String, deviceFingerprint: String, filter: String, query: String, page: Int, pageSize: Int): Response<ContactsPageResponseDto> =
      Response.success(ContactsPageResponseDto(success = true))

    override suspend fun followContact(bearer: String, deviceFingerprint: String, body: ContactActionDto): Response<FollowMutationResponseDto> =
      Response.success(FollowMutationResponseDto(success = true))

    override suspend fun unfollowContact(bearer: String, deviceFingerprint: String, body: ContactActionDto): Response<FollowMutationResponseDto> =
      Response.success(FollowMutationResponseDto(success = true))

    override suspend fun syncContactActions(bearer: String, deviceFingerprint: String, body: ContactSyncBatchRequestDto): Response<SyncBatchResponseDto> =
      Response.success(SyncBatchResponseDto(success = true))

    override suspend fun downloadMedia(url: String): Response<ResponseBody> =
      Response.success(ByteArray(0).toResponseBody("application/octet-stream".toMediaType()))
  }
}
