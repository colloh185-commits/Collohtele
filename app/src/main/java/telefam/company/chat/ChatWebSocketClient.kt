package telefam.company.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class ChatWsIncomingEvent(
  val event: String,
  val conversationId: String = "",
  val userId: String = "",
  val messageId: String = "",
  val status: String = "",
  val typingMode: String = "",
  val isOnline: Boolean = false,
  val lastSeenMs: Long = 0L,
  val lastSeenLabel: String = "",
  val starGiftAmount: Long = 0L,
  val starBalanceAvailable: Long = -1L,
  val starBalanceReceived: Long = -1L,
  val callId: String = "",
  val callType: String = "",
  val callState: String = "",
  val signalKind: String = "",
  val signalPayload: String = "",
  val mediaB64: String = "",
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val rawJson: String = "",
  val timestampMs: Long = 0L,
)

/**
 * Real-time RFC 6455 WebSocket transport for typing indicators, recording indicators,
 * online presence, delivery/seen receipts, message delivery, unsends, and view-once burn events.
 */
class ChatWebSocketClient(
  private val okHttp: OkHttpClient,
  private val baseHttpUrl: String,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _events = MutableSharedFlow<ChatWsIncomingEvent>(extraBufferCapacity = 64)
  val events: SharedFlow<ChatWsIncomingEvent> = _events.asSharedFlow()

  private var activeSocket: WebSocket? = null
  private var lifecycleJob: Job? = null
  private var lastToken: String = ""
  private var lastDeviceFp: String = ""

  fun connect(bearerToken: String, deviceFingerprint: String) {
    val cleanToken = bearerToken.removePrefix("Bearer ").trim()
    val cleanFp = deviceFingerprint.trim()
    if (cleanToken.isBlank() || cleanFp.isBlank() || baseHttpUrl.isBlank()) return
    if (lifecycleJob?.isActive == true && cleanToken == lastToken && cleanFp == lastDeviceFp) return

    disconnect()
    lastToken = cleanToken
    lastDeviceFp = cleanFp

    lifecycleJob = scope.launch {
      var attempt = 0
      while (isActive) {
        val wsUrl = buildWsUrl(baseHttpUrl)
        if (wsUrl.isBlank()) break
        val connectedDone = kotlinx.coroutines.CompletableDeferred<Unit>()
        val req = Request.Builder()
          .url(wsUrl)
          .header("Authorization", "Bearer $cleanToken")
          .header("X-Telefam-Device-FP", cleanFp)
          .build()

        val ws = okHttp.newWebSocket(
          req,
          object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              attempt = 0
              activeSocket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
              parseEvent(text)?.let { ev -> _events.tryEmit(ev) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
              webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
              if (activeSocket === webSocket) activeSocket = null
              connectedDone.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
              if (activeSocket === webSocket) activeSocket = null
              connectedDone.complete(Unit)
            }
          },
        )
        activeSocket = ws

        // Heartbeat ping every 25s while waiting on socket lifecycle
        val pinger = launch {
          while (isActive && !connectedDone.isCompleted) {
            delay(25_000L)
            activeSocket?.send("""{"event":"ping"}""")
          }
        }
        connectedDone.await()
        pinger.cancel()
        attempt++
        val backoffMs = (1500L shl attempt.coerceAtMost(4)).coerceAtMost(24_000L)
        delay(backoffMs)
      }
    }
  }

  fun disconnect() {
    lifecycleJob?.cancel()
    lifecycleJob = null
    runCatching { activeSocket?.close(1000, "signout") }
    activeSocket = null
  }

  fun isConnected(): Boolean = activeSocket != null

  fun sendTyping(conversationId: String, mode: String) {
    if (conversationId.isBlank()) return
    val json = JSONObject()
      .put("event", "typing")
      .put("conversation_id", conversationId)
      .put("typing_mode", mode)
      .toString()
    activeSocket?.send(json)
  }

  fun sendCallAccept(callId: String) {
    if (callId.isBlank()) return
    activeSocket?.send(JSONObject().put("event", "call_accept").put("call_id", callId).toString())
  }

  fun sendCallReject(callId: String, reason: String = "declined") {
    if (callId.isBlank()) return
    activeSocket?.send(
      JSONObject()
        .put("event", "call_reject")
        .put("call_id", callId)
        .put("status", reason)
        .toString(),
    )
  }

  fun sendCallEnd(callId: String, reason: String = "ended") {
    if (callId.isBlank()) return
    activeSocket?.send(
      JSONObject()
        .put("event", "call_end")
        .put("call_id", callId)
        .put("status", reason)
        .toString(),
    )
  }

  fun sendCallSignal(callId: String, signalKind: String, signalPayload: String = "") {
    if (callId.isBlank() || signalKind.isBlank()) return
    activeSocket?.send(
      JSONObject()
        .put("event", "call_signal")
        .put("call_id", callId)
        .put("signal_kind", signalKind)
        .put("signal_payload", signalPayload)
        .toString(),
    )
  }

  fun sendCallMedia(callId: String, mediaKind: String, mediaB64: String, width: Int = 0, height: Int = 0) {
    if (callId.isBlank() || mediaB64.isBlank()) return
    val obj = JSONObject()
      .put("event", "call_media")
      .put("call_id", callId)
      .put("signal_kind", mediaKind)
      .put("media_b64", mediaB64)
    if (width > 0) obj.put("video_width", width)
    if (height > 0) obj.put("video_height", height)
    activeSocket?.send(obj.toString())
  }

  private fun buildWsUrl(httpUrl: String): String {
    val trimmed = httpUrl.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    val wsBase = when {
      trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
      trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
      else -> return ""
    }
    return "$wsBase/v1/chat/ws"
  }

  private fun parseEvent(text: String): ChatWsIncomingEvent? = runCatching {
    val obj = JSONObject(text)
    ChatWsIncomingEvent(
      event = obj.optString("event"),
      conversationId = obj.optString("conversation_id"),
      userId = obj.optString("user_id"),
      messageId = obj.optString("message_id"),
      status = obj.optString("status"),
      typingMode = obj.optString("typing_mode"),
      isOnline = obj.optBoolean("is_online", false),
      lastSeenMs = obj.optLong("last_seen_ms", 0L),
      lastSeenLabel = obj.optString("last_seen_label"),
      starGiftAmount = obj.optLong("star_gift_amount", 0L),
      starBalanceAvailable = if (obj.has("star_balance_available")) obj.optLong("star_balance_available", -1L) else -1L,
      starBalanceReceived = if (obj.has("star_balance_received")) obj.optLong("star_balance_received", -1L) else -1L,
      callId = obj.optString("call_id"),
      callType = obj.optString("call_type"),
      callState = obj.optString("call_state"),
      signalKind = obj.optString("signal_kind"),
      signalPayload = obj.optString("signal_payload"),
      mediaB64 = obj.optString("media_b64"),
      videoWidth = obj.optInt("video_width", 0),
      videoHeight = obj.optInt("video_height", 0),
      rawJson = text,
      timestampMs = obj.optLong("timestamp_ms", System.currentTimeMillis()),
    )
  }.getOrNull()
}
