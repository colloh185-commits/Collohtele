package telefam.company.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import telefam.company.data.network.StarSenderSummaryDto
import telefam.company.data.network.StarTransactionDto
import telefam.company.stars.StarCelebrationState
import telefam.company.stars.StarScreenRoute
import telefam.company.stars.TelefamStarsCatalog
import telefam.company.stars.TelefamStarsUiState
import telefam.company.ui.theme.LocalTelefamDarkTheme
import telefam.company.ui.theme.TelefamCrimsonBright
import telefam.company.ui.theme.TelefamDarkBg
import telefam.company.ui.theme.TelefamDarkBorder
import telefam.company.ui.theme.TelefamDarkSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

private val StarsHeaderRed = Color(0xFFD00E16)
private val StarsButtonRed = Color(0xFFDF1119)
private val StarsSoftRose = Color(0xFFFDF3F4)
private val StarsGoldLight = Color(0xFFFFEE8A)
private val StarsGoldMain = Color(0xFFFBBF24)
private val StarsGoldDeep = Color(0xFFD97706)
private val StarsGoldRim = Color(0xFF92400E)

/**
 * Tier factor [0f..1f] growing smoothly with star amount from 5 to 50,000.
 */
private fun starTierIntensity(starAmount: Long): Float {
  val clamped = starAmount.coerceIn(5L, 50_000L).toDouble()
  // log10(5)=0.699 -> log10(50000)=4.699 => span = 4.0
  return ((log10(clamped) - log10(5.0)) / 4.0).toFloat().coerceIn(0f, 1f)
}

/**
 * Custom 3D animating Star with perspective Y-rotation, beveled golden facets,
 * orbiting sparkles, and red "Telefam" wording scaled proportionally by star amount.
 */
@Composable
fun Telefam3DAnimatedStar(
  starAmount: Long,
  baseSize: Dp,
  showTelefamWording: Boolean = true,
  animateSpin: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val intensity = remember(starAmount) { starTierIntensity(starAmount) }
  val inf = rememberInfiniteTransition(label = "star_3d_${starAmount}")
  val spinPhase by inf.animateFloat(
    initialValue = 0f,
    targetValue = (2.0 * PI).toFloat(),
    animationSpec = infiniteRepeatable(
      animation = tween(
        durationMillis = (3400 - (intensity * 1100).toInt()).coerceIn(2100, 3400),
        easing = LinearEasing,
      ),
      repeatMode = RepeatMode.Restart,
    ),
    label = "star_spin",
  )
  val pulseScale by inf.animateFloat(
    initialValue = 1f,
    targetValue = 1f + 0.05f + intensity * 0.09f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "star_pulse",
  )

  val activeAngle = if (animateSpin) spinPhase else 0.18f
  val effectivePulse = if (animateSpin) pulseScale else 1f
  val sparkleCount = remember(starAmount) { 4 + (intensity * 10f).toInt() }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier,
  ) {
    Canvas(modifier = Modifier.size(baseSize)) {
      val cx = size.width * 0.5f
      val cy = size.height * 0.5f
      val outerR = size.minDimension * 0.36f * effectivePulse
      val innerR = outerR * 0.46f

      // Warm radial aura scaled by star tier intensity
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(
            StarsGoldMain.copy(alpha = 0.28f + intensity * 0.24f),
            StarsButtonRed.copy(alpha = 0.06f + intensity * 0.12f),
            Color.Transparent,
          ),
          center = Offset(cx, cy),
          radius = size.minDimension * 0.49f,
        ),
        radius = size.minDimension * 0.49f,
        center = Offset(cx, cy),
      )

      // 3D Y-axis perspective squash (never collapses cleanly below 0.22 so silhouette stays regal)
      val yawCos = cos(activeAngle)
      val xPersp = (if (yawCos >= 0f) 1f else -1f) * abs(yawCos).coerceAtLeast(0.24f)
      val tiltY = sin(activeAngle * 2f) * (size.minDimension * 0.025f)
      val depthShiftX = -sin(activeAngle) * (outerR * 0.22f)
      val depthShiftY = outerR * 0.09f

      // Extruded 3D side/back bezel layer
      draw3DFacetedStarLayer(
        cx = cx + depthShiftX,
        cy = cy + depthShiftY + tiltY,
        outerR = outerR,
        innerR = innerR,
        xScale = xPersp,
        phase = activeAngle,
        isRimLayer = true,
      )

      // Front faceted 3D gold star body
      draw3DFacetedStarLayer(
        cx = cx,
        cy = cy + tiltY,
        outerR = outerR,
        innerR = innerR,
        xScale = xPersp,
        phase = activeAngle,
        isRimLayer = false,
      )

      // Orbiting 4-point twinkling sparkles scaled by amount
      for (i in 0 until sparkleCount) {
        val theta = activeAngle * 1.15f + (i * (2.0 * PI / sparkleCount)).toFloat()
        val orbitR = outerR * (1.06f + 0.18f * sin(activeAngle * 2f + i))
        val sx = cx + cos(theta) * orbitR
        val sy = cy + sin(theta) * orbitR * 0.88f
        val tw = 0.45f + 0.55f * abs(sin(activeAngle * 2.5f + i * 0.9f))
        val spSize = size.minDimension * (0.045f + intensity * 0.03f) * tw
        val spColor = if (i % 3 == 0) StarsButtonRed.copy(alpha = 0.85f * tw) else StarsGoldLight.copy(alpha = tw)
        drawSparkle4Point(Offset(sx, sy), spSize, spColor)
      }
    }

    if (showTelefamWording) {
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "Telefam",
        color = StarsButtonRed,
        fontSize = (11.5f + intensity * 4.5f).sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.4.sp,
      )
    }
  }
}

