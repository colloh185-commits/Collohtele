package telefam.company.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class DesktopProfileMenuIconKind {
  DASHBOARD,
  STARS,
  SUBSCRIPTION,
  MONETIZATION,
  WALLET,
  LINKED_DEVICE,
  CREATE_CAMPAIGN,
  VERIFIED,
}

private data class DesktopProfileMenuItem(
  val id: String,
  val title: String,
  val subtitle: String,
  val icon: DesktopProfileMenuIconKind,
  val hasVerifyPillCta: Boolean = false,
)

private val DesktopProfileMenuItems = listOf(
  DesktopProfileMenuItem(
    id = "dashboard",
    title = "Dashboard",
    subtitle = "View your performance, analytics and insights",
    icon = DesktopProfileMenuIconKind.DASHBOARD,
  ),
  DesktopProfileMenuItem(
    id = "stars",
    title = "Stars",
    subtitle = "Get support from your fans",
    icon = DesktopProfileMenuIconKind.STARS,
  ),
  DesktopProfileMenuItem(
    id = "subscription",
    title = "Subscription",
    subtitle = "Manage your subscriptions and exclusive content",
    icon = DesktopProfileMenuIconKind.SUBSCRIPTION,
  ),
  DesktopProfileMenuItem(
    id = "monetization",
    title = "Monetization",
    subtitle = "Earn from your content and creator programs",
    icon = DesktopProfileMenuIconKind.MONETIZATION,
  ),
  DesktopProfileMenuItem(
    id = "wallet",
    title = "Wallet",
    subtitle = "View your balance and transaction history",
    icon = DesktopProfileMenuIconKind.WALLET,
  ),
  DesktopProfileMenuItem(
    id = "linked_device",
    title = "Linked device",
    subtitle = "Manage your linked devices",
    icon = DesktopProfileMenuIconKind.LINKED_DEVICE,
  ),
  DesktopProfileMenuItem(
    id = "create_campaign",
    title = "Create campaign",
    subtitle = "Collaborate with brands and grow your reach",
    icon = DesktopProfileMenuIconKind.CREATE_CAMPAIGN,
  ),
  DesktopProfileMenuItem(
    id = "get_verified",
    title = "Get Verified",
    subtitle = "Apply for a verified badge and build more trust with your audience.",
    icon = DesktopProfileMenuIconKind.VERIFIED,
    hasVerifyPillCta = true,
  ),
)

