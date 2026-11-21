package telefam.company.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.telefam.company.app.R
import telefam.company.chat.ActiveChatCallUiState
import telefam.company.core.TelefamContactsRules
import telefam.company.ui.theme.TelefamCrimson
import telefam.company.ui.theme.TelefamPureWhite

@Composable
fun TelefamCallOverlayScreen(
  callState: ActiveChatCallUiState?,
  onAccept: () -> Unit,
  onReject: () -> Unit,
  onEnd: () -> Unit,
  onToggleMute: () -> Unit,
  onToggleSpeaker: () -> Unit,
  onToggleVideo: () -> Unit,
  onFlipCamera: () -> Unit,
) {
  AnimatedVisibility(
    visible = callState != null,
    enter = fadeIn(tween(200)),
    exit = fadeOut(tween(220)),
  ) {
    val active = callState ?: return@AnimatedVisibility
    val context = LocalContext.current

    val acceptPermsLauncher = rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { perms ->
      val audioOk = perms[Manifest.permission.RECORD_AUDIO] == true ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
      if (!audioOk) {
        Toast.makeText(context, "Microphone permission is required to answer calls", Toast.LENGTH_SHORT).show()
      }
      onAccept()
    }

    BackHandler(enabled = active.state != "ended") {
      if (!active.isOutgoing && active.state == "ringing") {
        onReject()
      } else {
        onEnd()
      }
    }

    val isVideo = active.callType == "video"
    val statusText = when (active.state) {
      "ringing" -> if (active.isOutgoing) {
        stringResource(R.string.call_status_ringing)
      } else if (isVideo) {
        stringResource(R.string.call_status_incoming_video)
      } else {
        stringResource(R.string.call_status_incoming_audio)
      }
      "connecting" -> stringResource(R.string.call_status_connecting)
      "connected" -> formatCallTimer(active.elapsedSec)
      "ended" -> active.endReason.ifBlank { "Call ended" }
      else -> active.state
    }

    val pulseTransition = rememberInfiniteTransition(label = "call_pulse")
    val pulseScale by pulseTransition.animateFloat(
      initialValue = 1.0f,
      targetValue = 1.26f,
      animationSpec = infiniteRepeatable(tween(1150), RepeatMode.Reverse),
      label = "call_pulse_scale",
    )

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            listOf(Color(0xFF0E131E), Color(0xFF151E2E), Color(0xFF0A0D14)),
          ),
        )
        .clickable(enabled = false) {}
        .testTag("telefam_call_overlay"),
    ) {
      // Full-bleed remote video frame when connected in video call
      val remoteBmp = active.remoteVideoBitmap
      if (isVideo && active.state == "connected" && remoteBmp != null && active.peerVideoEnabled) {
        Image(
          bitmap = remoteBmp.asImageBitmap(),
          contentDescription = active.peerName,
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .fillMaxSize()
            .testTag("call_remote_video_surface"),
        )
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                listOf(Color(0x88000000), Color.Transparent, Color(0xAA000000)),
              ),
            ),
        )
      }

      Column(
        modifier = Modifier
          .fillMaxSize()
          .statusBarsPadding()
          .navigationBarsPadding()
          .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        // Top encrypted pill + peer header
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Surface(
            color = Color(0x33FFFFFF),
            shape = RoundedCornerShape(18.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Lock,
                contentDescription = null,
                tint = TelefamPureWhite,
                modifier = Modifier.size(13.dp),
              )
              Text(
                text = if (isVideo) "Telefam Video Call" else "End-to-End Encrypted",
                color = TelefamPureWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }

          if (isVideo && active.state == "connected" && remoteBmp != null && active.peerVideoEnabled) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = active.peerName.ifBlank { "Telefam" },
              color = TelefamPureWhite,
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = statusText,
              color = Color(0xFFE2E8F0),
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.testTag("call_status_label"),
            )
          }
        }

        // Center avatar/halo when audio call or before remote video frame arrives
        if (!isVideo || active.state != "connected" || remoteBmp == null || !active.peerVideoEnabled) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Box(contentAlignment = Alignment.Center) {
              if (active.state == "ringing" || active.state == "connecting") {
                Box(
                  modifier = Modifier
                    .size(134.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(TelefamCrimson.copy(alpha = 0.22f)),
                )
              }
              Box(
                modifier = Modifier
                  .size(116.dp)
                  .clip(CircleShape)
                  .background(Color(0xFF273449))
                  .border(2.5.dp, Color(0x66FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
              ) {
                if (active.peerAvatarUrl.isNotBlank()) {
                  AsyncImage(
                    model = active.peerAvatarUrl,
                    contentDescription = active.peerName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                  )
                } else {
                  Text(
                    text = TelefamContactsRules.initialsFor(active.peerName, active.peerName),
                    color = TelefamPureWhite,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(22.dp))
            Text(
              text = active.peerName.ifBlank { "Telefam" },
              color = TelefamPureWhite,
              fontSize = 26.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = statusText,
              color = if (active.state == "connected") Color(0xFF4ADE80) else Color(0xFFCBD5E1),
              fontSize = 15.sp,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.testTag("call_status_label"),
            )
          }
        } else {
          Spacer(modifier = Modifier.height(1.dp))
        }

        // Bottom Controls Bar
        Column(
          modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (!active.isOutgoing && active.state == "ringing") {
            // Incoming Call: Decline & Accept
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceEvenly,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              CallRoundActionButton(
                icon = Icons.Filled.CallEnd,
                label = stringResource(R.string.call_action_decline),
                containerColor = TelefamCrimson,
                iconTint = TelefamPureWhite,
                sizeDp = 66,
                testTag = "call_reject_button",
                onClick = onReject,
              )
              CallRoundActionButton(
                icon = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                label = stringResource(R.string.call_action_accept),
                containerColor = Color(0xFF16A34A),
                iconTint = TelefamPureWhite,
                sizeDp = 66,
                testTag = "call_accept_button",
                onClick = {
                  val needed = mutableListOf<String>()
                  if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.RECORD_AUDIO)
                  }
                  if (isVideo && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.CAMERA)
                  }
                  if (needed.isEmpty()) {
                    onAccept()
                  } else {
                    acceptPermsLauncher.launch(needed.toTypedArray())
                  }
                },
              )
            }
          } else {
            // Active / Outgoing Call Control Bar
            Surface(
              color = Color(0x52101520),
              shape = RoundedCornerShape(32.dp),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                CallRoundActionButton(
                  icon = if (active.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                  label = stringResource(R.string.call_action_mute),
                  containerColor = if (active.isMuted) TelefamPureWhite else Color(0x33FFFFFF),
                  iconTint = if (active.isMuted) Color(0xFF111827) else TelefamPureWhite,
                  sizeDp = 52,
                  testTag = "call_mute_button",
                  onClick = onToggleMute,
                )
                CallRoundActionButton(
                  icon = if (active.isSpeakerOn) Icons.Filled.VolumeUp else Icons.Outlined.VolumeDown,
                  label = stringResource(R.string.call_action_speaker),
                  containerColor = if (active.isSpeakerOn) TelefamPureWhite else Color(0x33FFFFFF),
                  iconTint = if (active.isSpeakerOn) Color(0xFF111827) else TelefamPureWhite,
                  sizeDp = 52,
                  testTag = "call_speaker_button",
                  onClick = onToggleSpeaker,
                )
                if (isVideo) {
                  CallRoundActionButton(
                    icon = if (active.isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    label = stringResource(R.string.call_action_camera),
                    containerColor = if (!active.isVideoEnabled) TelefamPureWhite else Color(0x33FFFFFF),
                    iconTint = if (!active.isVideoEnabled) Color(0xFF111827) else TelefamPureWhite,
                    sizeDp = 52,
                    testTag = "call_video_toggle_button",
                    onClick = onToggleVideo,
                  )
                  CallRoundActionButton(
                    icon = Icons.Filled.Cameraswitch,
                    label = stringResource(R.string.call_action_flip),
                    containerColor = Color(0x33FFFFFF),
                    iconTint = TelefamPureWhite,
                    sizeDp = 52,
                    testTag = "call_flip_camera_button",
                    onClick = onFlipCamera,
                  )
                }
                CallRoundActionButton(
                  icon = Icons.Filled.CallEnd,
                  label = stringResource(R.string.call_action_end),
                  containerColor = TelefamCrimson,
                  iconTint = TelefamPureWhite,
                  sizeDp = 56,
                  testTag = "call_end_button",
                  onClick = onEnd,
                )
              }
            }
          }
        }
      }

      // Local Camera PiP preview in top-right during active video call
      val localBmp = active.localVideoBitmap
      if (isVideo && active.state == "connected" && active.isVideoEnabled && localBmp != null) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 56.dp, end = 16.dp)
            .width(104.dp)
            .height(148.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, Color(0x99FFFFFF), RoundedCornerShape(16.dp))
            .background(Color.Black),
        ) {
          Image(
            bitmap = localBmp.asImageBitmap(),
            contentDescription = "Your camera preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }
    }
  }
}

@Composable
private fun CallRoundActionButton(
  icon: ImageVector,
  label: String,
  containerColor: Color,
  iconTint: Color,
  sizeDp: Int,
  testTag: String,
  onClick: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      modifier = Modifier
        .size(sizeDp.dp)
        .clip(CircleShape)
        .background(containerColor)
        .clickable(onClick = onClick)
        .testTag(testTag),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = iconTint,
        modifier = Modifier.size((sizeDp * 0.44f).dp),
      )
    }
    Text(
      text = label,
      color = TelefamPureWhite,
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
    )
  }
}

private fun formatCallTimer(totalSec: Int): String {
  val m = totalSec / 60
  val s = totalSec % 60
  return String.format("%02d:%02d", m, s)
}
