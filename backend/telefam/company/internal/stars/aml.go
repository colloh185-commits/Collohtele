package stars

import (
	"sync"
	"time"
)

const (
	MaxGiftBurstPerMinute        = 6
	MaxGiftSendsPerHour          = 25
	MaxGiftStarsPerHour    int64 = 100_000
	MaxGiftStarsPerDay     int64 = 250_000
	MaxPairGiftsPerHour          = 10
	MaxPairStarsPerHour    int64 = 60_000
	MaxCheckoutsPer15Min         = 10
	MaxPurchaseStarsPerDay int64 = 300_000
)

type starEventRecord struct {
	At     time.Time
	Amount int64
}

type AmlDecision struct {
	Allowed           bool
	Reason            string
	Message           string
	RetryAfterSeconds int64
}

type AmlLimiter struct {
	mu           sync.Mutex
	sendsByUser  map[string][]starEventRecord
	sendsByDev   map[string][]starEventRecord
	sendsByIP    map[string][]starEventRecord
	sendsByPair  map[string][]starEventRecord
	buysByUser   map[string][]starEventRecord
	buysByDev    map[string][]starEventRecord
	nowFunc      func() time.Time
}

func NewAmlLimiter() *AmlLimiter {
	return &AmlLimiter{
		sendsByUser: make(map[string][]starEventRecord),
		sendsByDev:  make(map[string][]starEventRecord),
		sendsByIP:   make(map[string][]starEventRecord),
		sendsByPair: make(map[string][]starEventRecord),
		buysByUser:  make(map[string][]starEventRecord),
		buysByDev:   make(map[string][]starEventRecord),
		nowFunc:     time.Now,
	}
}

func pruneEvents(records []starEventRecord, cutoff time.Time) []starEventRecord {
	out := records[:0]
	for _, r := range records {
		if r.At.After(cutoff) {
			out = append(out, r)
		}
	}
	return out
}

func windowStats(records []starEventRecord, since time.Time) (count int, sum int64) {
	for _, r := range records {
		if r.At.After(since) {
			count++
			sum += r.Amount
		}
	}
	return count, sum
}

// CheckAndRecordSend enforces anti-money laundering velocity and structuring limits on star gifts.
func (a *AmlLimiter) CheckAndRecordSend(userID, deviceFP, ipFP, recipientID string, amount int64) AmlDecision {
	a.mu.Lock()
	defer a.mu.Unlock()

	now := a.nowFunc()
	dayAgo := now.Add(-24 * time.Hour)
	hourAgo := now.Add(-1 * time.Hour)
	minAgo := now.Add(-1 * time.Minute)
	pairKey := userID + "|" + recipientID

	a.sendsByUser[userID] = pruneEvents(a.sendsByUser[userID], dayAgo)
	a.sendsByDev[deviceFP] = pruneEvents(a.sendsByDev[deviceFP], dayAgo)
	a.sendsByIP[ipFP] = pruneEvents(a.sendsByIP[ipFP], dayAgo)
	a.sendsByPair[pairKey] = pruneEvents(a.sendsByPair[pairKey], dayAgo)

	if cMin, _ := windowStats(a.sendsByUser[userID], minAgo); cMin >= MaxGiftBurstPerMinute {
		return AmlDecision{
			Allowed:           false,
			Reason:            "velocity_burst_minute",
			Message:           "Too many star gifts sent in a short window. Please wait a minute.",
			RetryAfterSeconds: 60,
		}
	}
	if dMin, _ := windowStats(a.sendsByDev[deviceFP], minAgo); deviceFP != "" && dMin >= MaxGiftBurstPerMinute {
		return AmlDecision{
			Allowed:           false,
			Reason:            "device_burst_minute",
			Message:           "Device transfer rate limit reached. Please wait a minute.",
			RetryAfterSeconds: 60,
		}
	}
	if iMin, _ := windowStats(a.sendsByIP[ipFP], minAgo); ipFP != "" && iMin >= MaxGiftBurstPerMinute*2 {
		return AmlDecision{
			Allowed:           false,
			Reason:            "ip_burst_minute",
			Message:           "Network transfer rate limit reached. Please try shortly.",
			RetryAfterSeconds: 60,
		}
	}

	cHour, sumHour := windowStats(a.sendsByUser[userID], hourAgo)
	if cHour >= MaxGiftSendsPerHour {
		return AmlDecision{
			Allowed:           false,
			Reason:            "hourly_tx_count_cap",
			Message:           "Hourly gift transfer limit reached for account safety.",
			RetryAfterSeconds: 900,
		}
	}
	if sumHour+amount > MaxGiftStarsPerHour {
		return AmlDecision{
			Allowed:           false,
			Reason:            "hourly_volume_cap",
			Message:           "Hourly star transfer limit reached. Please try again later.",
			RetryAfterSeconds: 1800,
		}
	}

	_, sumDay := windowStats(a.sendsByUser[userID], dayAgo)
	if sumDay+amount > MaxGiftStarsPerDay {
		return AmlDecision{
			Allowed:           false,
			Reason:            "daily_volume_cap",
			Message:           "Daily star gifting limit reached for compliance protection.",
			RetryAfterSeconds: 3600,
		}
	}

	pairCount, pairSum := windowStats(a.sendsByPair[pairKey], hourAgo)
	if pairCount >= MaxPairGiftsPerHour || pairSum+amount > MaxPairStarsPerHour {
		return AmlDecision{
			Allowed:           false,
			Reason:            "pair_structuring_guard",
			Message:           "Repeated high-volume transfers to the same account are temporarily cooled down.",
			RetryAfterSeconds: 1800,
		}
	}

	rec := starEventRecord{At: now, Amount: amount}
	a.sendsByUser[userID] = append(a.sendsByUser[userID], rec)
	if deviceFP != "" {
		a.sendsByDev[deviceFP] = append(a.sendsByDev[deviceFP], rec)
	}
	if ipFP != "" {
		a.sendsByIP[ipFP] = append(a.sendsByIP[ipFP], rec)
	}
	a.sendsByPair[pairKey] = append(a.sendsByPair[pairKey], rec)

	return AmlDecision{Allowed: true}
}

