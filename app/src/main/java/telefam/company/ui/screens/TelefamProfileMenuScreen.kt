package telefam.company.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import telefam.company.ui.theme.LocalTelefamDarkTheme
import telefam.company.ui.theme.TelefamCrimsonBright
import telefam.company.ui.theme.TelefamDarkBg
import telefam.company.ui.theme.TelefamDarkBorder
import telefam.company.ui.theme.TelefamDarkSurface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class ProfileMenuIconKind {
  DASHBOARD,
  STARS,
  SUBSCRIPTION,
  MONETIZATION,
  WALLET,
  LINKED_DEVICE,
  CREATE_CAMPAIGN,
  VERIFIED,
}

private data class ProfileMenuEntry(
  val id: String,
  val title: String,
  val subtitle: String,
  val icon: ProfileMenuIconKind,
  val hasVerifyPillCta: Boolean = false,
)

private val ProfileMenuEntries = listOf(
  ProfileMenuEntry(
    id = "dashboard",
    title = "Dashboard",
    subtitle = "View your performance, analytics and insights",
    icon = ProfileMenuIconKind.DASHBOARD,
  ),
  ProfileMenuEntry(
    id = "stars",
    title = "Stars",
    subtitle = "Get support from your fans",
    icon = ProfileMenuIconKind.STARS,
  ),
  ProfileMenuEntry(
    id = "subscription",
    title = "Subscription",
    subtitle = "Manage your subscriptions and exclusive content",
    icon = ProfileMenuIconKind.SUBSCRIPTION,
  ),
  ProfileMenuEntry(
    id = "monetization",
    title = "Monetization",
    subtitle = "Earn from your content and creator programs",
    icon = ProfileMenuIconKind.MONETIZATION,
  ),
  ProfileMenuEntry(
    id = "wallet",
    title = "Wallet",
    subtitle = "View your balance and transaction history",
    icon = ProfileMenuIconKind.WALLET,
  ),
  ProfileMenuEntry(
    id = "linked_device",
    title = "Linked device",
    subtitle = "Manage your linked devices",
    icon = ProfileMenuIconKind.LINKED_DEVICE,
  ),
  ProfileMenuEntry(
    id = "create_campaign",
    title = "Create campaign",
    subtitle = "Collaborate with brands and grow your reach",
    icon = ProfileMenuIconKind.CREATE_CAMPAIGN,
  ),
  ProfileMenuEntry(
    id = "get_verified",
    title = "Get Verified",
    subtitle = "Apply for a verified badge and build more trust with your audience.",
    icon = ProfileMenuIconKind.VERIFIED,
    hasVerifyPillCta = true,
  ),
)

@Composable
fun TelefamProfileMenuScreen(
  onBack: () -> Unit,
  onStarsClick: () -> Unit,
  onComingSoonItemClick: (itemTitle: String, analyticsTag: String) -> Unit,
) {
  BackHandler(enabled = true) {
    onBack()
  }

  val context = LocalContext.current
  val isDark = LocalTelefamDarkTheme.current
  val screenBg = if (isDark) TelefamDarkBg else Color(0xFFF3F5F9)
  val cardBg = if (isDark) TelefamDarkSurface else Color.White
  val cardBorder = if (isDark) TelefamDarkBorder else Color(0xFFE7EBF1)
  val chevronTint = if (isDark) Color(0xFF9CA3AF) else Color(0xFF7B8190)
  val badgeCrimson = if (isDark) Color(0xFFE01B22) else TelefamCrimsonBright

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(screenBg)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = {},
      )
      .windowInsetsPadding(WindowInsets.statusBars)
      .navigationBarsPadding(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .clip(CircleShape)
          .clickable(onClick = onBack)
          .testTag("profile_menu_back_button")
          .semantics { contentDescription = "Back" },
        contentAlignment = Alignment.Center,
      ) {
        MenuBackArrowVector(
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.size(22.dp),
        )
      }
      Spacer(modifier = Modifier.width(6.dp))
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
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(ProfileMenuEntries, key = { it.id }) { entry ->
          val handleClick = {
            if (entry.id == "stars") {
              onStarsClick()
            } else {
              Toast.makeText(context, "${entry.title} — Coming soon", Toast.LENGTH_SHORT).show()
              onComingSoonItemClick(entry.title, "profile_menu_${entry.id}")
            }
          }
          Surface(
            onClick = handleClick,
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            shadowElevation = if (isDark) 0.dp else 1.5.dp,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("profile_menu_item_${entry.id}"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier = Modifier
                  .size(46.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(badgeCrimson),
                contentAlignment = Alignment.Center,
              ) {
                MenuBadgeSvgIcon(
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
                  lineHeight = 17.5.sp,
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
                    MenuBadgeSvgIcon(
                      kind = ProfileMenuIconKind.VERIFIED,
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
                    MenuChevronRightVector(
                      color = Color.White,
                      modifier = Modifier.size(11.dp),
                    )
                  }
                }
              } else {
                MenuChevronRightVector(
                  color = chevronTint,
                  modifier = Modifier.size(14.dp),
                )
              }
            }
          }
        }
        item {
          Spacer(modifier = Modifier.height(16.dp))
        }
      }
    }
  }
}

