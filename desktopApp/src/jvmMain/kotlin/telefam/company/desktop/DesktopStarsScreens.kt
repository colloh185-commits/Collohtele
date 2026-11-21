package telefam.company.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

private val DesktopStarRed = Color(0xFFD61921)
private val DesktopStarRedSoft = Color(0xFFFDF2F3)
private val DesktopStarGoldBright = Color(0xFFFFD84A)
private val DesktopStarGoldMid = Color(0xFFF9B115)
private val DesktopStarGoldDeep = Color(0xFFCC7800)
private val DesktopStarOnlineGreen = Color(0xFF1DB954)

private fun desktopStarIntensity(starAmount: Long): Float {
  val clamped = starAmount.coerceIn(5L, 50_000L).toDouble()
  val norm = ((log10(clamped) - log10(5.0)) / (log10(50_000.0) - log10(5.0))).toFloat()
  return norm.coerceIn(0f, 1f)
}

@Composable
fun Desktop3DAnimatedStar(
  size: Dp,
  starAmount: Long = 5L,
  animate3D: Boolean = true,
  showSparkles: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val intensity = remember(starAmount) { desktopStarIntensity(starAmount) }
  val inf = rememberInfiniteTransition()
  val spinPeriodMs = (2400 - (intensity * 1000f).toInt()).coerceIn(1200, 2400)

  val phase by inf.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(spinPeriodMs, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
  )
  val pulse by inf.animateFloat(
    initialValue = 0.94f,
    targetValue = 1.04f + intensity * 0.08f,
    animationSpec = infiniteRepeatable(
      animation = tween(900, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
  )

  val yaw = if (animate3D) sin(phase * PI / 180.0).toFloat() * (18f + intensity * 14f) else 0f
  val sparkleCount = (5 + (intensity * 7f).toInt()).coerceIn(5, 12)

  Box(
    modifier = modifier.size(size),
    contentAlignment = Alignment.Center,
  ) {
    if (showSparkles) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val baseRadius = this.size.minDimension * 0.44f
        val glowAlpha = 0.14f + intensity * 0.18f
        drawCircle(
          brush = Brush.radialGradient(
            listOf(DesktopStarGoldBright.copy(alpha = glowAlpha), Color.Transparent),
            center = Offset(cx, cy),
            radius = this.size.minDimension * 0.50f,
          ),
          radius = this.size.minDimension * 0.50f,
          center = Offset(cx, cy),
        )
        for (i in 0 until sparkleCount) {
          val a = (phase * (1.2 + intensity * 0.6) + i * (360f / sparkleCount)) * (PI / 180.0)
          val orbit = baseRadius * (0.86f + 0.14f * sin(a * 2.0).toFloat())
          val sx = cx + (cos(a) * orbit).toFloat()
          val sy = cy + (sin(a) * orbit).toFloat()
          val spSize = (this.size.minDimension * (0.045f + intensity * 0.03f)) *
            (0.65f + 0.35f * abs(sin(a * 1.5)).toFloat())
          val color = if (i % 3 == 0) Color.White else if (i % 2 == 0) DesktopStarGoldBright else DesktopStarRed.copy(alpha = 0.72f)
          val sp = Path().apply {
            moveTo(sx, sy - spSize)
            lineTo(sx + spSize * 0.28f, sy - spSize * 0.28f)
            lineTo(sx + spSize, sy)
            lineTo(sx + spSize * 0.28f, sy + spSize * 0.28f)
            lineTo(sx, sy + spSize)
            lineTo(sx - spSize * 0.28f, sy + spSize * 0.28f)
            lineTo(sx - spSize, sy)
            lineTo(sx - spSize * 0.28f, sy - spSize * 0.28f)
            close()
          }
          drawPath(sp, color = color, style = Fill)
        }
      }
    }

    Canvas(
      modifier = Modifier
        .size(size * 0.72f)
        .graphicsLayer {
          if (animate3D) {
            rotationY = yaw
            scaleX = pulse
            scaleY = pulse
            cameraDistance = 12f * density
          }
        },
    ) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val outerR = this.size.minDimension * 0.46f
      val innerR = outerR * 0.45f

      val outerPts = ArrayList<Offset>(10)
      for (i in 0 until 10) {
        val rad = (-PI / 2.0) + i * (PI / 5.0)
        val r = if (i % 2 == 0) outerR else innerR
        outerPts.add(Offset((cx + cos(rad) * r).toFloat(), (cy + sin(rad) * r).toFloat()))
      }

      val bodyPath = Path().apply {
        outerPts.forEachIndexed { idx, p ->
          if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
      }

      drawPath(
        bodyPath,
        brush = Brush.linearGradient(
          colors = listOf(DesktopStarGoldBright, DesktopStarGoldMid, DesktopStarGoldDeep),
          start = Offset(0f, 0f),
          end = Offset(this.size.width, this.size.height),
        ),
        style = Fill,
      )

      val centerPt = Offset(cx, cy)
      for (i in 0 until 10) {
        val p1 = outerPts[i]
        val p2 = outerPts[(i + 1) % 10]
        val facet = Path().apply {
          moveTo(centerPt.x, centerPt.y)
          lineTo(p1.x, p1.y)
          lineTo(p2.x, p2.y)
          close()
        }
        val facetColor = when {
          i < 3 || i == 9 -> Color.White.copy(alpha = 0.24f)
          i in 4..7 -> DesktopStarGoldDeep.copy(alpha = 0.28f)
          else -> DesktopStarGoldMid.copy(alpha = 0.14f)
        }
        drawPath(facet, color = facetColor, style = Fill)
      }

      drawPath(
        bodyPath,
        color = Color(0xFFB36200).copy(alpha = 0.42f),
        style = Stroke(width = (this.size.minDimension * 0.03f).coerceAtLeast(1.2f), join = StrokeJoin.Round),
      )
    }
  }
}

