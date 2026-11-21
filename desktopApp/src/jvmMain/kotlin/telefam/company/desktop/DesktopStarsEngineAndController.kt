package telefam.company.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

object DesktopStarsCatalog {
  val TIERS: List<Long> = listOf(
    5L, 10L, 20L, 50L, 100L, 200L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 50_000L,
  )

  data class CurrencyRule(
    val currencyCode: String,
    val symbol: String,
    val ratePerStar: Double,
    val decimals: Int,
  )

  private val rulesByCurrency = mapOf(
    "KES" to CurrencyRule("KES", "Ksh", 1.00, 0),
    "NGN" to CurrencyRule("NGN", "₦", 12.00, 0),
    "GHS" to CurrencyRule("GHS", "GH₵", 0.12, 2),
    "ZAR" to CurrencyRule("ZAR", "R", 0.15, 2),
    "UGX" to CurrencyRule("UGX", "UGX", 28.00, 0),
    "TZS" to CurrencyRule("TZS", "TZS", 20.00, 0),
    "RWF" to CurrencyRule("RWF", "RWF", 10.00, 0),
    "USD" to CurrencyRule("USD", "$", 0.01, 2),
    "EUR" to CurrencyRule("EUR", "€", 0.009, 2),
    "GBP" to CurrencyRule("GBP", "£", 0.008, 2),
    "CAD" to CurrencyRule("CAD", "CA$", 0.014, 2),
    "AUD" to CurrencyRule("AUD", "A$", 0.015, 2),
    "JPY" to CurrencyRule("JPY", "¥", 1.50, 0),
    "INR" to CurrencyRule("INR", "₹", 0.85, 2),
    "AED" to CurrencyRule("AED", "AED", 0.037, 2),
    "SAR" to CurrencyRule("SAR", "SAR", 0.038, 2),
    "BRL" to CurrencyRule("BRL", "R$", 0.055, 2),
    "MXN" to CurrencyRule("MXN", "MX$", 0.20, 2),
  )

  private val countryToCurrency = mapOf(
    "KE" to "KES", "NG" to "NGN", "GH" to "GHS", "ZA" to "ZAR",
    "UG" to "UGX", "TZ" to "TZS", "RW" to "RWF",
    "US" to "USD", "GB" to "GBP", "UK" to "GBP", "CA" to "CAD", "AU" to "AUD",
    "JP" to "JPY", "IN" to "INR", "AE" to "AED", "SA" to "SAR", "BR" to "BRL", "MX" to "MXN",
    "DE" to "EUR", "FR" to "EUR", "IT" to "EUR", "ES" to "EUR", "NL" to "EUR", "BE" to "EUR",
    "IE" to "EUR", "PT" to "EUR", "AT" to "EUR", "FI" to "EUR", "GR" to "EUR", "LU" to "EUR",
  )

  private val paypalDirectCurrencies = setOf("USD", "EUR", "GBP", "CAD", "AUD", "JPY", "BRL", "MXN")
  private val paystackDirectCurrencies = setOf("KES", "NGN", "GHS", "ZAR", "USD")

  fun ruleForCountry(countryIso: String): CurrencyRule {
    val cleanIso = countryIso.trim().uppercase(Locale.US)
    val currency = countryToCurrency[cleanIso] ?: "USD"
    return rulesByCurrency[currency] ?: rulesByCurrency.getValue("USD")
  }

  fun formatConvertedPrice(countryIso: String, stars: Long): String {
    val rule = ruleForCountry(countryIso)
    return formatRuleAmount(rule, stars * rule.ratePerStar)
  }

  fun gatewaySettlementNote(countryIso: String, provider: String, stars: Long): String? {
    val local = ruleForCountry(countryIso)
    val prov = provider.lowercase(Locale.US)
    val supported = if (prov == "paypal") paypalDirectCurrencies else paystackDirectCurrencies
    if (local.currencyCode in supported) return null
    val usdRule = rulesByCurrency.getValue("USD")
    val usdText = formatRuleAmount(usdRule, stars * usdRule.ratePerStar)
    return "Settled as $usdText USD via ${if (prov == "paypal") "PayPal" else "Paystack"}"
  }

  fun formatComma(value: Long): String =
    NumberFormat.getNumberInstance(Locale.US).format(value)

