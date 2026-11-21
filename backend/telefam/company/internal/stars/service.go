package stars

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"telefam.company/backend/internal/chat"
)

var (
	ErrBadRequest          = errors.New("invalid stars request")
	ErrForbidden           = errors.New("stars action not permitted")
	ErrNotFound            = errors.New("stars record not found")
	ErrIdempotencyConflict = errors.New("idempotency key reused with mismatched parameters")
	ErrPaymentPending      = errors.New("payment is still pending confirmation")
)

type AmlError struct {
	Decision AmlDecision
}

func (e *AmlError) Error() string {
	return e.Decision.Message
}

type UserProfileView struct {
	UserID     string
	Email      string
	FullName   string
	Username   string
	AvatarURL  string
	CountryISO string
}

type HostHooks struct {
	ResolveUser func(userID string) (UserProfileView, bool)
	IsBlocked   func(a, b string) bool
	ChatService func() *chat.Service
	AuditRecord func(userID, event, deviceFP, ipFP string, meta map[string]string)
	PublicBase  string
}

type storedIntent struct {
	DTO       StarPaymentIntentDTO
	UserID    string
	PayPalDec string
}

type storedWebhook struct {
	Provider   string
	EventID    string
	PayloadSHA string
	ReceivedMs int64
}

type Service struct {
	hooks               HostHooks
	Gateways            *PaymentGateways
	Aml                 *AmlLimiter
	mu                  sync.Mutex
	wallets             map[string]*StarWalletDTO
	intents             map[string]*storedIntent
	intentsByRef        map[string]*storedIntent
	idempotentCheckouts map[string]string // "userID|idempKey" -> intentID
	idempotentSends     map[string]*SendStarGiftResponse
	transactions        []*StarTransactionDTO
	processedWebhooks   map[string]storedWebhook // "provider|eventID"
	nowFunc             func() time.Time
}

func NewService(hooks HostHooks) *Service {
	return &Service{
		hooks:               hooks,
		Gateways:            NewPaymentGateways(LoadGatewayConfigFromEnv()),
		Aml:                 NewAmlLimiter(),
		wallets:             make(map[string]*StarWalletDTO),
		intents:             make(map[string]*storedIntent),
		intentsByRef:        make(map[string]*storedIntent),
		idempotentCheckouts: make(map[string]string),
		idempotentSends:     make(map[string]*SendStarGiftResponse),
		transactions:        make([]*StarTransactionDTO, 0),
		processedWebhooks:   make(map[string]storedWebhook),
		nowFunc:             time.Now,
	}
}

func (s *Service) nowMs() int64 {
	return s.nowFunc().UTC().UnixMilli()
}

func newID(prefix string) string {
	var b [12]byte
	_, _ = rand.Read(b[:])
	return prefix + "_" + hex.EncodeToString(b[:])
}

func (s *Service) walletLocked(userID string) *StarWalletDTO {
	w, ok := s.wallets[userID]
	if !ok {
		w = &StarWalletDTO{
			UserID:      userID,
			UpdatedAtMs: s.nowMs(),
		}
		s.wallets[userID] = w
	}
	return w
}

