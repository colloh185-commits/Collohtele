package telefam.company.stars

import telefam.company.data.network.StarPaymentIntentDto
import telefam.company.data.network.StarRateLimitStatusDto
import telefam.company.data.network.StarSenderSummaryDto
import telefam.company.data.network.StarTransactionDto
import telefam.company.data.network.StarWalletDto
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object TelefamStarsCatalog {
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

  /**
   * Client-side currency conversion derived from user's country ISO code.
   */
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

enum class StarScreenRoute {
  STARS_HUB,
  SEND_STARS,
  BUY_STARS,
  CHECKOUT,
}

data class StarGiftRecipientTarget(
  val userId: String,
  val fullName: String,
  val username: String,
  val avatarUrl: String,
  val isOnline: Boolean,
  val conversationId: String,
)

data class StarCelebrationState(
  val eventKey: String,
  val starAmount: Long,
  val senderName: String,
  val isIncoming: Boolean,
)

data class TelefamStarsUiState(
  val routeStack: List<StarScreenRoute> = emptyList(),
  val wallet: StarWalletDto = StarWalletDto(),
  val countryIso: String = Locale.getDefault().country.ifBlank { "US" },
  val senders: List<StarSenderSummaryDto> = emptyList(),
  val transactions: List<StarTransactionDto> = emptyList(),
  val pendingIntents: List<StarPaymentIntentDto> = emptyList(),
  val rateLimitStatus: StarRateLimitStatusDto = StarRateLimitStatusDto(),
  val isLoadingSummary: Boolean = false,
  val isSubmittingSend: Boolean = false,
  val isCreatingCheckout: Boolean = false,
  val isVerifyingPayment: Boolean = false,
  val recipientTarget: StarGiftRecipientTarget? = null,
  val selectedPackageStars: Long = 100L,
  val selectedProvider: String = "paystack",
  val pendingCheckoutIntent: StarPaymentIntentDto? = null,
  val currentCheckoutIdempKey: String = "",
  val launchCheckoutUrl: String? = null,
  val errorBanner: String? = null,
  val statusMessage: String? = null,
  val activeCelebration: StarCelebrationState? = null,
) {
  val currentRoute: StarScreenRoute?
    get() = routeStack.lastOrNull()
}