private fun DrawScope.draw3DFacetedStarLayer(
  cx: Float,
  cy: Float,
  outerR: Float,
  innerR: Float,
  xScale: Float,
  phase: Float,
  isRimLayer: Boolean,
) {
  val pts = Array(10) { idx ->
    val r = if (idx % 2 == 0) outerR else innerR
    val a = (-PI / 2.0 + idx * (PI / 5.0)).toFloat()
    Offset(
      cx + cos(a) * r * xScale,
      cy + sin(a) * r,
    )
  }
  val centerPt = Offset(cx, cy)

  for (i in 0 until 10) {
    val next = (i + 1) % 10
    val tri = Path().apply {
      moveTo(centerPt.x, centerPt.y)
      lineTo(pts[i].x, pts[i].y)
      lineTo(pts[next].x, pts[next].y)
      close()
    }
    val facetColor = if (isRimLayer) {
      StarsGoldRim
    } else {
      val light = sin(phase + i * 0.65f)
      when {
        i % 2 == 0 && light > 0.15f -> StarsGoldLight
        i % 2 == 0 -> StarsGoldMain
        light < -0.2f -> StarsGoldDeep
        else -> Color(0xFFF59E0B)
      }
    }
    drawPath(tri, color = facetColor)
  }

  if (!isRimLayer) {
    val outline = Path().apply {
      moveTo(pts[0].x, pts[0].y)
      for (i in 1 until 10) lineTo(pts[i].x, pts[i].y)
      close()
    }
    drawPath(
      path = outline,
      color = StarsGoldLight.copy(alpha = 0.72f),
      style = Stroke(width = outerR * 0.045f, join = StrokeJoin.Round),
    )
  }
}

private fun DrawScope.drawSparkle4Point(center: Offset, radius: Float, color: Color) {
  val inner = radius * 0.24f
  val p = Path().apply {
    moveTo(center.x, center.y - radius)
    lineTo(center.x + inner, center.y - inner)
    lineTo(center.x + radius, center.y)
    lineTo(center.x + inner, center.y + inner)
    lineTo(center.x, center.y + radius)
    lineTo(center.x - inner, center.y + inner)
    lineTo(center.x - radius, center.y)
    lineTo(center.x - inner, center.y - inner)
    close()
  }
  drawPath(p, color = color, style = Fill)
}

@Composable
fun StaticStarGoldIcon(
  size: Dp = 26.dp,
  modifier: Modifier = Modifier,
) {
  Telefam3DAnimatedStar(
    starAmount = 100L,
    baseSize = size,
    showTelefamWording = false,
    animateSpin = false,
    modifier = modifier,
  )
}

/**
 * In-chat Star Gift message bubble whose star size and animation energy scale dynamically with star amount.
 */
@Composable
fun TelefamStarGiftBubble(
  starAmount: Long,
  verified: Boolean,
  isOwn: Boolean,
  peerName: String,
  peerAvatarUrl: String,
  deliveryStatus: String,
  timeLabel: String,
  isDark: Boolean,
  modifier: Modifier = Modifier,
) {
  val intensity = remember(starAmount) { starTierIntensity(starAmount) }
  val starSize = remember(starAmount) { (58 + (intensity * 54f).toInt()).dp }
  val minCardWidth = remember(starAmount) { (196 + (intensity * 64f).toInt()).dp }
  val formattedAmount = remember(starAmount) { TelefamStarsCatalog.formatComma(starAmount) }

  val cardBg = if (isDark) Color(0xFF1A1518) else Color(0xFFFFFBF4)
  val borderColor = StarsButtonRed.copy(alpha = 0.25f + intensity * 0.45f)

  Surface(
    shape = RoundedCornerShape(20.dp),
    color = cardBg,
    border = BorderStroke(1.2.dp, borderColor),
    shadowElevation = 2.dp,
    modifier = modifier.widthIn(min = minCardWidth, max = 280.dp),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
      Telefam3DAnimatedStar(
        starAmount = starAmount,
        baseSize = starSize,
        showTelefamWording = true,
        animateSpin = true,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "$formattedAmount ${if (starAmount == 1L) "Star" else "Stars"}",
        fontSize = (16f + intensity * 4f).sp,
        fontWeight = FontWeight.ExtraBold,
        color = if (isDark) Color.White else Color(0xFF18181B),
      )
      if (verified) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier
            .clip(CircleShape)
            .background(StarsButtonRed.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        ) {
          StarShieldSvgIcon(color = StarsButtonRed, modifier = Modifier.size(12.dp))
          Text(
            text = "Verified Gift",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = StarsButtonRed,
          )
        }
      }
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = timeLabel,
          fontSize = 10.5.sp,
          color = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280),
        )
        if (isOwn) {
          Spacer(modifier = Modifier.width(4.dp))
          OutgoingStatusIndicator(
            status = deliveryStatus,
            peerName = peerName,
            peerAvatarUrl = peerAvatarUrl,
          )
        }
      }
    }
  }
}