// CheckAndRecordCheckout enforces purchase velocity and daily purchase volume caps.
func (a *AmlLimiter) CheckAndRecordCheckout(userID, deviceFP string, amount int64) AmlDecision {
	a.mu.Lock()
	defer a.mu.Unlock()

	now := a.nowFunc()
	dayAgo := now.Add(-24 * time.Hour)
	win15m := now.Add(-15 * time.Minute)

	a.buysByUser[userID] = pruneEvents(a.buysByUser[userID], dayAgo)
	a.buysByDev[deviceFP] = pruneEvents(a.buysByDev[deviceFP], dayAgo)

	if cnt15, _ := windowStats(a.buysByUser[userID], win15m); cnt15 >= MaxCheckoutsPer15Min {
		return AmlDecision{
			Allowed:           false,
			Reason:            "checkout_burst_15m",
			Message:           "Too many star purchase attempts. Please wait a few minutes.",
			RetryAfterSeconds: 300,
		}
	}
	if deviceFP != "" {
		if d15, _ := windowStats(a.buysByDev[deviceFP], win15m); d15 >= MaxCheckoutsPer15Min {
			return AmlDecision{
				Allowed:           false,
				Reason:            "device_checkout_burst_15m",
				Message:           "Device purchase rate limit reached. Please wait a few minutes.",
				RetryAfterSeconds: 300,
			}
		}
	}
	_, dayVol := windowStats(a.buysByUser[userID], dayAgo)
	if dayVol+amount > MaxPurchaseStarsPerDay {
		return AmlDecision{
			Allowed:           false,
			Reason:            "daily_purchase_volume_cap",
			Message:           "Daily star purchase limit reached for compliance protection.",
			RetryAfterSeconds: 3600,
		}
	}

	rec := starEventRecord{At: now, Amount: amount}
	a.buysByUser[userID] = append(a.buysByUser[userID], rec)
	if deviceFP != "" {
		a.buysByDev[deviceFP] = append(a.buysByDev[deviceFP], rec)
	}
	return AmlDecision{Allowed: true}
}

func (a *AmlLimiter) SnapshotForUser(userID string) StarRateLimitStatusDTO {
	a.mu.Lock()
	defer a.mu.Unlock()

	now := a.nowFunc()
	dayAgo := now.Add(-24 * time.Hour)
	hourAgo := now.Add(-1 * time.Hour)

	_, hSent := windowStats(a.sendsByUser[userID], hourAgo)
	_, dSent := windowStats(a.sendsByUser[userID], dayAgo)
	_, dBought := windowStats(a.buysByUser[userID], dayAgo)

	return StarRateLimitStatusDTO{
		HourlySentStars:     hSent,
		HourlySentLimit:     MaxGiftStarsPerHour,
		DailySentStars:      dSent,
		DailySentLimit:      MaxGiftStarsPerDay,
		DailyPurchasedStars: dBought,
		DailyPurchasedLimit: MaxPurchaseStarsPerDay,
	}
}
