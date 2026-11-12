package telefam.company.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import telefam.company.ui.components.TelefamAnimatedSplashOverlay
import telefam.company.ui.screens.TelefamAuthScreen
import telefam.company.ui.screens.TelefamHomeScreen
import telefam.company.ui.screens.TelefamPostScreen
import telefam.company.ui.screens.TelefamProfileSetupScreen
import telefam.company.ui.screens.TelefamSearchScreen
import telefam.company.ui.theme.TelefamTheme
import telefam.company.ui.theme.ThemeMode
import telefam.company.ui.viewmodel.HomeTab
import telefam.company.ui.viewmodel.TelefamViewModel

private enum class AppRootRoute {
  AUTH,
  PROFILE_SETUP,
  HOME,
}

class TelefamMainActivity : ComponentActivity() {
  private val viewModel: TelefamViewModel by viewModels {
    TelefamViewModel.Factory((application as TelefamApplication).repository)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(
        android.graphics.Color.TRANSPARENT,
        android.graphics.Color.TRANSPARENT,
      ),
      navigationBarStyle = SystemBarStyle.light(
        android.graphics.Color.TRANSPARENT,
        android.graphics.Color.TRANSPARENT,
      ),
    )

    handleVerificationIntent(intent)

    setContent {
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()
      val systemDark = isSystemInDarkTheme()
      val useDarkBars = when (uiState.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
      }

      val route = when {
        !uiState.isAuthenticated -> AppRootRoute.AUTH
        !uiState.profileCompleted -> AppRootRoute.PROFILE_SETUP
        else -> AppRootRoute.HOME
      }

      LaunchedEffect(
        useDarkBars,
        route,
        uiState.activeHomeTab,
        uiState.feedsState.isSearchOpen,
        uiState.feedsState.searchViewerItems != null,
        uiState.postComposer.isOpen,
      ) {
        val statusBarIsDark = when {
          uiState.postComposer.isOpen -> true
          route == AppRootRoute.HOME && uiState.feedsState.isSearchOpen -> {
            if (uiState.feedsState.searchViewerItems != null) true else useDarkBars
          }
          route == AppRootRoute.HOME && uiState.activeHomeTab == HomeTab.FEEDS -> true
          route == AppRootRoute.AUTH -> useDarkBars
          else -> true
        }
        enableEdgeToEdge(
          statusBarStyle = if (statusBarIsDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
          } else {
            SystemBarStyle.light(
              android.graphics.Color.TRANSPARENT,
              android.graphics.Color.TRANSPARENT,
            )
          },
          navigationBarStyle = if (useDarkBars || (route == AppRootRoute.HOME && uiState.activeHomeTab == HomeTab.FEEDS && !uiState.feedsState.isSearchOpen)) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
          } else {
            SystemBarStyle.light(
              android.graphics.Color.TRANSPARENT,
              android.graphics.Color.TRANSPARENT,
            )
          },
        )
      }

      var showSplash by remember { mutableStateOf(true) }
      LaunchedEffect(uiState.isSessionInitialized) {
        if (uiState.isSessionInitialized) {
          delay(420L)
          showSplash = false
        }
      }