func (s *Service) GetSummary(userID, countryOverride string) StarSummaryResponse {
	country := strings.ToUpper(strings.TrimSpace(countryOverride))
	if country == "" && s.hooks.ResolveUser != nil {
		if u, ok := s.hooks.ResolveUser(userID); ok && u.CountryISO != "" {
			country = u.CountryISO
		}
	}
	if country == "" {
		country = "US"
	}
	rule := RuleForCountry(country)
	pkgs := BuildPackagesForCountry(country)

	s.mu.Lock()
	w := *s.walletLocked(userID)

	senderTotals := make(map[string]*StarSenderSummaryDTO)
	txs := make([]StarTransactionDTO, 0)
	for i := len(s.transactions) - 1; i >= 0; i-- {
		tx := s.transactions[i]
		if tx.RecipientID != userID && tx.SenderUserID != userID {
			continue
		}
		copyTx := *tx
		switch {
		case tx.TxType == "purchase":
			copyTx.Direction = "purchased"
		case tx.RecipientID == userID:
			copyTx.Direction = "received"
			if tx.Status == "completed" {
				agg, exists := senderTotals[tx.SenderUserID]
				if !exists {
					agg = &StarSenderSummaryDTO{
						SenderUserID:   tx.SenderUserID,
						SenderName:     tx.SenderName,
						SenderUsername: tx.SenderUsername,
						SenderAvatar:   tx.SenderAvatar,
					}
					senderTotals[tx.SenderUserID] = agg
				}
				agg.TotalStars += tx.StarAmount
				agg.GiftsCount++
				if tx.CreatedAtMs > agg.LastGiftedAtMs {
					agg.LastGiftedAtMs = tx.CreatedAtMs
				}
			}
		default:
			copyTx.Direction = "sent"
		}
		txs = append(txs, copyTx)
		if len(txs) >= 80 {
			break
		}
	}

	pending := make([]StarPaymentIntentDTO, 0)
	for _, st := range s.intents {
		if st.UserID == userID && st.DTO.Status == "pending" {
			pending = append(pending, st.DTO)
		}
	}
	s.mu.Unlock()

	sort.Slice(pending, func(i, j int) bool {
		return pending[i].CreatedAtMs > pending[j].CreatedAtMs
	})

	senders := make([]StarSenderSummaryDTO, 0, len(senderTotals))
	for _, v := range senderTotals {
		senders = append(senders, *v)
	}
	sort.Slice(senders, func(i, j int) bool {
		if senders[i].TotalStars != senders[j].TotalStars {
			return senders[i].TotalStars > senders[j].TotalStars
		}
		return senders[i].LastGiftedAtMs > senders[j].LastGiftedAtMs
	})

	return StarSummaryResponse{
		Success:         true,
		CountryISO:      country,
		Currency:        rule.Currency,
		CurrencySymbol:  rule.Symbol,
		Wallet:          w,
		Packages:        pkgs,
		Senders:         senders,
		Transactions:    txs,
		PendingIntents:  pending,
		RateLimitStatus: s.Aml.SnapshotForUser(userID),
		ServerEpochMs:   s.nowMs(),
	}
}