/**
 * Real-time fullscreen celebration burst overlay rendered briefly when a Star gift arrives or is sent.
 */
@Composable
fun TelefamStarGiftCelebrationOverlay(
  celebration: StarCelebrationState?,
  onDismiss: () -> Unit,
) {
  AnimatedVisibility(
    visible = celebration != null,
    enter = fadeIn(tween(240)) + scaleIn(initialScale = 0.72f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
    exit = fadeOut(tween(340)) + scaleOut(targetScale = 1.08f),
  ) {
    if (celebration != null) {
      val intensity = remember(celebration.starAmount) { starTierIntensity(celebration.starAmount) }
      val heroSize = remember(celebration.starAmount) { (128 + (intensity * 92f).toInt()).dp }
      val formatted = remember(celebration.starAmount) { TelefamStarsCatalog.formatComma(celebration.starAmount) }

      LaunchedEffect(celebration.eventKey) {
        delay((2900L + (intensity * 1300L).toLong()))
        onDismiss()
      }

      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.42f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
          ),
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          shape = RoundedCornerShape(28.dp),
          color = Color(0xFF141013).copy(alpha = 0.94f),
          border = BorderStroke(1.5.dp, Brush.linearGradient(listOf(StarsButtonRed, StarsGoldMain))),
          shadowElevation = 12.dp,
          modifier = Modifier
            .padding(24.dp)
            .widthIn(max = 360.dp),
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
          ) {
            Telefam3DAnimatedStar(
              starAmount = celebration.starAmount,
              baseSize = heroSize,
              showTelefamWording = true,
              animateSpin = true,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = "+$formatted Stars",
              fontSize = (22f + intensity * 6f).sp,
              fontWeight = FontWeight.ExtraBold,
              color = StarsGoldLight,
            )
            if (celebration.senderName.isNotBlank()) {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = if (celebration.isIncoming) {
                  "Received from ${celebration.senderName}"
                } else {
                  "Sent to ${celebration.senderName}"
                },
                fontSize = 13.5.sp,
                color = Color(0xFFE5E7EB),
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Root modal host managing Send Stars, Buy Stars, Checkout, and Profile Menu Stars screens.
 */
@Composable
fun TelefamStarsModalHost(
  state: TelefamStarsUiState,
  onBack: () -> Unit,
  onRefreshSummary: () -> Unit,
  onSendStarsTier: (Long) -> Unit,
  onOpenBuyStars: (suggestedStars: Long?) -> Unit,
  onSelectPackageForCheckout: (Long) -> Unit,
  onSelectProvider: (String) -> Unit,
  onProceedToPay: () -> Unit,
  onVerifyPayment: () -> Unit,
  onOpenCheckoutUrlAgain: (String) -> Unit,
  onOpenRecipientProfile: (String) -> Unit,
  onClearError: () -> Unit,
) {
  val route = state.currentRoute ?: return
  BackHandler(enabled = true) { onBack() }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = {},
      ),
  ) {
    when (route) {
      StarScreenRoute.SEND_STARS -> TelefamSendStarsScreen(
        state = state,
        onBack = onBack,
        onSendAmount = onSendStarsTier,
        onInsufficientRedirectToBuy = { onOpenBuyStars(it) },
        onOpenRecipientProfile = onOpenRecipientProfile,
        onClearError = onClearError,
      )
      StarScreenRoute.BUY_STARS -> TelefamBuyStarsScreen(
        state = state,
        onBack = onBack,
        onChoosePackage = onSelectPackageForCheckout,
        onClearError = onClearError,
      )
      StarScreenRoute.CHECKOUT -> TelefamStarCheckoutScreen(
        state = state,
        onBack = onBack,
        onSelectProvider = onSelectProvider,
        onProceedToPay = onProceedToPay,
        onVerifyPayment = onVerifyPayment,
        onOpenCheckoutUrlAgain = onOpenCheckoutUrlAgain,
        onClearError = onClearError,
      )
      StarScreenRoute.STARS_HUB -> TelefamStarsHubScreen(
        state = state,
        onBack = onBack,
        onRefresh = onRefreshSummary,
        onOpenBuyStars = { onOpenBuyStars(100L) },
        onClearError = onClearError,
      )
    }
  }
}

@Composable
private fun StarsCrimsonHeader(
  title: String,
  onBack: () -> Unit,
  trailingContent: (@Composable () -> Unit)? = null,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(StarsHeaderRed)
      .windowInsetsPadding(WindowInsets.statusBars),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .clip(CircleShape)
          .clickable(onClick = onBack)
          .testTag("stars_back_button")
          .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
      ) {
        StarsBackArrowSvg(color = Color.White, modifier = Modifier.size(22.dp))
      }
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = title,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier.weight(1f),
      )
      if (trailingContent != null) {
        trailingContent()
      }
    }
  }
}

/**
 * Screen 1 (Image 1): Send Stars from Inbox Gift Button
 */