@Composable
private fun DesktopFlatStarBadge(size: Dp) {
  Box(
    modifier = Modifier
      .size(size)
      .clip(CircleShape)
      .background(Color(0xFFFFF5D6)),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(size * 0.62f)) {
      val cx = this.size.width / 2f
      val cy = this.size.height / 2f
      val outerR = this.size.minDimension * 0.48f
      val innerR = outerR * 0.45f
      val star = Path()
      for (i in 0 until 10) {
        val rad = (-PI / 2.0) + i * (PI / 5.0)
        val r = if (i % 2 == 0) outerR else innerR
        val x = (cx + cos(rad) * r).toFloat()
        val y = (cy + sin(rad) * r).toFloat()
        if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
      }
      star.close()
      drawPath(
        star,
        brush = Brush.linearGradient(listOf(DesktopStarGoldBright, DesktopStarGoldMid)),
        style = Fill,
      )
    }
  }
}

@Composable
private fun DesktopPaystackVectorIcon(size: Dp = 36.dp) {
  Canvas(modifier = Modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val cyan = Color(0xFF00C3F7)
    val barH = h * 0.13f
    val r = CornerRadius(barH * 0.45f, barH * 0.45f)
    drawRoundRect(cyan, Offset(w * 0.12f, h * 0.15f), Size(w * 0.76f, barH), r)
    drawRoundRect(cyan, Offset(w * 0.12f, h * 0.35f), Size(w * 0.76f, barH), r)
    drawRoundRect(cyan, Offset(w * 0.12f, h * 0.55f), Size(w * 0.56f, barH), r)
    drawRoundRect(cyan, Offset(w * 0.12f, h * 0.75f), Size(w * 0.34f, barH), r)
  }
}

@Composable
private fun DesktopPayPalVectorIcon(size: Dp = 36.dp) {
  Canvas(modifier = Modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val deepBlue = Color(0xFF003087)
    val skyBlue = Color(0xFF0079C1)
    val back = Path().apply {
      moveTo(w * 0.24f, h * 0.12f)
      lineTo(w * 0.58f, h * 0.12f)
      cubicTo(w * 0.78f, h * 0.12f, w * 0.80f, h * 0.42f, w * 0.58f, h * 0.48f)
      lineTo(w * 0.38f, h * 0.48f)
      lineTo(w * 0.32f, h * 0.78f)
      lineTo(w * 0.16f, h * 0.78f)
      close()
    }
    drawPath(back, color = deepBlue, style = Fill)

    val front = Path().apply {
      moveTo(w * 0.36f, h * 0.26f)
      lineTo(w * 0.68f, h * 0.26f)
      cubicTo(w * 0.88f, h * 0.26f, w * 0.88f, h * 0.56f, w * 0.66f, h * 0.60f)
      lineTo(w * 0.46f, h * 0.60f)
      lineTo(w * 0.40f, h * 0.88f)
      lineTo(w * 0.25f, h * 0.88f)
      close()
    }
    drawPath(front, color = skyBlue, style = Fill)
  }
}