func (s *Service) CreateCheckout(userID, deviceFP, ipFP string, req CreateStarCheckoutRequest) (CreateStarCheckoutResponse, error) {
	idemp := strings.TrimSpace(req.IdempotencyKey)
	if len(idemp) < 6 || len(idemp) > 128 {
		return CreateStarCheckoutResponse{}, fmt.Errorf("%w: valid idempotency_key required", ErrBadRequest)
	}
	if !IsValidStarTier(req.StarAmount) {
		return CreateStarCheckoutResponse{}, fmt.Errorf("%w: unsupported star package", ErrBadRequest)
	}
	provider := strings.ToLower(strings.TrimSpace(req.Provider))
	if provider != "paystack" && provider != "paypal" {
		return CreateStarCheckoutResponse{}, fmt.Errorf("%w: provider must be paystack or paypal", ErrBadRequest)
	}

	idempMapKey := userID + "|" + idemp
	s.mu.Lock()
	if existingID, exists := s.idempotentCheckouts[idempMapKey]; exists {
		st := s.intents[existingID]
		w := *s.walletLocked(userID)
		s.mu.Unlock()
		if st != nil {
			if st.DTO.Provider != provider || st.DTO.StarAmount != req.StarAmount {
				return CreateStarCheckoutResponse{}, ErrIdempotencyConflict
			}
			return CreateStarCheckoutResponse{
				Success:       true,
				Replayed:      true,
				Intent:        st.DTO,
				Wallet:        w,
				ServerEpochMs: s.nowMs(),
			}, nil
		}
	}
	s.mu.Unlock()

	if dec := s.Aml.CheckAndRecordCheckout(userID, deviceFP, req.StarAmount); !dec.Allowed {
		if s.hooks.AuditRecord != nil {
			s.hooks.AuditRecord(userID, "stars_checkout_aml_blocked", deviceFP, ipFP, map[string]string{
				"reason": dec.Reason,
			})
		}
		return CreateStarCheckoutResponse{}, &AmlError{Decision: dec}
	}

	var uInfo UserProfileView
	if s.hooks.ResolveUser != nil {
		uInfo, _ = s.hooks.ResolveUser(userID)
	}
	country := strings.ToUpper(strings.TrimSpace(req.CountryISO))
	if country == "" {
		country = uInfo.CountryISO
	}
	if country == "" {
		country = "US"
	}

	currency, expectedMinor, amountDisplay, paypalDec := AuthoritativeGatewayQuote(country, provider, req.StarAmount)
	returnURL, cancelURL := s.sanitizeCallbackURLs(req.ReturnURL, req.CancelURL)
	intentID := newID("sti")
	provRef := newID("tf_" + provider)

	var checkoutURL string
	var err error
	if provider == "paystack" {
		meta := map[string]any{
			"intent_id":       intentID,
			"user_id":         userID,
			"idempotency_key": idemp,
			"star_amount":     req.StarAmount,
		}
		var psRef string
		psRef, checkoutURL, err = s.Gateways.InitializePaystack(uInfo.Email, provRef, expectedMinor, currency, returnURL, meta)
		if err != nil {
			return CreateStarCheckoutResponse{}, err
		}
		provRef = psRef
	} else {
		customID := userID + "|" + idemp
		var ppOrderID string
		ppOrderID, checkoutURL, err = s.Gateways.CreatePayPalOrder(idemp, req.StarAmount, currency, paypalDec, returnURL, cancelURL, customID)
		if err != nil {
			return CreateStarCheckoutResponse{}, err
		}
		provRef = ppOrderID
	}

	now := s.nowMs()
	dto := StarPaymentIntentDTO{
		IntentID:          intentID,
		IdempotencyKey:    idemp,
		Provider:          provider,
		ProviderReference: provRef,
		StarAmount:        req.StarAmount,
		Currency:          currency,
		ExpectedMinor:     expectedMinor,
		AmountDisplay:     amountDisplay,
		CheckoutURL:       checkoutURL,
		Status:            "pending",
		Credited:          false,
		SignatureVerified: false,
		CreatedAtMs:       now,
	}

	s.mu.Lock()
	if existingID, race := s.idempotentCheckouts[idempMapKey]; race {
		st := s.intents[existingID]
		w := *s.walletLocked(userID)
		s.mu.Unlock()
		return CreateStarCheckoutResponse{
			Success:       true,
			Replayed:      true,
			Intent:        st.DTO,
			Wallet:        w,
			ServerEpochMs: s.nowMs(),
		}, nil
	}
	st := &storedIntent{
		DTO:       dto,
		UserID:    userID,
		PayPalDec: paypalDec,
	}
	s.intents[intentID] = st
	s.intentsByRef[provRef] = st
	s.idempotentCheckouts[idempMapKey] = intentID
	w := *s.walletLocked(userID)
	s.mu.Unlock()

	if s.hooks.AuditRecord != nil {
		s.hooks.AuditRecord(userID, "stars_checkout_created", deviceFP, ipFP, map[string]string{
			"intent_id": intentID,
			"provider":  provider,
			"currency":  currency,
			"stars":     fmt.Sprintf("%d", req.StarAmount),
		})
	}
	return CreateStarCheckoutResponse{
		Success:       true,
		Replayed:      false,
		Intent:        dto,
		Wallet:        w,
		ServerEpochMs: now,
	}, nil
}