@Composable
private fun TelefamSendStarsScreen(
  state: TelefamStarsUiState,
  onBack: () -> Unit,
  onSendAmount: (Long) -> Unit,
  onInsufficientRedirectToBuy: (Long) -> Unit,
  onOpenRecipientProfile: (String) -> Unit,
  onClearError: () -> Unit,
) {
  val isDark = LocalTelefamDarkTheme.current
  val sheetBg = if (isDark) TelefamDarkBg else Color(0xFFF6F7FA)
  val cardBg = if (isDark) TelefamDarkSurface else Color.White
  val border = if (isDark) TelefamDarkBorder else Color(0xFFE7EBF1)
  val infoBg = if (isDark) Color(0xFF24171A) else StarsSoftRose
  val recipient = state.recipientTarget

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(StarsHeaderRed),
  ) {
    StarsCrimsonHeader(
      title = "Send Stars",
      onBack = onBack,
      trailingContent = {
        Surface(
          shape = CircleShape,
          color = Color(0xFF8B0A10),
          modifier = Modifier.padding(end = 6.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          ) {
            StaticStarGoldIcon(size = 18.dp)
            Text(
              text = "Balance: ${TelefamStarsCatalog.formatComma(state.wallet.availableBalance)}",
              color = Color.White,
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      },
    )

    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
        .background(sheetBg)
        .navigationBarsPadding(),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier
          .widthIn(max = 720.dp)
          .fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (!state.errorBanner.isNullOrBlank()) {
          item(key = "err") {
            StarsErrorBanner(message = state.errorBanner, onDismiss = onClearError)
          }
        }

        if (recipient != null) {
          item(key = "recipient") {
            Surface(
              onClick = { onOpenRecipientProfile(recipient.userId) },
              shape = RoundedCornerShape(16.dp),
              color = cardBg,
              border = BorderStroke(1.dp, border),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
              ) {
                Box(modifier = Modifier.size(48.dp)) {
                  if (recipient.avatarUrl.isNotBlank()) {
                    AsyncImage(
                      model = recipient.avatarUrl,
                      contentDescription = recipient.fullName,
                      contentScale = ContentScale.Crop,
                      modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    )
                  } else {
                    Box(
                      modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(StarsButtonRed),
                      contentAlignment = Alignment.Center,
                    ) {
                      Text(
                        text = recipient.fullName.firstOrNull()?.uppercase() ?: "T",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                      )
                    }
                  }
                  if (recipient.isOnline) {
                    Box(
                      modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(Color(0xFF16A34A))
                        .border(2.dp, cardBg, CircleShape),
                    )
                  }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = recipient.fullName,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = "@${recipient.username.removePrefix("@")}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                StarsChevronRightSvg(
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(14.dp),
                )
              }
            }
          }
        }

        item(key = "info") {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = infoBg,
            border = BorderStroke(1.dp, StarsButtonRed.copy(alpha = 0.16f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
              Telefam3DAnimatedStar(
                starAmount = 500L,
                baseSize = 44.dp,
                showTelefamWording = false,
                animateSpin = true,
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = "Select stars to send",
                  fontSize = 15.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = "Show your support and help them grow!",
                  fontSize = 12.5.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                  shape = CircleShape,
                  color = StarsButtonRed.copy(alpha = 0.14f),
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                  ) {
                    StaticStarGoldIcon(size = 13.dp)
                    Text(
                      text = "1 star = 1 appreciation",
                      fontSize = 11.5.sp,
                      fontWeight = FontWeight.SemiBold,
                      color = StarsButtonRed,
                    )
                  }
                }
              }
            }
          }
        }

        items(TelefamStarsCatalog.TIERS, key = { it }) { tier ->
          val convertedPrice = TelefamStarsCatalog.formatConvertedPrice(state.countryIso, tier)
          val sufficient = state.wallet.availableBalance >= tier
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            border = BorderStroke(1.dp, border),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("send_star_tier_$tier"),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
              Box(
                modifier = Modifier
                  .size(44.dp)
                  .clip(CircleShape)
                  .background(if (isDark) Color(0xFF2B2313) else Color(0xFFFFF8E6)),
                contentAlignment = Alignment.Center,
              ) {
                StaticStarGoldIcon(size = 30.dp)
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = TelefamStarsCatalog.formatComma(tier),
                  fontSize = 15.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = convertedPrice,
                  fontSize = 12.5.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Button(
                onClick = {
                  if (sufficient) {
                    onSendAmount(tier)
                  } else {
                    onInsufficientRedirectToBuy(tier)
                  }
                },
                enabled = !state.isSubmittingSend,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                  containerColor = StarsButtonRed,
                  contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                modifier = Modifier
                  .height(40.dp)
                  .testTag("send_star_btn_$tier"),
              ) {
                Text(
                  text = "Send",
                  fontSize = 13.5.sp,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }
        }
        item { Spacer(modifier = Modifier.height(14.dp)) }
      }
    }
  }
}

/**
 * Screen 2 Left (Image 2): Buy Stars Package Selection
 */
