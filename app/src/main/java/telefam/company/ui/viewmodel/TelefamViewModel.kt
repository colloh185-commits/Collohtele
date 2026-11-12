package telefam.company.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.telefam.company.app.BuildConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import telefam.company.core.AdaptiveStreamSelector
import telefam.company.core.ContactFilterTab
import telefam.company.core.ContactRelationshipState
import telefam.company.core.FeedInteractionAction
import telefam.company.core.FeedSearchShortcut
import telefam.company.core.FeedTab
import telefam.company.core.FeedVideoRendition
import telefam.company.core.HashtagItem
import telefam.company.core.NetworkTier
import telefam.company.core.PostFontOption
import telefam.company.core.PostMode
import telefam.company.core.PostSongTrack
import telefam.company.core.PostUploadStatus
import telefam.company.core.TelefamContactsRules
import telefam.company.core.TelefamCoreEngine
import telefam.company.core.TelefamPostRules
import telefam.company.core.TelefamProfileSanitizer
import telefam.company.data.local.CachedFeedPostEntity
import telefam.company.data.model.CountryCatalog
import telefam.company.data.model.CountryDialOption
import telefam.company.data.repository.AuthActionResult
import telefam.company.data.repository.ContactToggleResult
import telefam.company.data.repository.PostPublishResult
import telefam.company.data.repository.PostSubmissionInput
import telefam.company.data.repository.ProfileActionResult
import telefam.company.data.repository.TelefamRepository
import telefam.company.media.PreparedPostVideo
import telefam.company.media.ProcessedAvatarMedia
import telefam.company.media.ProcessedPostPhoto
import telefam.company.media.TelefamVideoDownloader
import telefam.company.media.VideoDownloadResult
import telefam.company.media.VideoPrepareResult
import telefam.company.ui.theme.ThemeMode

enum class AuthStep {
  EMAIL_INPUT,
  OTP_VERIFICATION,
}

enum class HomeTab {
  CHATS,
  CONTACTS,
  POST,
  FEEDS,
  PROFILE,
}

data class ProfileFormState(
  val fullName: String = "",
  val username: String = "",
  val isUsernameAvailable: Boolean? = null,
  val selectedCountry: CountryDialOption = CountryCatalog.defaultCountry,
  val phoneDigits: String = "",
  val gender: String = "",
  val dobYear: Int? = null,
  val dobMonthZeroBased: Int? = null,
  val dobDay: Int? = null,
  val dobDisplay: String = "",
  val location: String = "",
  val bio: String = "",
  val rawBitmapForCrop: Bitmap? = null,
  val processedAvatar: ProcessedAvatarMedia? = null,
  val isSaving: Boolean = false,
  val errorMessage: String? = null,
)

data class ContactUiItem(
  val userId: String,
  val fullName: String,
  val username: String,
  val countryDisplay: String,
  val avatarLocalPath: String?,
  val isFollowing: Boolean,
  val followsYou: Boolean,
  val relationshipState: ContactRelationshipState,
  val initials: String,
)

data class ContactsUiState(
  val activeFilter: ContactFilterTab = ContactFilterTab.ALL,
  val searchQuery: String = "",
  val items: List<ContactUiItem> = emptyList(),
  val isLoading: Boolean = false,
  val isLoadingMore: Boolean = false,
  val hasMore: Boolean = false,
  val currentPage: Int = 1,
  val toastMessage: String? = null,
)

data class FeedUiItem(
  val postId: String,
  val authorUserId: String,
  val authorUsername: String,
  val authorFullName: String,
  val authorAvatarUrl: String?,
  val isFollowingAuthor: Boolean,
  val isOwner: Boolean,
  val mode: PostMode,
  val textContent: String,
  val caption: String,
  val themeId: String,
  val fontOption: PostFontOption,
  val song: PostSongTrack?,
  val hashtags: List<String>,
  val photoUrls: List<String>,
  val primaryStreamUrl: String,
  val rawDownloadUrl: String,
  val thumbnailUrl: String,
  val renditions: List<FeedVideoRendition>,
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
  val createdAtMs: Long,
)

data class FeedsUiState(
  val activeTab: FeedTab = FeedTab.FOR_YOU,
  val followingItems: List<FeedUiItem> = emptyList(),
  val forYouItems: List<FeedUiItem> = emptyList(),
  val isLoading: Boolean = false,
  val isLoadingMore: Boolean = false,
  val followingPage: Int = 1,
  val forYouPage: Int = 1,
  val hasMoreFollowing: Boolean = false,
  val hasMoreForYou: Boolean = false,
  val streamAccessToken: String = "",
  val streamDeviceFp: String = "",
  val isSearchOpen: Boolean = false,
  val searchQuery: String = "",
  val activeShortcut: String = "",
  val searchShortcuts: List<FeedSearchShortcut> = emptyList(),
  val searchResults: List<FeedUiItem> = emptyList(),
  val isSearching: Boolean = false,
  val searchViewerItems: List<FeedUiItem>? = null,
  val searchViewerInitialIndex: Int = 0,
  val toastMessage: String? = null,
) {
  val activeItems: List<FeedUiItem>
    get() = if (activeTab == FeedTab.FOLLOWING) followingItems else forYouItems

  val hasMoreActive: Boolean
    get() = if (activeTab == FeedTab.FOLLOWING) hasMoreFollowing else hasMoreForYou
}

data class PostComposerUiState(
  val isOpen: Boolean = false,
  val editingPostId: String? = null,
  val editingThumbnailUrl: String = "",
  val mode: PostMode = PostMode.TEXT,
  val textContent: String = "",
  val caption: String = "",
  val selectedThemeId: String = "crimson",
  val selectedFont: PostFontOption = PostFontOption.CLASSIC,
  val photos: List<ProcessedPostPhoto> = emptyList(),
  val video: PreparedPostVideo? = null,
  val videoTrimStartMs: Long = 0L,
  val videoTrimEndMs: Long = 0L,
  val muteOriginalAudio: Boolean = false,
  val selectedSong: PostSongTrack? = null,
  val taggedPeople: List<ContactUiItem> = emptyList(),
  val selectedHashtags: List<String> = emptyList(),
  val showTagPeopleSheet: Boolean = false,
  val tagPeopleQuery: String = "",
  val allDirectoryPeople: List<ContactUiItem> = emptyList(),
  val pendingTaggedIds: Set<String> = emptySet(),
  val showSongSheet: Boolean = false,
  val songQuery: String = "",
  val songResults: List<PostSongTrack> = emptyList(),
  val isSearchingSongs: Boolean = false,
  val playingPreviewTrackId: String? = null,
  val showHashtagsSheet: Boolean = false,
  val hashtagQuery: String = "",
  val hashtagResults: List<HashtagItem> = emptyList(),
  val isSearchingHashtags: Boolean = false,
  val uploadStatus: PostUploadStatus = PostUploadStatus.IDLE,
  val uploadProgressPct: Int = 0,
  val pendingQueueCount: Int = 0,
  val errorMessage: String? = null,
) {
  val canShare: Boolean
    get() = uploadStatus != PostUploadStatus.PREPARING &&
      uploadStatus != PostUploadStatus.UPLOADING &&
      when (mode) {
        PostMode.TEXT -> textContent.trim().isNotEmpty()
        PostMode.PHOTOS -> photos.isNotEmpty() || editingPostId != null
        PostMode.VIDEO -> (video != null && videoTrimEndMs > videoTrimStartMs) || editingPostId != null
      }
}

