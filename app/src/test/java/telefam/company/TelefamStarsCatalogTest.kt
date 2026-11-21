package telefam.company

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import telefam.company.stars.TelefamStarsCatalog

class TelefamStarsCatalogTest {
  @Test
  fun `star catalog exposes exact twelve tiers matching design`() {
    assertEquals(
      listOf(
        5L, 10L, 20L, 50L, 100L, 200L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 50_000L,
      ),
      TelefamStarsCatalog.TIERS,
    )
  }

  @Test
  fun `client currency conversion formats prices per user country`() {
    assertEquals("Ksh 5", TelefamStarsCatalog.formatConvertedPrice("KE", 5L))
    assertEquals("Ksh 1,000", TelefamStarsCatalog.formatConvertedPrice("ke", 1_000L))
    assertEquals("₦60", TelefamStarsCatalog.formatConvertedPrice("NG", 5L))
    assertEquals("$0.05", TelefamStarsCatalog.formatConvertedPrice("US", 5L))
    assertEquals("$1", TelefamStarsCatalog.formatConvertedPrice("US", 100L))
    assertEquals("£0.80", TelefamStarsCatalog.formatConvertedPrice("GB", 100L))
    assertEquals("¥150", TelefamStarsCatalog.formatConvertedPrice("JP", 100L))
    // Unknown country falls back to USD
    assertEquals("$0.05", TelefamStarsCatalog.formatConvertedPrice("ZZ", 5L))
  }

  @Test
  fun `gateway settlement note shows USD disclosure only for indirect currencies`() {
    assertNull(TelefamStarsCatalog.gatewaySettlementNote("KE", "paystack", 100L))
    assertNull(TelefamStarsCatalog.gatewaySettlementNote("US", "paypal", 100L))
    assertNull(TelefamStarsCatalog.gatewaySettlementNote("GB", "paypal", 100L))
    assertEquals(
      "Settled as $1 USD via PayPal",
      TelefamStarsCatalog.gatewaySettlementNote("KE", "paypal", 100L),
    )
    assertEquals(
      "Settled as $0.05 USD via PayPal",
      TelefamStarsCatalog.gatewaySettlementNote("KE", "paypal", 5L),
    )
  }
}