@Composable
private fun DesktopSecurityShieldVector(size: Dp = 28.dp) {
  Canvas(modifier = Modifier.size(size)) {
    val w = this.size.width
    val h = this.size.height
    val shield = Path().apply {
      moveTo(w * 0.5f, h * 0.08f)
      lineTo(w * 0.86f, h * 0.22f)
      lineTo(w * 0.84f, h * 0.54f)
      cubicTo(w * 0.82f, h * 0.74f, w * 0.66f, h * 0.86f, w * 0.5f, h * 0.94f)
      cubicTo(w * 0.34f, h * 0.86f, w * 0.18f, h * 0.74f, w * 0.16f, h * 0.54f)
      lineTo(w * 0.14f, h * 0.22f)
      close()
    }
    drawPath(shield, color = Color(0xFFB71C23), style = Fill)
    val check = Path().apply {
      moveTo(w * 0.34f, h * 0.51f)
      lineTo(w * 0.46f, h * 0.63f)
      lineTo(w * 0.68f, h * 0.38f)
    }
    drawPath(check, color = Color.White, style = Stroke(width = w * 0.10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

@Composable
fun DesktopStarGiftBubble(
  starAmount: Long,
  isOwn: Boolean,
  isVerified: Boolean,
  isDark: Boolean,
  deliveryStatus: String,
  peerAvatarUrl: String,
  formattedTime: String,
  apiClient: DesktopApiClient,
  modifier: Modifier = Modifier,
) {
  val intensity = desktopStarIntensity(starAmount)
  val starVisualSize = (64f + intensity * 46f).dp
  val bubbleBg = if (isDark) {
    Brush.verticalGradient(listOf(Color(0xFF28191B), Color(0xFF1A1416)))
  } else {
    Brush.verticalGradient(listOf(Color(0xFFFFF8F6), Color(0xFFFFF2EA)))
  }
  val borderColor = if (isDark) DesktopStarGoldMid.copy(alpha = 0.42f) else DesktopStarRed.copy(alpha = 0.22f)
  val metaColor = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)

  Surface(
    shape = RoundedCornerShape(18.dp),
    color = Color.Transparent,
    border = BorderStroke(1.2.dp, borderColor),
    modifier = modifier.widthIn(min = 190.dp, max = 280.dp),
  ) {
    Column(
      modifier = Modifier
        .background(bubbleBg)
        .padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Desktop3DAnimatedStar(
        size = starVisualSize,
        starAmount = starAmount,
        animate3D = true,
        showSparkles = true,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Telefam",
        color = DesktopStarRed,
        fontSize = (15f + intensity * 5f).sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.4.sp,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "${DesktopStarsCatalog.formatComma(starAmount)} Stars",
        color = if (isDark) Color.White else Color(0xFF1A1C22),
        fontSize = (16f + intensity * 4f).sp,
        fontWeight = FontWeight.Bold,
      )
      if (isVerified) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "Verified Gift",
          color = if (isDark) DesktopStarGoldBright else DesktopStarGoldDeep,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(formattedTime, color = metaColor, fontSize = 10.5.sp)
        if (isOwn) {
          Spacer(modifier = Modifier.width(4.dp))
          DesktopDeliveryStatusIcon(deliveryStatus, peerAvatarUrl, apiClient)
        }
      }
    }
  }
}

@Composable
fun DesktopStarGiftCelebrationOverlay(
  celebration: DesktopStarCelebrationState?,
  onDismiss: () -> Unit,
) {
  LaunchedEffect(celebration?.eventKey) {
    if (celebration != null) {
      delay(3400L)
      onDismiss()
    }
  }

  AnimatedVisibility(
    visible = celebration != null,
    enter = fadeIn(tween(220)) + scaleIn(tween(280)),
    exit = fadeOut(tween(260)) + scaleOut(tween(240)),
  ) {
    celebration?.let { c ->
      val intensity = desktopStarIntensity(c.starAmount)
      val scaleAnim = remember(c.eventKey) { Animatable(0.68f) }
      LaunchedEffect(c.eventKey) {
        scaleAnim.animateTo(1.06f, tween(360, easing = FastOutSlowInEasing))
        scaleAnim.animateTo(1f, tween(220))
      }

      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.36f))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
          ),
        contentAlignment = Alignment.Center,
      ) {
        Surface(
          shape = RoundedCornerShape(26.dp),
          color = Color.White,
          shadowElevation = 18.dp,
          border = BorderStroke(1.5.dp, DesktopStarGoldMid.copy(alpha = 0.55f)),
          modifier = Modifier
            .widthIn(max = 360.dp)
            .padding(horizontal = 24.dp)
            .graphicsLayer {
              scaleX = scaleAnim.value
              scaleY = scaleAnim.value
            },
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Desktop3DAnimatedStar(
              size = (120f + intensity * 68f).dp,
              starAmount = c.starAmount,
              animate3D = true,
              showSparkles = true,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "Telefam",
              color = DesktopStarRed,
              fontSize = (22f + intensity * 8f).sp,
              fontWeight = FontWeight.ExtraBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "${DesktopStarsCatalog.formatComma(c.starAmount)} Stars",
              color = Color(0xFF14161B),
              fontSize = (20f + intensity * 6f).sp,
              fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = if (c.isIncoming) "From ${c.senderName}" else "Sent to ${c.senderName}",
              color = Color(0xFF6B7280),
              fontSize = 13.5.sp,
            )
          }
        }
      }
    }
  }
}