@Composable
fun DesktopProfileMenuOverlay(
  isDark: Boolean,
  onBack: () -> Unit,
  onOpenStars: () -> Unit = {},
  onToast: (String) -> Unit,
) {
  val bg = if (isDark) Color(0xFF0E1014) else Color(0xFFF3F5F9)
  val cardBg = if (isDark) Color(0xFF171A21) else Color.White
  val border = if (isDark) Color(0xFF2B303C) else Color(0xFFE7EBF1)
  val chevronTint = if (isDark) Color(0xFF9CA3AF) else Color(0xFF7B8190)
  val badgeCrimson = Color(0xFFE11319)

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(bg)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = {},
      ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
      ) {
        DesktopSvgIcon(DesktopIconKind.BACK_ARROW, MaterialTheme.colorScheme.onBackground, 20.dp)
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "Menu",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
      )
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(DesktopProfileMenuItems, key = { it.id }) { entry ->
          val handleClick = {
            if (entry.id == "stars") {
              onOpenStars()
            } else {
              onToast("${entry.title} — Coming soon")
            }
          }
          Surface(
            onClick = handleClick,
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            border = BorderStroke(1.dp, border),
            shadowElevation = if (isDark) 0.dp else 1.5.dp,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier = Modifier
                  .size(46.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(badgeCrimson),
                contentAlignment = Alignment.Center,
              ) {
                DesktopMenuSvgIcon(
                  kind = entry.icon,
                  accentRed = badgeCrimson,
                  modifier = Modifier.size(26.dp),
                )
              }

              Spacer(modifier = Modifier.width(14.dp))

              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
              ) {
                Text(
                  text = entry.title,
                  fontSize = 15.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = entry.subtitle,
                  fontSize = 13.sp,
                  lineHeight = 18.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              Spacer(modifier = Modifier.width(10.dp))

              if (entry.hasVerifyPillCta) {
                Surface(
                  onClick = handleClick,
                  shape = CircleShape,
                  color = badgeCrimson,
                  modifier = Modifier.height(36.dp),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                  ) {
                    DesktopMenuSvgIcon(
                      kind = DesktopProfileMenuIconKind.VERIFIED,
                      accentRed = badgeCrimson,
                      modifier = Modifier.size(16.dp),
                    )
                    Text(
                      text = "Get Verified",
                      fontSize = 12.5.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color.White,
                      maxLines = 1,
                    )
                    DesktopMenuChevronRightVector(
                      color = Color.White,
                      modifier = Modifier.size(11.dp),
                    )
                  }
                }
              } else {
                DesktopMenuChevronRightVector(
                  color = chevronTint,
                  modifier = Modifier.size(14.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DesktopMenuChevronRightVector(color: Color, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path().apply {
      moveTo(w * 0.30f, h * 0.18f)
      lineTo(w * 0.68f, h * 0.50f)
      lineTo(w * 0.30f, h * 0.82f)
    }
    drawPath(path, color = color, style = stroke)
  }
}

@Composable
private fun DesktopMenuSvgIcon(
  kind: DesktopProfileMenuIconKind,
  accentRed: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    when (kind) {
      DesktopProfileMenuIconKind.DASHBOARD -> {
        val barW = w * 0.20f
        drawRoundRect(Color.White, Offset(w * 0.13f, h * 0.54f), Size(barW, h * 0.32f), CornerRadius(barW * 0.4f, barW * 0.4f))
        drawRoundRect(Color.White, Offset(w * 0.40f, h * 0.34f), Size(barW, h * 0.52f), CornerRadius(barW * 0.4f, barW * 0.4f))
        drawRoundRect(Color.White, Offset(w * 0.67f, h * 0.14f), Size(barW, h * 0.72f), CornerRadius(barW * 0.4f, barW * 0.4f))
      }

      DesktopProfileMenuIconKind.STARS -> {
        val star = Path()
        val cx = w * 0.5f
        val cy = h * 0.52f
        val outerR = w * 0.42f
        val innerR = w * 0.18f
        for (i in 0 until 10) {
          val rad = (-PI / 2.0) + i * (PI / 5.0)
          val r = if (i % 2 == 0) outerR else innerR
          val x = (cx + cos(rad) * r).toFloat()
          val y = (cy + sin(rad) * r).toFloat()
          if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, color = Color.White, style = Fill)
      }

      DesktopProfileMenuIconKind.SUBSCRIPTION -> {
        val crown = Path().apply {
          moveTo(w * 0.14f, h * 0.72f)
          lineTo(w * 0.12f, h * 0.32f)
          lineTo(w * 0.34f, h * 0.50f)
          lineTo(w * 0.50f, h * 0.22f)
          lineTo(w * 0.66f, h * 0.50f)
          lineTo(w * 0.88f, h * 0.32f)
          lineTo(w * 0.86f, h * 0.72f)
          close()
        }
        drawPath(crown, color = Color.White, style = Fill)
        drawRoundRect(Color.White, Offset(w * 0.16f, h * 0.76f), Size(w * 0.68f, h * 0.10f), CornerRadius(w * 0.05f, w * 0.05f))
      }

      DesktopProfileMenuIconKind.MONETIZATION -> {
        val bubble = Path().apply {
          addOval(Rect(w * 0.14f, h * 0.14f, w * 0.86f, h * 0.84f))
        }
        drawPath(bubble, color = Color.White, style = Fill)
        val tail = Path().apply {
          moveTo(w * 0.24f, h * 0.68f)
          lineTo(w * 0.14f, h * 0.86f)
          lineTo(w * 0.38f, h * 0.80f)
          close()
        }
        drawPath(tail, color = Color.White, style = Fill)
        val sStroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val dollar = Path().apply {
          moveTo(w * 0.50f, h * 0.27f)
          lineTo(w * 0.50f, h * 0.71f)
          moveTo(w * 0.60f, h * 0.37f)
          cubicTo(w * 0.60f, h * 0.31f, w * 0.40f, h * 0.31f, w * 0.40f, h * 0.41f)
          cubicTo(w * 0.40f, h * 0.50f, w * 0.60f, h * 0.49f, w * 0.60f, h * 0.58f)
          cubicTo(w * 0.60f, h * 0.66f, w * 0.40f, h * 0.67f, w * 0.40f, h * 0.59f)
        }
        drawPath(dollar, color = accentRed, style = sStroke)
      }

      DesktopProfileMenuIconKind.WALLET -> {
        drawRoundRect(Color.White, Offset(w * 0.12f, h * 0.24f), Size(w * 0.74f, h * 0.56f), CornerRadius(w * 0.14f, w * 0.14f))
        drawRoundRect(accentRed, Offset(w * 0.22f, h * 0.30f), Size(w * 0.48f, h * 0.07f), CornerRadius(w * 0.03f, w * 0.03f))
        drawRoundRect(Color.White, Offset(w * 0.58f, h * 0.44f), Size(w * 0.30f, h * 0.20f), CornerRadius(w * 0.08f, w * 0.08f))
        drawCircle(accentRed, radius = w * 0.048f, center = Offset(w * 0.68f, h * 0.54f))
      }

      DesktopProfileMenuIconKind.LINKED_DEVICE -> {
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val backRect = Path().apply {
          moveTo(w * 0.52f, h * 0.68f)
          lineTo(w * 0.16f, h * 0.68f)
          lineTo(w * 0.16f, h * 0.22f)
          lineTo(w * 0.64f, h * 0.22f)
          lineTo(w * 0.64f, h * 0.36f)
        }
        drawPath(backRect, color = Color.White, style = stroke)
        drawRoundRect(Color.White, Offset(w * 0.50f, h * 0.36f), Size(w * 0.34f, h * 0.46f), CornerRadius(w * 0.07f, w * 0.07f), style = stroke)
      }

      DesktopProfileMenuIconKind.CREATE_CAMPAIGN -> {
        val horn = Path().apply {
          moveTo(w * 0.20f, h * 0.46f)
          lineTo(w * 0.72f, h * 0.20f)
          lineTo(w * 0.72f, h * 0.74f)
          lineTo(w * 0.20f, h * 0.58f)
          close()
        }
        drawPath(horn, color = Color.White, style = Fill)
        val handle = Path().apply {
          moveTo(w * 0.30f, h * 0.58f)
          lineTo(w * 0.37f, h * 0.82f)
          lineTo(w * 0.48f, h * 0.80f)
          lineTo(w * 0.42f, h * 0.60f)
          close()
        }
        drawPath(handle, color = Color.White, style = Fill)
        drawRoundRect(Color.White, Offset(w * 0.77f, h * 0.38f), Size(w * 0.09f, h * 0.20f), CornerRadius(w * 0.045f, w * 0.045f))
      }

      DesktopProfileMenuIconKind.VERIFIED -> {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val seal = Path()
        val points = 16
        for (i in 0 until points) {
          val rad = i * (2.0 * PI / points)
          val r = if (i % 2 == 0) w * 0.44f else w * 0.35f
          val x = (cx + cos(rad) * r).toFloat()
          val y = (cy + sin(rad) * r).toFloat()
          if (i == 0) seal.moveTo(x, y) else seal.lineTo(x, y)
        }
        seal.close()
        drawPath(seal, color = Color.White, style = Fill)
        val check = Path().apply {
          moveTo(w * 0.34f, h * 0.51f)
          lineTo(w * 0.46f, h * 0.63f)
          lineTo(w * 0.68f, h * 0.39f)
        }
        drawPath(check, color = accentRed, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
      }
    }
  }
}
