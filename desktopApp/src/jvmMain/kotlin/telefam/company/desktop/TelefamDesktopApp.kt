package telefam.company.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import telefam.company.shared.ContactFilterTab
import telefam.company.shared.ContactRelationshipState
import telefam.company.shared.SharedFeedAction
import telefam.company.shared.SharedFeedMath
import telefam.company.shared.SharedFeedTab
import telefam.company.shared.SharedHashtagItem
import telefam.company.shared.SharedPostFont
import telefam.company.shared.SharedPostMode
import telefam.company.shared.SharedPostRules
import telefam.company.shared.SharedPostTheme
import telefam.company.shared.SharedPostThemeKind
import telefam.company.shared.SharedSongTrack
import telefam.company.shared.TelefamContactsRules
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val DesktopCrimson = Color(0xFFC80A0F)
private val DesktopBlush = Color(0xFFFCE8E8)
private val DesktopDarkBlush = Color(0xFF3A1416)
private val DesktopDarkBg = Color(0xFF0E1014)
private val DesktopDarkSurface = Color(0xFF171A21)
private val DesktopDarkSearch = Color(0xFF111824)
private val DesktopDarkBorder = Color(0xFF2D323F)

enum class DesktopThemeMode { SYSTEM, LIGHT, DARK }

data class DesktopContactItem(
  val userId: String,
  val fullName: String,
  val username: String,
  val countryDisplay: String,
  val isFollowing: Boolean,
  val followsYou: Boolean,
  val riskScore: Double,
) {
  val relationship: ContactRelationshipState
    get() = ContactRelationshipState.compute(isFollowing, followsYou)
}

data class DesktopSelectedPhoto(
  val id: String,
  val fileName: String,
  val sourceFile: File,
  val previewBitmap: ImageBitmap?,
  val byteSize: Long,
)

data class DesktopSelectedVideo(
  val id: String,
  val fileName: String,
  val sourceFile: File,
  val byteSize: Long,
  val durationMs: Long,
  val trimStartMs: Long,
  val trimEndMs: Long,
  val selectedThumbIndex: Int,
  val frameHashes: List<String>,
  val contentId: String,
)

data class DesktopPendingContactOp(
  val targetUserId: String,
  val wantFollow: Boolean,
)

data class DesktopFeedPost(
  val postId: String,
  val authorUserId: String,
  val authorUsername: String,
  val authorFullName: String,
  val isFollowingAuthor: Boolean,
  val isOwner: Boolean,
  val mode: SharedPostMode,
  val textContent: String,
  val caption: String,
  val themeId: String,
  val fontOption: SharedPostFont,
  val song: SharedSongTrack?,
  val hashtags: List<String>,
  val mediaWidth: Int = 1080,
  val mediaHeight: Int = 1920,
  val likesCount: Long = 0L,
  val commentsCount: Long = 0L,
  val sharesCount: Long = 0L,
  val favouritesCount: Long = 0L,
  val repostsCount: Long = 0L,
  val isLiked: Boolean = false,
  val isFavourited: Boolean = false,
  val isReposted: Boolean = false,
  val repostedByUsername: String = "",
  val recommendationLabel: String = "",
  val muteOriginalAudio: Boolean = false,
)

private sealed interface DesktopPostLaunchTarget {
  data object CreateNew : DesktopPostLaunchTarget
  data class PrefillHashtag(val tag: String, val mode: SharedPostMode) : DesktopPostLaunchTarget
  data class PrefillSong(val song: SharedSongTrack, val mode: SharedPostMode) : DesktopPostLaunchTarget
  data class EditPost(val item: DesktopFeedPost) : DesktopPostLaunchTarget
}