@Composable
private fun MenuBackArrowVector(color: Color, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path().apply {
      moveTo(w * 0.80f, h * 0.50f)
      lineTo(w * 0.22f, h * 0.50f)
      moveTo(w * 0.48f, h * 0.24f)
      lineTo(w * 0.20f, h * 0.50f)
      lineTo(w * 0.48f, h * 0.76f)
    }
    drawPath(path, color = color, style = stroke)
  }
}

@Composable
private fun MenuChevronRightVector(color: Color, modifier: Modifier = Modifier) {
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
private fun MenuBadgeSvgIcon(
  kind: ProfileMenuIconKind,
  accentRed: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    when (kind) {
      ProfileMenuIconKind.DASHBOARD -> {
        val barW = w * 0.20f
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(w * 0.13f, h * 0.54f),
          size = Size(barW, h * 0.32f),
          cornerRadius = CornerRadius(barW * 0.4f, barW * 0.4f),
        )
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(w * 0.40f, h * 0.34f),
          size = Size(barW, h * 0.52f),
          cornerRadius = CornerRadius(barW * 0.4f, barW * 0.4f),
        )
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(w * 0.67f, h * 0.14f),
          size = Size(barW, h * 0.72f),
          cornerRadius = CornerRadius(barW * 0.4f, barW * 0.4f),
        )
      }

      ProfileMenuIconKind.STARS -> {
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

      ProfileMenuIconKind.SUBSCRIPTION -> {
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
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(w * 0.16f, h * 0.76f),
          size = Size(w * 0.68f, h * 0.10f),
          cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
        )
      }

      ProfileMenuIconKind.MONETIZATION -> {
        val bubble = Path().apply {
          addOval(androidx.compose.ui.geometry.Rect(w * 0.14f, h * 0.14f, w * 0.86f, h * 0.84f))
        }
        drawPath(bubble, color = Color.White, style = Fill)
        val tail = Path().apply {
          moveTo(w * 0.24f, h * 0.68f)
          lineTo(w * 0.14f, h * 0.86f)
          lineTo(w * 0.38f, h * 0.80f)
          close()
        }
        drawPath(tail, color = Color.White, style = Fill)
        // Draw '$' glyph in red inside white coin
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

      ProfileMenuIconKind.WALLET -> {
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(w * 0.12f, h * 0.24f),
          size = Size(w * 0.74f, h * 0.56f),
          cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        )
        drawRoundRect(
          color = accentRed,
          topLeft = Offset(w * 0.22f, h * 0.30f),
          size = Size(w * 0.48f, h * 0.07f),
          cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
        )
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(w * 0.58f, h * 0.44f),
          size = Size(w * 0.30f, h * 0.20f),
          cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
        )
        drawCircle(
          color = accentRed,
          radius = w * 0.048f,
          center = Offset(w * 0.68f, h * 0.54f),
        )
      }

      ProfileMenuIconKind.LINKED_DEVICE -> {
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val backRect = Path().apply {
          moveTo(w * 0.52f, h * 0.68f)
          lineTo(w * 0.16f, h * 0.68f)
          lineTo(w * 0.16f, h * 0.22f)
          lineTo(w * 0.64f, h * 0.22f)
          lineTo(w * 0.64f, h * 0.36f)
        }
        drawPath(backRect, color = Color.White, style = stroke)
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(w * 0.50f, h * 0.36f),
          size = Size(w * 0.34f, h * 0.46f),
          cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
          style = stroke,
        )
      }

      ProfileMenuIconKind.CREATE_CAMPAIGN -> {
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
        drawRoundRect(
          color = Color.White,
          topLeft = Offset(w * 0.77f, h * 0.38f),
          size = Size(w * 0.09f, h * 0.20f),
          cornerRadius = CornerRadius(w * 0.045f, w * 0.045f),
        )
      }

      ProfileMenuIconKind.VERIFIED -> {
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
        drawPath(
          check,
          color = accentRed,
          style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
      }
    }
  }
}
