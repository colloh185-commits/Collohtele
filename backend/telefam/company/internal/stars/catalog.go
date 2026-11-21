package stars

import (
	"fmt"
	"math"
	"strings"
)

// Exact 12 star tiers matching Telefam Send Stars & Buy Stars UI.
var AllowedStarTiers = []int64{
	5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 50000,
}

func IsValidStarTier(amount int64) bool {
	for _, t := range AllowedStarTiers {
		if t == amount {
			return true
		}
	}
	return false
}

type CountryCurrencyRule struct {
	Currency      string
	Symbol        string
	RatePerStar   float64 // Display/major currency unit per 1 star (KE = 1.00 KES -> 5 Stars = Ksh 5)
	Decimals      int
	MinorFactor   int64 // 100 for cents/kobo, 1 for zero-decimal currencies
}

var currencyRulesByCode = map[string]CountryCurrencyRule{
	"KES": {Currency: "KES", Symbol: "Ksh", RatePerStar: 1.00, Decimals: 0, MinorFactor: 100},
	"NGN": {Currency: "NGN", Symbol: "₦", RatePerStar: 12.00, Decimals: 0, MinorFactor: 100},
	"GHS": {Currency: "GHS", Symbol: "GH₵", RatePerStar: 0.12, Decimals: 2, MinorFactor: 100},
	"ZAR": {Currency: "ZAR", Symbol: "R", RatePerStar: 0.15, Decimals: 2, MinorFactor: 100},
	"UGX": {Currency: "UGX", Symbol: "UGX", RatePerStar: 28.00, Decimals: 0, MinorFactor: 1},
	"TZS": {Currency: "TZS", Symbol: "TZS", RatePerStar: 20.00, Decimals: 0, MinorFactor: 1},
	"RWF": {Currency: "RWF", Symbol: "RWF", RatePerStar: 10.00, Decimals: 0, MinorFactor: 1},
	"USD": {Currency: "USD", Symbol: "$", RatePerStar: 0.01, Decimals: 2, MinorFactor: 100},
	"EUR": {Currency: "EUR", Symbol: "€", RatePerStar: 0.009, Decimals: 2, MinorFactor: 100},
	"GBP": {Currency: "GBP", Symbol: "£", RatePerStar: 0.008, Decimals: 2, MinorFactor: 100},
	"CAD": {Currency: "CAD", Symbol: "CA$", RatePerStar: 0.014, Decimals: 2, MinorFactor: 100},
	"AUD": {Currency: "AUD", Symbol: "A$", RatePerStar: 0.015, Decimals: 2, MinorFactor: 100},
	"JPY": {Currency: "JPY", Symbol: "¥", RatePerStar: 1.50, Decimals: 0, MinorFactor: 1},
	"INR": {Currency: "INR", Symbol: "₹", RatePerStar: 0.85, Decimals: 2, MinorFactor: 100},
	"AED": {Currency: "AED", Symbol: "AED", RatePerStar: 0.037, Decimals: 2, MinorFactor: 100},
	"SAR": {Currency: "SAR", Symbol: "SAR", RatePerStar: 0.038, Decimals: 2, MinorFactor: 100},
	"BRL": {Currency: "BRL", Symbol: "R$", RatePerStar: 0.055, Decimals: 2, MinorFactor: 100},
	"MXN": {Currency: "MXN", Symbol: "MX$", RatePerStar: 0.20, Decimals: 2, MinorFactor: 100},
}

var countryToCurrencyCode = map[string]string{
	"KE": "KES", "NG": "NGN", "GH": "GHS", "ZA": "ZAR",
	"UG": "UGX", "TZ": "TZS", "RW": "RWF",
	"US": "USD", "GB": "GBP", "UK": "GBP", "CA": "CAD", "AU": "AUD",
	"JP": "JPY", "IN": "INR", "AE": "AED", "SA": "SAR", "BR": "BRL", "MX": "MXN",
	"DE": "EUR", "FR": "EUR", "IT": "EUR", "ES": "EUR", "NL": "EUR", "BE": "EUR",
	"IE": "EUR", "PT": "EUR", "AT": "EUR", "FI": "EUR", "GR": "EUR", "LU": "EUR",
}

