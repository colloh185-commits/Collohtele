package telefam.company.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import telefam.company.core.AdaptiveStreamSelector
import telefam.company.data.network.ChatCallSessionDto
import telefam.company.data.repository.TelefamChatRepository
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class ActiveChatCallUiState(
  val callId: String,
  val conversationId: String,
  val peerUserId: String,
  val peerName: String,
  val peerAvatarUrl: String = "",
  val callType: String = "audio", // "audio" | "video"
  val isOutgoing: Boolean = true,
  val state: String = "ringing", // "ringing" | "connecting" | "connected" | "ended"
  val endReason: String = "",
  val startedAtMs: Long = 0L,
  val connectedAtMs: Long = 0L,
  val elapsedSec: Int = 0,
  val isMuted: Boolean = false,
  val isSpeakerOn: Boolean = false,
  val isVideoEnabled: Boolean = true,
  val peerVideoEnabled: Boolean = true,
  val useFrontCamera: Boolean = true,
  val localVideoBitmap: Bitmap? = null,
  val remoteVideoBitmap: Bitmap? = null,
  val errorMessage: String? = null,
)

/**
 * Full-duplex live Audio & Video 1-on-1 call manager over Telefam's authenticated
 * WebSocket + HTTP signaling & compressed media relay.
 */