@Composable
fun DesktopStarsModalHost(
  state: DesktopStarsUiState,
  apiClient: DesktopApiClient,
  isDark: Boolean,
  onNavigateBack: () -> Unit,
  onOpenBuy: (Long) -> Unit,
  onOpenCheckout: (Long) -> Unit,
  onSelectProvider: (String) -> Unit,
  onProceedToPay: () -> Unit,
  onVerifyPending: () -> Unit,
  onSendStars: (Long) -> Unit,
  onOpenSenderProfile: (String) -> Unit,
  onRefreshSummary: () -> Unit,
  onDismissError: () -> Unit,
) {
  val route = state.currentRoute ?: return
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(if (isDark) Color(0xFF0E1014) else Color(0xFFF6F7FA))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = {},
      ),
  ) {
    when (route) {
      DesktopStarRoute.SEND_STARS -> DesktopSendStarsScreen(
        state = state,
        apiClient = apiClient,
        isDark = isDark,
        onBack = onNavigateBack,
        onOpenRecipientProfile = {
          state.recipientTarget?.userId?.takeIf { it.isNotBlank() }?.let(onOpenSenderProfile)
        },
        onSendStars = onSendStars,
        onOpenBuy = { onOpenBuy(5L) },
        onDismissError = onDismissError,
      )
      DesktopStarRoute.BUY_STARS -> DesktopBuyStarsScreen(
        state = state,
        isDark = isDark,
        onBack = onNavigateBack,
        onSelectPackage = onOpenCheckout,
        onDismissError = onDismissError,
      )
      DesktopStarRoute.CHECKOUT -> DesktopStarCheckoutScreen(
        state = state,
        isDark = isDark,
        onBack = onNavigateBack,
        onSelectProvider = onSelectProvider,
        onProceedToPay = onProceedToPay,
        onVerifyPayment = onVerifyPending,
        onDismissError = onDismissError,
      )
      DesktopStarRoute.STARS_HUB -> DesktopStarsHubScreen(
        state = state,
        apiClient = apiClient,
        isDark = isDark,
        onBack = onNavigateBack,
        onBuyStars = { onOpenBuy(100L) },
        onOpenSenderProfile = onOpenSenderProfile,
        onRefresh = onRefreshSummary,
        onVerifyPending = onVerifyPending,
      )
    }
  }
}

@Composable
private fun DesktopStarsCrimsonTopBar(
  title: String,
  onBack: () -> Unit,
  trailingContent: (@Composable () -> Unit)? = null,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(DesktopStarRed)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .clickable(onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      DesktopSvgIcon(DesktopIconKind.BACK_ARROW, Color.White, 20.dp)
    }
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = title,
      color = Color.White,
      fontSize = 19.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f),
    )
    trailingContent?.invoke()
  }
}