@Composable
private fun TelefamBuyStarsScreen(
  state: TelefamStarsUiState,
  onBack: () -> Unit,
  onChoosePackage: (Long) -> Unit,
  onClearError: () -> Unit,
) {
  val isDark = LocalTelefamDarkTheme.current
  val sheetBg = if (isDark) TelefamDarkBg else Color(0xFFF6F7FA)
  val cardBg = if (isDark) TelefamDarkSurface else Color.White
  val border = if (isDark) TelefamDarkBorder else Color(0xFFE7EBF1)
  val infoBg = if (isDark) Color(0xFF24171A) else StarsSoftRose

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(StarsHeaderRed),
  ) {
    StarsCrimsonHeader(
      title = "Buy Stars",
      onBack = onBack,
      trailingContent = {
        Surface(shape = CircleShape, color = Color(0xFF8B0A10), modifier = Modifier.padding(end = 6.dp)) {
          Text(
            text = "${state.countryIso} · ${TelefamStarsCatalog.ruleForCountry(state.countryIso).currencyCode}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          )
        }
      },
    )

    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
        .background(sheetBg)
        .navigationBarsPadding(),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier
          .widthIn(max = 720.dp)
          .fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (!state.errorBanner.isNullOrBlank()) {
          item(key = "err") {
            StarsErrorBanner(message = state.errorBanner, onDismiss = onClearError)
          }
        }

        item(key = "banner") {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = infoBg,
            border = BorderStroke(1.dp, StarsButtonRed.copy(alpha = 0.16f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
              Telefam3DAnimatedStar(
                starAmount = 500L,
                baseSize = 46.dp,
                showTelefamWording = false,
                animateSpin = true,
              )
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                  text = "Support your favorite creators and help them grow!",
                  fontSize = 14.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = "Buy Stars and send them as a gift during live streams, or on their content.",
                  fontSize = 12.sp,
                  lineHeight = 16.5.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        item(key = "pkg_title") {
          Text(
            text = "Choose a package",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
          )
        }

        items(TelefamStarsCatalog.TIERS, key = { it }) { tier ->
          val isPopular = tier == 5L
          val convertedPrice = TelefamStarsCatalog.formatConvertedPrice(state.countryIso, tier)
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            border = BorderStroke(if (isPopular) 1.5.dp else 1.dp, if (isPopular) StarsButtonRed else border),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("buy_star_tier_$tier"),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
              Box(
                modifier = Modifier
                  .size(44.dp)
                  .clip(CircleShape)
                  .background(if (isDark) Color(0xFF2B2313) else Color(0xFFFFF8E6)),
                contentAlignment = Alignment.Center,
              ) {
                StaticStarGoldIcon(size = 30.dp)
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = TelefamStarsCatalog.formatComma(tier),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  if (isPopular) {
                    Surface(shape = CircleShape, color = StarsButtonRed) {
                      Text(
                        text = "Popular",
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                      )
                    }
                  }
                }
                Text(
                  text = convertedPrice,
                  fontSize = 12.5.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Button(
                onClick = { onChoosePackage(tier) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                  containerColor = StarsButtonRed,
                  contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                modifier = Modifier
                  .height(40.dp)
                  .testTag("buy_star_btn_$tier"),
              ) {
                Text(
                  text = "Buy",
                  fontSize = 13.5.sp,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }
        }
        item { Spacer(modifier = Modifier.height(14.dp)) }
      }
    }
  }
}

/**
 * Screen 2 Right (Image 2): Checkout with Paystack / PayPal + Idempotent Verification
 */
@Composable
private fun TelefamStarCheckoutScreen(
  state: TelefamStarsUiState,
  onBack: () -> Unit,
  onSelectProvider: (String) -> Unit,
  onProceedToPay: () -> Unit,
  onVerifyPayment: () -> Unit,
  onOpenCheckoutUrlAgain: (String) -> Unit,
  onClearError: () -> Unit,
) {
  val isDark = LocalTelefamDarkTheme.current
  val sheetBg = if (isDark) TelefamDarkBg else Color(0xFFF6F7FA)
  val cardBg = if (isDark) TelefamDarkSurface else Color.White
  val border = if (isDark) TelefamDarkBorder else Color(0xFFE7EBF1)
  val roseBg = if (isDark) Color(0xFF24171A) else StarsSoftRose

  val convertedPrice = TelefamStarsCatalog.formatConvertedPrice(state.countryIso, state.selectedPackageStars)
  val settlementNote = TelefamStarsCatalog.gatewaySettlementNote(
    state.countryIso,
    state.selectedProvider,
    state.selectedPackageStars,
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(StarsHeaderRed),
  ) {
    StarsCrimsonHeader(title = "Checkout", onBack = onBack)

    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
        .background(sheetBg)
        .navigationBarsPadding(),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier
          .widthIn(max = 720.dp)
          .fillMaxSize()
          .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        if (!state.errorBanner.isNullOrBlank()) {
          StarsErrorBanner(message = state.errorBanner, onDismiss = onClearError)
        }

        // Package summary card
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = cardBg,
          border = BorderStroke(1.dp, border),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
          ) {
            Box(
              modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(if (isDark) Color(0xFF2B2313) else Color(0xFFFFF8E6)),
              contentAlignment = Alignment.Center,
            ) {
              StaticStarGoldIcon(size = 34.dp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
              Text(
                text = "${TelefamStarsCatalog.formatComma(state.selectedPackageStars)} Stars",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = convertedPrice,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (settlementNote != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = settlementNote,
                  fontSize = 11.5.sp,
                  color = StarsButtonRed,
                  fontWeight = FontWeight.Medium,
                )
              }
            }
          }
        }

        Text(
          text = "Payment Method",
          fontSize = 15.5.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground,
        )

        // Paystack card
        PaymentMethodCard(
          selected = state.selectedProvider == "paystack",
          title = "Paystack",
          subtitle = "Pay securely with M-Pesa, cards and bank transfers",
          cardBg = cardBg,
          border = border,
          icon = { PaystackSvgMark(modifier = Modifier.size(34.dp)) },
          onClick = { onSelectProvider("paystack") },
          tag = "checkout_method_paystack",
        )

        // PayPal card
        PaymentMethodCard(
          selected = state.selectedProvider == "paypal",
          title = "PayPal",
          subtitle = "Pay with your PayPal account or card",
          cardBg = cardBg,
          border = border,
          icon = { PayPalSvgMark(modifier = Modifier.size(34.dp)) },
          onClick = { onSelectProvider("paypal") },
          tag = "checkout_method_paypal",
        )

        // Security shield card
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = roseBg,
          border = BorderStroke(1.dp, StarsButtonRed.copy(alpha = 0.16f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
          ) {
            StarShieldSvgIcon(color = StarsButtonRed, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text(
                text = "Your payment is secure and encrypted",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = "We use industry-standard encryption to protect your information.",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        val pendingIntent = state.pendingCheckoutIntent
        if (pendingIntent != null && !pendingIntent.credited) {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = BorderStroke(1.dp, StarsButtonRed.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Text(
                text = "Verification ready (${pendingIntent.provider.replaceFirstChar { it.uppercase() }})",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                  onClick = onVerifyPayment,
                  enabled = !state.isVerifyingPayment,
                  shape = CircleShape,
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D), contentColor = Color.White),
                  modifier = Modifier.weight(1f).height(44.dp),
                ) {
                  if (state.isVerifyingPayment) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                  } else {
                    Text("Verify Payment", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                  }
                }
                if (pendingIntent.checkoutUrl.isNotBlank()) {
                  OutlinedButton(
                    onClick = { onOpenCheckoutUrlAgain(pendingIntent.checkoutUrl) },
                    shape = CircleShape,
                    modifier = Modifier.height(44.dp),
                  ) {
                    Text("Open Checkout", fontSize = 13.sp, color = StarsButtonRed)
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
          onClick = onProceedToPay,
          enabled = !state.isCreatingCheckout && !state.isVerifyingPayment,
          shape = CircleShape,
          colors = ButtonDefaults.buttonColors(
            containerColor = StarsButtonRed,
            contentColor = Color.White,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("checkout_proceed_button"),
        ) {
          if (state.isCreatingCheckout) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
          } else {
            Text(
              text = "Proceed to Pay",
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PaymentMethodCard(
  selected: Boolean,
  title: String,
  subtitle: String,
  cardBg: Color,
  border: Color,
  icon: @Composable () -> Unit,
  onClick: () -> Unit,
  tag: String,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    color = cardBg,
    border = BorderStroke(if (selected) 1.6.dp else 1.dp, if (selected) StarsButtonRed else border),
    modifier = Modifier
      .fillMaxWidth()
      .testTag(tag),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
      icon()
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          fontSize = 15.5.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          fontSize = 12.5.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(modifier = Modifier.width(10.dp))
      Box(
        modifier = Modifier
          .size(22.dp)
          .clip(CircleShape)
          .border(2.dp, if (selected) StarsButtonRed else Color(0xFFD1D5DB), CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        if (selected) {
          Box(
            modifier = Modifier
              .size(11.dp)
              .clip(CircleShape)
              .background(StarsButtonRed),
          )
        }
      }
    }
  }
}

/**
 * Profile Menu -> Stars Hub Screen (Balance, Received, Top Senders, Verified History, Buy Stars — no payouts)
 */
@Composable
private fun TelefamStarsHubScreen(
  state: TelefamStarsUiState,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onOpenBuyStars: () -> Unit,
  onClearError: () -> Unit,
) {
  val isDark = LocalTelefamDarkTheme.current
  val screenBg = if (isDark) TelefamDarkBg else Color(0xFFF3F5F9)
  val cardBg = if (isDark) TelefamDarkSurface else Color.White
  val border = if (isDark) TelefamDarkBorder else Color(0xFFE7EBF1)
  var selectedTab by remember { mutableStateOf(0) } // 0 = Senders, 1 = History

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(screenBg)
      .windowInsetsPadding(WindowInsets.statusBars)
      .navigationBarsPadding(),
  ) {
    // Top Navigation Bar
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .clip(CircleShape)
          .clickable(onClick = onBack)
          .testTag("stars_hub_back_button")
          .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
      ) {
        StarsBackArrowSvg(color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
      }
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = "Stars",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(
        onClick = onRefresh,
        enabled = !state.isLoadingSummary,
        shape = CircleShape,
        border = BorderStroke(1.dp, border),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.height(36.dp),
      ) {
        Text(
          text = if (state.isLoadingSummary) "Syncing" else "Refresh",
          fontSize = 12.5.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onBackground,
        )
      }
    }

    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier
          .widthIn(max = 720.dp)
          .fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (!state.errorBanner.isNullOrBlank()) {
          item(key = "err") {
            StarsErrorBanner(message = state.errorBanner, onDismiss = onClearError)
          }
        }

        // Hero Balance & Received Card
        item(key = "hero") {
          Surface(
            shape = RoundedCornerShape(20.dp),
            color = cardBg,
            border = BorderStroke(1.dp, border),
            shadowElevation = if (isDark) 0.dp else 1.5.dp,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(18.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Telefam3DAnimatedStar(
                  starAmount = state.wallet.availableBalance.coerceAtLeast(100L),
                  baseSize = 54.dp,
                  showTelefamWording = true,
                  animateSpin = true,
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Available Balance",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text = "${TelefamStarsCatalog.formatComma(state.wallet.availableBalance)} Stars",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Text(
                    text = TelefamStarsCatalog.formatConvertedPrice(state.countryIso, state.wallet.availableBalance),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Button(
                  onClick = onOpenBuyStars,
                  shape = CircleShape,
                  colors = ButtonDefaults.buttonColors(containerColor = StarsButtonRed, contentColor = Color.White),
                  contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                  modifier = Modifier
                    .height(44.dp)
                    .testTag("stars_hub_buy_button"),
                ) {
                  Text("Buy Stars", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                }
              }

              // Metrics row: Stars Received | Stars Sent
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                StarMetricPill(
                  label = "Stars Received",
                  value = TelefamStarsCatalog.formatComma(state.wallet.totalReceived),
                  accent = Color(0xFF15803D),
                  isDark = isDark,
                  modifier = Modifier.weight(1f),
                )
                StarMetricPill(
                  label = "Stars Sent",
                  value = TelefamStarsCatalog.formatComma(state.wallet.totalSent),
                  accent = StarsButtonRed,
                  isDark = isDark,
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }

        // Clean Senders / History tab selector
        item(key = "tabs") {
          Surface(
            shape = CircleShape,
            color = cardBg,
            border = BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              listOf("Senders (${state.senders.size})", "History (${state.transactions.size})").forEachIndexed { idx, label ->
                val active = selectedTab == idx
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(if (active) StarsButtonRed else Color.Transparent)
                    .clickable { selectedTab = idx },
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text = label,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }

        if (selectedTab == 0) {
          if (state.senders.isEmpty()) {
            item(key = "empty_senders") {
              StarsEmptyCard(
                title = "No star senders yet",
                subtitle = "Supporters who send Stars to you will appear here in real time.",
                cardBg = cardBg,
                border = border,
              )
            }
          } else {
            items(state.senders, key = { it.senderUserId }) { sender ->
              StarSenderRowCard(sender = sender, cardBg = cardBg, border = border)
            }
          }
        } else {
          if (state.transactions.isEmpty()) {
            item(key = "empty_tx") {
              StarsEmptyCard(
                title = "No transactions yet",
                subtitle = "Verified purchases and gifts appear here automatically.",
                cardBg = cardBg,
                border = border,
              )
            }
          } else {
            items(state.transactions, key = { it.txId }) { tx ->
              StarTransactionRowCard(tx = tx, cardBg = cardBg, border = border)
            }
          }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
      }
    }
  }
}

@Composable
private fun StarMetricPill(
  label: String,
  value: String,
  accent: Color,
  isDark: Boolean,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = if (isDark) Color(0xFF1F242D) else Color(0xFFF8FAFC),
    modifier = modifier,
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
      Text(
        text = label,
        fontSize = 11.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(3.dp))
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        StaticStarGoldIcon(size = 16.dp)
        Text(
          text = value,
          fontSize = 16.5.sp,
          fontWeight = FontWeight.Bold,
          color = accent,
        )
      }
    }
  }
}

@Composable
private fun StarSenderRowCard(
  sender: StarSenderSummaryDto,
  cardBg: Color,
  border: Color,
) {
  val dateText = remember(sender.lastGiftedAtMs) {
    if (sender.lastGiftedAtMs <= 0L) "" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(sender.lastGiftedAtMs))
  }
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = cardBg,
    border = BorderStroke(1.dp, border),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      if (sender.senderAvatar.isNotBlank()) {
        AsyncImage(
          model = sender.senderAvatar,
          contentDescription = sender.senderName,
          contentScale = ContentScale.Crop,
          modifier = Modifier.size(44.dp).clip(CircleShape),
        )
      } else {
        Box(
          modifier = Modifier.size(44.dp).clip(CircleShape).background(StarsButtonRed),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = sender.senderName.firstOrNull()?.uppercase() ?: "T",
            color = Color.White,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = sender.senderName.ifBlank { sender.senderUserId },
          fontSize = 14.5.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "@${sender.senderUsername.removePrefix("@")} · ${sender.giftsCount} ${if (sender.giftsCount == 1) "gift" else "gifts"} · $dateText",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Surface(shape = CircleShape, color = StarsButtonRed.copy(alpha = 0.12f)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
          StaticStarGoldIcon(size = 14.dp)
          Text(
            text = "+${TelefamStarsCatalog.formatComma(sender.totalStars)}",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            color = StarsButtonRed,
          )
        }
      }
    }
  }
}

@Composable
private fun StarTransactionRowCard(
  tx: StarTransactionDto,
  cardBg: Color,
  border: Color,
) {
  val dateText = remember(tx.createdAtMs) {
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(tx.createdAtMs))
  }
  val title = when (tx.direction) {
    "received" -> "Received from ${tx.senderName.ifBlank { tx.senderUserId }}"
    "sent" -> "Sent to ${tx.recipientName.ifBlank { tx.recipientUserId }}"
    else -> "Purchased via ${tx.provider.replaceFirstChar { it.uppercase() }}"
  }
  val isPlus = tx.direction == "received" || tx.direction == "purchased"

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = cardBg,
    border = BorderStroke(1.dp, border),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(StarsSoftRose),
        contentAlignment = Alignment.Center,
      ) {
        StaticStarGoldIcon(size = 24.dp)
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "$dateText · ${tx.status.replaceFirstChar { it.uppercase() }}",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = "${if (isPlus) "+" else "-"}${TelefamStarsCatalog.formatComma(tx.starAmount)}",
        fontSize = 14.5.sp,
        fontWeight = FontWeight.ExtraBold,
        color = if (isPlus) Color(0xFF15803D) else StarsButtonRed,
      )
    }
  }
}

@Composable
private fun StarsEmptyCard(
  title: String,
  subtitle: String,
  cardBg: Color,
  border: Color,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = cardBg,
    border = BorderStroke(1.dp, border),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.padding(24.dp),
    ) {
      Telefam3DAnimatedStar(starAmount = 100L, baseSize = 56.dp, showTelefamWording = false, animateSpin = true)
      Spacer(modifier = Modifier.height(8.dp))
      Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
      Spacer(modifier = Modifier.height(4.dp))
      Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun StarsErrorBanner(message: String, onDismiss: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = StarsButtonRed.copy(alpha = 0.12f),
    border = BorderStroke(1.dp, StarsButtonRed.copy(alpha = 0.35f)),
    modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
  ) {
    Text(
      text = message,
      color = StarsButtonRed,
      fontSize = 12.5.sp,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
  }
}

// ---------- CUSTOM VECTOR SVG MARKS (ZERO EMOJIS) ----------

@Composable
private fun StarsBackArrowSvg(color: Color, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawLine(color, Offset(w * 0.80f, h * 0.50f), Offset(w * 0.22f, h * 0.50f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    val head = Path().apply {
      moveTo(w * 0.46f, h * 0.26f)
      lineTo(w * 0.22f, h * 0.50f)
      lineTo(w * 0.46f, h * 0.74f)
    }
    drawPath(head, color = color, style = stroke)
  }
}

@Composable
private fun StarsChevronRightSvg(color: Color, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
      moveTo(w * 0.32f, h * 0.18f)
      lineTo(w * 0.68f, h * 0.50f)
      lineTo(w * 0.32f, h * 0.82f)
    }
    drawPath(path, color = color, style = Stroke(width = w * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

@Composable
private fun StarShieldSvgIcon(color: Color, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val shield = Path().apply {
      moveTo(w * 0.50f, h * 0.08f)
      lineTo(w * 0.86f, h * 0.24f)
      lineTo(w * 0.86f, h * 0.52f)
      cubicTo(w * 0.86f, h * 0.74f, w * 0.68f, h * 0.88f, w * 0.50f, h * 0.94f)
      cubicTo(w * 0.32f, h * 0.88f, w * 0.14f, h * 0.74f, w * 0.14f, h * 0.52f)
      lineTo(w * 0.14f, h * 0.24f)
      close()
    }
    drawPath(shield, color = color, style = Fill)
    val check = Path().apply {
      moveTo(w * 0.34f, h * 0.52f)
      lineTo(w * 0.46f, h * 0.64f)
      lineTo(w * 0.68f, h * 0.39f)
    }
    drawPath(check, color = Color.White, style = Stroke(width = w * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

@Composable
private fun PaystackSvgMark(modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val cyan = Color(0xFF00C3F7)
    val barH = h * 0.14f
    val r = CornerRadius(barH * 0.5f, barH * 0.5f)
    drawRoundRect(cyan, topLeft = Offset(w * 0.12f, h * 0.18f), size = Size(w * 0.76f, barH), cornerRadius = r)
    drawRoundRect(cyan, topLeft = Offset(w * 0.12f, h * 0.37f), size = Size(w * 0.76f, barH), cornerRadius = r)
    drawRoundRect(cyan, topLeft = Offset(w * 0.12f, h * 0.56f), size = Size(w * 0.52f, barH), cornerRadius = r)
    drawRoundRect(cyan, topLeft = Offset(w * 0.12f, h * 0.75f), size = Size(w * 0.32f, barH), cornerRadius = r)
  }
}

@Composable
private fun PayPalSvgMark(modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val navy = Color(0xFF003087)
    val lightBlue = Color(0xFF0079C1)
    val backP = Path().apply {
      moveTo(w * 0.26f, h * 0.14f)
      lineTo(w * 0.60f, h * 0.14f)
      cubicTo(w * 0.80f, h * 0.14f, w * 0.84f, h * 0.48f, w * 0.58f, h * 0.52f)
      lineTo(w * 0.40f, h * 0.52f)
      lineTo(w * 0.34f, h * 0.82f)
      lineTo(w * 0.18f, h * 0.82f)
      close()
    }
    drawPath(backP, color = navy, style = Fill)
    val frontP = Path().apply {
      moveTo(w * 0.38f, h * 0.28f)
      lineTo(w * 0.68f, h * 0.28f)
      cubicTo(w * 0.86f, h * 0.28f, w * 0.88f, h * 0.60f, w * 0.64f, h * 0.64f)
      lineTo(w * 0.48f, h * 0.64f)
      lineTo(w * 0.42f, h * 0.90f)
      lineTo(w * 0.28f, h * 0.90f)
      close()
    }
    drawPath(frontP, color = lightBlue.copy(alpha = 0.92f), style = Fill)
  }
}