func RuleForCountry(countryISO string) CountryCurrencyRule {
	code := countryToCurrencyCode[strings.ToUpper(strings.TrimSpace(countryISO))]
	if rule, ok := currencyRulesByCode[code]; ok {
		return rule
	}
	return currencyRulesByCode["USD"]
}

// AuthoritativeGatewayQuote computes server-enforced payment gateway currency and integer minor amount.
func AuthoritativeGatewayQuote(countryISO, provider string, starAmount int64) (currency string, amountMinor int64, display string, paypalDecimal string) {
	local := RuleForCountry(countryISO)
	targetCode := local.Currency

	switch strings.ToLower(strings.TrimSpace(provider)) {
	case "paystack":
		switch targetCode {
		case "KES", "NGN", "GHS", "ZAR", "USD":
			// Paystack supports native settlement in these currencies (always x100 kobo/cents)
		default:
			targetCode = "USD"
		}
	case "paypal":
		switch targetCode {
		case "USD", "EUR", "GBP", "CAD", "AUD", "JPY", "BRL", "MXN":
			// Directly supported by PayPal Orders v2
		default:
			// Countries such as KE/NG/GH/ZA settle cleanly in USD on PayPal while showing local equivalent
			targetCode = "USD"
		}
	default:
		targetCode = "USD"
	}

	gwRule, ok := currencyRulesByCode[targetCode]
	if !ok {
		gwRule = currencyRulesByCode["USD"]
	}
	majorVal := float64(starAmount) * gwRule.RatePerStar
	minor := int64(math.Round(majorVal * float64(gwRule.MinorFactor)))
	if minor < 1 {
		minor = 1
	}

	if gwRule.MinorFactor == 1 {
		paypalDecimal = fmt.Sprintf("%d", minor)
	} else {
		paypalDecimal = fmt.Sprintf("%.2f", float64(minor)/float64(gwRule.MinorFactor))
	}

	display = FormatCurrencyAmount(gwRule, majorVal)
	return gwRule.Currency, minor, display, paypalDecimal
}

func BuildPackagesForCountry(countryISO string) []StarPackageDTO {
	rule := RuleForCountry(countryISO)
	out := make([]StarPackageDTO, 0, len(AllowedStarTiers))
	for _, tier := range AllowedStarTiers {
		majorVal := float64(tier) * rule.RatePerStar
		minor := int64(math.Round(majorVal * float64(rule.MinorFactor)))
		if minor < 1 {
			minor = 1
		}
		out = append(out, StarPackageDTO{
			StarAmount:     tier,
			IsPopular:      tier == 5,
			LocalCurrency:  rule.Currency,
			CurrencySymbol: rule.Symbol,
			LocalMinorUnit: minor,
			LocalDecimals:  rule.Decimals,
			LocalDisplay:   FormatCurrencyAmount(rule, majorVal),
		})
	}
	return out
}

func FormatCurrencyAmount(rule CountryCurrencyRule, major float64) string {
	rounded := math.Round(major*100) / 100
	if rule.Decimals == 0 || math.Abs(rounded-math.Round(rounded)) < 0.001 {
		intVal := int64(math.Round(rounded))
		formattedInt := formatCommaInt(intVal)
		if len(rule.Symbol) > 1 && rule.Symbol[len(rule.Symbol)-1] != '$' {
			return rule.Symbol + " " + formattedInt
		}
		return rule.Symbol + formattedInt
	}
	formattedDec := fmt.Sprintf("%.2f", rounded)
	if len(rule.Symbol) > 1 && rule.Symbol[len(rule.Symbol)-1] != '$' {
		return rule.Symbol + " " + formattedDec
	}
	return rule.Symbol + formattedDec
}

func formatCommaInt(n int64) string {
	s := fmt.Sprintf("%d", n)
	if len(s) <= 3 {
		return s
	}
	var b strings.Builder
	rem := len(s) % 3
	if rem > 0 {
		b.WriteString(s[:rem])
		b.WriteByte(',')
	}
	for i := rem; i < len(s); i += 3 {
		b.WriteString(s[i : i+3])
		if i+3 < len(s) {
			b.WriteByte(',')
		}
	}
	return b.String()
}