@Composable
private fun DesktopSendStarsScreen(
  state: DesktopStarsUiState,
  apiClient: DesktopApiClient,
  isDark: Boolean,
  onBack: () -> Unit,
  onOpenRecipientProfile: () -> Unit,
  onSendStars: (Long) -> Unit,
  onOpenBuy: () -> Unit,
  onDismissError: () -> Unit,
) {
  val bg = if (isDark) Color(0xFF0E1014) else Color.White
  val cardBg = if (isDark) Color(0xFF171A21) else Color.White
  val borderCol = if (isDark) Color(0xFF2B303C) else Color(0xFFE6E8EF)
  val bannerBg = if (isDark) Color(0xFF241618) else DesktopStarRedSoft
  val target = state.recipientTarget

  Column(modifier = Modifier.fillMaxSize().background(bg)) {
    DesktopStarsCrimsonTopBar(
      title = "Send Stars",
      onBack = onBack,
      trailingContent = {
        Surface(
          onClick = onOpenBuy,
          shape = CircleShape,
          color = Color(0xFFA91218),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            DesktopFlatStarBadge(size = 18.dp)
            Text(
              text = "Balance: ${DesktopStarsCatalog.formatComma(state.wallet.availableBalance)}",
              color = Color.White,
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      },
    )

    if (state.errorBanner != null) {
      Surface(
        color = if (isDark) Color(0xFF341618) else DesktopStarRedSoft,
        modifier = Modifier.fillMaxWidth().clickable { onDismissError() },
      ) {
        Text(
          text = state.errorBanner,
          color = DesktopStarRed,
          fontSize = 12.5.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
      }
    }

    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.widthIn(max = 620.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (target != null) {
          item(key = "recipient") {
            Surface(
              onClick = onOpenRecipientProfile,
              shape = RoundedCornerShape(14.dp),
              color = bannerBg,
              border = BorderStroke(1.dp, if (isDark) Color(0xFF3A2427) else Color(0xFFF5D8DA)),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Box(modifier = Modifier.size(48.dp)) {
                  DesktopChatAvatar(target.avatarUrl, target.fullName, apiClient, size = 48.dp)
                  if (target.isOnline) {
                    Box(
                      modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(DesktopStarOnlineGreen)
                        .border(2.dp, Color.White, CircleShape),
                    )
                  }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = target.fullName.ifBlank { target.username },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = "@${target.username.removePrefix("@")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                  )
                }
                DesktopSvgIcon(DesktopIconKind.CHEVRON_RIGHT, DesktopStarRed, 16.dp)
              }
            }
          }
        }

        item(key = "banner") {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = bannerBg,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Desktop3DAnimatedStar(size = 44.dp, starAmount = 200L, animate3D = false, showSparkles = false)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Select stars to send",
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 15.sp,
                  fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "Show your support and help them grow!",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 12.5.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                  shape = RoundedCornerShape(999.dp),
                  color = if (isDark) Color(0xFF422124) else Color(0xFFFAD7DA),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                  ) {
                    DesktopFlatStarBadge(size = 14.dp)
                    Text(
                      text = "1 star = 1 appreciation",
                      color = if (isDark) Color(0xFFF6A7AD) else Color(0xFF9F1A22),
                      fontSize = 11.5.sp,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                }
              }
            }
          }
        }

        items(DesktopStarsCatalog.TIERS, key = { it }) { tier ->
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = BorderStroke(1.dp, borderCol),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              DesktopFlatStarBadge(size = 38.dp)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = DesktopStarsCatalog.formatComma(tier),
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 15.5.sp,
                  fontWeight = FontWeight.Bold,
                )
                Text(
                  text = DesktopStarsCatalog.formatConvertedPrice(state.countryIso, tier),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 12.5.sp,
                )
              }
              Button(
                onClick = { onSendStars(tier) },
                enabled = !state.isSubmittingSend,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = DesktopStarRed),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                modifier = Modifier.height(38.dp),
              ) {
                Text("Send", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DesktopBuyStarsScreen(
  state: DesktopStarsUiState,
  isDark: Boolean,
  onBack: () -> Unit,
  onSelectPackage: (Long) -> Unit,
  onDismissError: () -> Unit,
) {
  val bg = if (isDark) Color(0xFF0E1014) else Color.White
  val cardBg = if (isDark) Color(0xFF171A21) else Color.White
  val borderCol = if (isDark) Color(0xFF2B303C) else Color(0xFFE6E8EF)
  val bannerBg = if (isDark) Color(0xFF241618) else DesktopStarRedSoft

  Column(modifier = Modifier.fillMaxSize().background(bg)) {
    DesktopStarsCrimsonTopBar(title = "Buy Stars", onBack = onBack)

    if (state.errorBanner != null) {
      Surface(
        color = if (isDark) Color(0xFF341618) else DesktopStarRedSoft,
        modifier = Modifier.fillMaxWidth().clickable { onDismissError() },
      ) {
        Text(
          text = state.errorBanner,
          color = DesktopStarRed,
          fontSize = 12.5.sp,
          modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
      }
    }

    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.widthIn(max = 620.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item(key = "header_banner") {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = bannerBg,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Desktop3DAnimatedStar(size = 46.dp, starAmount = 500L, animate3D = false, showSparkles = false)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Support your favorite creators and help them grow!",
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 14.5.sp,
                  fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                  text = "Buy Stars and send them as a gift in chats.",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 12.5.sp,
                )
              }
            }
          }
        }

        item(key = "section_title") {
          Text(
            text = "Choose a package",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
          )
        }

        items(DesktopStarsCatalog.TIERS, key = { it }) { tier ->
          val isPopular = tier == 5L
          val isHighlighted = isPopular || tier == state.selectedPackageStars
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = BorderStroke(
              width = if (isHighlighted) 1.4.dp else 1.dp,
              color = if (isHighlighted) DesktopStarRed else borderCol,
            ),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              DesktopFlatStarBadge(size = 38.dp)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = DesktopStarsCatalog.formatComma(tier),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                  )
                  if (isPopular) {
                    Surface(shape = CircleShape, color = DesktopStarRed) {
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
                  text = DesktopStarsCatalog.formatConvertedPrice(state.countryIso, tier),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 12.5.sp,
                )
              }
              Button(
                onClick = { onSelectPackage(tier) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = DesktopStarRed),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                modifier = Modifier.height(38.dp),
              ) {
                Text("Buy", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DesktopStarCheckoutScreen(
  state: DesktopStarsUiState,
  isDark: Boolean,
  onBack: () -> Unit,
  onSelectProvider: (String) -> Unit,
  onProceedToPay: () -> Unit,
  onVerifyPayment: () -> Unit,
  onDismissError: () -> Unit,
) {
  val bg = if (isDark) Color(0xFF0E1014) else Color.White
  val cardBg = if (isDark) Color(0xFF171A21) else Color.White
  val borderCol = if (isDark) Color(0xFF2B303C) else Color(0xFFE6E8EF)
  val settlementNote = DesktopStarsCatalog.gatewaySettlementNote(
    countryIso = state.countryIso,
    provider = state.selectedProvider,
    stars = state.selectedPackageStars,
  )

  Column(modifier = Modifier.fillMaxSize().background(bg)) {
    DesktopStarsCrimsonTopBar(title = "Checkout", onBack = onBack)

    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.widthIn(max = 580.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        item(key = "summary_card") {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = BorderStroke(1.dp, borderCol),
            shadowElevation = if (isDark) 0.dp else 1.dp,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              DesktopFlatStarBadge(size = 48.dp)
              Spacer(modifier = Modifier.width(14.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${DesktopStarsCatalog.formatComma(state.selectedPackageStars)} Stars",
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 19.sp,
                  fontWeight = FontWeight.Bold,
                )
                Text(
                  text = DesktopStarsCatalog.formatConvertedPrice(state.countryIso, state.selectedPackageStars),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 14.5.sp,
                  fontWeight = FontWeight.Medium,
                )
                if (settlementNote != null) {
                  Text(
                    text = settlementNote,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                  )
                }
              }
            }
          }
        }

        item(key = "pm_header") {
          Text(
            text = "Payment Method",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.5.sp,
            fontWeight = FontWeight.Bold,
          )
        }

        item(key = "paystack") {
          DesktopPaymentMethodCard(
            title = "Paystack",
            subtitle = "Pay securely with M-Pesa, cards and bank transfers",
            selected = state.selectedProvider == "paystack",
            cardBg = cardBg,
            borderCol = borderCol,
            icon = { DesktopPaystackVectorIcon(36.dp) },
            onClick = { onSelectProvider("paystack") },
          )
        }

        item(key = "paypal") {
          DesktopPaymentMethodCard(
            title = "PayPal",
            subtitle = "Pay with your PayPal account or card",
            selected = state.selectedProvider == "paypal",
            cardBg = cardBg,
            borderCol = borderCol,
            icon = { DesktopPayPalVectorIcon(36.dp) },
            onClick = { onSelectProvider("paypal") },
          )
        }

        item(key = "security_box") {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isDark) Color(0xFF241618) else DesktopStarRedSoft,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              DesktopSecurityShieldVector(28.dp)
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Your payment is secure and encrypted",
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 13.5.sp,
                  fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "We use industry-standard encryption to protect your information.",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 12.sp,
                )
              }
            }
          }
        }

        if (state.statusMessage != null) {
          item(key = "status_msg") {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (isDark) Color(0xFF17271C) else Color(0xFFECFDF5),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = state.statusMessage,
                color = if (isDark) Color(0xFF6EE7B7) else Color(0xFF065F46),
                fontSize = 12.5.sp,
                modifier = Modifier.padding(12.dp),
              )
            }
          }
        }

        if (state.errorBanner != null) {
          item(key = "err_msg") {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (isDark) Color(0xFF341618) else DesktopStarRedSoft,
              modifier = Modifier.fillMaxWidth().clickable { onDismissError() },
            ) {
              Text(
                text = state.errorBanner,
                color = DesktopStarRed,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(12.dp),
              )
            }
          }
        }

        item(key = "actions") {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
              onClick = onProceedToPay,
              enabled = !state.isCreatingCheckout && !state.isVerifyingPayment,
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.buttonColors(containerColor = DesktopStarRed),
              modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
              if (state.isCreatingCheckout) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
              } else {
                Text("Proceed to Pay", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
              }
            }

            val pending = state.pendingCheckoutIntent
            if (pending != null && !pending.credited) {
              OutlinedButton(
                onClick = onVerifyPayment,
                enabled = !state.isVerifyingPayment,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DesktopStarRed),
                modifier = Modifier.fillMaxWidth().height(46.dp),
              ) {
                if (state.isVerifyingPayment) {
                  CircularProgressIndicator(modifier = Modifier.size(18.dp), color = DesktopStarRed, strokeWidth = 2.dp)
                } else {
                  Text("Verify Completed Payment", color = DesktopStarRed, fontWeight = FontWeight.SemiBold)
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DesktopPaymentMethodCard(
  title: String,
  subtitle: String,
  selected: Boolean,
  cardBg: Color,
  borderCol: Color,
  icon: @Composable () -> Unit,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(14.dp),
    color = cardBg,
    border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) DesktopStarRed else borderCol),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon()
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          color = MaterialTheme.colorScheme.onSurface,
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = subtitle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 12.5.sp,
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Box(
        modifier = Modifier
          .size(20.dp)
          .clip(CircleShape)
          .border(2.dp, if (selected) DesktopStarRed else borderCol, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        if (selected) {
          Box(
            modifier = Modifier
              .size(10.dp)
              .clip(CircleShape)
              .background(DesktopStarRed),
          )
        }
      }
    }
  }
}