func (s *Service) VerifyPayment(userID, deviceFP, ipFP string, req VerifyStarPaymentRequest) (VerifyStarPaymentResponse, error) {
	idemp := strings.TrimSpace(req.IdempotencyKey)
	ref := strings.TrimSpace(req.ProviderReference)
	if idemp == "" && ref == "" {
		return VerifyStarPaymentResponse{}, fmt.Errorf("%w: idempotency_key or provider_reference required", ErrBadRequest)
	}

	s.mu.Lock()
	var st *storedIntent
	if idemp != "" {
		if id, ok := s.idempotentCheckouts[userID+"|"+idemp]; ok {
			st = s.intents[id]
		}
	}
	if st == nil && ref != "" {
		st = s.intentsByRef[ref]
	}
	if st == nil {
		s.mu.Unlock()
		return VerifyStarPaymentResponse{}, ErrNotFound
	}
	if st.UserID != userID {
		s.mu.Unlock()
		return VerifyStarPaymentResponse{}, ErrForbidden
	}
	if st.DTO.Credited {
		w := *s.walletLocked(userID)
		dto := st.DTO
		s.mu.Unlock()
		return VerifyStarPaymentResponse{
			Success:       true,
			Intent:        dto,
			Wallet:        w,
			ServerEpochMs: s.nowMs(),
		}, nil
	}
	intentSnap := st.DTO
	s.mu.Unlock()

	var paidMinor int64
	var gwCurrency, gwStatus, metaUID, metaIdemp, customID string
	var err error
	if intentSnap.Provider == "paystack" {
		paidMinor, gwCurrency, gwStatus, metaUID, metaIdemp, err = s.Gateways.VerifyPaystackTransaction(intentSnap.ProviderReference)
	} else {
		paidMinor, gwCurrency, gwStatus, customID, err = s.Gateways.VerifyAndCapturePayPalOrder(intentSnap.ProviderReference, intentSnap.IdempotencyKey)
	}
	if err != nil {
		return VerifyStarPaymentResponse{}, err
	}
	if gwStatus != "success" && gwStatus != "completed" {
		return VerifyStarPaymentResponse{}, ErrPaymentPending
	}
	if !strings.EqualFold(gwCurrency, intentSnap.Currency) || paidMinor < intentSnap.ExpectedMinor {
		return VerifyStarPaymentResponse{}, ErrAmountMismatch
	}
	if metaUID != "" && metaUID != userID {
		return VerifyStarPaymentResponse{}, ErrForbidden
	}
	if metaIdemp != "" && metaIdemp != intentSnap.IdempotencyKey {
		return VerifyStarPaymentResponse{}, ErrIdempotencyConflict
	}
	if customID != "" && customID != userID+"|"+intentSnap.IdempotencyKey {
		return VerifyStarPaymentResponse{}, ErrForbidden
	}

	dto, w := s.creditIntentAtomically(intentSnap.IntentID, paidMinor, true, "")
	if s.hooks.AuditRecord != nil {
		s.hooks.AuditRecord(userID, "stars_payment_verified", deviceFP, ipFP, map[string]string{
			"intent_id": intentSnap.IntentID,
			"provider":  intentSnap.Provider,
			"stars":     fmt.Sprintf("%d", dto.StarAmount),
		})
	}
	return VerifyStarPaymentResponse{
		Success:       true,
		Intent:        dto,
		Wallet:        w,
		ServerEpochMs: s.nowMs(),
	}, nil
}

