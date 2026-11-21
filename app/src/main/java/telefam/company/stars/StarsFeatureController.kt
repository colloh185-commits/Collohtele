package telefam.company.stars

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import telefam.company.chat.ChatSoundEffects
import telefam.company.data.local.ChatConversationEntity
import telefam.company.data.repository.TelefamRepository
import telefam.company.ui.viewmodel.TelefamUiState
import java.util.Locale
import java.util.UUID

/**
 * Coordinates Telefam Stars flows: Send Stars (from 1-on-1 inbox), Buy Stars + Idempotent
 * Paystack / PayPal Checkout, Profile Menu Stars Hub, and Real-Time WebSocket celebrations.
 */
class StarsFeatureController(
  private val scope: CoroutineScope,
  private val repository: TelefamRepository,
  private val uiStateFlow: MutableStateFlow<TelefamUiState>,
) {
  private val chatRepo = repository.chatRepository
  private val celebratedKeys = mutableSetOf<String>()

  init {
    // Load initial Stars balance & history when authenticated
    scope.launch {
      uiStateFlow
        .map { it.isAuthenticated to it.userId }
        .distinctUntilChanged()
        .collect { (authed, uid) ->
          if (authed && uid.isNotBlank()) {
            refreshSummary()
          } else {
            celebratedKeys.clear()
            updateStars { TelefamStarsUiState(countryIso = resolveCountryIso()) }
          }
        }
    }

    // Listen to real-time WebSocket Star wallet updates and incoming verified Star gift celebrations
    scope.launch {
      chatRepo.wsEvents.collect { ev ->
        val ownerUid = uiStateFlow.value.userId
        when (ev.event) {
          "star_balance_updated" -> {
            updateStars { s ->
              val updatedWallet = s.wallet.copy(
                availableBalance = if (ev.starBalanceAvailable >= 0L) ev.starBalanceAvailable else s.wallet.availableBalance,
                totalReceived = if (ev.starBalanceReceived >= 0L) ev.starBalanceReceived else s.wallet.totalReceived,
                updatedAtMs = System.currentTimeMillis(),
              )
              s.copy(wallet = updatedWallet)
            }
            refreshSummary()
          }

          "star_gift_received" -> {
            if (ev.starGiftAmount > 0L) {
              val key = ev.messageId.ifBlank { "ws_${ev.timestampMs}_${ev.starGiftAmount}" }
              triggerCelebration(
                eventKey = key,
                starAmount = ev.starGiftAmount,
                senderName = resolvePeerDisplayName(ev.userId, ev.conversationId),
                isIncoming = ev.userId != ownerUid,
                conversationId = ev.conversationId,
              )
              refreshSummary()
            }
          }

          "new_message", "message_new" -> {
            if (ev.starGiftAmount > 0L) {
              val key = ev.messageId.ifBlank { "msg_${ev.timestampMs}_${ev.starGiftAmount}" }
              triggerCelebration(
                eventKey = key,
                starAmount = ev.starGiftAmount,
                senderName = resolvePeerDisplayName(ev.userId, ev.conversationId),
                isIncoming = ev.userId != ownerUid,
                conversationId = ev.conversationId,
              )
              if (ev.conversationId.isNotBlank()) {
                chatRepo.loadConversationMessages(ev.conversationId)
              }
              refreshSummary()
            }
          }
        }
      }
    }
  }

  private inline fun updateStars(crossinline transform: (TelefamStarsUiState) -> TelefamStarsUiState) {
    uiStateFlow.update { cur ->
      cur.copy(starsState = transform(cur.starsState))
    }
  }

  private fun resolveCountryIso(): String {
    val snap = uiStateFlow.value
    return snap.ownProfileState?.countryIso?.trim()?.takeIf { it.length == 2 }?.uppercase(Locale.US)
      ?: snap.profileForm.selectedCountry.iso.trim().takeIf { it.length == 2 }?.uppercase(Locale.US)
      ?: Locale.getDefault().country.trim().takeIf { it.length == 2 }?.uppercase(Locale.US)
      ?: "US"
  }

  private fun resolvePeerDisplayName(userId: String, conversationId: String): String {
    val conv = uiStateFlow.value.chatState.conversations.firstOrNull {
      (conversationId.isNotBlank() && it.conversationId == conversationId) ||
        (userId.isNotBlank() && it.peerUserId == userId)
    }
    return conv?.peerFullName?.ifBlank { conv.peerUsername } ?: ""
  }

  private fun triggerCelebration(
    eventKey: String,
    starAmount: Long,
    senderName: String,
    isIncoming: Boolean,
    conversationId: String,
  ) {
    if (!celebratedKeys.add(eventKey)) return
    val conv = uiStateFlow.value.chatState.conversations.firstOrNull { it.conversationId == conversationId }
    val notif = uiStateFlow.value.settings.notifications
    val allowSound = !(conv?.isMuted == true) && !notif.masterPause && notif.typeMessages
    ChatSoundEffects.playStarGiftChime(allowSound)
    updateStars {
      it.copy(
        activeCelebration = StarCelebrationState(
          eventKey = eventKey,
          starAmount = starAmount,
          senderName = senderName,
          isIncoming = isIncoming,
        ),
      )
    }
  }

  fun dismissCelebration() {
    updateStars { it.copy(activeCelebration = null) }
  }

  fun refreshSummary() {
    val country = resolveCountryIso()
    updateStars { it.copy(isLoadingSummary = true, countryIso = country) }
    scope.launch {
      chatRepo.fetchStarsSummary(country)
        .onSuccess { dto ->
          updateStars { s ->
            val pending = dto.pendingIntents.firstOrNull {
              s.pendingCheckoutIntent != null && it.providerReference == s.pendingCheckoutIntent.providerReference
            } ?: s.pendingCheckoutIntent
            s.copy(
              countryIso = dto.countryIso.ifBlank { country },
              wallet = dto.wallet,
              senders = dto.senders,
              transactions = dto.transactions,
              pendingIntents = dto.pendingIntents,
              pendingCheckoutIntent = pending,
              rateLimitStatus = dto.rateLimitStatus,
              isLoadingSummary = false,
            )
          }
        }
        .onFailure { err ->
          updateStars { s ->
            s.copy(
              isLoadingSummary = false,
              errorBanner = if (s.currentRoute != null) err.message ?: "Unable to sync Stars" else s.errorBanner,
            )
          }
        }
    }
  }

  fun openSendStarsForConversation(conversation: ChatConversationEntity) {
    val country = resolveCountryIso()
    val target = StarGiftRecipientTarget(
      userId = conversation.peerUserId,
      fullName = conversation.peerFullName.ifBlank { conversation.peerUsername.ifBlank { "Telefam" } },
      username = conversation.peerUsername,
      avatarUrl = conversation.peerAvatarUrl,
      isOnline = conversation.showOnlineIndicator && conversation.isOnline,
      conversationId = conversation.conversationId,
    )
    updateStars {
      it.copy(
        routeStack = listOf(StarScreenRoute.SEND_STARS),
        recipientTarget = target,
        countryIso = country,
        errorBanner = null,
        statusMessage = null,
      )
    }
    refreshSummary()
  }

  fun openStarsHubFromMenu() {
    val country = resolveCountryIso()
    updateStars {
      it.copy(
        routeStack = listOf(StarScreenRoute.STARS_HUB),
        countryIso = country,
        errorBanner = null,
        statusMessage = null,
      )
    }
    refreshSummary()
  }

  fun openBuyStars(suggestedStars: Long? = null) {
    val country = resolveCountryIso()
    updateStars { s ->
      val nextStack = if (s.routeStack.lastOrNull() == StarScreenRoute.BUY_STARS) {
        s.routeStack
      } else {
        s.routeStack + StarScreenRoute.BUY_STARS
      }
      s.copy(
        routeStack = nextStack,
        countryIso = country,
        selectedPackageStars = suggestedStars ?: s.selectedPackageStars,
        errorBanner = null,
      )
    }
  }

  fun selectPackageForCheckout(starAmount: Long) {
    val country = resolveCountryIso()
    updateStars { s ->
      val nextStack = if (s.routeStack.lastOrNull() == StarScreenRoute.CHECKOUT) {
        s.routeStack
      } else {
        s.routeStack + StarScreenRoute.CHECKOUT
      }
      s.copy(
        routeStack = nextStack,
        countryIso = country,
        selectedPackageStars = starAmount,
        pendingCheckoutIntent = null,
        currentCheckoutIdempKey = "chk_${UUID.randomUUID()}",
        errorBanner = null,
      )
    }
  }

  fun selectProvider(provider: String) {
    val clean = if (provider.equals("paypal", ignoreCase = true)) "paypal" else "paystack"
    updateStars { s ->
      if (s.selectedProvider == clean) {
        s
      } else {
        s.copy(
          selectedProvider = clean,
          pendingCheckoutIntent = null,
          currentCheckoutIdempKey = "chk_${UUID.randomUUID()}",
          errorBanner = null,
        )
      }
    }
  }

  fun proceedToPay() {
    val snap = uiStateFlow.value.starsState
    if (snap.isCreatingCheckout || snap.isVerifyingPayment) return
    val country = resolveCountryIso()
    val idempKey = snap.currentCheckoutIdempKey.ifBlank { "chk_${UUID.randomUUID()}" }

    updateStars {
      it.copy(
        isCreatingCheckout = true,
        currentCheckoutIdempKey = idempKey,
        countryIso = country,
        errorBanner = null,
      )
    }

    scope.launch {
      chatRepo.createStarCheckout(
        idempotencyKey = idempKey,
        starAmount = snap.selectedPackageStars,
        provider = snap.selectedProvider,
        countryIso = country,
      ).onSuccess { resp ->
        updateStars { s ->
          s.copy(
            isCreatingCheckout = false,
            pendingCheckoutIntent = resp.intent,
            wallet = resp.wallet,
            launchCheckoutUrl = resp.intent.checkoutUrl.takeIf { it.isNotBlank() },
            errorBanner = if (resp.intent.checkoutUrl.isBlank()) "Checkout session created; verify once completed." else null,
          )
        }
      }.onFailure { err ->
        updateStars { s ->
          s.copy(
            isCreatingCheckout = false,
            errorBanner = err.message ?: "Unable to initialize payment",
          )
        }
      }
    }
  }

  fun consumeLaunchCheckoutUrl() {
    updateStars { it.copy(launchCheckoutUrl = null) }
  }

  fun verifyPendingPayment(referenceOverride: String? = null) {
    val snap = uiStateFlow.value.starsState
    if (snap.isVerifyingPayment) return
    val pending = snap.pendingCheckoutIntent
    val idemp = pending?.idempotencyKey?.ifBlank { snap.currentCheckoutIdempKey } ?: snap.currentCheckoutIdempKey
    val ref = referenceOverride?.trim()?.takeIf { it.isNotBlank() } ?: pending?.providerReference.orEmpty()
    if (idemp.isBlank() && ref.isBlank()) return

    updateStars { it.copy(isVerifyingPayment = true, errorBanner = null) }
    scope.launch {
      chatRepo.verifyStarPayment(
        idempotencyKey = idemp,
        providerReference = ref,
      ).onSuccess { resp ->
        val credited = resp.intent.credited
        updateStars { s ->
          val nextStack = if (credited && s.routeStack.contains(StarScreenRoute.SEND_STARS)) {
            listOf(StarScreenRoute.SEND_STARS)
          } else if (credited && s.currentRoute == StarScreenRoute.CHECKOUT) {
            s.routeStack.dropLastWhile { it == StarScreenRoute.CHECKOUT || it == StarScreenRoute.BUY_STARS }
          } else {
            s.routeStack
          }
          s.copy(
            isVerifyingPayment = false,
            wallet = resp.wallet,
            pendingCheckoutIntent = resp.intent,
            routeStack = nextStack,
            currentCheckoutIdempKey = if (credited) "chk_${UUID.randomUUID()}" else s.currentCheckoutIdempKey,
            errorBanner = if (!credited && resp.intent.status == "failed") {
              "Payment verification failed"
            } else if (!credited) {
              "Payment is still ${resp.intent.status}. Complete checkout then tap Verify."
            } else {
              null
            },
            statusMessage = if (credited) {
              "${TelefamStarsCatalog.formatComma(resp.intent.starAmount)} Stars added to your balance"
            } else {
              s.statusMessage
            },
          )
        }
        if (credited) {
          refreshSummary()
        }
      }.onFailure { err ->
        updateStars { s ->
          s.copy(
            isVerifyingPayment = false,
            errorBanner = err.message ?: "Unable to verify payment yet",
          )
        }
      }
    }
  }

  fun handleCheckoutCallback(status: String?, reference: String?) {
    val cleanStatus = status?.trim()?.lowercase(Locale.US).orEmpty()
    if (cleanStatus == "cancelled" || cleanStatus == "canceled") {
      updateStars { it.copy(errorBanner = "Checkout cancelled") }
      return
    }
    if (uiStateFlow.value.starsState.pendingCheckoutIntent != null || !reference.isNullOrBlank()) {
      verifyPendingPayment(reference)
    }
  }

  fun sendStarsTier(starAmount: Long) {
    val snap = uiStateFlow.value.starsState
    val target = snap.recipientTarget ?: return
    if (snap.isSubmittingSend) return

    if (snap.wallet.availableBalance < starAmount) {
      openBuyStars(starAmount)
      return
    }

    val idempKey = "gift_${UUID.randomUUID()}"
    updateStars { it.copy(isSubmittingSend = true, errorBanner = null) }

    scope.launch {
      chatRepo.sendStarGift(
        idempotencyKey = idempKey,
        recipientUserId = target.userId,
        conversationId = target.conversationId,
        starAmount = starAmount,
      ).onSuccess { resp ->
        if (resp.insufficientBalance || !resp.success) {
          updateStars { it.copy(isSubmittingSend = false, wallet = resp.wallet) }
          openBuyStars(starAmount)
        } else {
          val msgId = resp.message?.messageId.orEmpty().ifBlank { "sent_${UUID.randomUUID()}" }
          updateStars {
            it.copy(
              isSubmittingSend = false,
              wallet = resp.wallet,
              routeStack = emptyList(),
            )
          }
          triggerCelebration(
            eventKey = msgId,
            starAmount = starAmount,
            senderName = target.fullName,
            isIncoming = false,
            conversationId = target.conversationId,
          )
          refreshSummary()
        }
      }.onFailure { err ->
        updateStars { s ->
          s.copy(
            isSubmittingSend = false,
            errorBanner = err.message ?: "Unable to send stars",
          )
        }
      }
    }
  }

  fun navigateBack() {
    updateStars { s ->
      if (s.routeStack.isEmpty()) s
      else s.copy(routeStack = s.routeStack.dropLast(1), errorBanner = null)
    }
  }

  fun clearError() {
    updateStars { it.copy(errorBanner = null) }
  }

  fun clearStatusMessage() {
    updateStars { it.copy(statusMessage = null) }
  }
}