  private fun formatRuleAmount(rule: CurrencyRule, major: Double): String {
    val rounded = (major * 100.0).roundToLong() / 100.0
    val numberFmt = NumberFormat.getNumberInstance(Locale.US)
    val formatted = if (rule.decimals == 0 || abs(rounded - rounded.roundToLong().toDouble()) < 0.001) {
      numberFmt.maximumFractionDigits = 0
      numberFmt.format(rounded.roundToLong())
    } else {
      String.format(Locale.US, "%.2f", rounded)
    }
    return if (rule.symbol.length > 1 && !rule.symbol.endsWith("$")) {
      "${rule.symbol} $formatted"
    } else {
      "${rule.symbol}$formatted"
    }
  }
}

enum class DesktopStarRoute {
  STARS_HUB,
  SEND_STARS,
  BUY_STARS,
  CHECKOUT,
}

data class DesktopStarWallet(
  val userId: String = "",
  val availableBalance: Long = 0L,
  val totalReceived: Long = 0L,
  val totalSent: Long = 0L,
  val updatedAtMs: Long = 0L,
)

data class DesktopStarSender(
  val senderUserId: String = "",
  val senderName: String = "",
  val senderUsername: String = "",
  val senderAvatar: String = "",
  val totalStars: Long = 0L,
  val giftsCount: Int = 0,
  val lastGiftedAtMs: Long = 0L,
)

data class DesktopStarTx(
  val txId: String = "",
  val direction: String = "",
  val senderUserId: String = "",
  val senderName: String = "",
  val senderUsername: String = "",
  val senderAvatar: String = "",
  val recipientUserId: String = "",
  val recipientName: String = "",
  val recipientUsername: String = "",
  val starAmount: Long = 0L,
  val currency: String = "",
  val amountMinor: Long = 0L,
  val provider: String = "",
  val providerReference: String = "",
  val status: String = "",
  val createdAtMs: Long = 0L,
)

data class DesktopStarPaymentIntent(
  val intentId: String = "",
  val idempotencyKey: String = "",
  val starAmount: Long = 0L,
  val provider: String = "",
  val providerReference: String = "",
  val localCurrency: String = "",
  val localAmountMinor: Long = 0L,
  val settlementCurrency: String = "",
  val settlementAmountMinor: Long = 0L,
  val gatewayAmountStr: String = "",
  val status: String = "",
  val checkoutUrl: String = "",
  val credited: Boolean = false,
  val createdAtMs: Long = 0L,
  val verifiedAtMs: Long = 0L,
)

data class DesktopStarRateLimit(
  val hourlySentCount: Int = 0,
  val hourlySentLimit: Int = 20,
  val dailySentStars: Long = 0L,
  val dailySentLimit: Long = 100_000L,
  val dailyPurchaseStars: Long = 0L,
  val dailyPurchaseLimit: Long = 250_000L,
  val isRestricted: Boolean = false,
)

data class DesktopStarGiftRecipientTarget(
  val userId: String,
  val fullName: String,
  val username: String,
  val avatarUrl: String,
  val isOnline: Boolean,
  val conversationId: String,
)

data class DesktopStarCelebrationState(
  val eventKey: String,
  val starAmount: Long,
  val senderName: String,
  val isIncoming: Boolean,
)

data class DesktopStarsUiState(
  val routeStack: List<DesktopStarRoute> = emptyList(),
  val wallet: DesktopStarWallet = DesktopStarWallet(),
  val countryIso: String = Locale.getDefault().country.ifBlank { "US" },
  val senders: List<DesktopStarSender> = emptyList(),
  val transactions: List<DesktopStarTx> = emptyList(),
  val pendingIntents: List<DesktopStarPaymentIntent> = emptyList(),
  val rateLimitStatus: DesktopStarRateLimit = DesktopStarRateLimit(),
  val isLoadingSummary: Boolean = false,
  val isSubmittingSend: Boolean = false,
  val isCreatingCheckout: Boolean = false,
  val isVerifyingPayment: Boolean = false,
  val recipientTarget: DesktopStarGiftRecipientTarget? = null,
  val selectedPackageStars: Long = 100L,
  val selectedProvider: String = "paystack",
  val pendingCheckoutIntent: DesktopStarPaymentIntent? = null,
  val currentCheckoutIdempKey: String = "",
  val launchCheckoutUrl: String? = null,
  val errorBanner: String? = null,
  val statusMessage: String? = null,
  val activeCelebration: DesktopStarCelebrationState? = null,
) {
  val currentRoute: DesktopStarRoute?
    get() = routeStack.lastOrNull()
}