class ChatCallManager(
  private val appContext: Context,
  private val chatRepo: TelefamChatRepository,
  private val ownerUserIdProvider: () -> String,
  private val peerLookupProvider: (String) -> Triple<String, String, String>, // userId -> (name, avatarUrl, convId)
  private val baseHttpUrl: String,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _callState = MutableStateFlow<ActiveChatCallUiState?>(null)
  val callState: StateFlow<ActiveChatCallUiState?> = _callState.asStateFlow()

  private var pollJob: Job? = null
  private var durationTickerJob: Job? = null
  private var ringtoneJob: Job? = null
  private var audioCaptureJob: Job? = null
  private var clearEndedJob: Job? = null

  private var audioTrack: AudioTrack? = null
  private var echoCanceler: AcousticEchoCanceler? = null

  private var cameraThread: HandlerThread? = null
  private var cameraHandler: Handler? = null
  private var cameraDevice: CameraDevice? = null
  private var captureSession: CameraCaptureSession? = null
  private var imageReader: ImageReader? = null
  private var lastVideoSentMs: Long = 0L
  private var cameraSensorOrientation: Int = 270
  private var isMediaStartedForCallId: String? = null

  fun startCall(
    peerUserId: String,
    peerName: String,
    peerAvatarUrl: String,
    conversationId: String,
    callType: String,
    onError: (String) -> Unit = {},
  ) {
    val typeClean = if (callType == "video") "video" else "audio"
    clearEndedJob?.cancel()
    stopAllMediaAndTimers()

    val tempId = "pending_${System.currentTimeMillis()}"
    _callState.value = ActiveChatCallUiState(
      callId = tempId,
      conversationId = conversationId,
      peerUserId = peerUserId,
      peerName = peerName.ifBlank { "Telefam" },
      peerAvatarUrl = peerAvatarUrl,
      callType = typeClean,
      isOutgoing = true,
      state = "ringing",
      startedAtMs = System.currentTimeMillis(),
      isSpeakerOn = typeClean == "video",
      isVideoEnabled = typeClean == "video",
    )

    scope.launch {
      val res = chatRepo.startCall(peerUserId, conversationId, typeClean)
      res.onSuccess { dto ->
        val current = _callState.value
        if (current == null || current.callId == tempId || current.callId == dto.callId) {
          applyServerCallDto(dto)
          if (dto.state == "ringing") {
            startRingtoneLoop(isOutgoing = true)
            startActiveCallPoll(dto.callId)
          }
        }
      }.onFailure { err ->
        stopAllMediaAndTimers()
        _callState.value = null
        onError(err.message ?: "Unable to start call")
      }
    }
  }

  fun acceptIncomingCall() {
    val active = _callState.value ?: return
    if (active.callId.startsWith("pending_")) return
    stopRingtone()
    _callState.value = active.copy(state = "connecting")
    chatRepo.sendWsCallAccept(active.callId)
    scope.launch {
      val res = chatRepo.acceptCall(active.callId)
      res.onSuccess { dto ->
        applyServerCallDto(dto)
      }.onFailure { err ->
        finishAndClearCall(err.message ?: "Unable to connect call")
      }
    }
  }

  fun rejectIncomingCall() {
    val active = _callState.value ?: return
    stopAllMediaAndTimers()
    chatRepo.sendWsCallReject(active.callId, "declined")
    _callState.value = active.copy(state = "ended", endReason = "Declined")
    scope.launch {
      chatRepo.rejectCall(active.callId, "declined")
    }
    scheduleClearEnded()
  }

  fun endActiveCall() {
    val active = _callState.value ?: return
    val reason = if (active.state == "ringing" && active.isOutgoing) "cancelled" else "ended"
    stopAllMediaAndTimers()
    if (!active.callId.startsWith("pending_")) {
      chatRepo.sendWsCallEnd(active.callId, reason)
      scope.launch {
        chatRepo.endCall(active.callId, reason)
      }
    }
    _callState.value = active.copy(state = "ended", endReason = "Call ended")
    scheduleClearEnded()
  }

  fun toggleMute() {
    val cur = _callState.value ?: return
    val nextMute = !cur.isMuted
    _callState.value = cur.copy(isMuted = nextMute)
    if (!cur.callId.startsWith("pending_")) {
      chatRepo.sendWsCallSignal(cur.callId, if (nextMute) "mute_on" else "mute_off")
    }
  }

  fun toggleSpeaker() {
    val cur = _callState.value ?: return
    val nextSpeaker = !cur.isSpeakerOn
    _callState.value = cur.copy(isSpeakerOn = nextSpeaker)
    applyAudioRouting(nextSpeaker)
  }

  fun toggleVideo() {
    val cur = _callState.value ?: return
    if (cur.callType != "video") return
    val nextEnabled = !cur.isVideoEnabled
    _callState.value = cur.copy(isVideoEnabled = nextEnabled, localVideoBitmap = if (nextEnabled) cur.localVideoBitmap else null)
    if (!cur.callId.startsWith("pending_")) {
      chatRepo.sendWsCallSignal(cur.callId, if (nextEnabled) "cam_on" else "cam_off")
    }
    if (cur.state == "connected") {
      if (nextEnabled) {
        startCameraCapture(cur.callId, cur.useFrontCamera)
      } else {
        stopCameraCapture()
      }
    }
  }

  fun flipCamera() {
    val cur = _callState.value ?: return
    if (cur.callType != "video" || !cur.isVideoEnabled) return
    val nextFront = !cur.useFrontCamera
    _callState.value = cur.copy(useFrontCamera = nextFront)
    if (cur.state == "connected") {
      startCameraCapture(cur.callId, nextFront)
    }
  }

  suspend fun syncActiveCallFromServer() {
    val remote = chatRepo.getActiveCall()
    val local = _callState.value
    if (remote == null) {
      if (local != null && !local.callId.startsWith("pending_") && local.state != "ended") {
        finishAndClearCall("Call ended")
      }
      return
    }
    applyServerCallDto(remote)
  }

  fun handleWsEvent(ev: ChatWsIncomingEvent) {
    when (ev.event) {
      "call_incoming" -> {
        val cid = ev.callId.ifBlank { return }
        val existing = _callState.value
        if (existing != null && existing.callId == cid && existing.state != "ended") return
        if (existing != null && existing.state == "connected") {
          chatRepo.sendWsCallReject(cid, "busy")
          return
        }
        clearEndedJob?.cancel()
        val (lookupName, lookupAvatar, lookupConv) = peerLookupProvider(ev.userId)
        val typeClean = if (ev.callType == "video") "video" else "audio"
        _callState.value = ActiveChatCallUiState(
          callId = cid,
          conversationId = ev.conversationId.ifBlank { lookupConv },
          peerUserId = ev.userId,
          peerName = lookupName.ifBlank { "Telefam" },
          peerAvatarUrl = lookupAvatar,
          callType = typeClean,
          isOutgoing = false,
          state = "ringing",
          startedAtMs = ev.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
          isSpeakerOn = typeClean == "video",
          isVideoEnabled = typeClean == "video",
        )
        startRingtoneLoop(isOutgoing = false)
        startActiveCallPoll(cid)
        // Hydrate full caller profile fields from server if available
        scope.launch {
          chatRepo.getActiveCall()?.let { if (it.callId == cid) applyServerCallDto(it) }
        }
      }

      "call_state", "call_accepted", "call_connected" -> {
        val cid = ev.callId.ifBlank { return }
        val targetState = when {
          ev.event == "call_accepted" || ev.event == "call_connected" || ev.callState == "connected" -> "connected"
          ev.callState.isNotBlank() -> ev.callState
          else -> "ringing"
        }
        val cur = _callState.value
        if (cur == null || (cur.callId != cid && !cur.callId.startsWith("pending_"))) {
          scope.launch { syncActiveCallFromServer() }
          return
        }
        val next = cur.copy(
          callId = cid,
          state = targetState,
          connectedAtMs = if (targetState == "connected" && cur.connectedAtMs == 0L) System.currentTimeMillis() else cur.connectedAtMs,
        )
        _callState.value = next
        if (targetState == "connected") {
          transitionToConnected(next)
        }
      }

      "call_rejected", "call_ended" -> {
        val cur = _callState.value ?: return
        if (cur.callId == ev.callId || cur.callId.startsWith("pending_")) {
          val reasonLabel = when (ev.status.lowercase()) {
            "declined", "busy" -> "Declined"
            "missed" -> "Missed call"
            "cancelled" -> "Cancelled"
            else -> "Call ended"
          }
          finishAndClearCall(reasonLabel)
        }
      }

      "call_signal" -> {
        val cur = _callState.value ?: return
        if (cur.callId != ev.callId) return
        when (ev.signalKind) {
          "cam_off" -> _callState.value = cur.copy(peerVideoEnabled = false, remoteVideoBitmap = null)
          "cam_on" -> _callState.value = cur.copy(peerVideoEnabled = true)
        }
      }

      "call_media" -> {
        val cur = _callState.value ?: return
        if (cur.callId != ev.callId || cur.state == "ended") return
        if (cur.state != "connected") {
          transitionToConnected(cur.copy(state = "connected"))
        }
        when (ev.signalKind) {
          "audio_frame" -> playIncomingAudioFrame(ev.mediaB64)
          "video_frame" -> decodeAndSetRemoteFrame(ev.mediaB64)
        }
      }
    }
  }

  private fun applyServerCallDto(dto: ChatCallSessionDto) {
    if (dto.callId.isBlank()) return
    if (dto.state == "ended" || dto.state == "rejected" || dto.state == "missed") {
      val cur = _callState.value
      if (cur != null && cur.callId == dto.callId && cur.state != "ended") {
        finishAndClearCall(dto.endReason.ifBlank { "Call ended" })
      }
      return
    }
    val ownerUid = ownerUserIdProvider()
    val isOutgoing = dto.callerUserId == ownerUid
    val peerId = if (isOutgoing) dto.calleeUserId else dto.callerUserId
    val rawName = if (isOutgoing) dto.calleeName else dto.callerName
    val rawAvatar = if (isOutgoing) dto.calleeAvatarUrl else dto.callerAvatarUrl
    val resolvedAvatar = rawAvatar.takeIf { it.isNotBlank() }?.let { AdaptiveStreamSelector.resolveMediaUrl(baseHttpUrl, it) }.orEmpty()
    val (fallbackName, fallbackAvatar, fallbackConv) = peerLookupProvider(peerId)

    val existing = _callState.value
    val nextState = when {
      dto.state == "connected" -> "connected"
      existing?.state == "connected" && dto.state != "ended" -> "connected"
      existing?.state == "connecting" && dto.state == "ringing" -> "connecting"
      else -> dto.state.ifBlank { "ringing" }
    }
    val next = ActiveChatCallUiState(
      callId = dto.callId,
      conversationId = dto.conversationId.ifBlank { existing?.conversationId ?: fallbackConv },
      peerUserId = peerId,
      peerName = rawName.ifBlank { existing?.peerName ?: fallbackName.ifBlank { "Telefam" } },
      peerAvatarUrl = resolvedAvatar.ifBlank { existing?.peerAvatarUrl ?: fallbackAvatar },
      callType = if (dto.callType == "video") "video" else "audio",
      isOutgoing = isOutgoing,
      state = nextState,
      startedAtMs = dto.startedAtMs,
      connectedAtMs = if (dto.connectedAtMs > 0L) dto.connectedAtMs else existing?.connectedAtMs ?: 0L,
      elapsedSec = existing?.elapsedSec ?: 0,
      isMuted = existing?.isMuted ?: false,
      isSpeakerOn = existing?.isSpeakerOn ?: (dto.callType == "video"),
      isVideoEnabled = existing?.isVideoEnabled ?: (dto.callType == "video"),
      peerVideoEnabled = existing?.peerVideoEnabled ?: true,
      useFrontCamera = existing?.useFrontCamera ?: true,
      localVideoBitmap = existing?.localVideoBitmap,
      remoteVideoBitmap = existing?.remoteVideoBitmap,
    )
    _callState.value = next

    if (nextState == "ringing") {
      startRingtoneLoop(isOutgoing = isOutgoing)
      startActiveCallPoll(dto.callId)
    } else if (nextState == "connected") {
      transitionToConnected(next)
    }
  }

  private fun transitionToConnected(state: ActiveChatCallUiState) {
    stopRingtone()
    val startedAt = if (state.connectedAtMs > 0L) state.connectedAtMs else System.currentTimeMillis()
    _callState.value = state.copy(state = "connected", connectedAtMs = startedAt)
    startActiveCallPoll(state.callId)
    startDurationTicker(state.callId, startedAt)

    if (isMediaStartedForCallId != state.callId) {
      isMediaStartedForCallId = state.callId
      applyAudioRouting(state.isSpeakerOn)
      startAudioCaptureAndPlayback(state.callId)
      if (state.callType == "video" && state.isVideoEnabled) {
        startCameraCapture(state.callId, state.useFrontCamera)
      }
    }
  }

  private fun startActiveCallPoll(callId: String) {
    if (pollJob?.isActive == true) return
    pollJob = scope.launch {
      while (isActive) {
        delay(1_100L)
        val cur = _callState.value
        if (cur == null || cur.state == "ended") break
        val remote = chatRepo.getActiveCall()
        if (remote == null) {
          if (!cur.callId.startsWith("pending_") && cur.callId == callId) {
            finishAndClearCall("Call ended")
            break
          }
        } else if (remote.callId == cur.callId || cur.callId.startsWith("pending_")) {
          applyServerCallDto(remote)
        }
      }
    }
  }

  private fun startDurationTicker(callId: String, connectedAtMs: Long) {
    if (durationTickerJob?.isActive == true) return
    durationTickerJob = scope.launch {
      while (isActive) {
        val cur = _callState.value
        if (cur == null || cur.callId != callId || cur.state != "connected") break
        val sec = ((System.currentTimeMillis() - connectedAtMs) / 1000L).toInt().coerceAtLeast(0)
        _callState.value = cur.copy(elapsedSec = sec)
        delay(1_000L)
      }
    }
  }

  private fun finishAndClearCall(reason: String) {
    val prev = _callState.value ?: return
    stopAllMediaAndTimers()
    _callState.value = prev.copy(state = "ended", endReason = reason)
    scheduleClearEnded()
  }

  private fun scheduleClearEnded() {
    clearEndedJob?.cancel()
    clearEndedJob = scope.launch {
      delay(1_200L)
      val cur = _callState.value
      if (cur?.state == "ended") {
        _callState.value = null
      }
    }
  }

  private fun startRingtoneLoop(isOutgoing: Boolean) {
    if (ringtoneJob?.isActive == true) return
    ringtoneJob = scope.launch {
      while (isActive && _callState.value?.state == "ringing") {
        playSynthesizedRingBurst(isOutgoing)
        delay(if (isOutgoing) 2_200L else 1_750L)
      }
    }
  }

  private fun stopRingtone() {
    ringtoneJob?.cancel()
    ringtoneJob = null
  }

  private fun playSynthesizedRingBurst(isOutgoing: Boolean) {
    runCatching {
      val sampleRate = 16_000
      val durationMs = if (isOutgoing) 420 else 360
      val count = (sampleRate * durationMs) / 1000
      val buf = ShortArray(count)
      for (i in 0 until count) {
        val t = i.toDouble() / sampleRate.toDouble()
        val env = 0.14 * sin(PI * (i.toDouble() / count.toDouble()))
        val wave = if (isOutgoing) {
          (sin(2.0 * PI * 440.0 * t) + sin(2.0 * PI * 480.0 * t)) * 0.5
        } else {
          val note = if (i < count / 2) 587.33 else 783.99
          sin(2.0 * PI * note * t) + 0.25 * cos(2.0 * PI * note * 2.0 * t)
        }
        buf[i] = (wave * env * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
      }
      val track = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(if (isOutgoing) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        )
        .setBufferSizeInBytes(buf.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
      track.write(buf, 0, buf.size)
      track.play()
      Thread.sleep(durationMs.toLong() + 40L)
      track.stop()
      track.release()
    }
  }

  private fun applyAudioRouting(speakerOn: Boolean) {
    runCatching {
      val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
      am.mode = AudioManager.MODE_IN_COMMUNICATION
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val targetType = if (speakerOn) {
          android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
          android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        val dev = am.availableCommunicationDevices.firstOrNull { it.type == targetType }
        if (dev != null) {
          am.setCommunicationDevice(dev)
        } else {
          @Suppress("DEPRECATION")
          am.isSpeakerphoneOn = speakerOn
        }
      } else {
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speakerOn
      }
    }
  }

  private fun restoreAudioRouting() {
    runCatching {
      val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        am.clearCommunicationDevice()
      } else {
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
      }
      am.mode = AudioManager.MODE_NORMAL
    }
  }

  @SuppressLint("MissingPermission")
  private fun startAudioCaptureAndPlayback(callId: String) {
    runCatching {
      val sampleRate = 16_000
      val outBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        .coerceAtLeast(3200)
      audioTrack?.release()
      audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build(),
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        )
        .setBufferSizeInBytes(outBuf)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build().apply { play() }
    }

    if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      return
    }
    audioCaptureJob?.cancel()
    audioCaptureJob = scope.launch {
      var rec: AudioRecord? = null
      try {
        val sampleRate = 16_000
        val minIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
          .coerceAtLeast(3200)
        rec = AudioRecord(
          MediaRecorder.AudioSource.VOICE_COMMUNICATION,
          sampleRate,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          minIn,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) return@launch
        if (AcousticEchoCanceler.isAvailable()) {
          runCatching {
            echoCanceler = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
          }
        }
        rec.startRecording()
        // 40ms PCM frames (1280 bytes at 16kHz 16-bit mono) balance low latency & low frame overhead
        val frame = ByteArray(1280)
        while (isActive) {
          val cur = _callState.value
          if (cur == null || cur.callId != callId || cur.state != "connected") break
          val n = rec.read(frame, 0, frame.size)
          if (n > 0 && !cur.isMuted) {
            val slice = if (n == frame.size) frame else frame.copyOf(n)
            val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
            chatRepo.sendWsCallMedia(callId, "audio_frame", b64)
          }
        }
      } catch (_: Throwable) {
      } finally {
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
      }
    }
  }

  private fun playIncomingAudioFrame(mediaB64: String) {
    if (mediaB64.isBlank()) return
    val track = audioTrack ?: return
    runCatching {
      val pcm = Base64.decode(mediaB64, Base64.NO_WRAP)
      if (pcm.isNotEmpty()) {
        track.write(pcm, 0, pcm.size)
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun startCameraCapture(callId: String, frontCamera: Boolean) {
    stopCameraCapture()
    if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      return
    }
    runCatching {
      val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
      val desiredLens = if (frontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
      val camId = cm.cameraIdList.firstOrNull { id ->
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == desiredLens
      } ?: cm.cameraIdList.firstOrNull() ?: return
      cameraSensorOrientation = cm.getCameraCharacteristics(camId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270

      val ht = HandlerThread("TelefamCallCamera").apply { start() }
      val handler = Handler(ht.looper)
      cameraThread = ht
      cameraHandler = handler

      val reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
      imageReader = reader
      reader.setOnImageAvailableListener({ ir ->
        val img = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
        try {
          val now = System.currentTimeMillis()
          if (now - lastVideoSentMs >= 110L) {
            val cur = _callState.value
            if (cur != null && cur.callId == callId && cur.state == "connected" && cur.isVideoEnabled) {
              val jpeg = yuvToRotatedJpeg(img, cameraSensorOrientation, frontCamera)
              if (jpeg != null) {
                lastVideoSentMs = now
                val localBmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (localBmp != null) {
                  _callState.value = _callState.value?.copy(localVideoBitmap = localBmp)
                }
                val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                chatRepo.sendWsCallMedia(callId, "video_frame", b64, localBmp?.width ?: 240, localBmp?.height ?: 320)
              }
            }
          }
        } finally {
          img.close()
        }
      }, handler)

      cm.openCamera(
        camId,
        object : CameraDevice.StateCallback() {
          override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            runCatching {
              val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
              }
              @Suppress("DEPRECATION")
              device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                  override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    runCatching {
                      session.setRepeatingRequest(req.build(), null, handler)
                    }
                  }
                  override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                handler,
              )
            }
          }
          override fun onDisconnected(device: CameraDevice) { device.close() }
          override fun onError(device: CameraDevice, error: Int) { device.close() }
        },
        handler,
      )
    }
  }

  private fun stopCameraCapture() {
    runCatching { captureSession?.close() }
    captureSession = null
    runCatching { cameraDevice?.close() }
    cameraDevice = null
    runCatching { imageReader?.close() }
    imageReader = null
    runCatching { cameraThread?.quitSafely() }
    cameraThread = null
    cameraHandler = null
  }

  private fun yuvToRotatedJpeg(image: Image, sensorOrientation: Int, mirror: Boolean): ByteArray? {
    return runCatching {
      val yPlane = image.planes[0]
      val uPlane = image.planes[1]
      val vPlane = image.planes[2]
      val w = image.width
      val h = image.height
      val nv21 = ByteArray(w * h * 3 / 2)

      val yBuf = yPlane.buffer
      val yRowStride = yPlane.rowStride
      var outIdx = 0
      for (row in 0 until h) {
        yBuf.position(row * yRowStride)
        yBuf.get(nv21, outIdx, w)
        outIdx += w
      }

      val uBuf = uPlane.buffer
      val vBuf = vPlane.buffer
      val uvRowStride = vPlane.rowStride
      val uvPixelStride = vPlane.pixelStride
      for (row in 0 until h / 2) {
        for (col in 0 until w / 2) {
          val vuIdx = row * uvRowStride + col * uvPixelStride
          nv21[outIdx++] = vBuf.get(vuIdx)
          nv21[outIdx++] = uBuf.get(vuIdx)
        }
      }

      val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
      val rawOut = ByteArrayOutputStream()
      yuv.compressToJpeg(Rect(0, 0, w, h), 55, rawOut)
      val rawBytes = rawOut.toByteArray()
      val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
      val matrix = Matrix().apply {
        postRotate(sensorOrientation.toFloat())
        if (mirror) postScale(-1f, 1f)
      }
      val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
      if (rotated !== decoded) decoded.recycle()
      val finalOut = ByteArrayOutputStream()
      rotated.compress(Bitmap.CompressFormat.JPEG, 48, finalOut)
      finalOut.toByteArray()
    }.getOrNull()
  }

  private fun decodeAndSetRemoteFrame(mediaB64: String) {
    if (mediaB64.isBlank()) return
    runCatching {
      val bytes = Base64.decode(mediaB64, Base64.NO_WRAP)
      val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
      _callState.value = _callState.value?.copy(remoteVideoBitmap = bmp)
    }
  }

  private fun stopAllMediaAndTimers() {
    pollJob?.cancel()
    pollJob = null
    durationTickerJob?.cancel()
    durationTickerJob = null
    stopRingtone()
    audioCaptureJob?.cancel()
    audioCaptureJob = null
    runCatching { echoCanceler?.release() }
    echoCanceler = null
    runCatching { audioTrack?.stop() }
    runCatching { audioTrack?.release() }
    audioTrack = null
    stopCameraCapture()
    restoreAudioRouting()
    isMediaStartedForCallId = null
  }

  fun cleanup() {
    clearEndedJob?.cancel()
    stopAllMediaAndTimers()
    _callState.value = null
  }
}