fun main() = application {
  val vault = remember { DesktopEncryptedSessionVault() }
  var session by remember { mutableStateOf(vault.load()) }
  val apiClient = remember {
    DesktopApiClient(
      vault = vault,
      initialSession = session,
      onSessionUpdated = { updated -> session = updated },
    )
  }

  val scope = rememberCoroutineScope()
  var splashVisible by remember { mutableStateOf(true) }
  var activeTab by remember { mutableStateOf("contacts") }
  var postLaunchTarget by remember { mutableStateOf<DesktopPostLaunchTarget?>(null) }
  var showFeedSearch by remember { mutableStateOf(false) }
  var activeFeedTab by remember { mutableStateOf(SharedFeedTab.FOR_YOU) }
  var feedSearchQuery by remember { mutableStateOf("") }
  var feedActiveShortcut by remember { mutableStateOf("") }
  var feedSearchShortcuts by remember { mutableStateOf<List<DesktopSearchShortcut>>(emptyList()) }
  var feedSearchResults by remember { mutableStateOf<List<DesktopFeedPost>>(emptyList()) }

  var filter by remember { mutableStateOf(ContactFilterTab.ALL) }
  var searchQuery by remember { mutableStateOf("") }
  var contactsPage by remember { mutableStateOf(1) }
  var contactsHasMore by remember { mutableStateOf(false) }
  var feedPage by remember { mutableStateOf(1) }
  var feedHasMore by remember { mutableStateOf(false) }

  val contacts = remember { mutableStateListOf<DesktopContactItem>() }
  val pendingContactOps = remember { mutableStateListOf<DesktopPendingContactOp>() }
  val feedPosts = remember { mutableStateListOf<DesktopFeedPost>() }
  val knownHashtags = remember { mutableStateListOf<SharedHashtagItem>() }
  val recentActionEpochsMs = remember { mutableStateListOf<Long>() }
  var toastMessage by remember { mutableStateOf<String?>(null) }
  var showChatsRail by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    delay(700L)
    splashVisible = false
  }

  LaunchedEffect(toastMessage) {
    if (toastMessage != null) {
      delay(2200L)
      toastMessage = null
    }
  }

  // Load paginated contacts from Go backend whenever authenticated + filter/query changes
  LaunchedEffect(session.isAuthenticated, session.profileCompleted, filter, searchQuery) {
    if (!session.isAuthenticated || !session.profileCompleted) return@LaunchedEffect
    delay(180L)
    contactsPage = 1
    val res = withContext(Dispatchers.IO) { apiClient.listContacts(filter, searchQuery, 1) }
    if (res is DesktopApiOutcome.Ok) {
      contacts.clear()
      contacts.addAll(res.data.items)
      contactsHasMore = res.data.hasMore
    }
  }

  // Retry any queued offline follow/unfollow ops periodically
  LaunchedEffect(session.isAuthenticated, pendingContactOps.size) {
    if (!session.isAuthenticated || pendingContactOps.isEmpty()) return@LaunchedEffect
    while (pendingContactOps.isNotEmpty()) {
      val op = pendingContactOps.first()
      val outcome = withContext(Dispatchers.IO) { apiClient.setFollowContact(op.targetUserId, op.wantFollow) }
      if (outcome is DesktopApiOutcome.Ok) {
        pendingContactOps.removeAt(0)
      } else {
        delay(5000L)
      }
    }
  }

  // Load Feeds from Go backend whenever authenticated + tab changes
  LaunchedEffect(session.isAuthenticated, session.profileCompleted, activeTab, activeFeedTab) {
    if (!session.isAuthenticated || !session.profileCompleted || activeTab != "feeds") return@LaunchedEffect
    feedPage = 1
    val res = withContext(Dispatchers.IO) { apiClient.getFeeds(activeFeedTab, 1) }
    if (res is DesktopApiOutcome.Ok) {
      feedPosts.clear()
      feedPosts.addAll(res.data.items)
      feedHasMore = res.data.hasMore
    }
  }

  // Search Feeds from Go backend when search overlay is open
  LaunchedEffect(showFeedSearch, feedSearchQuery, feedActiveShortcut) {
    if (!showFeedSearch) return@LaunchedEffect
    delay(220L)
    val res = withContext(Dispatchers.IO) {
      apiClient.searchFeeds(feedSearchQuery, feedActiveShortcut, 1)
    }
    if (res is DesktopApiOutcome.Ok) {
      feedSearchShortcuts = res.data.shortcuts
      feedSearchResults = res.data.items
    } else {
      val q = feedSearchQuery.trim().lowercase()
      val sc = feedActiveShortcut.trim().lowercase()
      feedSearchResults = feedPosts.filter { post ->
        val matchQ = q.isEmpty() ||
          post.caption.lowercase().contains(q) ||
          post.textContent.lowercase().contains(q) ||
          post.authorUsername.lowercase().contains(q) ||
          post.hashtags.any { it.lowercase().contains(q) } ||
          (post.song?.title?.lowercase()?.contains(q) == true)
        val matchSc = sc.isEmpty() || post.hashtags.any { it.lowercase() == sc }
        matchQ && matchSc
      }
    }
  }

  val systemDark = isSystemInDarkTheme()
  val isDark = when (session.themeMode) {
    DesktopThemeMode.SYSTEM -> systemDark
    DesktopThemeMode.LIGHT -> false
    DesktopThemeMode.DARK -> true
  }
  val scheme = if (isDark) {
    darkColorScheme(
      primary = DesktopCrimson,
      background = DesktopDarkBg,
      surface = DesktopDarkSurface,
      onBackground = Color(0xFFF3F4F6),
      onSurface = Color(0xFFF3F4F6),
      onSurfaceVariant = Color(0xFF9CA3AF),
      outline = DesktopDarkBorder,
      outlineVariant = DesktopDarkBorder,
    )
  } else {
    lightColorScheme(
      primary = DesktopCrimson,
      background = Color.White,
      surface = Color.White,
      onBackground = Color(0xFF1A1C1E),
      onSurface = Color(0xFF1A1C1E),
      onSurfaceVariant = Color(0xFF6B7280),
      outline = Color(0xFFE2E4E9),
      outlineVariant = Color(0xFFE5E7EB),
    )
  }

  val toggleTheme: () -> Unit = {
    val next = when (session.themeMode) {
      DesktopThemeMode.SYSTEM -> DesktopThemeMode.LIGHT
      DesktopThemeMode.LIGHT -> DesktopThemeMode.DARK
      DesktopThemeMode.DARK -> DesktopThemeMode.SYSTEM
    }
    apiClient.updateThemeMode(next)
  }

  Window(
    onCloseRequest = ::exitApplication,
    title = "Telefam",
    state = WindowState(size = DpSize(1080.dp, 740.dp)),
  ) {
    MaterialTheme(colorScheme = scheme) {
      Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (splashVisible) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              DesktopLogoBadge(size = 86.dp, animated = true)
              Spacer(modifier = Modifier.height(14.dp))
              Text("Telefam", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = DesktopCrimson)
            }
          }
        } else if (!session.isAuthenticated) {
          DesktopAuthScreen(
            apiClient = apiClient,
            isDark = isDark,
            themeMode = session.themeMode,
            onToggleTheme = toggleTheme,
            onAuthenticated = {},
            onToast = { toastMessage = it },
          )
        } else if (!session.profileCompleted) {
          DesktopProfileSetupScreen(
            apiClient = apiClient,
            isDark = isDark,
            onCompleted = { activeTab = "contacts" },
            onSignOut = { apiClient.signOut() },
            onToast = { toastMessage = it },
          )
        } else {
          Column(modifier = Modifier.fillMaxSize()) {
            // Top Crimson Header matching mobile/tablet (hidden during fullscreen Feeds)
            if (activeTab != "feeds") {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(DesktopCrimson)
                  .padding(horizontal = 20.dp, vertical = 12.dp),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Surface(
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(21.dp),
                    color = if (isDark) DesktopDarkSearch else Color.White,
                  ) {
                    Row(
                      modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      DesktopSvgIcon(DesktopIconKind.SEARCH, MaterialTheme.colorScheme.onSurfaceVariant, 17.dp)
                      Spacer(modifier = Modifier.width(10.dp))
                      Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        val hint = if (activeTab == "contacts") "Search by name or username" else "Search chats, people, groups"
                        if (searchQuery.isEmpty()) {
                          Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                        BasicTextField(
                          value = searchQuery,
                          onValueChange = { searchQuery = it.take(64) },
                          singleLine = true,
                          textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                          ),
                          cursorBrush = SolidColor(DesktopCrimson),
                          modifier = Modifier.fillMaxWidth(),
                        )
                      }
                    }
                  }
                  Spacer(modifier = Modifier.width(12.dp))
                  Surface(
                    onClick = toggleTheme,
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.2f),
                  ) {
                    Text(
                      text = session.themeMode.name.lowercase().replaceFirstChar { it.uppercase() },
                      color = Color.White,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.SemiBold,
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                  }
                }

                if (activeTab == "contacts") {
                  Spacer(modifier = Modifier.height(10.dp))
                  Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    ContactFilterTab.entries.forEach { tab ->
                      val selected = tab == filter
                      Surface(
                        onClick = { filter = tab },
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) Color.White else Color.Transparent,
                      ) {
                        Text(
                          text = tab.label,
                          color = if (selected) DesktopCrimson else Color.White,
                          fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                          fontSize = 14.sp,
                          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                      }
                    }
                  }
                }
              }
            }

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
              AnimatedVisibility(
                visible = activeTab != "contacts" || showChatsRail,
                enter = fadeIn(),
                exit = fadeOut(),
              ) {
                Row(modifier = Modifier.fillMaxHeight()) {
                  Column(
                    modifier = Modifier
                      .width(96.dp)
                      .fillMaxHeight()
                      .background(MaterialTheme.colorScheme.surface)
                      .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                  ) {
                    DesktopRailButton("Chats", DesktopIconKind.CHATS, activeTab == "chats") { activeTab = "chats" }
                    DesktopRailButton("Contacts", DesktopIconKind.CONTACTS, activeTab == "contacts") {
                      activeTab = "contacts"
                      showChatsRail = true
                    }

                    // Center Crimson + Post FAB matching mobile & tablet
                    Column(
                      modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { postLaunchTarget = DesktopPostLaunchTarget.CreateNew }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                      horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                      Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(DesktopCrimson),
                        contentAlignment = Alignment.Center,
                      ) {
                        DesktopSvgIcon(DesktopIconKind.PLUS, Color.White, 22.dp)
                      }
                      Spacer(modifier = Modifier.height(4.dp))
                      Text("Post", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesktopCrimson)
                    }

                    DesktopRailButton("Feeds", DesktopIconKind.FEEDS, activeTab == "feeds") { activeTab = "feeds" }
                    DesktopRailButton("Profile", DesktopIconKind.PROFILE, activeTab == "profile") { activeTab = "profile" }
                  }
                  HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxHeight())
                }
              }

              Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (activeTab) {
                  "contacts" -> {
                    val gridState = rememberLazyGridState()
                    LaunchedEffect(gridState) {
                      var lastIdx = 0
                      var lastOffset = 0
                      snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                        .collect { (idx, off) ->
                          val scrollingDown = idx > lastIdx || (idx == lastIdx && off > lastOffset + 12)
                          val scrollingUp = idx < lastIdx || (idx == lastIdx && off < lastOffset - 12)
                          if (scrollingDown && gridState.isScrollInProgress) {
                            showChatsRail = false
                          } else if (scrollingUp || idx == 0) {
                            showChatsRail = true
                          }
                          lastIdx = idx
                          lastOffset = off
                        }
                    }

                    if (contacts.isEmpty()) {
                      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                          text = "No people found",
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          fontSize = 15.sp,
                        )
                      }
                    } else {
                      LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 340.dp),
                        contentPadding = PaddingValues(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize().widthIn(max = 1180.dp),
                      ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                          Text(
                            text = filter.sectionSubtitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                          )
                        }
                        items(contacts, key = { it.userId }) { contact ->
                          DesktopContactCard(contact = contact, isDark = isDark) {
                            val now = System.currentTimeMillis()
                            recentActionEpochsMs.removeAll { now - it >= TelefamContactsRules.ONE_MINUTE_MS }
                            if (recentActionEpochsMs.size >= TelefamContactsRules.MAX_ACTIONS_PER_MINUTE) {
                              toastMessage = TelefamContactsRules.RATE_LIMIT_TOAST_MESSAGE
                            } else {
                              recentActionEpochsMs.add(now)
                              val wantFollow = !contact.isFollowing
                              val idx = contacts.indexOfFirst { it.userId == contact.userId }
                              if (idx >= 0) {
                                contacts[idx] = contact.copy(isFollowing = wantFollow)
                              }
                              scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                  apiClient.setFollowContact(contact.userId, wantFollow)
                                }
                                when (res) {
                                  is DesktopApiOutcome.Ok -> {
                                    val i2 = contacts.indexOfFirst { it.userId == contact.userId }
                                    if (i2 >= 0) contacts[i2] = contacts[i2].copy(isFollowing = res.data)
                                  }
                                  is DesktopApiOutcome.Fail -> {
                                    if (res.statusCode == 429) {
                                      val i2 = contacts.indexOfFirst { it.userId == contact.userId }
                                      if (i2 >= 0) contacts[i2] = contact
                                      toastMessage = TelefamContactsRules.RATE_LIMIT_TOAST_MESSAGE
                                    } else {
                                      pendingContactOps.add(DesktopPendingContactOp(contact.userId, wantFollow))
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                        if (contactsHasMore) {
                          item(span = { GridItemSpan(maxLineSpan) }) {
                            LaunchedEffect(contactsPage) {
                              val nextPage = contactsPage + 1
                              val res = withContext(Dispatchers.IO) {
                                apiClient.listContacts(filter, searchQuery, nextPage)
                              }
                              if (res is DesktopApiOutcome.Ok) {
                                contactsPage = nextPage
                                res.data.items.forEach { item ->
                                  if (contacts.none { it.userId == item.userId }) contacts.add(item)
                                }
                                contactsHasMore = res.data.hasMore
                              }
                            }
                          }
                        }
                      }
                    }
                  }

                  "feeds" -> {
                    val onInteractFeed: (String, SharedFeedAction, String) -> Unit = { pid, act, reason ->
                      scope.launch {
                        withContext(Dispatchers.IO) {
                          apiClient.interactFeed(postId = pid, action = act, reason = reason)
                        }
                      }
                    }
                    val shareHandler: (DesktopFeedPost) -> Unit = { post ->
                      val url = apiClient.shareUrlForPost(post.postId)
                      runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
                      }
                      val idx = feedPosts.indexOfFirst { it.postId == post.postId }
                      if (idx >= 0) {
                        val cur = feedPosts[idx]
                        feedPosts[idx] = cur.copy(sharesCount = cur.sharesCount + 1)
                      }
                      onInteractFeed(post.postId, SharedFeedAction.SHARE, "")
                      toastMessage = "Link copied"
                    }

                    DesktopReusableFeedStage(
                      items = feedPosts,
                      initialIndex = 0,
                      activeTab = activeFeedTab,
                      isStandaloneViewer = false,
                      onSelectTab = { activeFeedTab = it },
                      onLiveClick = { toastMessage = "LIVE — Coming soon" },
                      onSearchClick = { showFeedSearch = true },
                      onBackClick = {},
                      onCommentClick = { toastMessage = "Comments — Coming soon" },
                      onToggleAuthorFollow = { authorId, currentlyFollowing ->
                        val wantFollow = !currentlyFollowing
                        for (i in feedPosts.indices) {
                          if (feedPosts[i].authorUserId == authorId) {
                            feedPosts[i] = feedPosts[i].copy(isFollowingAuthor = wantFollow)
                          }
                        }
                        scope.launch {
                          withContext(Dispatchers.IO) { apiClient.setFollowContact(authorId, wantFollow) }
                        }
                      },
                      onToggleLike = { pid ->
                        val idx = feedPosts.indexOfFirst { it.postId == pid }
                        if (idx >= 0) {
                          val cur = feedPosts[idx]
                          val next = !cur.isLiked
                          feedPosts[idx] = cur.copy(
                            isLiked = next,
                            likesCount = if (next) cur.likesCount + 1 else (cur.likesCount - 1).coerceAtLeast(0),
                          )
                          onInteractFeed(pid, if (next) SharedFeedAction.LIKE else SharedFeedAction.UNLIKE, "")
                        }
                      },
                      onToggleFavourite = { pid ->
                        val idx = feedPosts.indexOfFirst { it.postId == pid }
                        if (idx >= 0) {
                          val cur = feedPosts[idx]
                          val next = !cur.isFavourited
                          feedPosts[idx] = cur.copy(
                            isFavourited = next,
                            favouritesCount = if (next) cur.favouritesCount + 1 else (cur.favouritesCount - 1).coerceAtLeast(0),
                          )
                          onInteractFeed(pid, if (next) SharedFeedAction.FAVOURITE else SharedFeedAction.UNFAVOURITE, "")
                        }
                      },
                      onToggleRepost = { pid ->
                        val idx = feedPosts.indexOfFirst { it.postId == pid }
                        if (idx >= 0) {
                          val cur = feedPosts[idx]
                          val next = !cur.isReposted
                          feedPosts[idx] = cur.copy(
                            isReposted = next,
                            repostsCount = if (next) cur.repostsCount + 1 else (cur.repostsCount - 1).coerceAtLeast(0),
                          )
                          onInteractFeed(pid, if (next) SharedFeedAction.REPOST else SharedFeedAction.UNREPOST, "")
                          if (next) toastMessage = "Reposted"
                        }
                      },
                      onCopyShareLink = shareHandler,
                      onDownloadVideo = { post ->
                        scope.launch {
                          val outFile = File(System.getProperty("user.home"), "Downloads/telefam_${post.postId}.mp4")
                          val res = withContext(Dispatchers.IO) { apiClient.downloadFeedVideo(post.postId, outFile) }
                          toastMessage = when (res) {
                            is DesktopApiOutcome.Ok -> "Saved to Downloads"
                            is DesktopApiOutcome.Fail -> res.message
                          }
                        }
                      },
                      onNotInterested = { pid ->
                        feedPosts.removeAll { it.postId == pid }
                        onInteractFeed(pid, SharedFeedAction.NOT_INTERESTED, "")
                      },
                      onReport = { pid, reason ->
                        onInteractFeed(pid, SharedFeedAction.REPORT, reason)
                        toastMessage = "Report submitted"
                      },
                      onBlock = { pid, uid ->
                        feedPosts.removeAll { it.authorUserId == uid }
                        onInteractFeed(pid, SharedFeedAction.BLOCK, "")
                      },
                      onEditOwnerPost = { item -> postLaunchTarget = DesktopPostLaunchTarget.EditPost(item) },
                      onDeleteOwnerPost = { pid ->
                        feedPosts.removeAll { it.postId == pid }
                        scope.launch { withContext(Dispatchers.IO) { apiClient.deletePost(pid) } }
                      },
                      onTapHashtag = { tag, mode -> postLaunchTarget = DesktopPostLaunchTarget.PrefillHashtag(tag, mode) },
                      onTapAudio = { song, mode -> postLaunchTarget = DesktopPostLaunchTarget.PrefillSong(song, mode) },
                      onLoadMore = {
                        if (feedHasMore) {
                          scope.launch {
                            val nextP = feedPage + 1
                            val res = withContext(Dispatchers.IO) { apiClient.getFeeds(activeFeedTab, nextP) }
                            if (res is DesktopApiOutcome.Ok) {
                              feedPage = nextP
                              res.data.items.forEach { item ->
                                if (feedPosts.none { it.postId == item.postId }) feedPosts.add(item)
                              }
                              feedHasMore = res.data.hasMore
                            }
                          }
                        }
                      },
                      onCreateFirstPost = { postLaunchTarget = DesktopPostLaunchTarget.CreateNew },
                      onToast = { toastMessage = it },
                    )
                  }

                  "profile" -> {
                    Column(
                      modifier = Modifier.fillMaxSize().padding(32.dp),
                      horizontalAlignment = Alignment.CenterHorizontally,
                      verticalArrangement = Arrangement.Center,
                    ) {
                      Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
                      ) {
                        Column(
                          modifier = Modifier.padding(24.dp),
                          horizontalAlignment = Alignment.CenterHorizontally,
                          verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                          Box(
                            modifier = Modifier.size(72.dp).clip(CircleShape).background(DesktopCrimson),
                            contentAlignment = Alignment.Center,
                          ) {
                            Text(
                              text = TelefamContactsRules.initialsFor(session.fullName, session.username),
                              color = Color.White,
                              fontWeight = FontWeight.Bold,
                              fontSize = 22.sp,
                            )
                          }
                          Text(session.fullName.ifBlank { session.email }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                          if (session.username.isNotBlank()) {
                            Text("@${session.username}", color = DesktopCrimson, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                          }
                          if (session.location.isNotBlank()) {
                            Text(session.location, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                          }
                          if (session.bio.isNotBlank()) {
                            Text(session.bio, textAlign = TextAlign.Center, fontSize = 13.5.sp)
                          }
                          HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                          OutlinedButton(
                            onClick = { apiClient.signOut() },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, DesktopCrimson),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                          ) {
                            Text("Sign out", color = DesktopCrimson, fontWeight = FontWeight.Bold)
                          }
                        }
                      }
                    }
                  }

                  else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                      Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                          text = "Start a conversation",
                          fontSize = 18.sp,
                          fontWeight = FontWeight.Bold,
                          color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                          onClick = { activeTab = "contacts" },
                          colors = ButtonDefaults.buttonColors(containerColor = DesktopCrimson),
                        ) {
                          Text("Open contacts", color = Color.White)
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          if (showFeedSearch) {
            DesktopFeedSearchOverlay(
              results = feedSearchResults,
              shortcuts = feedSearchShortcuts,
              query = feedSearchQuery,
              activeShortcut = feedActiveShortcut,
              onQueryChanged = { feedSearchQuery = it },
              onShortcutSelected = { sc -> feedActiveShortcut = if (feedActiveShortcut == sc) "" else sc },
              onClose = { showFeedSearch = false },
              onCommentClick = { toastMessage = "Comments — Coming soon" },
              onToggleAuthorFollow = { authorId, currentlyFollowing ->
                val wantFollow = !currentlyFollowing
                feedSearchResults = feedSearchResults.map {
                  if (it.authorUserId == authorId) it.copy(isFollowingAuthor = wantFollow) else it
                }
                scope.launch { withContext(Dispatchers.IO) { apiClient.setFollowContact(authorId, wantFollow) } }
              },
              onToggleLike = { pid ->
                feedSearchResults = feedSearchResults.map {
                  if (it.postId == pid) {
                    val next = !it.isLiked
                    it.copy(isLiked = next, likesCount = if (next) it.likesCount + 1 else (it.likesCount - 1).coerceAtLeast(0))
                  } else it
                }
                val item = feedSearchResults.firstOrNull { it.postId == pid }
                scope.launch {
                  withContext(Dispatchers.IO) {
                    apiClient.interactFeed(pid, if (item?.isLiked == true) SharedFeedAction.LIKE else SharedFeedAction.UNLIKE)
                  }
                }
              },
              onToggleFavourite = { pid ->
                feedSearchResults = feedSearchResults.map {
                  if (it.postId == pid) {
                    val next = !it.isFavourited
                    it.copy(isFavourited = next, favouritesCount = if (next) it.favouritesCount + 1 else (it.favouritesCount - 1).coerceAtLeast(0))
                  } else it
                }
                val item = feedSearchResults.firstOrNull { it.postId == pid }
                scope.launch {
                  withContext(Dispatchers.IO) {
                    apiClient.interactFeed(pid, if (item?.isFavourited == true) SharedFeedAction.FAVOURITE else SharedFeedAction.UNFAVOURITE)
                  }
                }
              },
              onToggleRepost = { pid ->
                feedSearchResults = feedSearchResults.map {
                  if (it.postId == pid) {
                    val next = !it.isReposted
                    it.copy(isReposted = next, repostsCount = if (next) it.repostsCount + 1 else (it.repostsCount - 1).coerceAtLeast(0))
                  } else it
                }
                val item = feedSearchResults.firstOrNull { it.postId == pid }
                if (item?.isReposted == true) toastMessage = "Reposted"
                scope.launch {
                  withContext(Dispatchers.IO) {
                    apiClient.interactFeed(pid, if (item?.isReposted == true) SharedFeedAction.REPOST else SharedFeedAction.UNREPOST)
                  }
                }
              },
              onCopyShareLink = { post ->
                val url = apiClient.shareUrlForPost(post.postId)
                runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null) }
                toastMessage = "Link copied"
              },
              onDownloadVideo = { post ->
                scope.launch {
                  val outFile = File(System.getProperty("user.home"), "Downloads/telefam_${post.postId}.mp4")
                  val res = withContext(Dispatchers.IO) { apiClient.downloadFeedVideo(post.postId, outFile) }
                  toastMessage = if (res is DesktopApiOutcome.Ok) "Saved to Downloads" else "Download unavailable"
                }
              },
              onNotInterested = { pid -> feedSearchResults = feedSearchResults.filterNot { it.postId == pid } },
              onReport = { pid, reason ->
                scope.launch { withContext(Dispatchers.IO) { apiClient.interactFeed(pid, SharedFeedAction.REPORT, reason) } }
                toastMessage = "Report submitted"
              },
              onBlock = { pid, uid ->
                feedSearchResults = feedSearchResults.filterNot { it.authorUserId == uid }
                scope.launch { withContext(Dispatchers.IO) { apiClient.interactFeed(pid, SharedFeedAction.BLOCK) } }
              },
              onEditOwnerPost = { item ->
                showFeedSearch = false
                postLaunchTarget = DesktopPostLaunchTarget.EditPost(item)
              },
              onDeleteOwnerPost = { pid ->
                feedSearchResults = feedSearchResults.filterNot { it.postId == pid }
                scope.launch { withContext(Dispatchers.IO) { apiClient.deletePost(pid) } }
              },
              onTapHashtag = { tag, mode ->
                showFeedSearch = false
                postLaunchTarget = DesktopPostLaunchTarget.PrefillHashtag(tag, mode)
              },
              onTapAudio = { song, mode ->
                showFeedSearch = false
                postLaunchTarget = DesktopPostLaunchTarget.PrefillSong(song, mode)
              },
              onToast = { toastMessage = it },
            )
          }

          val currentTarget = postLaunchTarget
          if (currentTarget != null) {
            DesktopPostComposerOverlay(
              apiClient = apiClient,
              isDark = isDark,
              contacts = contacts,
              knownHashtags = knownHashtags,
              launchTarget = currentTarget,
              onDismiss = { postLaunchTarget = null },
              onPublished = { publishedPost ->
                val existingIdx = feedPosts.indexOfFirst { it.postId == publishedPost.postId }
                if (existingIdx >= 0) {
                  feedPosts[existingIdx] = publishedPost
                } else {
                  feedPosts.add(0, publishedPost)
                }
                scope.launch {
                  delay(450L)
                  postLaunchTarget = null
                }
              },
              onToast = { msg -> toastMessage = msg },
            )
          }
        }

        if (toastMessage != null) {
          Surface(
            color = Color.Black.copy(alpha = 0.85f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
          ) {
            Text(
              text = toastMessage.orEmpty(),
              color = Color.White,
              fontSize = 13.5.sp,
              modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DesktopRailButton(
  label: String,
  icon: DesktopIconKind,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val tint = if (selected) DesktopCrimson else MaterialTheme.colorScheme.onSurfaceVariant
  Column(
    modifier = Modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    DesktopSvgIcon(icon, tint, 21.dp)
    Text(
      text = label,
      fontSize = 12.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      color = tint,
    )
  }
}

private enum class DesktopIconKind {
  CLOSE, PLUS, TEXT_MODE, PHOTOS_MODE, VIDEO_MODE, MUSIC, TAG_PEOPLE, HASHTAG,
  MUTE, UNMUTE, CHECK, CHEVRON_RIGHT, HEART, HEART_FILLED, COMMENT, SHARE_ARROW,
  BOOKMARK, BOOKMARK_FILLED, MORE_DOTS, CHEVRON_UP, CHEVRON_DOWN, BACK_ARROW,
  SEARCH, LIVE_TV, SPARKLE, CHATS, CONTACTS, FEEDS, PROFILE
}

@Composable
private fun DesktopSvgIcon(
  kind: DesktopIconKind,
  tint: Color,
  size: Dp = 18.dp,
) {
  Canvas(modifier = Modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val stroke = Stroke(width = w * 0.095f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
      DesktopIconKind.CLOSE -> {
        drawLine(tint, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.PLUS -> {
        drawLine(tint, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.TEXT_MODE -> {
        drawLine(tint, Offset(w * 0.2f, h * 0.25f), Offset(w * 0.8f, h * 0.25f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.25f), Offset(w * 0.5f, h * 0.8f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.PHOTOS_MODE -> {
        drawRoundRect(tint, Offset(w * 0.16f, h * 0.2f), Size(w * 0.68f, h * 0.6f), CornerRadius(w * 0.1f), style = stroke)
        drawCircle(tint, radius = w * 0.08f, center = Offset(w * 0.34f, h * 0.38f))
        val path = Path().apply {
          moveTo(w * 0.22f, h * 0.72f)
          lineTo(w * 0.44f, h * 0.50f)
          lineTo(w * 0.60f, h * 0.64f)
          lineTo(w * 0.78f, h * 0.48f)
        }
        drawPath(path, tint, style = stroke)
      }
      DesktopIconKind.VIDEO_MODE -> {
        drawRoundRect(tint, Offset(w * 0.14f, h * 0.22f), Size(w * 0.72f, h * 0.56f), CornerRadius(w * 0.1f), style = stroke)
        val tri = Path().apply {
          moveTo(w * 0.42f, h * 0.38f)
          lineTo(w * 0.64f, h * 0.50f)
          lineTo(w * 0.42f, h * 0.62f)
          close()
        }
        drawPath(tri, tint)
      }
      DesktopIconKind.MUSIC -> {
        drawCircle(tint, radius = w * 0.14f, center = Offset(w * 0.34f, h * 0.72f))
        drawLine(tint, Offset(w * 0.46f, h * 0.72f), Offset(w * 0.46f, h * 0.22f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.46f, h * 0.22f), Offset(w * 0.76f, h * 0.30f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.TAG_PEOPLE, DesktopIconKind.CONTACTS -> {
        drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.42f, h * 0.34f), style = stroke)
        val arc = Path().apply {
          moveTo(w * 0.18f, h * 0.78f)
          cubicTo(w * 0.18f, h * 0.56f, w * 0.66f, h * 0.56f, w * 0.66f, h * 0.78f)
        }
        drawPath(arc, tint, style = stroke)
        if (kind == DesktopIconKind.TAG_PEOPLE) {
          drawLine(tint, Offset(w * 0.78f, h * 0.34f), Offset(w * 0.78f, h * 0.54f), stroke.width, StrokeCap.Round)
          drawLine(tint, Offset(w * 0.68f, h * 0.44f), Offset(w * 0.88f, h * 0.44f), stroke.width, StrokeCap.Round)
        }
      }
      DesktopIconKind.HASHTAG -> {
        drawLine(tint, Offset(w * 0.38f, h * 0.18f), Offset(w * 0.30f, h * 0.82f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.68f, h * 0.18f), Offset(w * 0.60f, h * 0.82f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.38f), Offset(w * 0.82f, h * 0.38f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.16f, h * 0.64f), Offset(w * 0.80f, h * 0.64f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.MUTE -> {
        val spk = Path().apply {
          moveTo(w * 0.2f, h * 0.42f)
          lineTo(w * 0.38f, h * 0.42f)
          lineTo(w * 0.56f, h * 0.24f)
          lineTo(w * 0.56f, h * 0.76f)
          lineTo(w * 0.38f, h * 0.58f)
          lineTo(w * 0.2f, h * 0.58f)
          close()
        }
        drawPath(spk, tint)
        drawLine(tint, Offset(w * 0.68f, h * 0.38f), Offset(w * 0.86f, h * 0.62f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.86f, h * 0.38f), Offset(w * 0.68f, h * 0.62f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.UNMUTE -> {
        val spk = Path().apply {
          moveTo(w * 0.2f, h * 0.42f)
          lineTo(w * 0.38f, h * 0.42f)
          lineTo(w * 0.56f, h * 0.24f)
          lineTo(w * 0.56f, h * 0.76f)
          lineTo(w * 0.38f, h * 0.58f)
          lineTo(w * 0.2f, h * 0.58f)
          close()
        }
        drawPath(spk, tint)
        drawLine(tint, Offset(w * 0.70f, h * 0.34f), Offset(w * 0.70f, h * 0.66f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.84f, h * 0.24f), Offset(w * 0.84f, h * 0.76f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.CHECK -> {
        val chk = Path().apply {
          moveTo(w * 0.22f, h * 0.52f)
          lineTo(w * 0.42f, h * 0.72f)
          lineTo(w * 0.78f, h * 0.28f)
        }
        drawPath(chk, tint, style = stroke)
      }
      DesktopIconKind.CHEVRON_RIGHT -> {
        val chev = Path().apply {
          moveTo(w * 0.36f, h * 0.24f)
          lineTo(w * 0.64f, h * 0.50f)
          lineTo(w * 0.36f, h * 0.76f)
        }
        drawPath(chev, tint, style = stroke)
      }
      DesktopIconKind.HEART, DesktopIconKind.HEART_FILLED -> {
        val heart = Path().apply {
          moveTo(w * 0.50f, h * 0.84f)
          cubicTo(w * 0.14f, h * 0.60f, w * 0.10f, h * 0.28f, w * 0.32f, h * 0.20f)
          cubicTo(w * 0.42f, h * 0.16f, w * 0.49f, h * 0.24f, w * 0.50f, h * 0.30f)
          cubicTo(w * 0.51f, h * 0.24f, w * 0.58f, h * 0.16f, w * 0.68f, h * 0.20f)
          cubicTo(w * 0.90f, h * 0.28f, w * 0.86f, h * 0.60f, w * 0.50f, h * 0.84f)
          close()
        }
        if (kind == DesktopIconKind.HEART_FILLED) drawPath(heart, tint) else drawPath(heart, tint, style = stroke)
      }
      DesktopIconKind.COMMENT, DesktopIconKind.CHATS -> {
        val bubble = Path().apply {
          moveTo(w * 0.16f, h * 0.22f)
          lineTo(w * 0.84f, h * 0.22f)
          lineTo(w * 0.84f, h * 0.70f)
          lineTo(w * 0.42f, h * 0.70f)
          lineTo(w * 0.24f, h * 0.84f)
          lineTo(w * 0.24f, h * 0.70f)
          lineTo(w * 0.16f, h * 0.70f)
          close()
        }
        drawPath(bubble, tint, style = stroke)
      }
      DesktopIconKind.SHARE_ARROW -> {
        val plane = Path().apply {
          moveTo(w * 0.88f, h * 0.16f)
          lineTo(w * 0.12f, h * 0.46f)
          lineTo(w * 0.44f, h * 0.58f)
          lineTo(w * 0.56f, h * 0.88f)
          close()
        }
        drawPath(plane, tint, style = stroke)
      }
      DesktopIconKind.BOOKMARK, DesktopIconKind.BOOKMARK_FILLED -> {
        val bm = Path().apply {
          moveTo(w * 0.26f, h * 0.16f)
          lineTo(w * 0.74f, h * 0.16f)
          lineTo(w * 0.74f, h * 0.86f)
          lineTo(w * 0.50f, h * 0.68f)
          lineTo(w * 0.26f, h * 0.86f)
          close()
        }
        if (kind == DesktopIconKind.BOOKMARK_FILLED) drawPath(bm, tint) else drawPath(bm, tint, style = stroke)
      }
      DesktopIconKind.MORE_DOTS -> {
        listOf(0.24f, 0.50f, 0.76f).forEach { rx ->
          drawCircle(tint, radius = w * 0.08f, center = Offset(w * rx, h * 0.50f))
        }
      }
      DesktopIconKind.CHEVRON_UP -> {
        val p = Path().apply {
          moveTo(w * 0.24f, h * 0.62f)
          lineTo(w * 0.50f, h * 0.36f)
          lineTo(w * 0.76f, h * 0.62f)
        }
        drawPath(p, tint, style = stroke)
      }
      DesktopIconKind.CHEVRON_DOWN -> {
        val p = Path().apply {
          moveTo(w * 0.24f, h * 0.38f)
          lineTo(w * 0.50f, h * 0.64f)
          lineTo(w * 0.76f, h * 0.38f)
        }
        drawPath(p, tint, style = stroke)
      }
      DesktopIconKind.BACK_ARROW -> {
        val p = Path().apply {
          moveTo(w * 0.58f, h * 0.22f)
          lineTo(w * 0.28f, h * 0.50f)
          lineTo(w * 0.58f, h * 0.78f)
        }
        drawPath(p, tint, style = stroke)
        drawLine(tint, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.82f, h * 0.50f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.SEARCH -> {
        drawCircle(tint, radius = w * 0.26f, center = Offset(w * 0.42f, h * 0.42f), style = stroke)
        drawLine(tint, Offset(w * 0.61f, h * 0.61f), Offset(w * 0.84f, h * 0.84f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.LIVE_TV -> {
        drawRoundRect(tint, Offset(w * 0.14f, h * 0.28f), Size(w * 0.72f, h * 0.54f), CornerRadius(w * 0.12f), style = stroke)
        drawLine(tint, Offset(w * 0.36f, h * 0.14f), Offset(w * 0.50f, h * 0.28f), stroke.width, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.64f, h * 0.14f), Offset(w * 0.50f, h * 0.28f), stroke.width, StrokeCap.Round)
      }
      DesktopIconKind.SPARKLE -> {
        val sp = Path().apply {
          moveTo(w * 0.50f, h * 0.12f)
          lineTo(w * 0.60f, h * 0.40f)
          lineTo(w * 0.88f, h * 0.50f)
          lineTo(w * 0.60f, h * 0.60f)
          lineTo(w * 0.50f, h * 0.88f)
          lineTo(w * 0.40f, h * 0.60f)
          lineTo(w * 0.12f, h * 0.50f)
          lineTo(w * 0.40f, h * 0.40f)
          close()
        }
        drawPath(sp, tint)
      }
      DesktopIconKind.FEEDS -> {
        drawRoundRect(tint, Offset(w * 0.20f, h * 0.14f), Size(w * 0.60f, h * 0.72f), CornerRadius(w * 0.1f), style = stroke)
        val tri = Path().apply {
          moveTo(w * 0.42f, h * 0.38f)
          lineTo(w * 0.64f, h * 0.50f)
          lineTo(w * 0.42f, h * 0.62f)
          close()
        }
        drawPath(tri, tint)
      }
      DesktopIconKind.PROFILE -> {
        drawCircle(tint, radius = w * 0.18f, center = Offset(w * 0.50f, h * 0.34f), style = stroke)
        val shoulders = Path().apply {
          moveTo(w * 0.20f, h * 0.82f)
          cubicTo(w * 0.20f, h * 0.58f, w * 0.80f, h * 0.58f, w * 0.80f, h * 0.82f)
        }
        drawPath(shoulders, tint, style = stroke)
      }
    }
  }
}

@Composable
private fun DesktopPostThemeCanvas(
  theme: SharedPostTheme,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit = {},
) {
  val start = Color(theme.startColorArgb)
  val end = Color(theme.endColorArgb)
  val accent = Color(theme.accentColorArgb)

  Box(modifier = modifier.background(Brush.linearGradient(colors = listOf(start, end)))) {
    if (theme.kind == SharedPostThemeKind.IMAGE_SCENE) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        when (theme.id) {
          "scene_aurora" -> {
            val aurora = Path().apply {
              moveTo(0f, h * 0.42f)
              cubicTo(w * 0.25f, h * 0.12f, w * 0.65f, h * 0.56f, w, h * 0.24f)
              lineTo(w, h * 0.48f)
              cubicTo(w * 0.65f, h * 0.72f, w * 0.30f, h * 0.32f, 0f, h * 0.60f)
              close()
            }
            drawPath(aurora, color = accent.copy(alpha = 0.32f))
          }
          "scene_crimson_silk" -> {
            val wave = Path().apply {
              moveTo(0f, h * 0.32f)
              cubicTo(w * 0.35f, h * 0.08f, w * 0.65f, h * 0.54f, w, h * 0.22f)
              lineTo(w, h)
              lineTo(0f, h)
              close()
            }
            drawPath(wave, color = accent.copy(alpha = 0.20f))
          }
          "scene_dunes" -> {
            drawCircle(accent.copy(alpha = 0.48f), radius = w * 0.14f, center = Offset(w * 0.76f, h * 0.26f))
            val dune = Path().apply {
              moveTo(0f, h * 0.68f)
              cubicTo(w * 0.30f, h * 0.52f, w * 0.70f, h * 0.80f, w, h * 0.60f)
              lineTo(w, h)
              lineTo(0f, h)
              close()
            }
            drawPath(dune, color = Color(0xFF531814).copy(alpha = 0.55f))
          }
          "scene_waves" -> {
            for (idx in 0..2) {
              val base = h * (0.48f + idx * 0.16f)
              val p = Path().apply {
                moveTo(0f, base)
                cubicTo(w * 0.33f, base - h * 0.10f, w * 0.66f, base + h * 0.10f, w, base - h * 0.04f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
              }
              drawPath(p, color = accent.copy(alpha = 0.16f + idx * 0.08f))
            }
          }
          "scene_stars" -> {
            listOf(
              Offset(w * 0.16f, h * 0.20f),
              Offset(w * 0.32f, h * 0.14f),
              Offset(w * 0.82f, h * 0.22f),
              Offset(w * 0.72f, h * 0.36f),
              Offset(w * 0.20f, h * 0.76f),
              Offset(w * 0.84f, h * 0.78f),
            ).forEachIndexed { idx, pt ->
              drawCircle(accent.copy(alpha = if (idx % 2 == 0) 0.75f else 0.45f), radius = 3.2f, center = pt)
            }
          }
          "scene_botanical" -> {
            drawCircle(accent.copy(alpha = 0.18f), radius = w * 0.26f, center = Offset(w * 0.12f, h * 0.86f))
            drawCircle(accent.copy(alpha = 0.16f), radius = w * 0.22f, center = Offset(w * 0.88f, h * 0.14f))
          }
          "scene_paper" -> {
            drawRoundRect(
              color = accent.copy(alpha = 0.55f),
              topLeft = Offset(w * 0.05f, h * 0.05f),
              size = Size(w * 0.90f, h * 0.90f),
              cornerRadius = CornerRadius(24f, 24f),
              style = Stroke(width = 2.5f),
            )
          }
        }
      }
    }
    content()
  }
}

private fun desktopPostTextStyle(font: SharedPostFont, ink: Color): TextStyle = when (font) {
  SharedPostFont.CLASSIC -> TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = ink)
  SharedPostFont.SERIF -> TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, color = ink)
  SharedPostFont.HAND -> TextStyle(fontFamily = FontFamily.Cursive, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = ink)
  SharedPostFont.TYPEWRITER -> TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = ink)
  SharedPostFont.BOLD -> TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = 0.6.sp, color = ink)
  SharedPostFont.MONO -> TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, color = ink)
  SharedPostFont.DISPLAY -> TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 28.sp, color = ink)
  SharedPostFont.ROUNDED -> TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = ink)
  SharedPostFont.ITALIC -> TextStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 25.sp, color = ink)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopPostComposerOverlay(
  apiClient: DesktopApiClient,
  isDark: Boolean,
  contacts: List<DesktopContactItem>,
  knownHashtags: MutableList<SharedHashtagItem>,
  launchTarget: DesktopPostLaunchTarget,
  onDismiss: () -> Unit,
  onPublished: (DesktopFeedPost) -> Unit,
  onToast: (String) -> Unit,
) {
  val editPost = (launchTarget as? DesktopPostLaunchTarget.EditPost)?.item
  val scope = rememberCoroutineScope()
  var mode by remember(launchTarget) {
    mutableStateOf(
      when (launchTarget) {
        DesktopPostLaunchTarget.CreateNew -> SharedPostMode.TEXT
        is DesktopPostLaunchTarget.PrefillHashtag -> launchTarget.mode
        is DesktopPostLaunchTarget.PrefillSong -> launchTarget.mode
        is DesktopPostLaunchTarget.EditPost -> launchTarget.item.mode
      },
    )
  }
  var textContent by remember(launchTarget) { mutableStateOf(editPost?.textContent.orEmpty()) }
  var caption by remember(launchTarget) { mutableStateOf(editPost?.caption.orEmpty()) }
  var selectedThemeId by remember(launchTarget) { mutableStateOf(editPost?.themeId?.ifBlank { "crimson" } ?: "crimson") }
  var selectedFont by remember(launchTarget) { mutableStateOf(editPost?.fontOption ?: SharedPostFont.CLASSIC) }
  val selectedPhotos = remember(launchTarget) { mutableStateListOf<DesktopSelectedPhoto>() }
  var selectedVideo by remember(launchTarget) { mutableStateOf<DesktopSelectedVideo?>(null) }
  var muteOriginalAudio by remember(launchTarget) { mutableStateOf(editPost?.muteOriginalAudio ?: false) }

  var selectedSong by remember(launchTarget) {
    mutableStateOf(
      when (launchTarget) {
        is DesktopPostLaunchTarget.PrefillSong -> launchTarget.song
        is DesktopPostLaunchTarget.EditPost -> launchTarget.item.song
        else -> null
      },
    )
  }
  val taggedUserIds = remember(launchTarget) { mutableStateListOf<String>() }
  val selectedHashtags = remember(launchTarget) {
    mutableStateListOf<String>().apply {
      when (launchTarget) {
        is DesktopPostLaunchTarget.PrefillHashtag -> add(launchTarget.tag)
        is DesktopPostLaunchTarget.EditPost -> addAll(launchTarget.item.hashtags)
        else -> Unit
      }
    }
  }

  var activeSheet by remember { mutableStateOf<String?>(null) }
  var songQuery by remember { mutableStateOf("") }
  var songResults by remember { mutableStateOf<List<SharedSongTrack>>(emptyList()) }
  var isSearchingSongs by remember { mutableStateOf(false) }
  var tagQuery by remember { mutableStateOf("") }
  var hashtagQuery by remember { mutableStateOf("") }

  LaunchedEffect(activeSheet, songQuery) {
    if (activeSheet != "song") return@LaunchedEffect
    val term = songQuery.trim().ifEmpty { "trending" }
    delay(250L)
    isSearchingSongs = true
    val res = withContext(Dispatchers.IO) { apiClient.searchSongs(term) }
    if (res is DesktopApiOutcome.Ok) songResults = res.data
    isSearchingSongs = false
  }

  LaunchedEffect(activeSheet, hashtagQuery) {
    if (activeSheet != "hashtags") return@LaunchedEffect
    delay(200L)
    val res = withContext(Dispatchers.IO) { apiClient.searchHashtags(hashtagQuery) }
    if (res is DesktopApiOutcome.Ok) {
      knownHashtags.clear()
      knownHashtags.addAll(res.data)
    }
  }

  var isUploading by remember { mutableStateOf(false) }
  var uploadPct by remember { mutableStateOf(0) }
  var uploadedDone by remember { mutableStateOf(false) }

  val canShare = !isUploading && !uploadedDone && when (mode) {
    SharedPostMode.TEXT -> textContent.trim().isNotEmpty()
    SharedPostMode.PHOTOS -> selectedPhotos.isNotEmpty() || editPost != null
    SharedPostMode.VIDEO -> selectedVideo != null || editPost != null
  }
  val activeTheme = SharedPostRules.themes.firstOrNull { it.id == selectedThemeId } ?: SharedPostRules.themes.first()
  val blushBg = if (isDark) DesktopDarkBlush else DesktopBlush

  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier.fillMaxWidth().background(DesktopCrimson).padding(horizontal = 24.dp, vertical = 14.dp),
      ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
          ) {
            DesktopSvgIcon(DesktopIconKind.CLOSE, Color.White, 20.dp)
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = if (editPost != null) "Edit post" else "New post",
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
          )
          Spacer(modifier = Modifier.weight(1f))
          Surface(
            onClick = {
              if (!canShare) return@Surface
              isUploading = true
              uploadPct = 10
              scope.launch {
                val outcome: DesktopApiOutcome<DesktopFeedPost> = withContext(Dispatchers.IO) {
                  if (editPost != null) {
                    val upd = apiClient.updatePost(
                      postId = editPost.postId,
                      textContent = textContent,
                      caption = caption,
                      themeId = selectedThemeId,
                      fontId = selectedFont.id,
                      song = selectedSong,
                      hashtags = selectedHashtags.toList(),
                      muteOriginalAudio = muteOriginalAudio,
                    )
                    when (upd) {
                      is DesktopApiOutcome.Ok -> DesktopApiOutcome.Ok(
                        editPost.copy(
                          textContent = textContent,
                          caption = caption,
                          themeId = selectedThemeId,
                          fontOption = selectedFont,
                          song = selectedSong,
                          hashtags = selectedHashtags.toList(),
                          muteOriginalAudio = muteOriginalAudio,
                        ),
                      )
                      is DesktopApiOutcome.Fail -> upd
                    }
                  } else {
                    val photoIds = mutableListOf<String>()
                    var contentId = ""
                    var videoMediaId = ""
                    var thumbId = ""

                    if (mode == SharedPostMode.PHOTOS) {
                      selectedPhotos.forEachIndexed { idx, p ->
                        val compressed = compressDesktopPhotoToJpeg(p.sourceFile)
                        val up = apiClient.uploadPostPhoto(compressed)
                        if (up is DesktopApiOutcome.Ok) {
                          photoIds.add(up.data.mediaId)
                          if (contentId.isBlank()) contentId = up.data.contentId
                        } else if (up is DesktopApiOutcome.Fail) {
                          return@withContext up
                        }
                        uploadPct = 20 + ((idx + 1) * 55 / selectedPhotos.size.coerceAtLeast(1))
                      }
                    } else if (mode == SharedPostMode.VIDEO && selectedVideo != null) {
                      val vid = selectedVideo!!
                      val videoBytes = vid.sourceFile.readBytes()
                      val up = apiClient.uploadPostVideo(
                        videoBytes = videoBytes,
                        frameHashesCsv = vid.frameHashes.joinToString(","),
                        durationMs = vid.durationMs,
                        trimStartMs = vid.trimStartMs,
                        trimEndMs = vid.trimEndMs,
                        width = 1080,
                        height = 1920,
                        thumbnailBytes = null,
                      )
                      when (up) {
                        is DesktopApiOutcome.Ok -> {
                          videoMediaId = up.data.mediaId
                          thumbId = up.data.thumbnailMediaId
                          contentId = up.data.contentId.ifBlank { vid.contentId }
                          uploadPct = 82
                        }
                        is DesktopApiOutcome.Fail -> return@withContext up
                      }
                    }

                    val created = apiClient.createPost(
                      clientOpId = UUID.randomUUID().toString(),
                      mode = mode,
                      textContent = textContent,
                      caption = caption,
                      themeId = selectedThemeId,
                      fontId = selectedFont.id,
                      song = selectedSong,
                      taggedUserIds = taggedUserIds.toList(),
                      hashtags = selectedHashtags.toList(),
                      photoMediaIds = photoIds,
                      videoMediaId = videoMediaId,
                      thumbnailMediaId = thumbId,
                      trimStartMs = selectedVideo?.trimStartMs ?: 0L,
                      trimEndMs = selectedVideo?.trimEndMs ?: 0L,
                      muteOriginalAudio = muteOriginalAudio,
                      contentId = contentId,
                    )
                    when (created) {
                      is DesktopApiOutcome.Ok -> DesktopApiOutcome.Ok(
                        DesktopFeedPost(
                          postId = created.data,
                          authorUserId = apiClient.session.email,
                          authorUsername = apiClient.session.username.ifBlank { "you" },
                          authorFullName = apiClient.session.fullName.ifBlank { "You" },
                          isFollowingAuthor = false,
                          isOwner = true,
                          mode = mode,
                          textContent = textContent,
                          caption = caption,
                          themeId = selectedThemeId,
                          fontOption = selectedFont,
                          song = selectedSong,
                          hashtags = selectedHashtags.toList(),
                          muteOriginalAudio = muteOriginalAudio,
                        ),
                      )
                      is DesktopApiOutcome.Fail -> created
                    }
                  }
                }
                isUploading = false
                when (outcome) {
                  is DesktopApiOutcome.Ok -> {
                    uploadPct = 100
                    uploadedDone = true
                    onPublished(outcome.data)
                  }
                  is DesktopApiOutcome.Fail -> onToast(outcome.message)
                }
              }
            },
            shape = RoundedCornerShape(20.dp),
            color = if (canShare) Color.White else Color.White.copy(alpha = 0.72f),
          ) {
            val label = when {
              isUploading -> "$uploadPct%"
              uploadedDone -> "Uploaded"
              editPost != null -> "Save"
              else -> "Share"
            }
            Text(
              text = label,
              color = DesktopCrimson.copy(alpha = if (canShare) 1f else 0.55f),
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp,
              modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
          }
        }

        if (editPost == null) {
          Spacer(modifier = Modifier.height(10.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SharedPostMode.entries.forEach { m ->
              val selected = mode == m
              val kind = when (m) {
                SharedPostMode.TEXT -> DesktopIconKind.TEXT_MODE
                SharedPostMode.PHOTOS -> DesktopIconKind.PHOTOS_MODE
                SharedPostMode.VIDEO -> DesktopIconKind.VIDEO_MODE
              }
              Surface(
                onClick = { mode = m },
                shape = RoundedCornerShape(20.dp),
                color = if (selected) Color.White else Color.Transparent,
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  DesktopSvgIcon(kind, if (selected) DesktopCrimson else Color.White, 16.dp)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = m.label,
                    color = if (selected) DesktopCrimson else Color.White,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.5.sp,
                  )
                }
              }
            }
          }
        }
      }

      if (isUploading) {
        LinearProgressIndicator(
          progress = { (uploadPct / 100f).coerceIn(0.05f, 1f) },
          color = DesktopCrimson,
          trackColor = MaterialTheme.colorScheme.outlineVariant,
          modifier = Modifier.fillMaxWidth().height(3.dp),
        )
      }

      Row(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .widthIn(max = 1080.dp)
          .align(Alignment.CenterHorizontally)
          .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
      ) {
        Column(modifier = Modifier.weight(1.05f).verticalScroll(rememberScrollState())) {
          when (mode) {
            SharedPostMode.TEXT -> {
              val ink = if (activeTheme.isLightInk) Color.White else Color(0xFF1C1F26)
              val cardStyle = desktopPostTextStyle(selectedFont, ink).copy(textAlign = TextAlign.Center)
              DesktopPostThemeCanvas(
                theme = activeTheme,
                modifier = Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(22.dp)),
              ) {
                Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                  if (textContent.isEmpty()) {
                    Text("What's on your mind?", style = cardStyle.copy(color = ink.copy(alpha = 0.72f)), textAlign = TextAlign.Center)
                  }
                  BasicTextField(
                    value = textContent,
                    onValueChange = { if (it.length <= SharedPostRules.MAX_TEXT_CHARS) textContent = it },
                    textStyle = cardStyle,
                    cursorBrush = SolidColor(ink),
                    modifier = Modifier.fillMaxWidth(),
                  )
                  Text(
                    text = "${textContent.length}/${SharedPostRules.MAX_TEXT_CHARS}",
                    color = ink.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomEnd),
                  )
                }
              }
            }

            SharedPostMode.PHOTOS -> {
              val pickPhotosDesktop = {
                val dlg = FileDialog(null as Frame?, "Select photos", FileDialog.LOAD).apply {
                  isMultipleMode = true
                  setFilenameFilter { _, name ->
                    val low = name.lowercase()
                    low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") || low.endsWith(".webp")
                  }
                  isVisible = true
                }
                val picked = dlg.files.orEmpty()
                val slots = (SharedPostRules.MAX_PHOTOS - selectedPhotos.size).coerceAtLeast(0)
                picked.take(slots).forEach { f ->
                  val preview = runCatching { SkiaImage.makeFromEncoded(f.readBytes()).toComposeImageBitmap() }.getOrNull()
                  selectedPhotos.add(
                    DesktopSelectedPhoto(
                      id = UUID.randomUUID().toString(),
                      fileName = "${UUID.randomUUID()}.jpg",
                      sourceFile = f,
                      previewBitmap = preview,
                      byteSize = f.length(),
                    ),
                  )
                }
              }

              if (selectedPhotos.isEmpty()) {
                val dashColor = MaterialTheme.colorScheme.outline
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .drawBehind {
                      drawRoundRect(
                        color = dashColor,
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f), 0f)),
                        cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                      )
                    }
                    .clickable(onClick = pickPhotosDesktop),
                  contentAlignment = Alignment.Center,
                ) {
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(blushBg), contentAlignment = Alignment.Center) {
                      DesktopSvgIcon(DesktopIconKind.PHOTOS_MODE, DesktopCrimson, 26.dp)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Add photos", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Up to 10", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                  }
                }
              } else {
                Surface(
                  shape = RoundedCornerShape(20.dp),
                  color = MaterialTheme.colorScheme.surface,
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                      Text("${selectedPhotos.size}/${SharedPostRules.MAX_PHOTOS}", fontWeight = FontWeight.Bold)
                      Spacer(modifier = Modifier.weight(1f))
                      if (selectedPhotos.size < SharedPostRules.MAX_PHOTOS) {
                        Surface(onClick = pickPhotosDesktop, shape = RoundedCornerShape(16.dp), color = DesktopCrimson) {
                          Text("Add", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                      }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                      items(selectedPhotos, key = { it.id }) { p ->
                        Box(
                          modifier = Modifier.size(132.dp).clip(RoundedCornerShape(14.dp)).background(blushBg),
                          contentAlignment = Alignment.Center,
                        ) {
                          if (p.previewBitmap != null) {
                            Image(p.previewBitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                          }
                          Box(
                            modifier = Modifier
                              .align(Alignment.TopEnd)
                              .padding(6.dp)
                              .size(24.dp)
                              .clip(CircleShape)
                              .background(Color.Black.copy(alpha = 0.6f))
                              .clickable { selectedPhotos.removeAll { it.id == p.id } },
                            contentAlignment = Alignment.Center,
                          ) {
                            DesktopSvgIcon(DesktopIconKind.CLOSE, Color.White, 12.dp)
                          }
                        }
                      }
                    }
                  }
                }
              }
            }

            SharedPostMode.VIDEO -> {
              val pickVideoDesktop = {
                val dlg = FileDialog(null as Frame?, "Select video", FileDialog.LOAD).apply {
                  setFilenameFilter { _, name ->
                    val low = name.lowercase()
                    low.endsWith(".mp4") || low.endsWith(".mov") || low.endsWith(".webm")
                  }
                  isVisible = true
                }
                val f = dlg.files?.firstOrNull()
                if (f != null) {
                  if (f.length() > SharedPostRules.MAX_VIDEO_BYTES) {
                    onToast("Video exceeds 200 MB limit")
                  } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val bytes = f.inputStream().use { inp ->
                      val buf = ByteArray(8192)
                      val n = inp.read(buf)
                      if (n > 0) buf.copyOf(n) else ByteArray(0)
                    }
                    val frames = (0 until 8).map { i ->
                      val h = digest.digest(bytes + byteArrayOf(i.toByte()))
                      h.take(8).joinToString("") { "%02x".format(it) }
                    }
                    val dur = 15_000L
                    val cidPayload = "tf_vid_fp_v1|${frames.joinToString(",")}|dur=$dur|trim=0:$dur"
                    val cidHex = digest.digest(cidPayload.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
                    selectedVideo = DesktopSelectedVideo(
                      id = UUID.randomUUID().toString(),
                      fileName = "${UUID.randomUUID()}.mp4",
                      sourceFile = f,
                      byteSize = f.length(),
                      durationMs = dur,
                      trimStartMs = 0L,
                      trimEndMs = dur,
                      selectedThumbIndex = 1,
                      frameHashes = frames,
                      contentId = "vid_$cidHex",
                    )
                  }
                }
              }

              val vid = selectedVideo
              if (vid == null) {
                val dashColor = MaterialTheme.colorScheme.outline
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .drawBehind {
                      drawRoundRect(
                        color = dashColor,
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f), 0f)),
                        cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                      )
                    }
                    .clickable(onClick = pickVideoDesktop),
                  contentAlignment = Alignment.Center,
                ) {
                  Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(blushBg), contentAlignment = Alignment.Center) {
                      DesktopSvgIcon(DesktopIconKind.VIDEO_MODE, DesktopCrimson, 26.dp)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Add video", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Up to 200 MB", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                  }
                }
              } else {
                Surface(
                  shape = RoundedCornerShape(20.dp),
                  color = MaterialTheme.colorScheme.surface,
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                      modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF10131A)),
                      contentAlignment = Alignment.Center,
                    ) {
                      DesktopSvgIcon(DesktopIconKind.VIDEO_MODE, Color.White.copy(alpha = 0.7f), 42.dp)
                      Box(
                        modifier = Modifier
                          .align(Alignment.TopEnd)
                          .padding(10.dp)
                          .size(28.dp)
                          .clip(CircleShape)
                          .background(Color.Black.copy(alpha = 0.65f))
                          .clickable { selectedVideo = null },
                        contentAlignment = Alignment.Center,
                      ) {
                        DesktopSvgIcon(DesktopIconKind.CLOSE, Color.White, 14.dp)
                      }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                      items(vid.frameHashes.indices.toList()) { idx ->
                        val active = idx == vid.selectedThumbIndex
                        Box(
                          modifier = Modifier
                            .size(width = 52.dp, height = 64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(blushBg)
                            .border(if (active) 2.dp else 1.dp, if (active) DesktopCrimson else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .clickable { selectedVideo = vid.copy(selectedThumbIndex = idx) },
                          contentAlignment = Alignment.Center,
                        ) {
                          Text("${idx + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DesktopCrimson)
                        }
                      }
                    }

                    RangeSlider(
                      value = vid.trimStartMs.toFloat()..vid.trimEndMs.toFloat(),
                      onValueChange = { r ->
                        val s = r.start.toLong().coerceIn(0L, vid.durationMs)
                        val e = r.endInclusive.toLong().coerceIn(s + 500L, vid.durationMs)
                        selectedVideo = vid.copy(trimStartMs = s, trimEndMs = e)
                      },
                      valueRange = 0f..vid.durationMs.toFloat(),
                      colors = SliderDefaults.colors(thumbColor = DesktopCrimson, activeTrackColor = DesktopCrimson),
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                      DesktopSvgIcon(if (muteOriginalAudio) DesktopIconKind.MUTE else DesktopIconKind.UNMUTE, if (muteOriginalAudio) DesktopCrimson else MaterialTheme.colorScheme.onSurfaceVariant, 20.dp)
                      Spacer(modifier = Modifier.width(10.dp))
                      Text("Mute original audio", modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                      Switch(
                        checked = muteOriginalAudio,
                        onCheckedChange = { muteOriginalAudio = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DesktopCrimson),
                      )
                    }
                  }
                }
              }
            }
          }
        }

        Column(
          modifier = Modifier.weight(0.95f).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          if (mode == SharedPostMode.TEXT) {
            Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              SharedPostRules.themes.forEach { t ->
                val selected = t.id == selectedThemeId
                Box(
                  modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .padding(if (selected) 3.dp else 0.dp)
                    .clip(CircleShape)
                    .clickable { selectedThemeId = t.id },
                ) {
                  DesktopPostThemeCanvas(theme = t, modifier = Modifier.fillMaxSize()) {
                    if (selected) {
                      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        DesktopSvgIcon(DesktopIconKind.CHECK, if (t.isLightInk) Color.White else Color(0xFF1C1F26), 16.dp)
                      }
                    }
                  }
                }
              }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              SharedPostFont.entries.forEach { fontOpt ->
                val selected = fontOpt == selectedFont
                Surface(
                  onClick = { selectedFont = fontOpt },
                  shape = RoundedCornerShape(18.dp),
                  color = if (selected) blushBg else MaterialTheme.colorScheme.surface,
                  border = BorderStroke(1.dp, if (selected) DesktopCrimson else MaterialTheme.colorScheme.outlineVariant),
                ) {
                  Text(
                    text = "${if (fontOpt == SharedPostFont.BOLD) "AA" else "Aa"}  ${fontOpt.label}",
                    color = if (selected) DesktopCrimson else MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                  )
                }
              }
            }
          } else {
            Surface(
              shape = RoundedCornerShape(18.dp),
              color = MaterialTheme.colorScheme.surface,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
            ) {
              Box(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                if (caption.isEmpty()) Text("Write a caption...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.5.sp)
                BasicTextField(
                  value = caption,
                  onValueChange = { if (it.length <= SharedPostRules.MAX_CAPTION_CHARS) caption = it },
                  textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.5.sp),
                  cursorBrush = SolidColor(DesktopCrimson),
                  modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                )
                Text(
                  text = "${caption.length}/${SharedPostRules.MAX_CAPTION_CHARS}",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 12.sp,
                  modifier = Modifier.align(Alignment.BottomEnd),
                )
              }
            }
          }

          Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column {
              DesktopMetadataRow(
                icon = DesktopIconKind.MUSIC,
                blush = blushBg,
                title = selectedSong?.title ?: "Song",
                trailing = selectedSong?.artist ?: "Add",
                highlight = selectedSong != null,
                onClick = { activeSheet = "song" },
              )
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
              DesktopMetadataRow(
                icon = DesktopIconKind.TAG_PEOPLE,
                blush = blushBg,
                title = "Tag people",
                trailing = if (taggedUserIds.isEmpty()) "Add" else "${taggedUserIds.size}",
                highlight = taggedUserIds.isNotEmpty(),
                onClick = { activeSheet = "tag" },
              )
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
              DesktopMetadataRow(
                icon = DesktopIconKind.HASHTAG,
                blush = blushBg,
                title = "Hashtags",
                trailing = if (selectedHashtags.isEmpty()) "Add" else selectedHashtags.joinToString(" ") { "#$it" },
                highlight = selectedHashtags.isNotEmpty(),
                onClick = { activeSheet = "hashtags" },
              )
            }
          }
        }
      }
    }

    if (activeSheet != null) {
      Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable { activeSheet = null },
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          shape = RoundedCornerShape(22.dp),
          color = MaterialTheme.colorScheme.surface,
          modifier = Modifier.width(460.dp).clickable(enabled = false) {},
        ) {
          Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val sheetTitle = when (activeSheet) {
              "song" -> "Song"
              "tag" -> "Tag people"
              else -> "Hashtags"
            }
            Text(sheetTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            when (activeSheet) {
              "tag" -> {
                DesktopSheetSearchBar(tagQuery, { tagQuery = it }, "Search people", isDark)
                val q = tagQuery.trim().lowercase()
                val matches = contacts.filter { q.isEmpty() || it.fullName.lowercase().contains(q) || it.username.lowercase().contains(q) }
                LazyColumn(modifier = Modifier.heightIn(min = 120.dp, max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  items(matches, key = { it.userId }) { person ->
                    val checked = person.userId in taggedUserIds
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { if (checked) taggedUserIds.remove(person.userId) else taggedUserIds.add(person.userId) }
                        .padding(6.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(person.fullName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("@${person.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp)
                      }
                      Box(
                        modifier = Modifier
                          .size(20.dp)
                          .clip(CircleShape)
                          .background(if (checked) DesktopCrimson else Color.Transparent)
                          .border(1.5.dp, if (checked) DesktopCrimson else MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                      ) {
                        if (checked) DesktopSvgIcon(DesktopIconKind.CHECK, Color.White, 12.dp)
                      }
                    }
                  }
                }
              }
              "hashtags" -> {
                DesktopSheetSearchBar(hashtagQuery, { hashtagQuery = it }, "Search hashtags", isDark)
                val clean = SharedPostRules.sanitizeHashtag(hashtagQuery)
                val rows = buildList {
                  addAll(knownHashtags.filter { clean.isEmpty() || it.tag.contains(clean) })
                  if (clean.isNotEmpty() && none { it.tag == clean }) {
                    add(SharedHashtagItem(tag = clean, postCount = 0L))
                  }
                }
                LazyColumn(modifier = Modifier.heightIn(min = 120.dp, max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  items(rows, key = { it.tag }) { item ->
                    val chosen = item.tag in selectedHashtags
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { if (chosen) selectedHashtags.remove(item.tag) else selectedHashtags.add(item.tag) }
                        .padding(8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text("#${item.tag}", fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                        Text("${item.postCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                      }
                      Box(
                        modifier = Modifier
                          .size(20.dp)
                          .clip(CircleShape)
                          .background(if (chosen) DesktopCrimson else Color.Transparent)
                          .border(1.5.dp, if (chosen) DesktopCrimson else MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                      ) {
                        if (chosen) DesktopSvgIcon(DesktopIconKind.CHECK, Color.White, 12.dp)
                      }
                    }
                  }
                }
              }
              "song" -> {
                DesktopSheetSearchBar(songQuery, { songQuery = it }, "Search songs", isDark)
                if (isSearchingSongs) {
                  LinearProgressIndicator(color = DesktopCrimson, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(min = 120.dp, max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  items(songResults, key = { it.trackId }) { track ->
                    val chosen = selectedSong?.trackId == track.trackId
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                          selectedSong = if (chosen) null else track
                          activeSheet = null
                        }
                        .padding(8.dp),
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                      }
                      if (track.previewUrl.startsWith("https://")) {
                        Surface(
                          onClick = {
                            runCatching {
                              if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().browse(URI(track.previewUrl))
                              }
                            }
                          },
                          shape = RoundedCornerShape(12.dp),
                          color = blushBg,
                        ) {
                          Text("Preview", color = DesktopCrimson, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                      }
                      if (chosen) DesktopSvgIcon(DesktopIconKind.CHECK, DesktopCrimson, 16.dp)
                    }
                  }
                }
              }
            }
            Button(
              onClick = { activeSheet = null },
              shape = RoundedCornerShape(22.dp),
              colors = ButtonDefaults.buttonColors(containerColor = DesktopCrimson),
              modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
              Text("Done", color = Color.White, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }
}

private fun compressDesktopPhotoToJpeg(file: File): ByteArray {
  val src = ImageIO.read(file) ?: return file.readBytes()
  val maxDim = 1440
  val scale = if (max(src.width, src.height) > maxDim) {
    maxDim.toDouble() / max(src.width, src.height).toDouble()
  } else {
    1.0
  }
  val w = (src.width * scale).toInt().coerceAtLeast(1)
  val h = (src.height * scale).toInt().coerceAtLeast(1)
  val outImg = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
  val g = outImg.createGraphics()
  g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
  g.drawImage(src, 0, 0, w, h, null)
  g.dispose()
  val baos = ByteArrayOutputStream()
  ImageIO.write(outImg, "jpg", baos)
  return baos.toByteArray()
}

@Composable
private fun DesktopSheetSearchBar(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  isDark: Boolean,
) {
  Surface(
    shape = RoundedCornerShape(22.dp),
    border = BorderStroke(1.5.dp, DesktopCrimson),
    color = if (isDark) DesktopDarkSearch else MaterialTheme.colorScheme.background,
    modifier = Modifier.fillMaxWidth().height(42.dp),
  ) {
    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
      DesktopSvgIcon(DesktopIconKind.SEARCH, MaterialTheme.colorScheme.onSurfaceVariant, 16.dp)
      Spacer(modifier = Modifier.width(8.dp))
      Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        BasicTextField(
          value = value,
          onValueChange = onValueChange,
          singleLine = true,
          textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
          cursorBrush = SolidColor(DesktopCrimson),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun DesktopMetadataRow(
  icon: DesktopIconKind,
  blush: Color,
  title: String,
  trailing: String,
  highlight: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.size(34.dp).clip(CircleShape).background(blush), contentAlignment = Alignment.Center) {
      DesktopSvgIcon(icon, DesktopCrimson, 18.dp)
    }
    Spacer(modifier = Modifier.width(12.dp))
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
    Text(
      text = trailing,
      color = if (highlight) DesktopCrimson else MaterialTheme.colorScheme.onSurfaceVariant,
      fontSize = 13.5.sp,
      fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(modifier = Modifier.width(4.dp))
    DesktopSvgIcon(DesktopIconKind.CHEVRON_RIGHT, MaterialTheme.colorScheme.onSurfaceVariant, 16.dp)
  }
}

@Composable
private fun DesktopContactCard(
  contact: DesktopContactItem,
  isDark: Boolean,
  onToggle: () -> Unit,
) {
  val rel = contact.relationship
  val buttonLabel = when (rel) {
    ContactRelationshipState.FOLLOW -> "Follow"
    ContactRelationshipState.FOLLOWING -> "Following"
    ContactRelationshipState.FOLLOW_BACK -> "Follow back"
    ContactRelationshipState.FRIENDS -> "Friends"
  }
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(46.dp)
          .clip(CircleShape)
          .background(if (isDark) Color(0xFF471115) else Color(0xFFFDE8E8)),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = TelefamContactsRules.initialsFor(contact.fullName, contact.username),
          color = if (isDark) Color(0xFFF87171) else DesktopCrimson,
          fontWeight = FontWeight.Bold,
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(contact.fullName, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          text = if (contact.countryDisplay.isNotBlank()) "@${contact.username} · ${contact.countryDisplay}" else "@${contact.username}",
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.width(10.dp))
      Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(18.dp),
        color = if (rel == ContactRelationshipState.FOLLOW || rel == ContactRelationshipState.FOLLOW_BACK) DesktopCrimson else MaterialTheme.colorScheme.background,
        border = if (rel == ContactRelationshipState.FOLLOWING || rel == ContactRelationshipState.FRIENDS) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
      ) {
        Text(
          text = buttonLabel,
          color = if (rel == ContactRelationshipState.FOLLOW || rel == ContactRelationshipState.FOLLOW_BACK) Color.White else MaterialTheme.colorScheme.onSurface,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
      }
    }
  }
}

@Composable
private fun DesktopTricolorSpinner(size: Dp = 42.dp) {
  val trans = rememberInfiniteTransition()
  val deg by trans.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Restart),
  )
  Canvas(modifier = Modifier.size(size).rotate(deg)) {
    val cx = this.size.width / 2f
    val cy = this.size.height / 2f
    val orbit = this.size.minDimension * 0.28f
    val dotR = this.size.minDimension * 0.13f
    val palette = listOf(Color(0xFFC80A0F), Color.White, Color(0xFF16A34A))
    palette.forEachIndexed { idx, col ->
      val rad = (-90.0 + idx * 120.0) * PI / 180.0
      drawCircle(
        color = col,
        radius = dotR,
        center = Offset(cx + (cos(rad) * orbit).toFloat(), cy + (sin(rad) * orbit).toFloat()),
      )
    }
  }
}

@Composable
private fun DesktopAudioBars(isPlaying: Boolean) {
  val trans = rememberInfiniteTransition()
  val p1 by trans.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse))
  val p2 by trans.animateFloat(1f, 0.3f, infiniteRepeatable(tween(360), RepeatMode.Reverse))
  val p3 by trans.animateFloat(0.45f, 0.95f, infiniteRepeatable(tween(500), RepeatMode.Reverse))
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(14.dp)) {
    listOf(p1, p2, p3).forEach { factor ->
      val h = if (isPlaying) 14f * factor else 6f
      Box(modifier = Modifier.width(2.5.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopReusableFeedStage(
  items: List<DesktopFeedPost>,
  initialIndex: Int,
  activeTab: SharedFeedTab,
  isStandaloneViewer: Boolean,
  onSelectTab: (SharedFeedTab) -> Unit,
  onLiveClick: () -> Unit,
  onSearchClick: () -> Unit,
  onBackClick: () -> Unit,
  onCommentClick: () -> Unit,
  onToggleAuthorFollow: (String, Boolean) -> Unit,
  onToggleLike: (String) -> Unit,
  onToggleFavourite: (String) -> Unit,
  onToggleRepost: (String) -> Unit,
  onCopyShareLink: (DesktopFeedPost) -> Unit,
  onDownloadVideo: (DesktopFeedPost) -> Unit,
  onNotInterested: (String) -> Unit,
  onReport: (String, String) -> Unit,
  onBlock: (String, String) -> Unit,
  onEditOwnerPost: (DesktopFeedPost) -> Unit,
  onDeleteOwnerPost: (String) -> Unit,
  onTapHashtag: (String, SharedPostMode) -> Unit,
  onTapAudio: (SharedSongTrack, SharedPostMode) -> Unit,
  onLoadMore: () -> Unit,
  onCreateFirstPost: () -> Unit,
  onToast: (String) -> Unit,
) {
  var currentIndex by remember(initialIndex, items.size) {
    mutableStateOf(initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
  }
  var isPaused by remember(currentIndex) { mutableStateOf(false) }
  var isFast2x by remember(currentIndex) { mutableStateOf(false) }
  var showDoubleHeart by remember { mutableStateOf(false) }
  var activeDialog by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(currentIndex, items.size) {
    if (items.isNotEmpty() && currentIndex >= items.size - 3) {
      onLoadMore()
    }
  }

  LaunchedEffect(showDoubleHeart) {
    if (showDoubleHeart) {
      delay(650L)
      showDoubleHeart = false
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF07080B))) {
    // Top Safe Overlay Bar matching reference screenshot
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp, vertical = 14.dp)
        .align(Alignment.TopCenter),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (isStandaloneViewer) {
        Surface(
          onClick = onBackClick,
          shape = CircleShape,
          color = Color.White.copy(alpha = 0.14f),
        ) {
          Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            DesktopSvgIcon(DesktopIconKind.BACK_ARROW, Color.White, 19.dp)
          }
        }
      } else {
        Surface(
          onClick = onLiveClick,
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(1.3.dp, Color.White.copy(alpha = 0.85f)),
          color = Color.Transparent,
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            DesktopSvgIcon(DesktopIconKind.LIVE_TV, Color.White, 16.dp)
            Text("LIVE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
          }
        }
      }

      Spacer(modifier = Modifier.weight(1f))

      if (!isStandaloneViewer) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
          listOf(SharedFeedTab.FOLLOWING, SharedFeedTab.FOR_YOU).forEach { tab ->
            val selected = tab == activeTab
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onSelectTab(tab) }) {
              Text(
                text = tab.label,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 16.sp,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Box(
                modifier = Modifier
                  .width(28.dp)
                  .height(3.dp)
                  .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(2.dp)),
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.weight(1f))

      Surface(
        onClick = onSearchClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.14f),
      ) {
        Box(modifier = Modifier.size(38.dp), contentAlignment = Alignment.Center) {
          DesktopSvgIcon(DesktopIconKind.SEARCH, Color.White, 19.dp)
        }
      }
    }

    val current = items.getOrNull(currentIndex)
    if (current == null) {
      Column(modifier = Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No videos yet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Create a post to start watching in Feeds.", color = Color.White.copy(alpha = 0.72f), fontSize = 13.5.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onCreateFirstPost, colors = ButtonDefaults.buttonColors(containerColor = DesktopCrimson)) {
          Text("Post", color = Color.White)
        }
      }
      return@Box
    }

    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = 64.dp, bottom = 18.dp, start = 18.dp, end = 18.dp)
        .pointerInput(current.postId) {
          detectTapGestures(
            onTap = { isPaused = !isPaused },
            onDoubleTap = {
              showDoubleHeart = true
              if (!current.isLiked) onToggleLike(current.postId)
            },
            onLongPress = { isFast2x = true },
            onPress = {
              try {
                awaitRelease()
              } finally {
                isFast2x = false
              }
            },
          )
        },
    ) {
      val density = LocalDensity.current
      val fitted = SharedFeedMath.computeFittedMediaSize(
        containerWidth = with(density) { maxWidth.toPx() },
        containerHeight = with(density) { maxHeight.toPx() },
        mediaWidth = current.mediaWidth,
        mediaHeight = current.mediaHeight,
      )
      val cardW = with(density) { fitted.width.toDp() }
      val cardH = with(density) { fitted.height.toDp() }
      val theme = SharedPostRules.themes.firstOrNull { it.id == current.themeId } ?: SharedPostRules.themes.first()

      DesktopPostThemeCanvas(
        theme = theme,
        modifier = Modifier.size(cardW, cardH).align(Alignment.Center).clip(RoundedCornerShape(18.dp)),
      ) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
          val ink = if (theme.isLightInk) Color.White else Color(0xFF1A1C1E)
          Text(
            text = current.textContent.ifBlank { current.caption.ifBlank { "@${current.authorUsername}" } },
            style = desktopPostTextStyle(current.fontOption, ink).copy(textAlign = TextAlign.Center),
          )
          if (current.mode == SharedPostMode.VIDEO && !isPaused) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
              DesktopTricolorSpinner(size = 34.dp)
            }
          }
        }
      }

      if (isPaused) {
        Surface(color = Color.Black.copy(alpha = 0.6f), shape = CircleShape, modifier = Modifier.align(Alignment.Center)) {
          Text("Paused", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
      }
      if (isFast2x) {
        Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(14.dp), modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)) {
          Text(">> 2x", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
      }
      if (showDoubleHeart) {
        Box(modifier = Modifier.align(Alignment.Center)) {
          DesktopSvgIcon(DesktopIconKind.HEART_FILLED, Color(0xFFFF5A76), 74.dp)
        }
      }

      // Bottom-Left Metadata matching screenshot
      Column(
        modifier = Modifier.align(Alignment.BottomStart).widthIn(max = 460.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val badgeText = when {
          current.repostedByUsername.isNotEmpty() -> "Reposted · @${current.repostedByUsername}"
          current.isReposted -> "Reposted"
          current.recommendationLabel.isNotBlank() -> current.recommendationLabel
          else -> ""
        }
        if (badgeText.isNotEmpty()) {
          Surface(color = Color(0xFF14181F).copy(alpha = 0.78f), shape = RoundedCornerShape(14.dp)) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(DesktopCrimson), contentAlignment = Alignment.Center) {
                DesktopSvgIcon(DesktopIconKind.SPARKLE, Color.White, 10.dp)
              }
              Text(badgeText, color = Color.White, fontSize = 12.5.sp)
            }
          }
        }
        Text(current.authorUsername, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        if (current.caption.isNotEmpty()) {
          Text(current.caption, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (current.hashtags.isNotEmpty()) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            current.hashtags.forEach { tag ->
              Text(
                text = "#$tag",
                color = Color(0xFFFF4D67),
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                modifier = Modifier.clickable { onTapHashtag(tag, current.mode) },
              )
            }
          }
        }
        Row(
          modifier = Modifier.clickable {
            val song = current.song ?: SharedSongTrack("orig_${current.postId}", "Original sound", current.authorUsername)
            onTapAudio(song, current.mode)
          },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          DesktopSvgIcon(DesktopIconKind.MUSIC, Color.White, 15.dp)
          Text(
            text = current.song?.let { "${it.title} · ${it.artist}" } ?: "Original sound",
            color = Color.White,
            fontSize = 13.5.sp,
          )
          DesktopAudioBars(isPlaying = !isPaused)
        }
      }

      // Bottom-Right Action Rail with SVG icons & creator + follow badge
      Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (items.size > 1) {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(onClick = { if (currentIndex > 0) currentIndex-- }, shape = CircleShape, color = Color.White.copy(alpha = 0.16f)) {
              Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                DesktopSvgIcon(DesktopIconKind.CHEVRON_UP, Color.White, 16.dp)
              }
            }
            Surface(onClick = { if (currentIndex < items.lastIndex) currentIndex++ }, shape = CircleShape, color = Color.White.copy(alpha = 0.16f)) {
              Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                DesktopSvgIcon(DesktopIconKind.CHEVRON_DOWN, Color.White, 16.dp)
              }
            }
          }
        }

        // Author avatar + Crimson + follow button matching screenshot
        Box(modifier = Modifier.size(54.dp), contentAlignment = Alignment.TopCenter) {
          Box(
            modifier = Modifier
              .size(46.dp)
              .clip(CircleShape)
              .background(Color(0xFF1F2430))
              .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = TelefamContactsRules.initialsFor(current.authorFullName, current.authorUsername),
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp,
            )
          }
          if (!current.isOwner && !current.isFollowingAuthor) {
            Box(
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(20.dp)
                .clip(CircleShape)
                .background(DesktopCrimson)
                .clickable { onToggleAuthorFollow(current.authorUserId, current.isFollowingAuthor) },
              contentAlignment = Alignment.Center,
            ) {
              DesktopSvgIcon(DesktopIconKind.PLUS, Color.White, 12.dp)
            }
          }
        }

        DesktopFeedSvgRailAction(
          icon = if (current.isLiked) DesktopIconKind.HEART_FILLED else DesktopIconKind.HEART,
          tint = if (current.isLiked) Color(0xFFFF5A76) else Color.White,
          label = SharedFeedMath.formatCompactCount(current.likesCount),
          onClick = { onToggleLike(current.postId) },
        )
        DesktopFeedSvgRailAction(
          icon = DesktopIconKind.COMMENT,
          tint = Color.White,
          label = SharedFeedMath.formatCompactCount(current.commentsCount),
          onClick = onCommentClick,
        )
        DesktopFeedSvgRailAction(
          icon = DesktopIconKind.SHARE_ARROW,
          tint = DesktopCrimson,
          label = SharedFeedMath.formatCompactCount(current.sharesCount),
          onClick = { activeDialog = "share" },
        )
        DesktopFeedSvgRailAction(
          icon = if (current.isFavourited) DesktopIconKind.BOOKMARK_FILLED else DesktopIconKind.BOOKMARK,
          tint = DesktopCrimson,
          label = SharedFeedMath.formatCompactCount(current.favouritesCount),
          onClick = { onToggleFavourite(current.postId) },
        )
        Surface(onClick = { activeDialog = "more" }, shape = CircleShape, color = Color.White.copy(alpha = 0.14f)) {
          Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            DesktopSvgIcon(DesktopIconKind.MORE_DOTS, Color.White, 18.dp)
          }
        }
      }
    }

    if (activeDialog != null) {
      Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { activeDialog = null },
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = MaterialTheme.colorScheme.surface,
          modifier = Modifier.width(340.dp).clickable(enabled = false) {},
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (activeDialog) {
              "share" -> {
                Text("Share", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                DesktopDialogButton("Copy link") { onCopyShareLink(current); activeDialog = null }
                DesktopDialogButton(if (current.isReposted) "Remove repost" else "Repost") { onToggleRepost(current.postId); activeDialog = null }
              }
              "report" -> {
                Text("Report", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                listOf("Spam", "Harassment", "Inappropriate content", "Misinformation").forEach { reason ->
                  DesktopDialogButton(reason) { onReport(current.postId, reason); activeDialog = null }
                }
              }
              else -> {
                Text("Options", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                DesktopDialogButton(if (current.isReposted) "Remove repost" else "Repost") { onToggleRepost(current.postId); activeDialog = null }
                DesktopDialogButton("Download") { onDownloadVideo(current); activeDialog = null }
                DesktopDialogButton("Not interested") { onNotInterested(current.postId); activeDialog = null }
                DesktopDialogButton("Report") { activeDialog = "report" }
                if (current.isOwner) {
                  DesktopDialogButton("Edit video") { activeDialog = null; onEditOwnerPost(current) }
                  DesktopDialogButton("Delete video") { onDeleteOwnerPost(current.postId); activeDialog = null }
                } else {
                  DesktopDialogButton("Block @${current.authorUsername}") { onBlock(current.postId, current.authorUserId); activeDialog = null }
                }
              }
            }
            DesktopDialogButton("Cancel") { activeDialog = null }
          }
        }
      }
    }
  }
}