      TelefamTheme(themeMode = uiState.themeMode) {
        Box(modifier = Modifier.fillMaxSize()) {
          AnimatedContent(
            targetState = route,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "root_route_transition",
          ) { activeRoute ->
            when (activeRoute) {
              AppRootRoute.AUTH -> {
                TelefamAuthScreen(
                  uiState = uiState,
                  onEmailChanged = viewModel::onEmailChanged,
                  onSendOtp = viewModel::sendOtp,
                  onOtpChanged = viewModel::onOtpChanged,
                  onVerifyOtp = viewModel::verifyOtp,
                  onResendOtp = viewModel::resendOtp,
                  onChangeEmail = viewModel::changeEmail,
                  onGoogleTokenSuccess = viewModel::onGoogleSignInSuccess,
                  onGoogleTokenError = viewModel::onGoogleSignInError,
                  onToggleTheme = viewModel::cycleThemeMode,
                )
              }

              AppRootRoute.PROFILE_SETUP -> {
                TelefamProfileSetupScreen(
                  formState = uiState.profileForm,
                  themeMode = uiState.themeMode,
                  onToggleTheme = viewModel::cycleThemeMode,
                  onBackSignOut = viewModel::signOut,
                  onPhotoPickedForCrop = viewModel::onPhotoPickedForCrop,
                  onDismissCrop = viewModel::dismissPhotoCropper,
                  onConfirmCrop = viewModel::confirmCroppedPhoto,
                  onFullNameChanged = viewModel::onProfileFullNameChanged,
                  onUsernameChanged = viewModel::onProfileUsernameChanged,
                  onCountrySelected = viewModel::onProfileCountrySelected,
                  onPhoneDigitsChanged = viewModel::onProfilePhoneDigitsChanged,
                  onGenderSelected = viewModel::onProfileGenderSelected,
                  onDobSelected = viewModel::onProfileDobSelected,
                  onLocationChanged = viewModel::onProfileLocationChanged,
                  onBioChanged = viewModel::onProfileBioChanged,
                  onContinue = viewModel::submitProfileSetup,
                )
              }

              AppRootRoute.HOME -> {
                TelefamHomeScreen(
                  uiState = uiState,
                  onSelectTab = viewModel::selectHomeTab,
                  onTriggerComingSoon = viewModel::triggerComingSoon,
                  onDismissComingSoon = viewModel::dismissComingSoon,
                  onToggleTheme = viewModel::cycleThemeMode,
                  onContactsSearchChanged = viewModel::onContactsSearchChanged,
                  onContactsFilterSelected = viewModel::onContactsFilterSelected,
                  onToggleContactFollow = viewModel::onToggleContactFollow,
                  onLoadNextContactsPage = viewModel::loadNextContactsPage,
                  onContactsScrollVisibilityChanged = viewModel::onContactsScrollVisibilityChanged,
                  onDismissContactsToast = viewModel::dismissContactsToast,
                  onSelectFeedTab = viewModel::selectFeedTab,
                  onOpenFeedSearch = { viewModel.openFeedSearch("") },
                  onLoadNextFeedPage = { viewModel.loadNextFeedPage() },
                  onRefreshFeeds = { viewModel.refreshFeeds() },
                  onToggleFeedAuthorFollow = viewModel::onToggleFeedAuthorFollow,
                  onToggleFeedLike = viewModel::onToggleFeedLike,
                  onDoubleTapFeedLike = viewModel::onDoubleTapFeedLike,
                  onToggleFeedFavourite = viewModel::onToggleFeedFavourite,
                  onShareFeedPost = viewModel::onShareFeedPost,
                  onToggleFeedRepost = viewModel::onToggleFeedRepost,
                  onDownloadFeedVideo = { item -> viewModel.onDownloadFeedVideo(this@TelefamMainActivity, item) },
                  onNotInterestedFeedPost = viewModel::onNotInterestedFeedPost,
                  onReportFeedPost = { postId, reason -> viewModel.onReportFeedPost(postId, reason) },
                  onBlockFeedAuthor = viewModel::onBlockFeedAuthor,
                  onEditOwnerFeedPost = viewModel::openPostForEdit,
                  onDeleteOwnerFeedPost = viewModel::onDeleteOwnerFeedPost,
                  onTapFeedHashtag = viewModel::openPostWithHashtag,
                  onTapFeedAudio = viewModel::openPostWithSong,
                  onRecordFeedWatchProgress = viewModel::onRecordFeedWatchProgress,
                  onShowFeedToast = viewModel::showFeedToast,
                  onDismissFeedToast = viewModel::dismissFeedToast,
                )
              }
            }
          }

          AnimatedVisibility(
            visible = route == AppRootRoute.HOME && uiState.feedsState.isSearchOpen,
            enter = slideInVertically(initialOffsetY = { it / 6 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut(),
          ) {
            TelefamSearchScreen(
              feedsState = uiState.feedsState,
              networkTier = uiState.networkTier,
              onQueryChanged = viewModel::onFeedSearchQueryChanged,
              onShortcutSelected = viewModel::onFeedSearchShortcutSelected,
              onOpenCardAtIndex = viewModel::openSearchResultViewer,
              onCloseViewer = viewModel::closeSearchResultViewer,
              onBack = viewModel::closeFeedSearch,
              onToggleFollowAuthor = viewModel::onToggleFeedAuthorFollow,
              onToggleLike = viewModel::onToggleFeedLike,
              onDoubleTapLike = viewModel::onDoubleTapFeedLike,
              onCommentClick = { viewModel.triggerComingSoon("Comments", "feed_comments_tapped") },
              onToggleFavourite = viewModel::onToggleFeedFavourite,
              onShareRecorded = viewModel::onShareFeedPost,
              onToggleRepost = viewModel::onToggleFeedRepost,
              onDownloadVideo = { item -> viewModel.onDownloadFeedVideo(this@TelefamMainActivity, item) },
              onNotInterested = viewModel::onNotInterestedFeedPost,
              onReportPost = { postId, reason -> viewModel.onReportFeedPost(postId, reason) },
              onBlockAuthor = viewModel::onBlockFeedAuthor,
              onEditOwnerPost = viewModel::openPostForEdit,
              onDeleteOwnerPost = viewModel::onDeleteOwnerFeedPost,
              onTapHashtag = viewModel::openPostWithHashtag,
              onTapAudio = viewModel::openPostWithSong,
              onRecordWatchProgress = viewModel::onRecordFeedWatchProgress,
              onShowToast = viewModel::showFeedToast,
            )
          }

          AnimatedVisibility(
            visible = route == AppRootRoute.HOME && uiState.postComposer.isOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
          ) {
            TelefamPostScreen(
              state = uiState.postComposer,
              onClose = viewModel::closePostComposer,
              onSelectMode = viewModel::onPostModeSelected,
              onTextChanged = viewModel::onPostTextChanged,
              onCaptionChanged = viewModel::onPostCaptionChanged,
              onSelectTheme = viewModel::onPostThemeSelected,
              onSelectFont = viewModel::onPostFontSelected,
              onPhotosPicked = viewModel::onPostPhotosPicked,
              onRemovePhoto = viewModel::removePostPhoto,
              onVideoPicked = viewModel::onPostVideoPicked,
              onClearVideo = viewModel::clearPostVideo,
              onTrimChanged = viewModel::onVideoTrimChanged,
              onSelectThumbnailFrame = viewModel::onSelectVideoThumbnailFrame,
              onToggleMuteOriginalAudio = viewModel::onToggleMuteOriginalAudio,
              onOpenSongSheet = viewModel::openSongSheet,
              onDismissSongSheet = viewModel::dismissSongSheet,
              onSongQueryChanged = viewModel::onSongSearchChanged,
              onSelectSong = viewModel::selectSongTrack,
              onToggleSongPreview = viewModel::toggleSongPreview,
              onClearSong = viewModel::clearSelectedSong,
              onOpenTagPeopleSheet = viewModel::openTagPeopleSheet,
              onDismissTagPeopleSheet = viewModel::dismissTagPeopleSheet,
              onTagPeopleQueryChanged = viewModel::onTagPeopleSearchChanged,
              onTogglePendingTagPerson = viewModel::togglePendingTaggedPerson,
              onConfirmTaggedPeople = viewModel::confirmTaggedPeople,
              onOpenHashtagsSheet = viewModel::openHashtagsSheet,
              onDismissHashtagsSheet = viewModel::dismissHashtagsSheet,
              onHashtagQueryChanged = viewModel::onHashtagSearchChanged,
              onToggleHashtag = viewModel::toggleHashtagSelection,
              onShare = viewModel::sharePost,
            )
          }

          TelefamAnimatedSplashOverlay(visible = showSplash)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleVerificationIntent(intent)
  }

  private fun handleVerificationIntent(intent: Intent?) {
    val data: Uri = intent?.data ?: return
    val token = data.getQueryParameter("token")
    if (!token.isNullOrBlank()) {
      viewModel.handleVerificationDeepLink(token)
    }
  }
}
