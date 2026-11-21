package auth

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"telefam.company/backend/internal/chat"
	"telefam.company/backend/internal/contacts"
	"telefam.company/backend/internal/stars"
)

func TestStarsPaystackPayPalIdempotencyWebhooksAmlAndChatGift(t *testing.T) {
	h, mux, tokens := newTestMuxAndTokens()
	dfpAlice := "device-stars-alice"
	dfpBob := "device-stars-bob"
	alicePair, _, _ := tokens.IssueInitialPair("u_alice", "alice@telefam.company", dfpAlice)
	bobPair, _, _ := tokens.IssueInitialPair("u_bob", "bob@telefam.company", dfpBob)

	h.contacts.RegisterOrUpdateUser(contacts.DirectoryUser{
		UserID: "u_alice", FullName: "Alice M", Username: "alicem", Country: "Kenya", CountryISO: "KE",
	}, dfpAlice)
	h.contacts.RegisterOrUpdateUser(contacts.DirectoryUser{
		UserID: "u_bob", FullName: "Bob K", Username: "bobk", Country: "United States", CountryISO: "US",
	}, dfpBob)

	h.mu.Lock()
	h.profiles["u_alice"] = storedProfile{UserID: "u_alice", FullName: "Alice M", Username: "alicem", CountryISO: "KE"}
	h.profiles["u_bob"] = storedProfile{UserID: "u_bob", FullName: "Bob K", Username: "bobk", CountryISO: "US"}
	h.mu.Unlock()

	paystackSecret := "sk_test_telefam_hmac_secret_123456"
	tamperedAmountMode := false

	// Mock Paystack & PayPal HTTP endpoints for server-side verification tests
	mockPaystack := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/transaction/initialize":
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			ref, _ := body["reference"].(string)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"status":  true,
				"message": "Authorization URL created",
				"data": map[string]any{
					"authorization_url": "https://checkout.paystack.com/" + ref,
					"access_code":       "acc_" + ref,
					"reference":         ref,
				},
			})
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/transaction/verify/"):
			ref := strings.TrimPrefix(r.URL.Path, "/transaction/verify/")
			amountMinor := int64(10000) // 100 KES = 10000 cents
			if tamperedAmountMode {
				amountMinor = 500
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"status":  true,
				"message": "Verification successful",
				"data": map[string]any{
					"id":        998877,
					"status":    "success",
					"reference": ref,
					"amount":    amountMinor,
					"currency":  "KES",
				},
			})
		}
	}))
	defer mockPaystack.Close()

	mockPayPal := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/v1/oauth2/token":
			_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "pp_tok_123", "expires_in": 3600})
		case r.URL.Path == "/v2/checkout/orders" && r.Method == http.MethodPost:
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id": "PP-ORD-100",
				"links": []map[string]string{
					{"rel": "approve", "href": "https://www.paypal.com/checkoutnow?token=PP-ORD-100"},
				},
			})
		case r.URL.Path == "/v2/checkout/orders/PP-ORD-100" && r.Method == http.MethodGet:
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id":     "PP-ORD-100",
				"status": "APPROVED",
				"purchase_units": []map[string]any{{
					"custom_id": "u_alice|pp-idemp-001",
					"amount":    map[string]string{"currency_code": "USD", "value": "0.50"},
				}},
			})
		case r.URL.Path == "/v2/checkout/orders/PP-ORD-100/capture" && r.Method == http.MethodPost:
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id":     "PP-ORD-100",
				"status": "COMPLETED",
				"purchase_units": []map[string]any{{
					"custom_id": "u_alice|pp-idemp-001",
					"payments": map[string]any{
						"captures": []map[string]any{{
							"id":     "CAP-100",
							"status": "COMPLETED",
							"amount": map[string]string{"currency_code": "USD", "value": "0.50"},
						}},
					},
				}},
			})
		case r.URL.Path == "/v1/notifications/verify-webhook-signature":
			_ = json.NewEncoder(w).Encode(map[string]string{"verification_status": "SUCCESS"})
		}
	}))
	defer mockPayPal.Close()

	starSvc := h.ensureStarsService()
	starSvc.Gateways.SetConfigForTest(stars.GatewayConfig{
		PaystackSecretKey: paystackSecret,
		PaystackAPIBase:   mockPaystack.URL,
		PayPalClientID:    "pp_client",
		PayPalSecret:      "pp_secret",
		PayPalWebhookID:   "WH-PP-123",
		PayPalAPIBase:     mockPayPal.URL,
	})

	// 1. Attempting to send 100 stars with 0 balance returns insufficient_balance=true cleanly (redirect trigger)
	lowRec := httptest.NewRecorder()
	mux.ServeHTTP(lowRec, authReq("POST", "/v1/stars/send", stars.SendStarGiftRequest{
		IdempotencyKey:  "send-low-001",
		RecipientUserID: "u_bob",
		StarAmount:      100,
	}, alicePair.AccessToken, dfpAlice))
	if lowRec.Code != http.StatusOK {
		t.Fatalf("expected 200 structured insufficient response, got %d %s", lowRec.Code, lowRec.Body.String())
	}
	var lowResp stars.SendStarGiftResponse
	_ = json.Unmarshal(lowRec.Body.Bytes(), &lowResp)
	if !lowResp.InsufficientBalance || lowResp.AvailableBalance != 0 {
		t.Fatalf("expected insufficient_balance=true and 0 balance, got %+v", lowResp)
	}

	// 2. Paystack checkout with Idempotency-Key + replay + conflict test
	coRec1 := httptest.NewRecorder()
	mux.ServeHTTP(coRec1, authReq("POST", "/v1/stars/checkout", stars.CreateStarCheckoutRequest{
		IdempotencyKey: "ps-idemp-001",
		StarAmount:     100,
		Provider:       "paystack",
		CountryISO:     "KE",
	}, alicePair.AccessToken, dfpAlice))
	if coRec1.Code != http.StatusOK {
		t.Fatalf("paystack checkout failed: %d %s", coRec1.Code, coRec1.Body.String())
	}
	var coResp1 stars.CreateStarCheckoutResponse
	_ = json.Unmarshal(coRec1.Body.Bytes(), &coResp1)
	if coResp1.Intent.Currency != "KES" || coResp1.Intent.ExpectedMinor != 10000 {
		t.Fatalf("expected KES 10000 minor units for KE 100 stars, got %+v", coResp1.Intent)
	}

	// Same key replayed -> exact same intent returned, Replayed=true
	coRecReplay := httptest.NewRecorder()
	mux.ServeHTTP(coRecReplay, authReq("POST", "/v1/stars/checkout", stars.CreateStarCheckoutRequest{
		IdempotencyKey: "ps-idemp-001",
		StarAmount:     100,
		Provider:       "paystack",
	}, alicePair.AccessToken, dfpAlice))
	var coReplay stars.CreateStarCheckoutResponse
	_ = json.Unmarshal(coRecReplay.Body.Bytes(), &coReplay)
	if !coReplay.Replayed || coReplay.Intent.IntentID != coResp1.Intent.IntentID {
		t.Fatalf("expected idempotent replay, got %+v", coReplay)
	}

	// Same key reused with DIFFERENT star_amount -> 409 Conflict
	coRecConflict := httptest.NewRecorder()
	mux.ServeHTTP(coRecConflict, authReq("POST", "/v1/stars/checkout", stars.CreateStarCheckoutRequest{
		IdempotencyKey: "ps-idemp-001",
		StarAmount:     5000,
		Provider:       "paystack",
	}, alicePair.AccessToken, dfpAlice))
	if coRecConflict.Code != http.StatusConflict {
		t.Fatalf("expected 409 idempotency conflict, got %d", coRecConflict.Code)
	}

	// 3. Tampered Paystack webhook signature MUST fail with 401
	whBody, _ := json.Marshal(map[string]any{
		"event": "charge.success",
		"data": map[string]any{
			"id":        998877,
			"status":    "success",
			"reference": coResp1.Intent.ProviderReference,
			"amount":    10000,
			"currency":  "KES",
		},
	})
	badWh := httptest.NewRecorder()
	reqBadWh := httptest.NewRequest(http.MethodPost, "/v1/webhooks/paystack", bytes.NewReader(whBody))
	reqBadWh.Header.Set("X-Paystack-Signature", "deadbeef")
	mux.ServeHTTP(badWh, reqBadWh)
	if badWh.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 on invalid paystack webhook sig, got %d", badWh.Code)
	}

	// 4. Tampered Paystack amount fails verification (422)
	tamperedAmountMode = true
	mac := hmac.New(sha512.New, []byte(paystackSecret))
	_, _ = mac.Write(whBody)
	validSig := hex.EncodeToString(mac.Sum(nil))
	tamperWh := httptest.NewRecorder()
	reqTamper := httptest.NewRequest(http.MethodPost, "/v1/webhooks/paystack", bytes.NewReader(whBody))
	reqTamper.Header.Set("X-Paystack-Signature", validSig)
	mux.ServeHTTP(tamperWh, reqTamper)
	if tamperWh.Code != http.StatusUnprocessableEntity {
		t.Fatalf("expected 422 on amount mismatch, got %d", tamperWh.Code)
	}
	tamperedAmountMode = false

	// 5. Valid signed Paystack webhook credits wallet ONCE (idempotent on duplicate webhook & verify call)
	goodWh := httptest.NewRecorder()
	reqGood := httptest.NewRequest(http.MethodPost, "/v1/webhooks/paystack", bytes.NewReader(whBody))
	reqGood.Header.Set("X-Paystack-Signature", validSig)
	mux.ServeHTTP(goodWh, reqGood)
	if goodWh.Code != http.StatusOK {
		t.Fatalf("expected 200 on valid paystack webhook, got %d %s", goodWh.Code, goodWh.Body.String())
	}
	// Duplicate webhook + explicit /verify must NOT double-credit
	dupWh := httptest.NewRecorder()
	reqDup := httptest.NewRequest(http.MethodPost, "/v1/webhooks/paystack", bytes.NewReader(whBody))
	reqDup.Header.Set("X-Paystack-Signature", validSig)
	mux.ServeHTTP(dupWh, reqDup)
	verifyRec := httptest.NewRecorder()
	mux.ServeHTTP(verifyRec, authReq("POST", "/v1/stars/payments/verify", stars.VerifyStarPaymentRequest{
		IdempotencyKey: "ps-idemp-001",
	}, alicePair.AccessToken, dfpAlice))

	var vResp stars.VerifyStarPaymentResponse
	_ = json.Unmarshal(verifyRec.Body.Bytes(), &vResp)
	if vResp.Wallet.AvailableBalance != 100 || !vResp.Intent.Credited {
		t.Fatalf("expected exactly 100 stars credited once, got %+v", vResp)
	}

	// 6. PayPal checkout + capture verification (50 stars = $0.50 USD)
	ppCoRec := httptest.NewRecorder()
	mux.ServeHTTP(ppCoRec, authReq("POST", "/v1/stars/checkout", stars.CreateStarCheckoutRequest{
		IdempotencyKey: "pp-idemp-001",
		StarAmount:     50,
		Provider:       "paypal",
		CountryISO:     "KE",
	}, alicePair.AccessToken, dfpAlice))
	if ppCoRec.Code != http.StatusOK {
		t.Fatalf("paypal checkout failed: %d %s", ppCoRec.Code, ppCoRec.Body.String())
	}
	ppVerRec := httptest.NewRecorder()
	mux.ServeHTTP(ppVerRec, authReq("POST", "/v1/stars/payments/verify", stars.VerifyStarPaymentRequest{
		IdempotencyKey: "pp-idemp-001",
	}, alicePair.AccessToken, dfpAlice))
	if ppVerRec.Code != http.StatusOK {
		t.Fatalf("paypal verify failed: %d %s", ppVerRec.Code, ppVerRec.Body.String())
	}
	var ppVer stars.VerifyStarPaymentResponse
	_ = json.Unmarshal(ppVerRec.Body.Bytes(), &ppVer)
	if ppVer.Wallet.AvailableBalance != 150 {
		t.Fatalf("expected 150 balance after paypal credit, got %d", ppVer.Wallet.AvailableBalance)
	}

	// 7. Send 20 Stars to Bob -> creates verified star_gift message in chat & forbids client spoofing
	sendRec := httptest.NewRecorder()
	mux.ServeHTTP(sendRec, authReq("POST", "/v1/stars/send", stars.SendStarGiftRequest{
		IdempotencyKey:  "gift-idemp-001",
		RecipientUserID: "u_bob",
		StarAmount:      20,
	}, alicePair.AccessToken, dfpAlice))
	if sendRec.Code != http.StatusOK {
		t.Fatalf("send stars failed: %d %s", sendRec.Code, sendRec.Body.String())
	}
	var giftResp stars.SendStarGiftResponse
	_ = json.Unmarshal(sendRec.Body.Bytes(), &giftResp)
	if giftResp.Wallet.AvailableBalance != 130 {
		t.Fatalf("expected alice balance 130 after sending 20 stars, got %d", giftResp.Wallet.AvailableBalance)
	}

	// Verify Bob's Stars screen summary shows 20 received & Alice as sender
	bobSumRec := httptest.NewRecorder()
	mux.ServeHTTP(bobSumRec, authReq("GET", "/v1/stars/summary", nil, bobPair.AccessToken, dfpBob))
	var bobSum stars.StarSummaryResponse
	_ = json.Unmarshal(bobSumRec.Body.Bytes(), &bobSum)
	if bobSum.Wallet.TotalReceived != 20 || len(bobSum.Senders) != 1 || bobSum.Senders[0].SenderUserID != "u_alice" {
		t.Fatalf("expected bob received 20 stars from alice, got %+v", bobSum)
	}

	// Client spoofing "star_gift" directly on /v1/chat/conversations/{id}/messages must fail with 403
	openConvRec := httptest.NewRecorder()
	mux.ServeHTTP(openConvRec, authReq("POST", "/v1/chat/conversations/direct/u_bob", nil, alicePair.AccessToken, dfpAlice))
	var openConv struct {
		Conversation chat.ConversationDTO `json:"conversation"`
	}
	_ = json.Unmarshal(openConvRec.Body.Bytes(), &openConv)
	spoofRec := httptest.NewRecorder()
	mux.ServeHTTP(spoofRec, authReq("POST", "/v1/chat/conversations/"+openConv.Conversation.ConversationID+"/messages", chat.SendMessagePayload{
		ClientOpID:    "spoof-star-1",
		MessageType:   "star_gift",
		CiphertextB64: "fake",
	}, alicePair.AccessToken, dfpAlice))
	if spoofRec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 when trying to forge star_gift on chat send endpoint, got %d", spoofRec.Code)
	}

	// 8. AML rate limit burst test: sending > 6 gifts in 1 minute must return 429 Too Many Requests
	for i := 0; i < 6; i++ {
		r := httptest.NewRecorder()
		mux.ServeHTTP(r, authReq("POST", "/v1/stars/send", stars.SendStarGiftRequest{
			IdempotencyKey:  "burst-idemp-" + string(rune('a'+i)),
			RecipientUserID: "u_bob",
			StarAmount:      5,
		}, alicePair.AccessToken, dfpAlice))
		if i < 5 && r.Code != http.StatusOK {
			t.Fatalf("expected burst send %d allowed, got %d %s", i, r.Code, r.Body.String())
		}
		if i == 5 && r.Code != http.StatusTooManyRequests {
			t.Fatalf("expected 429 AML velocity block on 7th send within 1m, got %d", r.Code)
		}
	}
}