data class TelefamUiState(
  val isSessionInitialized: Boolean = false,
  val isAuthenticated: Boolean = false,
  val profileCompleted: Boolean = false,
  val userId: String = "",
  val userEmail: String = "",
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val authStep: AuthStep = AuthStep.EMAIL_INPUT,
  val emailInput: String = "",
  val otpCode: String = "",
  val isLoading: Boolean = false,
  val errorMessage: String? = null,
  val cooldownRemainingSeconds: Long = 0L,
  val activeCooldownDurationSeconds: Long = 60L,
  val networkTier: NetworkTier = NetworkTier.GOOD,
  val profileForm: ProfileFormState = ProfileFormState(),
  val savedAvatarLocalPath: String? = null,
  val activeHomeTab: HomeTab = HomeTab.CHATS,
  val isBottomBarVisible: Boolean = true,
  val contactsState: ContactsUiState = ContactsUiState(),
  val feedsState: FeedsUiState = FeedsUiState(),
  val postComposer: PostComposerUiState = PostComposerUiState(),
  val comingSoonMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TelefamViewModel(
  private val repository: TelefamRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(TelefamUiState())
  val uiState: StateFlow<TelefamUiState> = _uiState.asStateFlow()

  private var countdownJob: Job? = null
  private var usernameCheckJob: Job? = null
  private var contactsSearchJob: Job? = null
  private var feedSearchJob: Job? = null
  private var songSearchJob: Job? = null
  private var hashtagSearchJob: Job? = null
  private var previewPlayer: MediaPlayer? = null

  init {
    viewModelScope.launch {
      // Refresh short-lived access token silently on launch if long-lived refresh token is valid
      repository.ensureSessionFresh()

      combine(repository.sessionFlow, repository.networkTier) { session, tier ->
        session to tier
      }.collect { (session, tier) ->
        val wasOffline = _uiState.value.networkTier == NetworkTier.OFFLINE
        val isAuthenticated = !session.userId.isNullOrBlank() && !session.accessToken.isNullOrBlank()
        val email = session.email.orEmpty()
        val userId = session.userId.orEmpty()

        val savedProfile = if (isAuthenticated && userId.isNotBlank()) {
          repository.observeProfile(userId).first()
        } else {
          null
        }

        _uiState.update { current ->
          val shouldAutofill = isAuthenticated && !session.profileCompleted &&
            current.profileForm.fullName.isBlank() && email.isNotBlank()
          val updatedForm = if (shouldAutofill) {
            val (autoName, autoUsername) = TelefamProfileSanitizer.autofillFromEmail(email)
            current.profileForm.copy(
              fullName = autoName,
              username = autoUsername,
              isUsernameAvailable = true,
            )
          } else {
            current.profileForm
          }

          current.copy(
            isSessionInitialized = true,
            isAuthenticated = isAuthenticated,
            profileCompleted = session.profileCompleted,
            userId = userId,
            userEmail = email,
            themeMode = session.themeMode,
            networkTier = tier,
            profileForm = updatedForm,
            savedAvatarLocalPath = savedProfile?.avatarLocalPath ?: current.savedAvatarLocalPath,
          )
        }

        if (!isAuthenticated && !session.pendingOtpEmail.isNullOrBlank() &&
          _uiState.value.authStep == AuthStep.EMAIL_INPUT &&
          _uiState.value.emailInput.isBlank()
        ) {
          val pendingEmail = session.pendingOtpEmail
          val (rem, total) = repository.getCurrentCooldownForEmail(pendingEmail)
          _uiState.update {
            it.copy(
              emailInput = pendingEmail,
              authStep = AuthStep.OTP_VERIFICATION,
              cooldownRemainingSeconds = rem,
              activeCooldownDurationSeconds = total,
            )
          }
          if (rem > 0L) startCountdown(rem)
        }

        if (wasOffline && tier != NetworkTier.OFFLINE && isAuthenticated) {
          repository.flushOfflineQueue()
          when (_uiState.value.activeHomeTab) {
            HomeTab.CONTACTS -> refreshContacts()
            HomeTab.FEEDS -> refreshFeeds()
            else -> Unit
          }
        }
      }
    }

    // Reactive Contacts cache observer keyed on filter tab, search query, and authenticated userId
    viewModelScope.launch {
      _uiState
        .map { Triple(it.contactsState.activeFilter, it.contactsState.searchQuery, it.userId to it.isAuthenticated) }
        .distinctUntilChanged()
        .flatMapLatest { (filter, query, auth) ->
          val (uid, authed) = auth
          if (!authed || uid.isBlank()) {
            flowOf(emptyList())
          } else {
            repository.observeCachedContacts(filter, query, uid)
          }
        }
        .collect { cached ->
          val countryByIso = CountryCatalog.allCountries.associateBy { it.iso.uppercase() }
          val uiItems = cached.map { entity ->
            val countryName = countryByIso[entity.countryIso.uppercase()]?.name
              ?: entity.location.ifBlank { entity.countryIso }
            ContactUiItem(
              userId = entity.userId,
              fullName = entity.fullName,
              username = entity.username,
              countryDisplay = countryName,
              avatarLocalPath = entity.avatarLocalPath,
              isFollowing = entity.isFollowing,
              followsYou = entity.followsYou,
              relationshipState = ContactRelationshipState.compute(entity.isFollowing, entity.followsYou),
              initials = TelefamContactsRules.initialsFor(entity.fullName, entity.username),
            )
          }
          _uiState.update { state ->
            state.copy(contactsState = state.contactsState.copy(items = uiItems))
          }
        }
    }

    // Reactive full directory observer for Post "Tag people" sheet
    viewModelScope.launch {
      _uiState
        .map { it.userId to it.isAuthenticated }
        .distinctUntilChanged()
        .flatMapLatest { (uid, authed) ->
          if (!authed || uid.isBlank()) {
            flowOf(emptyList())
          } else {
            repository.observeCachedContacts(ContactFilterTab.ALL, "", uid)
          }
        }
        .collect { cachedAll ->
          val countryByIso = CountryCatalog.allCountries.associateBy { it.iso.uppercase() }
          val items = cachedAll.map { entity ->
            val countryName = countryByIso[entity.countryIso.uppercase()]?.name
              ?: entity.location.ifBlank { entity.countryIso }
            ContactUiItem(
              userId = entity.userId,
              fullName = entity.fullName,
              username = entity.username,
              countryDisplay = countryName,
              avatarLocalPath = entity.avatarLocalPath,
              isFollowing = entity.isFollowing,
              followsYou = entity.followsYou,
              relationshipState = ContactRelationshipState.compute(entity.isFollowing, entity.followsYou),
              initials = TelefamContactsRules.initialsFor(entity.fullName, entity.username),
            )
          }
          _uiState.update { state ->
            val selectedIds = state.postComposer.taggedPeople.map { it.userId }.toSet()
            state.copy(
              postComposer = state.postComposer.copy(
                allDirectoryPeople = items,
                taggedPeople = items.filter { it.userId in selectedIds },
              ),
            )
          }
        }
    }

    // Observe background queued post uploads count
    viewModelScope.launch {
      repository.pendingPostUploadsFlow.collect { pending ->
        _uiState.update { state ->
          state.copy(postComposer = state.postComposer.copy(pendingQueueCount = pending.size))
        }
      }
    }

    // Observe cached For You + Following feeds adaptively
    viewModelScope.launch {
      combine(
        _uiState.map { Triple(it.userId, it.isAuthenticated, it.networkTier) }.distinctUntilChanged(),
        repository.observeFeedPosts(FeedTab.FOR_YOU),
        repository.observeFeedPosts(FeedTab.FOLLOWING),
      ) { (_, authed, tier), forYouEntities, followingEntities ->
        if (!authed) {
          Triple(emptyList<FeedUiItem>(), emptyList<FeedUiItem>(), tier)
        } else {
          Triple(
            forYouEntities.map { mapFeedEntityToUi(it, tier) },
            followingEntities.map { mapFeedEntityToUi(it, tier) },
            tier,
          )
        }
      }.collect { (forYouUi, followingUi, _) ->
        _uiState.update { state ->
          val currentSearchViewer = state.feedsState.searchViewerItems?.mapNotNull { old ->
            val updated = (forYouUi + followingUi).firstOrNull { it.postId == old.postId }
            updated ?: old.takeIf { _ -> forYouUi.isEmpty() && followingUi.isEmpty() } ?: updated
          }
          state.copy(
            feedsState = state.feedsState.copy(
              forYouItems = forYouUi,
              followingItems = followingUi,
              searchViewerItems = currentSearchViewer,
            ),
          )
        }
      }
    }
  }

  fun cycleThemeMode() {
    val next = when (_uiState.value.themeMode) {
      ThemeMode.SYSTEM -> ThemeMode.LIGHT
      ThemeMode.LIGHT -> ThemeMode.DARK
      ThemeMode.DARK -> ThemeMode.SYSTEM
    }
    viewModelScope.launch {
      repository.setThemeMode(next)
      val uid = _uiState.value.userId
      if (uid.isNotBlank()) {
        repository.logAudit(uid, "theme_changed", "{\"mode\":\"${next.name}\"}")
      }
    }
  }

  fun onEmailChanged(value: String) {
    _uiState.update { it.copy(emailInput = value, errorMessage = null) }
  }

  fun onOtpChanged(value: String) {
    val digitsOnly = value.filter { it.isDigit() }.take(6)
    _uiState.update { it.copy(otpCode = digitsOnly, errorMessage = null) }
  }

  fun sendOtp() {
    val email = _uiState.value.emailInput.trim()
    if (!isValidEmail(email)) {
      _uiState.update { it.copy(errorMessage = "Please enter a valid email address.") }
      return
    }
    if (_uiState.value.isLoading) return

    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null) }
      when (val result = repository.requestOtp(email)) {
        is AuthActionResult.OtpSent -> {
          _uiState.update {
            it.copy(
              isLoading = false,
              authStep = AuthStep.OTP_VERIFICATION,
              emailInput = result.email,
              otpCode = "",
              errorMessage = null,
              cooldownRemainingSeconds = result.cooldownSeconds,
              activeCooldownDurationSeconds = result.totalCooldownDurationSeconds,
            )
          }
          startCountdown(result.cooldownSeconds)
        }

        is AuthActionResult.SanitizedFailure -> {
          val rem = result.remainingCooldownSeconds
          val (_, total) = repository.getCurrentCooldownForEmail(email)
          _uiState.update {
            it.copy(
              isLoading = false,
              errorMessage = if (rem > 0L) null else result.message,
              authStep = if (rem > 0L) AuthStep.OTP_VERIFICATION else it.authStep,
              cooldownRemainingSeconds = if (rem > 0L) rem else it.cooldownRemainingSeconds,
              activeCooldownDurationSeconds = total,
            )
          }
          if (rem > 0L) startCountdown(rem)
        }

        is AuthActionResult.Verified -> _uiState.update { it.copy(isLoading = false) }
      }
    }
  }

  fun resendOtp() {
    if (_uiState.value.cooldownRemainingSeconds > 0L || _uiState.value.isLoading) return
    sendOtp()
  }

  fun verifyOtp() {
    val state = _uiState.value
    if (state.otpCode.length != 6) {
      _uiState.update { it.copy(errorMessage = "Please enter the 6-digit verification code.") }
      return
    }
    if (state.isLoading) return

    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null) }
      when (val result = repository.verifyOtp(state.emailInput, state.otpCode)) {
        is AuthActionResult.Verified -> onAuthVerified(result)
        is AuthActionResult.SanitizedFailure -> {
          _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
        }
        is AuthActionResult.OtpSent -> Unit
      }
    }
  }

  fun handleVerificationDeepLink(token: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null) }
      when (val result = repository.verifyMagicLink(token, _uiState.value.emailInput)) {
        is AuthActionResult.Verified -> onAuthVerified(result)
        is AuthActionResult.SanitizedFailure -> {
          _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
        }
        is AuthActionResult.OtpSent -> Unit
      }
    }
  }

  fun onGoogleSignInSuccess(idToken: String, email: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null) }
      when (val result = repository.authenticateWithGoogle(idToken, email)) {
        is AuthActionResult.Verified -> onAuthVerified(result)
        is AuthActionResult.SanitizedFailure -> {
          _uiState.update { it.copy(isLoading = false, errorMessage = result.message) }
        }
        is AuthActionResult.OtpSent -> Unit
      }
    }
  }

  fun onGoogleSignInError() {
    _uiState.update {
      it.copy(isLoading = false, errorMessage = TelefamCoreEngine.sanitizeServerError(401))
    }
  }

  fun changeEmail() {
    countdownJob?.cancel()
    viewModelScope.launch { repository.savePendingOtpEmail(null) }
    _uiState.update {
      it.copy(
        authStep = AuthStep.EMAIL_INPUT,
        otpCode = "",
        errorMessage = null,
        cooldownRemainingSeconds = 0L,
      )
    }
  }

  // Profile Setup handlers
  fun onProfileFullNameChanged(value: String) {
    _uiState.update {
      it.copy(profileForm = it.profileForm.copy(fullName = value.take(64), errorMessage = null))
    }
  }

  fun onProfileUsernameChanged(value: String) {
    val clean = TelefamProfileSanitizer.sanitizeUsernameInput(value)
    _uiState.update {
      it.copy(
        profileForm = it.profileForm.copy(
          username = clean,
          isUsernameAvailable = null,
          errorMessage = null,
        ),
      )
    }
    usernameCheckJob?.cancel()
    if (TelefamProfileSanitizer.isValidUsername(clean)) {
      usernameCheckJob = viewModelScope.launch {
        delay(250L)
        val avail = repository.isUsernameAvailable(clean, _uiState.value.userId)
        _uiState.update {
          it.copy(profileForm = it.profileForm.copy(isUsernameAvailable = avail))
        }
      }
    }
  }

  fun onProfileCountrySelected(country: CountryDialOption) {
    _uiState.update {
      it.copy(profileForm = it.profileForm.copy(selectedCountry = country, errorMessage = null))
    }
  }

  fun onProfilePhoneDigitsChanged(value: String) {
    val digits = value.filter { it.isDigit() }.take(15)
    _uiState.update {
      it.copy(profileForm = it.profileForm.copy(phoneDigits = digits, errorMessage = null))
    }
  }

  fun onProfileGenderSelected(gender: String) {
    _uiState.update {
      it.copy(profileForm = it.profileForm.copy(gender = gender, errorMessage = null))
    }
  }

  fun onProfileDobSelected(year: Int, monthZeroBased: Int, day: Int) {
    val formatted = String.format("%04d-%02d-%02d", year, monthZeroBased + 1, day)
    val ageError = if (!TelefamProfileSanitizer.isAtLeast13YearsOld(year, monthZeroBased, day)) {
      "You must be at least 13 years old to use Telefam."
    } else {
      null
    }
    _uiState.update {
      it.copy(
        profileForm = it.profileForm.copy(
          dobYear = year,
          dobMonthZeroBased = monthZeroBased,
          dobDay = day,
          dobDisplay = formatted,
          errorMessage = ageError,
        ),
      )
    }
  }

  fun onProfileLocationChanged(value: String) {
    _uiState.update {
      it.copy(profileForm = it.profileForm.copy(location = value.take(80), errorMessage = null))
    }
  }

  fun onProfileBioChanged(value: String) {
    _uiState.update {
      it.copy(profileForm = it.profileForm.copy(bio = value.take(150), errorMessage = null))
    }
  }

  fun onPhotoPickedForCrop(bitmap: Bitmap) {
    _uiState.update {
      it.copy(profileForm = it.profileForm.copy(rawBitmapForCrop = bitmap, errorMessage = null))
    }
  }

  fun dismissPhotoCropper() {
    _uiState.update {
      it.copy(profileForm = it.profileForm.copy(rawBitmapForCrop = null))
    }
  }

  fun confirmCroppedPhoto(bitmap: Bitmap, centerX: Float, centerY: Float, zoomScale: Float) {
    val userId = _uiState.value.userId.ifBlank { "local_user" }
    _uiState.update {
      it.copy(profileForm = it.profileForm.copy(rawBitmapForCrop = null, isSaving = true))
    }
    viewModelScope.launch {
      val processed = repository.processAndUploadAvatar(userId, bitmap, centerX, centerY, zoomScale)
      _uiState.update {
        it.copy(
          profileForm = it.profileForm.copy(
            processedAvatar = processed,
            isSaving = false,
            errorMessage = null,
          ),
          savedAvatarLocalPath = processed.localFilePath,
        )
      }
    }
  }

  fun submitProfileSetup() {
    val state = _uiState.value
    val form = state.profileForm
    if (form.isSaving) return

    val dobYear = form.dobYear
    val dobMonth = form.dobMonthZeroBased
    val dobDay = form.dobDay
    if (dobYear == null || dobMonth == null || dobDay == null) {
      _uiState.update {
        it.copy(profileForm = form.copy(errorMessage = "Please complete all required profile fields."))
      }
      return
    }

    viewModelScope.launch {
      _uiState.update { it.copy(profileForm = form.copy(isSaving = true, errorMessage = null)) }
      val res = repository.saveProfileSetup(
        userId = state.userId,
        email = state.userEmail,
        fullNameRaw = form.fullName,
        usernameRaw = form.username,
        dialCode = form.selectedCountry.dialCode,
        phoneDigitsRaw = form.phoneDigits,
        countryIso = form.selectedCountry.iso,
        genderRaw = form.gender,
        dobYear = dobYear,
        dobMonthZeroBased = dobMonth,
        dobDay = dobDay,
        locationRaw = form.location,
        bioRaw = form.bio,
        avatar = form.processedAvatar,
      )
      when (res) {
        is ProfileActionResult.Saved -> {
          _uiState.update {
            it.copy(
              profileCompleted = true,
              profileForm = it.profileForm.copy(isSaving = false, errorMessage = null),
            )
          }
        }
        is ProfileActionResult.Error -> {
          _uiState.update {
            it.copy(profileForm = it.profileForm.copy(isSaving = false, errorMessage = res.message))
          }
        }
      }
    }
  }

  // Home screen & Contacts / Feeds interactions + audit logging
  fun selectHomeTab(tab: HomeTab) {
    if (tab == HomeTab.POST) {
      openPostComposer()
      return
    }
    _uiState.update {
      it.copy(
        activeHomeTab = tab,
        isBottomBarVisible = true,
        comingSoonMessage = if (tab == HomeTab.PROFILE) {
          "Profile — Coming soon"
        } else {
          null
        },
      )
    }
    when (tab) {
      HomeTab.CONTACTS -> refreshContacts()
      HomeTab.FEEDS -> {
        viewModelScope.launch {
          val (tok, fp) = repository.getStreamCredentials()
          _uiState.update { s ->
            s.copy(feedsState = s.feedsState.copy(streamAccessToken = tok, streamDeviceFp = fp))
          }
        }
        refreshFeeds()
      }
      else -> Unit
    }
    logClientAction("nav_tab_${tab.name.lowercase()}")
  }

  // Feeds & Reusable Search Viewer handlers
  fun selectFeedTab(tab: FeedTab) {
    _uiState.update {
      it.copy(feedsState = it.feedsState.copy(activeTab = tab))
    }
    refreshFeeds(tab)
    logClientAction("feed_tab_${tab.apiParam}")
  }

  fun refreshFeeds(tab: FeedTab = _uiState.value.feedsState.activeTab) {
    if (!_uiState.value.isAuthenticated) return
    viewModelScope.launch {
      _uiState.update { it.copy(feedsState = it.feedsState.copy(isLoading = true)) }
      val res = repository.loadFeedPage(tab = tab, page = 1, pageSize = 15)
      _uiState.update { cur ->
        cur.copy(
          feedsState = if (tab == FeedTab.FOLLOWING) {
            cur.feedsState.copy(
              isLoading = false,
              followingPage = res.page,
              hasMoreFollowing = res.hasMore,
            )
          } else {
            cur.feedsState.copy(
              isLoading = false,
              forYouPage = res.page,
              hasMoreForYou = res.hasMore,
            )
          },
        )
      }
    }
  }

  fun loadNextFeedPage(tab: FeedTab = _uiState.value.feedsState.activeTab) {
    val fs = _uiState.value.feedsState
    if (!_uiState.value.isAuthenticated || fs.isLoading || fs.isLoadingMore) return
    val hasMore = if (tab == FeedTab.FOLLOWING) fs.hasMoreFollowing else fs.hasMoreForYou
    if (!hasMore) return
    val nextPage = (if (tab == FeedTab.FOLLOWING) fs.followingPage else fs.forYouPage) + 1
    viewModelScope.launch {
      _uiState.update { it.copy(feedsState = it.feedsState.copy(isLoadingMore = true)) }
      val res = repository.loadFeedPage(tab = tab, page = nextPage, pageSize = 15)
      _uiState.update { cur ->
        cur.copy(
          feedsState = if (tab == FeedTab.FOLLOWING) {
            cur.feedsState.copy(
              isLoadingMore = false,
              followingPage = res.page,
              hasMoreFollowing = res.hasMore,
            )
          } else {
            cur.feedsState.copy(
              isLoadingMore = false,
              forYouPage = res.page,
              hasMoreForYou = res.hasMore,
            )
          },
        )
      }
    }
  }

  fun onToggleFeedLike(postId: String, currentlyLiked: Boolean) {
    val action = if (currentlyLiked) FeedInteractionAction.UNLIKE else FeedInteractionAction.LIKE
    mutateSearchResultOptimistically(postId) { item ->
      item.copy(
        isLiked = !currentlyLiked,
        likesCount = if (currentlyLiked) (item.likesCount - 1L).coerceAtLeast(0L) else item.likesCount + 1L,
      )
    }
    viewModelScope.launch { repository.performFeedInteraction(postId, action) }
  }

  fun onDoubleTapFeedLike(postId: String) {
    val item = (_uiState.value.feedsState.activeItems + _uiState.value.feedsState.searchResults)
      .firstOrNull { it.postId == postId }
    if (item != null && !item.isLiked) {
      onToggleFeedLike(postId, currentlyLiked = false)
    }
  }

  fun onToggleFeedFavourite(postId: String, currentlyFavourited: Boolean) {
    val action = if (currentlyFavourited) FeedInteractionAction.UNFAVOURITE else FeedInteractionAction.FAVOURITE
    mutateSearchResultOptimistically(postId) { item ->
      item.copy(
        isFavourited = !currentlyFavourited,
        favouritesCount = if (currentlyFavourited) (item.favouritesCount - 1L).coerceAtLeast(0L) else item.favouritesCount + 1L,
      )
    }
    viewModelScope.launch { repository.performFeedInteraction(postId, action) }
  }

  fun onToggleFeedRepost(postId: String, currentlyReposted: Boolean) {
    val action = if (currentlyReposted) FeedInteractionAction.UNREPOST else FeedInteractionAction.REPOST
    mutateSearchResultOptimistically(postId) { item ->
      item.copy(
        isReposted = !currentlyReposted,
        repostsCount = if (currentlyReposted) (item.repostsCount - 1L).coerceAtLeast(0L) else item.repostsCount + 1L,
      )
    }
    viewModelScope.launch {
      repository.performFeedInteraction(postId, action)
      if (!currentlyReposted) {
        _uiState.update { it.copy(feedsState = it.feedsState.copy(toastMessage = "Reposted")) }
      }
    }
  }

  fun onShareFeedPost(postId: String) {
    mutateSearchResultOptimistically(postId) { item ->
      item.copy(sharesCount = item.sharesCount + 1L)
    }
    viewModelScope.launch { repository.performFeedInteraction(postId, FeedInteractionAction.SHARE) }
  }

  fun onNotInterestedFeedPost(postId: String) {
    removeFromSearchLists(postId)
    viewModelScope.launch {
      repository.performFeedInteraction(postId, FeedInteractionAction.NOT_INTERESTED)
    }
  }

  fun onReportFeedPost(postId: String, reason: String, details: String = "") {
    viewModelScope.launch {
      repository.performFeedInteraction(
        postId = postId,
        action = FeedInteractionAction.REPORT,
        reason = reason,
        details = details,
      )
      _uiState.update { it.copy(feedsState = it.feedsState.copy(toastMessage = "Report submitted")) }
    }
  }

  fun onBlockFeedAuthor(postId: String, authorUserId: String) {
    _uiState.update { s ->
      s.copy(
        feedsState = s.feedsState.copy(
          searchResults = s.feedsState.searchResults.filterNot { it.authorUserId == authorUserId },
          searchViewerItems = s.feedsState.searchViewerItems?.filterNot { it.authorUserId == authorUserId },
        ),
      )
    }
    viewModelScope.launch {
      repository.performFeedInteraction(postId, FeedInteractionAction.BLOCK)
    }
  }

  fun onToggleFeedAuthorFollow(authorUserId: String, currentlyFollowing: Boolean) {
    _uiState.update { s ->
      s.copy(
        feedsState = s.feedsState.copy(
          searchResults = s.feedsState.searchResults.map {
            if (it.authorUserId == authorUserId) it.copy(isFollowingAuthor = !currentlyFollowing) else it
          },
          searchViewerItems = s.feedsState.searchViewerItems?.map {
            if (it.authorUserId == authorUserId) it.copy(isFollowingAuthor = !currentlyFollowing) else it
          },
        ),
      )
    }
    viewModelScope.launch {
      when (val res = repository.toggleFollowAuthorFromFeed(authorUserId, currentlyFollowing)) {
        is ContactToggleResult.RateLimited -> {
          _uiState.update { it.copy(feedsState = it.feedsState.copy(toastMessage = res.message)) }
        }
        else -> Unit
      }
    }
  }

  fun onRecordFeedWatchProgress(postId: String, watchMs: Long, durationMs: Long) {
    if (watchMs <= 0L) return
    val action = if (watchMs < 1500L && durationMs > 4000L) {
      FeedInteractionAction.SKIP
    } else {
      FeedInteractionAction.WATCH
    }
    viewModelScope.launch {
      repository.performFeedInteraction(postId, action, watchMs = watchMs, durationMs = durationMs)
    }
  }

  fun onDownloadFeedVideo(context: Context, item: FeedUiItem) {
    viewModelScope.launch {
      val (token, fp) = repository.getStreamCredentials()
      val targetUrl = item.rawDownloadUrl.ifBlank { item.primaryStreamUrl }
      val result = TelefamVideoDownloader.downloadVideoToDevice(
        context = context,
        postId = item.postId,
        sourceUrlOrPath = targetUrl,
        bearerToken = token,
        deviceFingerprint = fp,
      )
      val msg = when (result) {
        is VideoDownloadResult.Saved -> "Saved to Downloads"
        is VideoDownloadResult.Failed -> result.reason
      }
      _uiState.update { it.copy(feedsState = it.feedsState.copy(toastMessage = msg)) }
      if (result is VideoDownloadResult.Saved) {
        logClientAction("feed_video_downloaded_${item.postId}")
      }
    }
  }

  fun onDeleteOwnerFeedPost(postId: String) {
    removeFromSearchLists(postId)
    viewModelScope.launch {
      repository.deleteOwnerPost(postId)
    }
  }

  fun openFeedSearch(initialQuery: String = "") {
    val cleanInit = TelefamProfileSanitizer.sanitizePlainText(initialQuery, 80, singleLine = true)
    _uiState.update {
      it.copy(
        feedsState = it.feedsState.copy(
          isSearchOpen = true,
          searchQuery = cleanInit,
          searchViewerItems = null,
        ),
      )
    }
    triggerFeedSearch(cleanInit, _uiState.value.feedsState.activeShortcut)
    logClientAction("feed_search_opened")
  }

  fun closeFeedSearch() {
    feedSearchJob?.cancel()
    _uiState.update {
      it.copy(
        feedsState = it.feedsState.copy(
          isSearchOpen = false,
          searchViewerItems = null,
        ),
      )
    }
  }

  fun onFeedSearchQueryChanged(query: String) {
    val clean = query.take(80)
    _uiState.update {
      it.copy(feedsState = it.feedsState.copy(searchQuery = clean))
    }
    feedSearchJob?.cancel()
    feedSearchJob = viewModelScope.launch {
      delay(260L)
      triggerFeedSearch(clean, _uiState.value.feedsState.activeShortcut)
    }
  }

  fun onFeedSearchShortcutSelected(shortcutKey: String) {
    val next = if (_uiState.value.feedsState.activeShortcut == shortcutKey) "" else shortcutKey
    _uiState.update {
      it.copy(feedsState = it.feedsState.copy(activeShortcut = next))
    }
    triggerFeedSearch(_uiState.value.feedsState.searchQuery, next)
  }

  fun openSearchResultViewer(index: Int) {
    val results = _uiState.value.feedsState.searchResults
    if (results.isEmpty()) return
    val safeIndex = index.coerceIn(0, results.lastIndex)
    _uiState.update {
      it.copy(
        feedsState = it.feedsState.copy(
          searchViewerItems = results,
          searchViewerInitialIndex = safeIndex,
        ),
      )
    }
  }

  fun closeSearchResultViewer() {
    _uiState.update {
      it.copy(feedsState = it.feedsState.copy(searchViewerItems = null))
    }
  }

  fun showFeedToast(message: String) {
    _uiState.update { it.copy(feedsState = it.feedsState.copy(toastMessage = message)) }
  }

  fun dismissFeedToast() {
    _uiState.update { it.copy(feedsState = it.feedsState.copy(toastMessage = null)) }
  }

  fun openPostWithHashtag(rawTag: String, preferredMode: PostMode = PostMode.VIDEO) {
    val clean = TelefamPostRules.sanitizeHashtag(rawTag)
    if (clean.isBlank()) {
      openPostComposer()
      return
    }
    _uiState.update { s ->
      s.copy(
        postComposer = PostComposerUiState(
          isOpen = true,
          editingPostId = null,
          mode = preferredMode,
          selectedHashtags = listOf(clean),
          allDirectoryPeople = s.postComposer.allDirectoryPeople,
          pendingQueueCount = s.postComposer.pendingQueueCount,
        ),
      )
    }
    logClientAction("post_autofill_hashtag_$clean")
  }

  fun openPostWithSong(song: PostSongTrack, preferredMode: PostMode = PostMode.VIDEO) {
    _uiState.update { s ->
      s.copy(
        postComposer = PostComposerUiState(
          isOpen = true,
          editingPostId = null,
          mode = preferredMode,
          selectedSong = song,
          allDirectoryPeople = s.postComposer.allDirectoryPeople,
          pendingQueueCount = s.postComposer.pendingQueueCount,
        ),
      )
    }
    logClientAction("post_autofill_song_${song.trackId}")
  }

  fun openPostForEdit(item: FeedUiItem) {
    _uiState.update { s ->
      s.copy(
        postComposer = PostComposerUiState(
          isOpen = true,
          editingPostId = item.postId,
          editingThumbnailUrl = item.thumbnailUrl,
          mode = item.mode,
          textContent = item.textContent,
          caption = item.caption,
          selectedThemeId = item.themeId.ifBlank { "crimson" },
          selectedFont = item.fontOption,
          selectedSong = item.song,
          selectedHashtags = item.hashtags,
          muteOriginalAudio = item.muteOriginalAudio,
          allDirectoryPeople = s.postComposer.allDirectoryPeople,
          pendingQueueCount = s.postComposer.pendingQueueCount,
        ),
      )
    }
    logClientAction("post_edit_opened_${item.postId}")
  }

  private fun triggerFeedSearch(query: String, shortcut: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(feedsState = it.feedsState.copy(isSearching = true)) }
      val tier = _uiState.value.networkTier
      val res = repository.searchFeeds(query = query, shortcut = shortcut, page = 1, pageSize = 24)
      val uiResults = res.items.map { mapFeedEntityToUi(it, tier) }
      _uiState.update { cur ->
        cur.copy(
          feedsState = cur.feedsState.copy(
            isSearching = false,
            searchShortcuts = if (res.shortcuts.isNotEmpty()) res.shortcuts else cur.feedsState.searchShortcuts,
            searchResults = uiResults,
          ),
        )
      }
    }
  }

  private fun mutateSearchResultOptimistically(postId: String, transform: (FeedUiItem) -> FeedUiItem) {
    _uiState.update { s ->
      s.copy(
        feedsState = s.feedsState.copy(
          searchResults = s.feedsState.searchResults.map { if (it.postId == postId) transform(it) else it },
          searchViewerItems = s.feedsState.searchViewerItems?.map { if (it.postId == postId) transform(it) else it },
        ),
      )
    }
  }

  private fun removeFromSearchLists(postId: String) {
    _uiState.update { s ->
      s.copy(
        feedsState = s.feedsState.copy(
          searchResults = s.feedsState.searchResults.filterNot { it.postId == postId },
          searchViewerItems = s.feedsState.searchViewerItems?.filterNot { it.postId == postId },
        ),
      )
    }
  }

  private fun mapFeedEntityToUi(entity: CachedFeedPostEntity, tier: NetworkTier): FeedUiItem {
    val renditions = repository.parseRenditions(entity.renditionsJson)
    val baseUrl = BuildConfig.TELEFAM_API_BASE_URL
    val streamUrl = AdaptiveStreamSelector.selectRenditionUrl(
      baseUrl = baseUrl,
      primaryVideoUrl = entity.videoUrl,
      renditions = renditions,
      networkTier = tier,
    )
    val rawDownload = if (entity.postId.isNotBlank() && baseUrl.isNotBlank()) {
      AdaptiveStreamSelector.resolveMediaUrl(baseUrl, "/v1/feeds/${entity.postId}/download")
    } else {
      streamUrl
    }
    return FeedUiItem(
      postId = entity.postId,
      authorUserId = entity.authorUserId,
      authorUsername = entity.authorUsername,
      authorFullName = entity.authorFullName,
      authorAvatarUrl = entity.authorAvatarUrl?.let { AdaptiveStreamSelector.resolveMediaUrl(baseUrl, it) },
      isFollowingAuthor = entity.isFollowingAuthor,
      isOwner = entity.isOwner,
      mode = PostMode.entries.firstOrNull { it.apiValue == entity.mode } ?: PostMode.VIDEO,
      textContent = entity.textContent,
      caption = entity.caption,
      themeId = entity.themeId.ifBlank { "crimson" },
      fontOption = TelefamPostRules.findFont(entity.fontId),
      song = repository.parseSongTrack(entity.songJson),
      hashtags = repository.parseStringList(entity.hashtagsJson),
      photoUrls = repository.parseStringList(entity.photoUrlsJson)
        .map { AdaptiveStreamSelector.resolveMediaUrl(baseUrl, it) },
      primaryStreamUrl = streamUrl,
      rawDownloadUrl = rawDownload,
      thumbnailUrl = AdaptiveStreamSelector.resolveMediaUrl(baseUrl, entity.thumbnailUrl),
      renditions = renditions,
      width = entity.width,
      height = entity.height,
      durationMs = entity.durationMs,
      muteOriginalAudio = entity.muteOriginalAudio,
      contentId = entity.contentId,
      likesCount = entity.likesCount,
      commentsCount = entity.commentsCount,
      sharesCount = entity.sharesCount,
      favouritesCount = entity.favouritesCount,
      repostsCount = entity.repostsCount,
      isLiked = entity.isLiked,
      isFavourited = entity.isFavourited,
      isReposted = entity.isReposted,
      repostedByUsername = entity.repostedByUsername,
      recommendationLabel = entity.recommendationLabel,
      createdAtMs = entity.createdAtMs,
    )
  }

  // New Post composer handlers
  fun openPostComposer() {
    _uiState.update {
      it.copy(
        postComposer = it.postComposer.copy(
          isOpen = true,
          errorMessage = null,
          uploadStatus = if (it.postComposer.uploadStatus == PostUploadStatus.UPLOADED) {
            PostUploadStatus.IDLE
          } else {
            it.postComposer.uploadStatus
          },
        ),
      )
    }
    logClientAction("post_composer_opened")
  }

  fun closePostComposer() {
    stopSongPreview()
    _uiState.update {
      it.copy(
        postComposer = it.postComposer.copy(
          isOpen = false,
          showTagPeopleSheet = false,
          showSongSheet = false,
          showHashtagsSheet = false,
          errorMessage = null,
        ),
      )
    }
  }

  fun onPostModeSelected(mode: PostMode) {
    _uiState.update {
      it.copy(postComposer = it.postComposer.copy(mode = mode, errorMessage = null))
    }
  }

  fun onPostTextChanged(value: String) {
    val limited = value.take(TelefamPostRules.MAX_TEXT_POST_CHARS)
    _uiState.update {
      it.copy(postComposer = it.postComposer.copy(textContent = limited, errorMessage = null))
    }
  }

  fun onPostCaptionChanged(value: String) {
    val limited = value.take(TelefamPostRules.MAX_CAPTION_CHARS)
    _uiState.update {
      it.copy(postComposer = it.postComposer.copy(caption = limited, errorMessage = null))
    }
  }

  fun onPostThemeSelected(themeId: String) {
    _uiState.update {
      it.copy(postComposer = it.postComposer.copy(selectedThemeId = themeId))
    }
  }

  fun onPostFontSelected(font: PostFontOption) {
    _uiState.update {
      it.copy(postComposer = it.postComposer.copy(selectedFont = font))
    }
  }

  fun onPostPhotosPicked(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val existingCount = _uiState.value.postComposer.photos.size
    if (existingCount >= TelefamPostRules.MAX_PHOTOS_COUNT) {
      _uiState.update {
        it.copy(postComposer = it.postComposer.copy(errorMessage = "Up to 10 photos allowed"))
      }
      return
    }
    _uiState.update {
      it.copy(postComposer = it.postComposer.copy(uploadStatus = PostUploadStatus.PREPARING, errorMessage = null))
    }
    viewModelScope.launch {
      val compressed = repository.compressPostPhotos(uris, existingCount)
      _uiState.update { state ->
        val merged = (state.postComposer.photos + compressed).take(TelefamPostRules.MAX_PHOTOS_COUNT)
        state.copy(
          postComposer = state.postComposer.copy(
            photos = merged,
            uploadStatus = PostUploadStatus.IDLE,
            errorMessage = if (compressed.isEmpty()) "Unable to process selected photos" else null,
          ),
        )
      }
    }
  }

  fun removePostPhoto(localId: String) {
    _uiState.update { state ->
      state.copy(
        postComposer = state.postComposer.copy(
          photos = state.postComposer.photos.filterNot { it.localId == localId },
        ),
      )
    }
  }

  fun onPostVideoPicked(uri: Uri?) {
    if (uri == null) return
    _uiState.update {
      it.copy(postComposer = it.postComposer.copy(uploadStatus = PostUploadStatus.PREPARING, errorMessage = null))
    }
    viewModelScope.launch {
      when (val res = repository.preparePostVideo(uri)) {
        is VideoPrepareResult.Ready -> {
          val vid = res.video
          _uiState.update { state ->
            state.copy(
              postComposer = state.postComposer.copy(
                video = vid,
                videoTrimStartMs = 0L,
                videoTrimEndMs = vid.durationMs,
                uploadStatus = PostUploadStatus.IDLE,
                errorMessage = null,
              ),
            )
          }
        }
        is VideoPrepareResult.Rejected -> {
          _uiState.update { state ->
            state.copy(
              postComposer = state.postComposer.copy(
                uploadStatus = PostUploadStatus.IDLE,
                errorMessage = res.reason,
              ),
            )
          }
        }
      }
    }
  }

  fun onVideoTrimChanged(startMs: Long, endMs: Long) {
    val video = _uiState.value.postComposer.video ?: return
    val clampedStart = startMs.coerceIn(0L, (video.durationMs - 500L).coerceAtLeast(0L))
    val clampedEnd = endMs.coerceIn(clampedStart + 500L, video.durationMs.coerceAtLeast(clampedStart + 500L))
    val updatedContentId = TelefamPostRules.computeVideoContentId(
      frameHashes = video.frames.map { it.dHashHex },
      durationMs = video.durationMs,
      trimStartMs = clampedStart,
      trimEndMs = clampedEnd,
    )
    _uiState.update { state ->
      state.copy(
        postComposer = state.postComposer.copy(
          video = video.copy(contentId = updatedContentId),
          videoTrimStartMs = clampedStart,
          videoTrimEndMs = clampedEnd,
        ),
      )
    }
  }

  fun onSelectVideoThumbnailFrame(frameIndex: Int) {
    val video = _uiState.value.postComposer.video ?: return
    val targetFrame = video.frames.firstOrNull { it.index == frameIndex } ?: return
    _uiState.update { state ->
      state.copy(
        postComposer = state.postComposer.copy(
          video = video.copy(
            selectedThumbnailIndex = targetFrame.index,
            selectedThumbnailPath = targetFrame.previewPath,
          ),
        ),
      )
    }
  }

  fun onToggleMuteOriginalAudio() {
    _uiState.update { state ->
      state.copy(
        postComposer = state.postComposer.copy(
          muteOriginalAudio = !state.postComposer.muteOriginalAudio,
        ),
      )
    }
  }

  fun clearPostVideo() {
    _uiState.update { state ->
      state.copy(
        postComposer = state.postComposer.copy(
          video = null,
          videoTrimStartMs = 0L,
          videoTrimEndMs = 0L,
        ),
      )
    }
  }

  // Tag people modal controls
  fun openTagPeopleSheet() {
    _uiState.update { state ->
      state.copy(
        postComposer = state.postComposer.copy(
          showTagPeopleSheet = true,
          tagPeopleQuery = "",
          pendingTaggedIds = state.postComposer.taggedPeople.map { it.userId }.toSet(),
        ),
      )
    }
    if (_uiState.value.postComposer.allDirectoryPeople.isEmpty()) {
      viewModelScope.launch {
        repository.loadContactsPage(ContactFilterTab.ALL, "", page = 1, pageSize = 50)
      }
    }
  }

  fun onTagPeopleSearchChanged(query: String) {
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(tagPeopleQuery = query.take(64)))
    }
  }

  fun togglePendingTaggedPerson(userId: String) {
    _uiState.update { state ->
      val current = state.postComposer.pendingTaggedIds
      val next = if (userId in current) {
        current - userId
      } else if (current.size < TelefamPostRules.MAX_TAGGED_PEOPLE) {
        current + userId
      } else {
        current
      }
      state.copy(postComposer = state.postComposer.copy(pendingTaggedIds = next))
    }
  }

  fun confirmTaggedPeople() {
    _uiState.update { state ->
      val chosen = state.postComposer.pendingTaggedIds
      val selected = state.postComposer.allDirectoryPeople.filter { it.userId in chosen }
      state.copy(
        postComposer = state.postComposer.copy(
          taggedPeople = selected,
          showTagPeopleSheet = false,
        ),
      )
    }
  }

  fun dismissTagPeopleSheet() {
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(showTagPeopleSheet = false))
    }
  }

  // Song search + preview modal controls
  fun openSongSheet() {
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(showSongSheet = true))
    }
    if (_uiState.value.postComposer.songResults.isEmpty()) {
      triggerSongSearch(_uiState.value.postComposer.songQuery)
    }
  }

  fun onSongSearchChanged(query: String) {
    val clean = query.take(80)
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(songQuery = clean))
    }
    songSearchJob?.cancel()
    songSearchJob = viewModelScope.launch {
      delay(320L)
      triggerSongSearch(clean)
    }
  }

  private fun triggerSongSearch(query: String) {
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(isSearchingSongs = true))
    }
    viewModelScope.launch {
      val tracks = repository.searchSongs(query)
      _uiState.update { state ->
        state.copy(
          postComposer = state.postComposer.copy(
            songResults = tracks,
            isSearchingSongs = false,
          ),
        )
      }
    }
  }

  fun selectSongTrack(track: PostSongTrack) {
    stopSongPreview()
    _uiState.update { state ->
      state.copy(
        postComposer = state.postComposer.copy(
          selectedSong = track,
          showSongSheet = false,
        ),
      )
    }
  }

  fun clearSelectedSong() {
    stopSongPreview()
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(selectedSong = null))
    }
  }

  fun dismissSongSheet() {
    stopSongPreview()
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(showSongSheet = false))
    }
  }

  fun toggleSongPreview(track: PostSongTrack) {
    if (track.previewUrl.isBlank()) return
    val currentPlaying = _uiState.value.postComposer.playingPreviewTrackId
    if (currentPlaying == track.trackId) {
      stopSongPreview()
      return
    }
    stopSongPreview()
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(playingPreviewTrackId = track.trackId))
    }
    runCatching {
      val player = MediaPlayer().apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build(),
        )
        setDataSource(track.previewUrl)
        setOnPreparedListener { it.start() }
        setOnCompletionListener {
          _uiState.update { s -> s.copy(postComposer = s.postComposer.copy(playingPreviewTrackId = null)) }
        }
        setOnErrorListener { _, _, _ ->
          _uiState.update { s -> s.copy(postComposer = s.postComposer.copy(playingPreviewTrackId = null)) }
          true
        }
        prepareAsync()
      }
      previewPlayer = player
    }.onFailure {
      _uiState.update { s -> s.copy(postComposer = s.postComposer.copy(playingPreviewTrackId = null)) }
    }
  }

  fun stopSongPreview() {
    runCatching {
      previewPlayer?.stop()
      previewPlayer?.release()
    }
    previewPlayer = null
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(playingPreviewTrackId = null))
    }
  }

  // Hashtags search & post count modal controls
  fun openHashtagsSheet() {
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(showHashtagsSheet = true))
    }
    triggerHashtagSearch(_uiState.value.postComposer.hashtagQuery)
  }

  fun onHashtagSearchChanged(query: String) {
    val clean = query.take(50)
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(hashtagQuery = clean))
    }
    hashtagSearchJob?.cancel()
    hashtagSearchJob = viewModelScope.launch {
      delay(220L)
      triggerHashtagSearch(clean)
    }
  }

  private fun triggerHashtagSearch(query: String) {
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(isSearchingHashtags = true))
    }
    viewModelScope.launch {
      val items = repository.searchHashtags(query)
      _uiState.update { state ->
        state.copy(
          postComposer = state.postComposer.copy(
            hashtagResults = items,
            isSearchingHashtags = false,
          ),
        )
      }
    }
  }

  fun toggleHashtagSelection(rawTag: String) {
    val clean = TelefamPostRules.sanitizeHashtag(rawTag)
    if (clean.isBlank()) return
    _uiState.update { state ->
      val cur = state.postComposer.selectedHashtags
      val next = if (clean in cur) {
        cur - clean
      } else if (cur.size < TelefamPostRules.MAX_HASHTAGS) {
        cur + clean
      } else {
        cur
      }
      state.copy(postComposer = state.postComposer.copy(selectedHashtags = next))
    }
  }

  fun dismissHashtagsSheet() {
    _uiState.update { state ->
      state.copy(postComposer = state.postComposer.copy(showHashtagsSheet = false))
    }
  }

  fun sharePost() {
    val post = _uiState.value.postComposer
    if (!post.canShare) return
    stopSongPreview()

    _uiState.update {
      it.copy(
        postComposer = it.postComposer.copy(
          uploadStatus = PostUploadStatus.UPLOADING,
          uploadProgressPct = if (post.editingPostId != null) 70 else 1,
          errorMessage = null,
        ),
      )
    }

    val editingId = post.editingPostId
    if (editingId != null) {
      viewModelScope.launch {
        val result = repository.updateOwnerPost(
          postId = editingId,
          textContent = post.textContent,
          caption = post.caption,
          themeId = post.selectedThemeId,
          fontId = post.selectedFont.id,
          song = post.selectedSong,
          taggedUserIds = post.taggedPeople.map { it.userId },
          hashtags = post.selectedHashtags,
          muteOriginalAudio = post.muteOriginalAudio,
        )
        when (result) {
          is PostPublishResult.Uploaded -> {
            _uiState.update { s ->
              s.copy(
                postComposer = PostComposerUiState(
                  isOpen = false,
                  allDirectoryPeople = s.postComposer.allDirectoryPeople,
                  pendingQueueCount = s.postComposer.pendingQueueCount,
                ),
                feedsState = s.feedsState.copy(toastMessage = "Updated"),
              )
            }
            refreshFeeds()
          }
          is PostPublishResult.Rejected -> {
            _uiState.update { s ->
              s.copy(
                postComposer = s.postComposer.copy(
                  uploadStatus = PostUploadStatus.FAILED,
                  errorMessage = result.message,
                ),
              )
            }
          }
          is PostPublishResult.QueuedOffline -> Unit
        }
      }
      return
    }

    val vid = post.video
    val input = PostSubmissionInput(
      mode = post.mode,
      textContent = post.textContent,
      caption = post.caption,
      themeId = post.selectedThemeId,
      fontId = post.selectedFont.id,
      song = post.selectedSong,
      taggedUserIds = post.taggedPeople.map { it.userId },
      hashtags = post.selectedHashtags,
      localPhotoPaths = post.photos.map { it.localFilePath },
      localVideoPath = vid?.localVideoPath,
      localThumbnailPath = vid?.selectedThumbnailPath,
      frameHashes = vid?.frames?.map { it.dHashHex }.orEmpty(),
      durationMs = vid?.durationMs ?: 0L,
      trimStartMs = post.videoTrimStartMs,
      trimEndMs = post.videoTrimEndMs,
      width = vid?.width ?: 1280,
      height = vid?.height ?: 720,
      muteOriginalAudio = post.muteOriginalAudio,
      contentId = vid?.contentId ?: post.photos.firstOrNull()?.contentId.orEmpty(),
    )

    viewModelScope.launch {
      val result = repository.submitOrQueuePost(input) { pct ->
        _uiState.update { s ->
          s.copy(
            postComposer = s.postComposer.copy(
              uploadStatus = PostUploadStatus.UPLOADING,
              uploadProgressPct = pct,
            ),
          )
        }
      }

      when (result) {
        is PostPublishResult.Uploaded -> {
          _uiState.update { s ->
            s.copy(
              postComposer = s.postComposer.copy(
                uploadStatus = PostUploadStatus.UPLOADED,
                uploadProgressPct = 100,
              ),
            )
          }
          delay(650L)
          _uiState.update { s ->
            s.copy(
              postComposer = PostComposerUiState(
                isOpen = false,
                allDirectoryPeople = s.postComposer.allDirectoryPeople,
                pendingQueueCount = s.postComposer.pendingQueueCount,
              ),
              comingSoonMessage = "Uploaded",
            )
          }
        }

        is PostPublishResult.QueuedOffline -> {
          _uiState.update { s ->
            s.copy(
              postComposer = PostComposerUiState(
                isOpen = false,
                allDirectoryPeople = s.postComposer.allDirectoryPeople,
                pendingQueueCount = s.postComposer.pendingQueueCount,
              ),
              comingSoonMessage = "Queued",
            )
          }
        }

        is PostPublishResult.Rejected -> {
          _uiState.update { s ->
            s.copy(
              postComposer = s.postComposer.copy(
                uploadStatus = PostUploadStatus.FAILED,
                errorMessage = result.message,
              ),
            )
          }
        }
      }
    }
  }

  fun onContactsFilterSelected(filter: ContactFilterTab) {
    if (_uiState.value.contactsState.activeFilter == filter) return
    _uiState.update {
      it.copy(
        isBottomBarVisible = true,
        contactsState = it.contactsState.copy(
          activeFilter = filter,
          currentPage = 1,
          hasMore = false,
        ),
      )
    }
    logClientAction("contacts_filter_${filter.apiParam}")
    refreshContacts()
  }

  fun onContactsSearchChanged(query: String) {
    val limited = query.take(64)
    _uiState.update {
      it.copy(
        contactsState = it.contactsState.copy(
          searchQuery = limited,
          currentPage = 1,
          hasMore = false,
        ),
      )
    }
    contactsSearchJob?.cancel()
    contactsSearchJob = viewModelScope.launch {
      delay(280L)
      refreshContacts()
    }
  }

  fun refreshContacts() {
    val state = _uiState.value
    if (!state.isAuthenticated) return
    val filter = state.contactsState.activeFilter
    val query = state.contactsState.searchQuery
    viewModelScope.launch {
      _uiState.update { it.copy(contactsState = it.contactsState.copy(isLoading = true)) }
      val result = repository.loadContactsPage(
        filter = filter,
        query = query,
        page = 1,
        pageSize = TelefamContactsRules.PAGE_SIZE,
      )
      _uiState.update { cur ->
        if (cur.contactsState.activeFilter != filter || cur.contactsState.searchQuery != query) {
          cur.copy(contactsState = cur.contactsState.copy(isLoading = false))
        } else {
          cur.copy(
            contactsState = cur.contactsState.copy(
              isLoading = false,
              isLoadingMore = false,
              currentPage = result.page,
              hasMore = result.hasMore,
            ),
          )
        }
      }
    }
  }

  fun loadNextContactsPage() {
    val state = _uiState.value
    val contacts = state.contactsState
    if (!state.isAuthenticated || contacts.isLoading || contacts.isLoadingMore || !contacts.hasMore) {
      return
    }
    val nextPage = contacts.currentPage + 1
    val filter = contacts.activeFilter
    val query = contacts.searchQuery
    viewModelScope.launch {
      _uiState.update { it.copy(contactsState = it.contactsState.copy(isLoadingMore = true)) }
      val result = repository.loadContactsPage(
        filter = filter,
        query = query,
        page = nextPage,
        pageSize = TelefamContactsRules.PAGE_SIZE,
      )
      _uiState.update { cur ->
        if (cur.contactsState.activeFilter != filter || cur.contactsState.searchQuery != query) {
          cur.copy(contactsState = cur.contactsState.copy(isLoadingMore = false))
        } else {
          cur.copy(
            contactsState = cur.contactsState.copy(
              isLoadingMore = false,
              currentPage = result.page,
              hasMore = result.hasMore,
            ),
          )
        }
      }
    }
  }

  fun onToggleContactFollow(targetUserId: String) {
    viewModelScope.launch {
      when (val result = repository.toggleContactFollow(targetUserId)) {
        is ContactToggleResult.RateLimited -> {
          _uiState.update {
            it.copy(contactsState = it.contactsState.copy(toastMessage = result.message))
          }
        }
        ContactToggleResult.Synced, ContactToggleResult.QueuedOffline -> Unit
      }
    }
  }

  fun onContactsScrollVisibilityChanged(visible: Boolean) {
    if (_uiState.value.isBottomBarVisible != visible) {
      _uiState.update { it.copy(isBottomBarVisible = visible) }
    }
  }

  fun dismissContactsToast() {
    _uiState.update { it.copy(contactsState = it.contactsState.copy(toastMessage = null)) }
  }

  fun triggerComingSoon(featureLabel: String, actionKey: String) {
    _uiState.update { it.copy(comingSoonMessage = "$featureLabel — Coming soon") }
    logClientAction(actionKey)
  }

  fun dismissComingSoon() {
    _uiState.update { it.copy(comingSoonMessage = null) }
  }

  fun signOut() {
    countdownJob?.cancel()
    viewModelScope.launch {
      repository.signOut()
      _uiState.update {
        TelefamUiState(isSessionInitialized = true, themeMode = it.themeMode, networkTier = it.networkTier)
      }
    }
  }

  private fun onAuthVerified(result: AuthActionResult.Verified) {
    countdownJob?.cancel()
    val (autoName, autoUser) = TelefamProfileSanitizer.autofillFromEmail(result.email)
    _uiState.update {
      it.copy(
        isLoading = false,
        isAuthenticated = true,
        profileCompleted = result.profileCompleted,
        userId = result.userId,
        userEmail = result.email,
        errorMessage = null,
        otpCode = "",
        cooldownRemainingSeconds = 0L,
        profileForm = if (result.profileCompleted) {
          it.profileForm
        } else {
          it.profileForm.copy(
            fullName = autoName,
            username = autoUser,
            isUsernameAvailable = true,
          )
        },
      )
    }
  }

  private fun logClientAction(action: String) {
    val uid = _uiState.value.userId
    if (uid.isBlank()) return
    viewModelScope.launch {
      repository.logAudit(uid, action, "{}")
    }
  }

  private fun startCountdown(seconds: Long) {
    countdownJob?.cancel()
    if (seconds <= 0L) return
    countdownJob = viewModelScope.launch {
      var remaining = seconds
      while (remaining > 0L) {
        delay(1_000L)
        remaining--
        _uiState.update { it.copy(cooldownRemainingSeconds = remaining) }
      }
    }
  }

  private fun isValidEmail(email: String): Boolean {
    return email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
  }

  override fun onCleared() {
    super.onCleared()
    stopSongPreview()
  }

  class Factory(private val repository: TelefamRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return TelefamViewModel(repository) as T
    }
  }
}