@Composable
private fun DesktopFeedSvgRailAction(
  icon: DesktopIconKind,
  tint: Color,
  label: String,
  onClick: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
  ) {
    DesktopSvgIcon(icon, tint, 26.dp)
    Spacer(modifier = Modifier.height(2.dp))
    Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun DesktopDialogButton(label: String, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.background,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
  }
}

@Composable
private fun DesktopFeedSearchOverlay(
  results: List<DesktopFeedPost>,
  shortcuts: List<DesktopSearchShortcut>,
  query: String,
  activeShortcut: String,
  onQueryChanged: (String) -> Unit,
  onShortcutSelected: (String) -> Unit,
  onClose: () -> Unit,
  onCommentClick: () -> Unit,
  onToggleAuthorFollow: (String, Boolean) -> Unit,
  onToggleLike: (String) -> Unit,
  onToggleFavourite: (String) -> Unit,
  onToggleRepost: (String) -> Unit,
  onCopyShareLink: (DesktopFeedPost) -> Unit,
  onDownloadVideo: (DesktopFeedPost) -> Unit,
  onNotInterested: (String) -> Unit,
  onReport: (String, String) -> Unit,
  onBlock: (String, String) -> Unit,
  onEditOwnerPost: (DesktopFeedPost) -> Unit,
  onDeleteOwnerPost: (String) -> Unit,
  onTapHashtag: (String, SharedPostMode) -> Unit,
  onTapAudio: (SharedSongTrack, SharedPostMode) -> Unit,
  onToast: (String) -> Unit,
) {
  var viewerStartIndex by remember { mutableStateOf<Int?>(null) }

  val openViewerIdx = viewerStartIndex
  if (openViewerIdx != null) {
    DesktopReusableFeedStage(
      items = results,
      initialIndex = openViewerIdx,
      activeTab = SharedFeedTab.FOR_YOU,
      isStandaloneViewer = true,
      onSelectTab = {},
      onLiveClick = {},
      onSearchClick = { viewerStartIndex = null },
      onBackClick = { viewerStartIndex = null },
      onCommentClick = onCommentClick,
      onToggleAuthorFollow = onToggleAuthorFollow,
      onToggleLike = onToggleLike,
      onToggleFavourite = onToggleFavourite,
      onToggleRepost = onToggleRepost,
      onCopyShareLink = onCopyShareLink,
      onDownloadVideo = onDownloadVideo,
      onNotInterested = onNotInterested,
      onReport = onReport,
      onBlock = onBlock,
      onEditOwnerPost = onEditOwnerPost,
      onDeleteOwnerPost = onDeleteOwnerPost,
      onTapHashtag = onTapHashtag,
      onTapAudio = onTapAudio,
      onLoadMore = {},
      onCreateFirstPost = {},
      onToast = onToast,
    )
    return
  }

  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Surface(
            onClick = onClose,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
              DesktopSvgIcon(DesktopIconKind.BACK_ARROW, MaterialTheme.colorScheme.onSurface, 18.dp)
            }
          }
          Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.weight(1f).height(44.dp),
          ) {
            Row(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
              DesktopSvgIcon(DesktopIconKind.SEARCH, MaterialTheme.colorScheme.onSurfaceVariant, 17.dp)
              Spacer(modifier = Modifier.width(8.dp))
              Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search videos, hashtags, audio...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                BasicTextField(
                  value = query,
                  onValueChange = { onQueryChanged(it.take(80)) },
                  singleLine = true,
                  textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                  cursorBrush = SolidColor(DesktopCrimson),
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }
        }
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          item {
            val sel = activeShortcut.isEmpty()
            Surface(onClick = { onShortcutSelected("") }, shape = RoundedCornerShape(18.dp), color = if (sel) DesktopCrimson else MaterialTheme.colorScheme.background) {
              Text("All", color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
          }
          items(shortcuts, key = { it.key }) { sc ->
            val sel = activeShortcut == sc.key
            Surface(onClick = { onShortcutSelected(sc.key) }, shape = RoundedCornerShape(18.dp), color = if (sel) DesktopCrimson else MaterialTheme.colorScheme.background) {
              Text("${sc.label} (${sc.count})", color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
          }
        }
      }
      HorizontalDivider()
      if (results.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text("No videos found", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        }
      } else {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 210.dp),
          contentPadding = PaddingValues(16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          itemsIndexed(results, key = { _, it -> it.postId }) { idx, item ->
            val theme = SharedPostRules.themes.firstOrNull { it.id == item.themeId } ?: SharedPostRules.themes.first()
            Surface(
              onClick = { viewerStartIndex = idx },
              shape = RoundedCornerShape(14.dp),
              color = Color(0xFF0E121A),
              modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f),
            ) {
              DesktopPostThemeCanvas(theme = theme, modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                  val ink = if (theme.isLightInk) Color.White else Color(0xFF1A1C1E)
                  Text(
                    text = item.textContent.ifBlank { item.caption },
                    color = ink,
                    fontWeight = FontWeight.Bold,
                    maxLines = 4,
                    modifier = Modifier.align(Alignment.Center),
                  )
                  Text(
                    text = "@${item.authorUsername}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart),
                  )
                  Row(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                  ) {
                    DesktopSvgIcon(DesktopIconKind.HEART_FILLED, Color.White, 12.dp)
                    Text(
                      text = SharedFeedMath.formatCompactCount(item.likesCount),
                      color = Color.White,
                      fontSize = 11.5.sp,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