@Composable
private fun DesktopStarsHubScreen(
  state: DesktopStarsUiState,
  apiClient: DesktopApiClient,
  isDark: Boolean,
  onBack: () -> Unit,
  onBuyStars: () -> Unit,
  onOpenSenderProfile: (String) -> Unit,
  onRefresh: () -> Unit,
  onVerifyPending: () -> Unit,
) {
  var selectedTab by remember { mutableStateOf(0) } // 0 = Senders, 1 = Activity
  val bg = if (isDark) Color(0xFF0E1014) else Color(0xFFF6F7FA)
  val cardBg = if (isDark) Color(0xFF171A21) else Color.White
  val borderCol = if (isDark) Color(0xFF2B303C) else Color(0xFFE7EBF1)

  Column(modifier = Modifier.fillMaxSize().background(bg)) {
    DesktopStarsCrimsonTopBar(
      title = "Stars",
      onBack = onBack,
      trailingContent = {
        Surface(
          onClick = onRefresh,
          shape = CircleShape,
          color = Color(0xFFA91218),
        ) {
          Text(
            text = if (state.isLoadingSummary) "Syncing" else "Refresh",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          )
        }
      },
    )

    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(key = "balance_card") {
          Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.Transparent,
            shadowElevation = if (isDark) 0.dp else 4.dp,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Color(0xFFC5131B), DesktopStarRed)))
                .padding(18.dp),
            ) {
              Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Desktop3DAnimatedStar(size = 56.dp, starAmount = state.wallet.availableBalance.coerceAtLeast(100L))
                  Spacer(modifier = Modifier.width(12.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "Available Balance",
                      color = Color.White.copy(alpha = 0.85f),
                      fontSize = 12.5.sp,
                    )
                    Text(
                      text = "${DesktopStarsCatalog.formatComma(state.wallet.availableBalance)} Stars",
                      color = Color.White,
                      fontSize = 26.sp,
                      fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                      text = DesktopStarsCatalog.formatConvertedPrice(state.countryIso, state.wallet.availableBalance),
                      color = Color.White.copy(alpha = 0.88f),
                      fontSize = 13.sp,
                    )
                  }
                  Button(
                    onClick = onBuyStars,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                  ) {
                    Text("Buy Stars", color = DesktopStarRed, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                  }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier.weight(1f),
                  ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                      Text("Stars Received", color = Color.White.copy(alpha = 0.8f), fontSize = 11.5.sp)
                      Spacer(modifier = Modifier.height(2.dp))
                      Text(
                        text = DesktopStarsCatalog.formatComma(state.wallet.totalReceived),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                      )
                    }
                  }
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier.weight(1f),
                  ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                      Text("Stars Sent", color = Color.White.copy(alpha = 0.8f), fontSize = 11.5.sp)
                      Spacer(modifier = Modifier.height(2.dp))
                      Text(
                        text = DesktopStarsCatalog.formatComma(state.wallet.totalSent),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                      )
                    }
                  }
                }
              }
            }
          }
        }

        val pendingIntent = state.pendingIntents.firstOrNull { !it.credited }
        if (pendingIntent != null) {
          item(key = "pending_verify") {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = cardBg,
              border = BorderStroke(1.dp, DesktopStarRed.copy(alpha = 0.35f)),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                DesktopFlatStarBadge(size = 34.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Pending ${DesktopStarsCatalog.formatComma(pendingIntent.starAmount)} Stars (${pendingIntent.provider.uppercase()})",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    text = "Ref: ${pendingIntent.providerReference}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                  )
                }
                Button(
                  onClick = onVerifyPending,
                  colors = ButtonDefaults.buttonColors(containerColor = DesktopStarRed),
                  shape = CircleShape,
                ) {
                  Text("Verify", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
              }
            }
          }
        }

        item(key = "tabs") {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Star Senders (${state.senders.size})", "History (${state.transactions.size})").forEachIndexed { idx, label ->
              val active = selectedTab == idx
              Surface(
                onClick = { selectedTab = idx },
                shape = CircleShape,
                color = if (active) DesktopStarRed else cardBg,
                border = if (active) null else BorderStroke(1.dp, borderCol),
              ) {
                Text(
                  text = label,
                  color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
              }
            }
          }
        }

        if (selectedTab == 0) {
          if (state.senders.isEmpty()) {
            item(key = "empty_senders") {
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderCol),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = "No star senders yet",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 14.sp,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(28.dp),
                )
              }
            }
          } else {
            items(state.senders, key = { it.senderUserId }) { sender ->
              Surface(
                onClick = { onOpenSenderProfile(sender.senderUserId) },
                shape = RoundedCornerShape(14.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderCol),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(14.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  DesktopChatAvatar(sender.senderAvatar, sender.senderName, apiClient, size = 44.dp)
                  Spacer(modifier = Modifier.width(12.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = sender.senderName.ifBlank { sender.senderUsername },
                      color = MaterialTheme.colorScheme.onSurface,
                      fontSize = 14.5.sp,
                      fontWeight = FontWeight.Bold,
                    )
                    Text(
                      text = "@${sender.senderUsername.removePrefix("@")} · ${sender.giftsCount} ${if (sender.giftsCount == 1) "gift" else "gifts"}",
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      fontSize = 12.sp,
                    )
                  }
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    DesktopFlatStarBadge(size = 24.dp)
                    Text(
                      text = DesktopStarsCatalog.formatComma(sender.totalStars),
                      color = DesktopStarRed,
                      fontSize = 15.sp,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                }
              }
            }
          }
        } else {
          if (state.transactions.isEmpty()) {
            item(key = "empty_txs") {
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderCol),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = "No star transactions yet",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  fontSize = 14.sp,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(28.dp),
                )
              }
            }
          } else {
            items(state.transactions, key = { it.txId }) { tx ->
              val isCredit = tx.direction == "received" || tx.direction == "purchased"
              val title = when (tx.direction) {
                "purchased" -> "Purchased Stars (${tx.provider.replaceFirstChar { it.uppercase() }})"
                "received" -> "Received from ${tx.senderName.ifBlank { tx.senderUsername }}"
                "sent" -> "Sent to ${tx.recipientName.ifBlank { tx.recipientUsername }}"
                else -> "Stars"
              }
              val dateLabel = remember(tx.createdAtMs) {
                if (tx.createdAtMs <= 0L) "" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(tx.createdAtMs))
              }
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderCol),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(14.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  DesktopFlatStarBadge(size = 38.dp)
                  Spacer(modifier = Modifier.width(12.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = title,
                      color = MaterialTheme.colorScheme.onSurface,
                      fontSize = 14.sp,
                      fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                      text = "$dateLabel · ${tx.status.replaceFirstChar { it.uppercase() }}",
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      fontSize = 12.sp,
                    )
                  }
                  Text(
                    text = "${if (isCredit) "+" else "-"}${DesktopStarsCatalog.formatComma(tx.starAmount)}",
                    color = if (isCredit) Color(0xFF15803D) else DesktopStarRed,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
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