func (s *Service) HandlePaystackWebhook(rawBody []byte, sigHeader string) error {
	if !s.Gateways.VerifyPaystackSignature(rawBody, sigHeader) {
		return ErrInvalidSignature
	}
	var payload struct {
		Event string `json:"event"`
		Data  struct {
			ID        int64          `json:"id"`
			Status    string         `json:"status"`
			Reference string         `json:"reference"`
			Amount    int64          `json:"amount"`
			Currency  string         `json:"currency"`
			Metadata  map[string]any `json:"metadata"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rawBody, &payload); err != nil {
		return ErrBadRequest
	}
	if !strings.EqualFold(payload.Event, "charge.success") || !strings.EqualFold(payload.Data.Status, "success") {
		return nil
	}
	eventID := fmt.Sprintf("charge:%d:%s", payload.Data.ID, payload.Data.Reference)
	if s.hasWebhookProcessed("paystack", eventID) {
		return nil
	}

	s.mu.Lock()
	st := s.intentsByRef[strings.TrimSpace(payload.Data.Reference)]
	if st == nil {
		s.mu.Unlock()
		return nil
	}
	if st.DTO.Credited {
		s.mu.Unlock()
		return nil
	}
	snap := st.DTO
	s.mu.Unlock()

	// Cross-verify independently with Paystack Verify API + exact amount/currency match
	paidMinor, gwCurr, gwStatus, _, _, err := s.Gateways.VerifyPaystackTransaction(snap.ProviderReference)
	if err != nil || gwStatus != "success" {
		return err
	}
	if !strings.EqualFold(gwCurr, snap.Currency) || paidMinor < snap.ExpectedMinor ||
		!strings.EqualFold(payload.Data.Currency, snap.Currency) || payload.Data.Amount < snap.ExpectedMinor {
		return ErrAmountMismatch
	}
	if !s.recordWebhookOnce("paystack", eventID, rawBody) {
		return nil
	}
	s.creditIntentAtomically(snap.IntentID, paidMinor, true, eventID)
	return nil
}

func (s *Service) HandlePayPalWebhook(headers http.Header, rawBody []byte) error {
	valid, err := s.Gateways.VerifyPayPalWebhookSignature(headers, rawBody)
	if err != nil {
		return err
	}
	if !valid {
		return ErrInvalidSignature
	}

	var event struct {
		ID        string `json:"id"`
		EventType string `json:"event_type"`
		Resource  struct {
			ID            string `json:"id"`
			Status        string `json:"status"`
			CustomID      string `json:"custom_id"`
			Supplementary struct {
				RelatedIDs struct {
					OrderID string `json:"order_id"`
				} `json:"related_ids"`
			} `json:"supplementary_data"`
			Amount struct {
				CurrencyCode string `json:"currency_code"`
				Value        string `json:"value"`
			} `json:"amount"`
		} `json:"resource"`
	}
	if err := json.Unmarshal(rawBody, &event); err != nil {
		return ErrBadRequest
	}
	if event.ID == "" || s.hasWebhookProcessed("paypal", event.ID) {
		return nil
	}
	if event.EventType != "PAYMENT.CAPTURE.COMPLETED" && event.EventType != "CHECKOUT.ORDER.APPROVED" {
		return nil
	}

	orderID := strings.TrimSpace(event.Resource.Supplementary.RelatedIDs.OrderID)
	if orderID == "" && event.EventType == "CHECKOUT.ORDER.APPROVED" {
		orderID = strings.TrimSpace(event.Resource.ID)
	}
	if orderID == "" {
		return nil
	}

	s.mu.Lock()
	st := s.intentsByRef[orderID]
	if st == nil || st.DTO.Credited {
		s.mu.Unlock()
		return nil
	}
	snap := st.DTO
	ownerID := st.UserID
	s.mu.Unlock()

	paidMinor, gwCurr, gwStatus, customID, err := s.Gateways.VerifyAndCapturePayPalOrder(orderID, snap.IdempotencyKey)
	if err != nil || gwStatus != "completed" {
		return err
	}
	if !strings.EqualFold(gwCurr, snap.Currency) || paidMinor < snap.ExpectedMinor {
		return ErrAmountMismatch
	}
	if customID != "" && customID != ownerID+"|"+snap.IdempotencyKey {
		return ErrForbidden
	}
	if !s.recordWebhookOnce("paypal", event.ID, rawBody) {
		return nil
	}
	s.creditIntentAtomically(snap.IntentID, paidMinor, true, event.ID)
	return nil
}

func (s *Service) hasWebhookProcessed(provider, eventID string) bool {
	key := provider + "|" + eventID
	s.mu.Lock()
	defer s.mu.Unlock()
	_, exists := s.processedWebhooks[key]
	return exists
}

func (s *Service) recordWebhookOnce(provider, eventID string, rawBody []byte) bool {
	sum := sha256.Sum256(rawBody)
	key := provider + "|" + eventID
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, exists := s.processedWebhooks[key]; exists {
		return false
	}
	s.processedWebhooks[key] = storedWebhook{
		Provider:   provider,
		EventID:    eventID,
		PayloadSHA: hex.EncodeToString(sum[:]),
		ReceivedMs: s.nowMs(),
	}
	return true
}

func (s *Service) creditIntentAtomically(intentID string, verifiedMinor int64, sigVerified bool, webhookEventID string) (StarPaymentIntentDTO, StarWalletDTO) {
	now := s.nowMs()
	var targetUser string
	var dto StarPaymentIntentDTO
	var wCopy StarWalletDTO

	s.mu.Lock()
	st := s.intents[intentID]
	if st == nil {
		s.mu.Unlock()
		return StarPaymentIntentDTO{}, StarWalletDTO{}
	}
	targetUser = st.UserID
	w := s.walletLocked(targetUser)
	if !st.DTO.Credited {
		st.DTO.Credited = true
		st.DTO.Status = "completed"
		st.DTO.VerifiedMinor = verifiedMinor
		st.DTO.SignatureVerified = sigVerified
		if webhookEventID != "" {
			st.DTO.CompletedAtMs = now
		} else if st.DTO.CompletedAtMs == 0 {
			st.DTO.CompletedAtMs = now
		}

		w.AvailableBalance += st.DTO.StarAmount
		w.TotalPurchased += st.DTO.StarAmount
		w.UpdatedAtMs = now

		var prof UserProfileView
		if s.hooks.ResolveUser != nil {
			prof, _ = s.hooks.ResolveUser(targetUser)
		}
		s.transactions = append(s.transactions, &StarTransactionDTO{
			TxID:           newID("stx"),
			TxType:         "purchase",
			Direction:      "purchased",
			RecipientID:    targetUser,
			RecipientName:  prof.FullName,
			SenderUserID:   targetUser,
			SenderName:     prof.FullName,
			SenderUsername: prof.Username,
			SenderAvatar:   prof.AvatarURL,
			StarAmount:     st.DTO.StarAmount,
			Currency:       st.DTO.Currency,
			AmountMinor:    verifiedMinor,
			Provider:       st.DTO.Provider,
			Status:         "completed",
			CreatedAtMs:    now,
		})
	}
	dto = st.DTO
	wCopy = *w
	s.mu.Unlock()

	s.pushStarBalanceWS(targetUser, wCopy, 0, "")
	return dto, wCopy
}

func (s *Service) SendStars(callerID, deviceFP, ipFP string, req SendStarGiftRequest) (SendStarGiftResponse, error) {
	idemp := strings.TrimSpace(req.IdempotencyKey)
	recipientID := strings.TrimSpace(req.RecipientUserID)
	convID := strings.TrimSpace(req.ConversationID)
	if len(idemp) < 6 || len(idemp) > 128 || recipientID == "" || !IsValidStarTier(req.StarAmount) {
		return SendStarGiftResponse{}, ErrBadRequest
	}
	if recipientID == callerID {
		return SendStarGiftResponse{}, fmt.Errorf("%w: cannot send stars to yourself", ErrForbidden)
	}
	if s.hooks.IsBlocked != nil && s.hooks.IsBlocked(callerID, recipientID) {
		return SendStarGiftResponse{}, fmt.Errorf("%w: messaging blocked between accounts", ErrForbidden)
	}

	var senderProf, recipientProf UserProfileView
	if s.hooks.ResolveUser != nil {
		var okRec bool
		senderProf, _ = s.hooks.ResolveUser(callerID)
		recipientProf, okRec = s.hooks.ResolveUser(recipientID)
		if !okRec {
			return SendStarGiftResponse{}, ErrNotFound
		}
	}

	idempKey := callerID + "|" + idemp
	s.mu.Lock()
	if prev, exists := s.idempotentSends[idempKey]; exists {
		copyResp := *prev
		copyResp.Replayed = true
		copyResp.Wallet = *s.walletLocked(callerID)
		copyResp.AvailableBalance = copyResp.Wallet.AvailableBalance
		s.mu.Unlock()
		if copyResp.Transaction != nil && (copyResp.Transaction.RecipientID != recipientID || copyResp.Transaction.StarAmount != req.StarAmount) {
			return SendStarGiftResponse{}, ErrIdempotencyConflict
		}
		return copyResp, nil
	}
	senderWallet := s.walletLocked(callerID)
	if senderWallet.AvailableBalance < req.StarAmount {
		wCopy := *senderWallet
		s.mu.Unlock()
		return SendStarGiftResponse{
			Success:             false,
			InsufficientBalance: true,
			RequiredStarAmount:  req.StarAmount,
			AvailableBalance:    wCopy.AvailableBalance,
			Wallet:              wCopy,
			ServerEpochMs:       s.nowMs(),
		}, nil
	}
	s.mu.Unlock()

	if dec := s.Aml.CheckAndRecordSend(callerID, deviceFP, ipFP, recipientID, req.StarAmount); !dec.Allowed {
		if s.hooks.AuditRecord != nil {
			s.hooks.AuditRecord(callerID, "stars_send_aml_blocked", deviceFP, ipFP, map[string]string{
				"reason":       dec.Reason,
				"recipient_id": recipientID,
				"stars":        fmt.Sprintf("%d", req.StarAmount),
			})
		}
		return SendStarGiftResponse{}, &AmlError{Decision: dec}
	}

	now := s.nowMs()
	txID := newID("stx")
	s.mu.Lock()
	// Re-check inside lock against concurrent deductions
	senderWallet = s.walletLocked(callerID)
	if senderWallet.AvailableBalance < req.StarAmount {
		wCopy := *senderWallet
		s.mu.Unlock()
		return SendStarGiftResponse{
			Success:             false,
			InsufficientBalance: true,
			RequiredStarAmount:  req.StarAmount,
			AvailableBalance:    wCopy.AvailableBalance,
			Wallet:              wCopy,
			ServerEpochMs:       now,
		}, nil
	}
	recipientWallet := s.walletLocked(recipientID)
	senderWallet.AvailableBalance -= req.StarAmount
	senderWallet.TotalSent += req.StarAmount
	senderWallet.UpdatedAtMs = now
	recipientWallet.AvailableBalance += req.StarAmount
	recipientWallet.TotalReceived += req.StarAmount
	recipientWallet.UpdatedAtMs = now

	senderWalletCopy := *senderWallet
	recipientWalletCopy := *recipientWallet

	txRec := &StarTransactionDTO{
		TxID:           txID,
		TxType:         "gift",
		SenderUserID:   callerID,
		SenderName:     senderProf.FullName,
		SenderUsername: senderProf.Username,
		SenderAvatar:   senderProf.AvatarURL,
		RecipientID:    recipientID,
		RecipientName:  recipientProf.FullName,
		ConversationID: convID,
		StarAmount:     req.StarAmount,
		Status:         "pending",
		CreatedAtMs:    now,
	}
	s.transactions = append(s.transactions, txRec)
	s.mu.Unlock()

	var msgDTO any
	var convDTO any
	if s.hooks.ChatService != nil {
		if chatSvc := s.hooks.ChatService(); chatSvc != nil {
			createdMsg, createdConv, chatErr := chatSvc.CreateVerifiedStarGiftMessage(
				callerID,
				recipientID,
				convID,
				txID,
				req.StarAmount,
				req.CiphertextB64,
				req.SenderCipherB64,
				idemp,
			)
			if chatErr != nil {
				// Atomic rollback if chat delivery failed (e.g. peer blocked simultaneously)
				s.mu.Lock()
				sw := s.walletLocked(callerID)
				rw := s.walletLocked(recipientID)
				sw.AvailableBalance += req.StarAmount
				sw.TotalSent -= req.StarAmount
				rw.AvailableBalance -= req.StarAmount
				if rw.AvailableBalance < 0 {
					rw.AvailableBalance = 0
				}
				rw.TotalReceived -= req.StarAmount
				if rw.TotalReceived < 0 {
					rw.TotalReceived = 0
				}
				txRec.Status = "reverted"
				s.mu.Unlock()
				return SendStarGiftResponse{}, chatErr
			}
			msgDTO = createdMsg
			convDTO = createdConv
			s.mu.Lock()
			txRec.MessageID = createdMsg.MessageID
			txRec.ConversationID = createdMsg.ConversationID
			txRec.Status = "completed"
			s.mu.Unlock()
		}
	}

	resp := SendStarGiftResponse{
		Success:          true,
		Replayed:         false,
		AvailableBalance: senderWalletCopy.AvailableBalance,
		Wallet:           senderWalletCopy,
		Transaction:      txRec,
		Message:          msgDTO,
		Conversation:     convDTO,
		ServerEpochMs:    now,
	}
	s.mu.Lock()
	if txRec.Status == "pending" {
		txRec.Status = "completed"
	}
	respCopy := resp
	s.idempotentSends[idempKey] = &respCopy
	s.mu.Unlock()

	s.pushStarBalanceWS(callerID, senderWalletCopy, 0, "")
	s.pushStarBalanceWS(recipientID, recipientWalletCopy, req.StarAmount, callerID)

	if s.hooks.AuditRecord != nil {
		s.hooks.AuditRecord(callerID, "stars_gift_sent", deviceFP, ipFP, map[string]string{
			"tx_id":        txID,
			"recipient_id": recipientID,
			"stars":        fmt.Sprintf("%d", req.StarAmount),
		})
	}
	return resp, nil
}

func (s *Service) pushStarBalanceWS(userID string, w StarWalletDTO, giftReceived int64, senderID string) {
	if s.hooks.ChatService == nil {
		return
	}
	chatSvc := s.hooks.ChatService()
	if chatSvc == nil {
		return
	}
	evType := "star_balance_updated"
	if giftReceived > 0 {
		evType = "star_gift_received"
	}
	chatSvc.PushUserEvent(userID, chat.WebSocketEvent{
		Event:                evType,
		UserID:               senderID,
		StarGiftAmount:       giftReceived,
		StarBalanceAvailable: w.AvailableBalance,
		StarBalanceReceived:  w.TotalReceived,
		TimestampMs:          s.nowMs(),
	})
}

func (s *Service) sanitizeCallbackURLs(retRaw, cancelRaw string) (string, string) {
	base := strings.TrimRight(strings.TrimSpace(s.hooks.PublicBase), "/")
	if base == "" {
		base = "https://telefam.company"
	}
	defRet := base + "/v1/stars/callback?status=success"
	defCancel := base + "/v1/stars/callback?status=cancelled"
	return pickSafeRedirect(retRaw, defRet), pickSafeRedirect(cancelRaw, defCancel)
}

func pickSafeRedirect(raw, fallback string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return fallback
	}
	u, err := url.Parse(trimmed)
	if err != nil {
		return fallback
	}
	if strings.EqualFold(u.Scheme, "telefam") && strings.EqualFold(u.Host, "stars") {
		return trimmed
	}
	if strings.EqualFold(u.Scheme, "https") && strings.EqualFold(u.Hostname(), "telefam.company") {
		return trimmed
	}
	return fallback
}