class DesktopStarsController(
  private val apiClient: DesktopApiClient,
  private val chatController: DesktopChatController,
  private val soundsEnabledProvider: () -> Boolean,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _state = MutableStateFlow(DesktopStarsUiState())
  val state: StateFlow<DesktopStarsUiState> = _state.asStateFlow()

  private var autoVerifyJob: kotlinx.coroutines.Job? = null

  init {
    chatController.onStarWsEvent = { ev -> onRealtimeStarEvent(ev) }
  }

  fun attachSession(userId: String) {
    if (userId.isBlank()) {
      detachSession()
      return
    }
    refreshSummary(silent = true)
  }

  fun detachSession() {
    autoVerifyJob?.cancel()
    autoVerifyJob = null
    _state.value = DesktopStarsUiState(countryIso = resolveDeviceCountryIso())
  }

  private fun resolveDeviceCountryIso(): String {
    val snapIso = DesktopDeviceContextCollector.collectSnapshot().localeCountryIso
    if (snapIso.length == 2) return snapIso
    val def = Locale.getDefault().country.trim().uppercase(Locale.US)
    return if (def.length == 2) def else "US"
  }

  fun openStarsHub() {
    _state.update {
      it.copy(
        routeStack = listOf(DesktopStarRoute.STARS_HUB),
        errorBanner = null,
        statusMessage = null,
      )
    }
    refreshSummary(silent = false)
  }

  fun openSendStars(target: DesktopStarGiftRecipientTarget) {
    _state.update {
      it.copy(
        recipientTarget = target,
        routeStack = listOf(DesktopStarRoute.SEND_STARS),
        errorBanner = null,
        statusMessage = null,
      )
    }
    refreshSummary(silent = true)
  }

  fun openBuyStars(preselectedTier: Long = 5L) {
    _state.update {
      val nextStack = if (it.routeStack.lastOrNull() == DesktopStarRoute.BUY_STARS) {
        it.routeStack
      } else {
        it.routeStack + DesktopStarRoute.BUY_STARS
      }
      it.copy(
        routeStack = nextStack,
        selectedPackageStars = preselectedTier,
        errorBanner = null,
        statusMessage = null,
      )
    }
    refreshSummary(silent = true)
  }

  fun openCheckoutForPackage(starsAmount: Long) {
    _state.update {
      val nextStack = if (it.routeStack.lastOrNull() == DesktopStarRoute.CHECKOUT) {
        it.routeStack
      } else {
        it.routeStack + DesktopStarRoute.CHECKOUT
      }
      it.copy(
        routeStack = nextStack,
        selectedPackageStars = starsAmount,
        currentCheckoutIdempKey = UUID.randomUUID().toString(),
        pendingCheckoutIntent = null,
        errorBanner = null,
        statusMessage = null,
      )
    }
  }

  fun selectProvider(provider: String) {
    val clean = if (provider.equals("paypal", ignoreCase = true)) "paypal" else "paystack"
    _state.update { it.copy(selectedProvider = clean, errorBanner = null) }
  }

  fun navigateBack(): Boolean {
    val cur = _state.value.routeStack
    if (cur.isEmpty()) return false
    autoVerifyJob?.cancel()
    autoVerifyJob = null
    _state.update {
      it.copy(
        routeStack = cur.dropLast(1),
        errorBanner = null,
        statusMessage = null,
      )
    }
    return true
  }

  fun closeAll() {
    autoVerifyJob?.cancel()
    autoVerifyJob = null
    _state.update {
      it.copy(
        routeStack = emptyList(),
        errorBanner = null,
        statusMessage = null,
      )
    }
  }

  fun clearErrorBanner() {
    _state.update { it.copy(errorBanner = null) }
  }

  fun consumeLaunchCheckoutUrl() {
    _state.update { it.copy(launchCheckoutUrl = null) }
  }

  fun dismissCelebration() {
    _state.update { it.copy(activeCelebration = null) }
  }

  fun refreshSummary(silent: Boolean = false) {
    val country = resolveDeviceCountryIso()
    if (!silent) {
      _state.update { it.copy(isLoadingSummary = true, errorBanner = null) }
    }
    scope.launch {
      val encoded = URLEncoder.encode(country, StandardCharsets.UTF_8)
      val res = apiClient.executeChatRaw("GET", "/v1/stars/summary?country=$encoded")
      if (res != null && res.first in 200..299) {
        val m = runCatching { DesktopJson.parseObject(res.second) }.getOrNull() ?: emptyMap()
        val wallet = parseWallet(DesktopJson.obj(m, "wallet"))
        val senders = DesktopJson.objList(m, "senders").map(::parseSender)
        val txs = DesktopJson.objList(m, "transactions").map(::parseTx)
        val intents = DesktopJson.objList(m, "pending_intents").map(::parseIntent)
        val rl = parseRateLimit(DesktopJson.obj(m, "rate_limit_status"))
        val serverIso = DesktopJson.str(m, "country_iso").ifBlank { country }
        _state.update {
          it.copy(
            wallet = wallet,
            countryIso = serverIso,
            senders = senders,
            transactions = txs,
            pendingIntents = intents,
            rateLimitStatus = rl,
            isLoadingSummary = false,
          )
        }
      } else {
        val msg = parseErrMsg(res?.first ?: 0, res?.second.orEmpty())
        _state.update {
          it.copy(
            isLoadingSummary = false,
            errorBanner = if (silent) it.errorBanner else msg,
          )
        }
      }
    }
  }

  fun sendStarsFromSheet(starsAmount: Long) {
    val cur = _state.value
    val target = cur.recipientTarget ?: return
    if (cur.isSubmittingSend) return

    if (cur.wallet.availableBalance < starsAmount) {
      openBuyStars(preselectedTier = starsAmount)
      return
    }

    val idempKey = UUID.randomUUID().toString()
    _state.update { it.copy(isSubmittingSend = true, errorBanner = null, statusMessage = null) }
    scope.launch {
      val body = buildString {
        append('{')
        append("\"idempotency_key\":\"").append(DesktopJson.escape(idempKey)).append("\",")
        append("\"recipient_user_id\":\"").append(DesktopJson.escape(target.userId)).append("\",")
        append("\"conversation_id\":\"").append(DesktopJson.escape(target.conversationId)).append("\",")
        append("\"star_amount\":").append(starsAmount)
        append('}')
      }
      val res = apiClient.executeChatRaw(
        method = "POST",
        path = "/v1/stars/send",
        jsonBody = body,
        extraHeaders = mapOf("Idempotency-Key" to idempKey),
      )
      val map = res?.second?.let { runCatching { DesktopJson.parseObject(it) }.getOrNull() } ?: emptyMap()
      val insufficient = DesktopJson.bool(map, "insufficient_balance")
      val ok = res != null && res.first in 200..299 && DesktopJson.bool(map, "success")
      if (ok) {
        val wallet = parseWallet(DesktopJson.obj(map, "wallet"))
        val tx = parseTx(DesktopJson.obj(map, "transaction"))
        val msgObj = DesktopJson.objOrNull(map, "message")
        val targetConvId = msgObj?.let { DesktopJson.str(it, "conversation_id") }.orEmpty().ifBlank { target.conversationId }
        if (targetConvId.isNotBlank()) {
          chatController.syncConversationMessages(targetConvId)
          chatController.refreshConversations(false)
        }
        val evKey = tx.txId.ifBlank { idempKey }
        _state.update {
          it.copy(
            isSubmittingSend = false,
            wallet = wallet,
            routeStack = emptyList(),
            statusMessage = "Sent ${DesktopStarsCatalog.formatComma(starsAmount)} Stars",
            activeCelebration = DesktopStarCelebrationState(
              eventKey = evKey,
              starAmount = starsAmount,
              senderName = target.fullName,
              isIncoming = false,
            ),
          )
        }
        chatController.audioEngine.playStarGiftChime(soundsEnabledProvider())
        refreshSummary(silent = true)
      } else if (insufficient) {
        _state.update { it.copy(isSubmittingSend = false) }
        openBuyStars(preselectedTier = starsAmount)
      } else {
        val err = DesktopJson.str(map, "error").ifBlank {
          parseErrMsg(res?.first ?: 0, res?.second.orEmpty())
        }
        _state.update { it.copy(isSubmittingSend = false, errorBanner = err) }
      }
    }
  }

  fun proceedToPay() {
    val cur = _state.value
    if (cur.isCreatingCheckout || cur.isVerifyingPayment) return
    val idempKey = cur.currentCheckoutIdempKey.ifBlank { UUID.randomUUID().toString() }
    val country = resolveDeviceCountryIso()
    _state.update {
      it.copy(
        isCreatingCheckout = true,
        currentCheckoutIdempKey = idempKey,
        errorBanner = null,
        statusMessage = null,
      )
    }
    scope.launch {
      val body = buildString {
        append('{')
        append("\"idempotency_key\":\"").append(DesktopJson.escape(idempKey)).append("\",")
        append("\"star_amount\":").append(cur.selectedPackageStars).append(',')
        append("\"provider\":\"").append(DesktopJson.escape(cur.selectedProvider)).append("\",")
        append("\"country_iso\":\"").append(DesktopJson.escape(country)).append("\"")
        append('}')
      }
      val res = apiClient.executeChatRaw(
        method = "POST",
        path = "/v1/stars/checkout",
        jsonBody = body,
        extraHeaders = mapOf("Idempotency-Key" to idempKey),
      )
      val map = res?.second?.let { runCatching { DesktopJson.parseObject(it) }.getOrNull() } ?: emptyMap()
      val ok = res != null && res.first in 200..299 && DesktopJson.bool(map, "success")
      if (ok) {
        val intent = parseIntent(DesktopJson.obj(map, "intent"))
        val wallet = parseWallet(DesktopJson.obj(map, "wallet"))
        if (intent.credited) {
          _state.update {
            it.copy(
              isCreatingCheckout = false,
              pendingCheckoutIntent = intent,
              wallet = wallet,
              statusMessage = "${DesktopStarsCatalog.formatComma(intent.starAmount)} Stars credited",
              routeStack = if (it.recipientTarget != null) listOf(DesktopStarRoute.SEND_STARS) else listOf(DesktopStarRoute.STARS_HUB),
            )
          }
          refreshSummary(silent = true)
        } else {
          _state.update {
            it.copy(
              isCreatingCheckout = false,
              pendingCheckoutIntent = intent,
              wallet = wallet,
              launchCheckoutUrl = intent.checkoutUrl.ifBlank { null },
              statusMessage = "Complete payment in browser, then confirm verification.",
            )
          }
          startAutoVerifyPolling(idempKey, intent.providerReference)
        }
      } else {
        val err = DesktopJson.str(map, "error").ifBlank {
          parseErrMsg(res?.first ?: 0, res?.second.orEmpty())
        }
        _state.update { it.copy(isCreatingCheckout = false, errorBanner = err) }
      }
    }
  }

  private fun startAutoVerifyPolling(idempKey: String, providerRef: String) {
    autoVerifyJob?.cancel()
    autoVerifyJob = scope.launch {
      repeat(18) {
        delay(5_000L)
        val cur = _state.value
        val intent = cur.pendingCheckoutIntent ?: return@launch
        if (intent.credited || cur.currentRoute != DesktopStarRoute.CHECKOUT) return@launch
        val ok = verifyInternal(idempKey, providerRef, silent = true)
        if (ok) return@launch
      }
    }
  }

  fun verifyPendingPayment(silent: Boolean = false) {
    val cur = _state.value
    val pending = cur.pendingCheckoutIntent ?: cur.pendingIntents.firstOrNull { !it.credited } ?: return
    val idemp = pending.idempotencyKey.ifBlank { cur.currentCheckoutIdempKey }
    if (!silent) {
      _state.update { it.copy(isVerifyingPayment = true, errorBanner = null) }
    }
    scope.launch {
      verifyInternal(idemp, pending.providerReference, silent)
    }
  }

  private fun verifyInternal(idempKey: String, providerRef: String, silent: Boolean): Boolean {
    val body = buildString {
      append('{')
      append("\"idempotency_key\":\"").append(DesktopJson.escape(idempKey)).append("\",")
      append("\"provider_reference\":\"").append(DesktopJson.escape(providerRef)).append("\"")
      append('}')
    }
    val res = apiClient.executeChatRaw("POST", "/v1/stars/payments/verify", body)
    val map = res?.second?.let { runCatching { DesktopJson.parseObject(it) }.getOrNull() } ?: emptyMap()
    val ok = res != null && res.first in 200..299 && DesktopJson.bool(map, "success")
    if (ok) {
      val intent = parseIntent(DesktopJson.obj(map, "intent"))
      val wallet = parseWallet(DesktopJson.obj(map, "wallet"))
      if (intent.credited) {
        autoVerifyJob?.cancel()
        _state.update {
          val dest = if (it.recipientTarget != null) {
            listOf(DesktopStarRoute.SEND_STARS)
          } else {
            listOf(DesktopStarRoute.STARS_HUB)
          }
          it.copy(
            isVerifyingPayment = false,
            pendingCheckoutIntent = intent,
            wallet = wallet,
            routeStack = dest,
            statusMessage = "Payment verified. ${DesktopStarsCatalog.formatComma(intent.starAmount)} Stars added!",
          )
        }
        chatController.audioEngine.playStarGiftChime(soundsEnabledProvider())
        refreshSummary(silent = true)
        return true
      } else if (!silent) {
        _state.update {
          it.copy(
            isVerifyingPayment = false,
            pendingCheckoutIntent = intent,
            wallet = wallet,
            statusMessage = "Payment status: ${intent.status.ifBlank { "pending" }}",
          )
        }
      }
    } else if (!silent) {
      val err = DesktopJson.str(map, "error").ifBlank {
        parseErrMsg(res?.first ?: 0, res?.second.orEmpty())
      }
      _state.update { it.copy(isVerifyingPayment = false, errorBanner = err) }
    }
    return false
  }

  private fun onRealtimeStarEvent(ev: DesktopChatWsEvent) {
    when (ev.event) {
      "star_balance_updated" -> {
        _state.update { cur ->
          cur.copy(
            wallet = cur.wallet.copy(
              availableBalance = if (ev.starBalanceAvailable >= 0L) ev.starBalanceAvailable else cur.wallet.availableBalance,
              totalReceived = if (ev.starBalanceReceived >= 0L) ev.starBalanceReceived else cur.wallet.totalReceived,
              updatedAtMs = System.currentTimeMillis(),
            ),
          )
        }
        refreshSummary(silent = true)
      }
      "star_gift_received" -> {
        val convName = chatController.conversations.value
          .firstOrNull { it.conversationId == ev.conversationId || it.peerUserId == ev.userId }
          ?.peerFullName.orEmpty().ifBlank { "Supporter" }
        _state.update { cur ->
          cur.copy(
            activeCelebration = DesktopStarCelebrationState(
              eventKey = ev.messageId.ifBlank { "${ev.conversationId}_${ev.timestampMs}" },
              starAmount = ev.starGiftAmount.coerceAtLeast(5L),
              senderName = convName,
              isIncoming = true,
            ),
          )
        }
        chatController.audioEngine.playStarGiftChime(soundsEnabledProvider())
        refreshSummary(silent = true)
      }
      "new_message", "message_new" -> {
        if (ev.starGiftAmount > 0L && ev.userId != apiClient.session.userId) {
          val convName = chatController.conversations.value
            .firstOrNull { it.conversationId == ev.conversationId || it.peerUserId == ev.userId }
            ?.peerFullName.orEmpty().ifBlank { "Supporter" }
          _state.update { cur ->
            cur.copy(
              activeCelebration = DesktopStarCelebrationState(
                eventKey = ev.messageId.ifBlank { "${ev.conversationId}_${ev.timestampMs}" },
                starAmount = ev.starGiftAmount,
                senderName = convName,
                isIncoming = true,
              ),
            )
          }
          chatController.audioEngine.playStarGiftChime(soundsEnabledProvider())
          refreshSummary(silent = true)
        }
      }
    }
  }

  private fun parseWallet(m: Map<String, Any?>): DesktopStarWallet = DesktopStarWallet(
    userId = DesktopJson.str(m, "user_id"),
    availableBalance = DesktopJson.long(m, "available_balance", 0L),
    totalReceived = DesktopJson.long(m, "total_received", 0L),
    totalSent = DesktopJson.long(m, "total_sent", 0L),
    updatedAtMs = DesktopJson.long(m, "updated_at_ms", 0L),
  )

  private fun parseSender(m: Map<String, Any?>): DesktopStarSender = DesktopStarSender(
    senderUserId = DesktopJson.str(m, "sender_user_id"),
    senderName = DesktopJson.str(m, "sender_name"),
    senderUsername = DesktopJson.str(m, "sender_username"),
    senderAvatar = DesktopJson.str(m, "sender_avatar"),
    totalStars = DesktopJson.long(m, "total_stars", 0L),
    giftsCount = DesktopJson.long(m, "gifts_count", 0L).toInt(),
    lastGiftedAtMs = DesktopJson.long(m, "last_gifted_at_ms", 0L),
  )

  private fun parseTx(m: Map<String, Any?>): DesktopStarTx = DesktopStarTx(
    txId = DesktopJson.str(m, "tx_id"),
    direction = DesktopJson.str(m, "direction"),
    senderUserId = DesktopJson.str(m, "sender_user_id"),
    senderName = DesktopJson.str(m, "sender_name"),
    senderUsername = DesktopJson.str(m, "sender_username"),
    senderAvatar = DesktopJson.str(m, "sender_avatar"),
    recipientUserId = DesktopJson.str(m, "recipient_user_id"),
    recipientName = DesktopJson.str(m, "recipient_name"),
    recipientUsername = DesktopJson.str(m, "recipient_username"),
    starAmount = DesktopJson.long(m, "star_amount", 0L),
    currency = DesktopJson.str(m, "currency"),
    amountMinor = DesktopJson.long(m, "amount_minor", 0L),
    provider = DesktopJson.str(m, "provider"),
    providerReference = DesktopJson.str(m, "provider_reference"),
    status = DesktopJson.str(m, "status"),
    createdAtMs = DesktopJson.long(m, "created_at_ms", 0L),
  )

  private fun parseIntent(m: Map<String, Any?>): DesktopStarPaymentIntent = DesktopStarPaymentIntent(
    intentId = DesktopJson.str(m, "intent_id"),
    idempotencyKey = DesktopJson.str(m, "idempotency_key"),
    starAmount = DesktopJson.long(m, "star_amount", 0L),
    provider = DesktopJson.str(m, "provider"),
    providerReference = DesktopJson.str(m, "provider_reference"),
    localCurrency = DesktopJson.str(m, "local_currency"),
    localAmountMinor = DesktopJson.long(m, "local_amount_minor", 0L),
    settlementCurrency = DesktopJson.str(m, "settlement_currency"),
    settlementAmountMinor = DesktopJson.long(m, "settlement_amount_minor", 0L),
    gatewayAmountStr = DesktopJson.str(m, "gateway_amount_str"),
    status = DesktopJson.str(m, "status"),
    checkoutUrl = DesktopJson.str(m, "checkout_url"),
    credited = DesktopJson.bool(m, "credited"),
    createdAtMs = DesktopJson.long(m, "created_at_ms", 0L),
    verifiedAtMs = DesktopJson.long(m, "verified_at_ms", 0L),
  )

  private fun parseRateLimit(m: Map<String, Any?>): DesktopStarRateLimit = DesktopStarRateLimit(
    hourlySentCount = DesktopJson.long(m, "hourly_sent_count", 0L).toInt(),
    hourlySentLimit = DesktopJson.long(m, "hourly_sent_limit", 20L).toInt(),
    dailySentStars = DesktopJson.long(m, "daily_sent_stars", 0L),
    dailySentLimit = DesktopJson.long(m, "daily_sent_limit", 100_000L),
    dailyPurchaseStars = DesktopJson.long(m, "daily_purchase_stars", 0L),
    dailyPurchaseLimit = DesktopJson.long(m, "daily_purchase_limit", 250_000L),
    isRestricted = DesktopJson.bool(m, "is_restricted"),
  )

  private fun parseErrMsg(status: Int, body: String): String {
    val err = runCatching { DesktopJson.str(DesktopJson.parseObject(body), "error") }.getOrDefault("")
    if (err.isNotBlank()) return err
    return when {
      status == 429 -> "Rate limit reached. Please wait a bit before sending more stars."
      status <= 0 -> "Connection unavailable. Check your network."
      else -> "Request failed ($status)"
    }
  }
}
