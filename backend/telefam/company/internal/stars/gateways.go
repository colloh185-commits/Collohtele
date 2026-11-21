package stars

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha512"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	ErrGatewayUnconfigured = errors.New("payment gateway credentials not configured")
	ErrGatewayRejected     = errors.New("payment gateway rejected request")
	ErrInvalidSignature    = errors.New("invalid webhook signature")
	ErrAmountMismatch      = errors.New("payment amount or currency mismatch")
)

type GatewayConfig struct {
	PaystackSecretKey string
	PaystackAPIBase   string
	PayPalClientID    string
	PayPalSecret      string
	PayPalWebhookID   string
	PayPalAPIBase     string
}

func LoadGatewayConfigFromEnv() GatewayConfig {
	psBase := strings.TrimRight(strings.TrimSpace(os.Getenv("PAYSTACK_API_BASE")), "/")
	if psBase == "" {
		psBase = "https://api.paystack.co"
	}
	ppBase := strings.TrimRight(strings.TrimSpace(os.Getenv("PAYPAL_API_BASE")), "/")
	if ppBase == "" {
		ppBase = "https://api-m.paypal.com"
	}
	return GatewayConfig{
		PaystackSecretKey: strings.TrimSpace(os.Getenv("PAYSTACK_SECRET_KEY")),
		PaystackAPIBase:   psBase,
		PayPalClientID:    strings.TrimSpace(os.Getenv("PAYPAL_CLIENT_ID")),
		PayPalSecret:      strings.TrimSpace(os.Getenv("PAYPAL_CLIENT_SECRET")),
		PayPalWebhookID:   strings.TrimSpace(os.Getenv("PAYPAL_WEBHOOK_ID")),
		PayPalAPIBase:     ppBase,
	}
}

type PaymentGateways struct {
	mu           sync.Mutex
	cfg          GatewayConfig
	httpClient   *http.Client
	ppToken      string
	ppTokenExpAt time.Time
}

func NewPaymentGateways(cfg GatewayConfig) *PaymentGateways {
	return &PaymentGateways{
		cfg:        cfg,
		httpClient: &http.Client{Timeout: 20 * time.Second},
	}
}

func (g *PaymentGateways) SetConfigForTest(cfg GatewayConfig) {
	g.mu.Lock()
	g.cfg = cfg
	g.ppToken = ""
	g.ppTokenExpAt = time.Time{}
	g.mu.Unlock()
}

func (g *PaymentGateways) getConfig() GatewayConfig {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.cfg
}

// ---------- PAYSTACK ----------

type paystackInitRequest struct {
	Email       string         `json:"email"`
	Amount      int64          `json:"amount"`
	Currency    string         `json:"currency"`
	Reference   string         `json:"reference"`
	CallbackURL string         `json:"callback_url,omitempty"`
	Metadata    map[string]any `json:"metadata,omitempty"`
}

type paystackInitResponse struct {
	Status  bool   `json:"status"`
	Message string `json:"message"`
	Data    struct {
		AuthorizationURL string `json:"authorization_url"`
		AccessCode       string `json:"access_code"`
		Reference        string `json:"reference"`
	} `json:"data"`
}

type paystackVerifyResponse struct {
	Status  bool   `json:"status"`
	Message string `json:"message"`
	Data    struct {
		ID        int64          `json:"id"`
		Status    string         `json:"status"`
		Reference string         `json:"reference"`
		Amount    int64          `json:"amount"`
		Currency  string         `json:"currency"`
		Metadata  map[string]any `json:"metadata"`
	} `json:"data"`
}

func (g *PaymentGateways) InitializePaystack(email, reference string, amountMinor int64, currency, callbackURL string, metadata map[string]any) (string, string, error) {
	cfg := g.getConfig()
	if cfg.PaystackSecretKey == "" {
		return "", "", ErrGatewayUnconfigured
	}
	if strings.TrimSpace(email) == "" {
		email = "stars-buyer@telefam.company"
	}
	bodyBytes, _ := json.Marshal(paystackInitRequest{
		Email:       email,
		Amount:      amountMinor,
		Currency:    currency,
		Reference:   reference,
		CallbackURL: callbackURL,
		Metadata:    metadata,
	})
	req, err := http.NewRequest(http.MethodPost, cfg.PaystackAPIBase+"/transaction/initialize", bytes.NewReader(bodyBytes))
	if err != nil {
		return "", "", err
	}
	req.Header.Set("Authorization", "Bearer "+cfg.PaystackSecretKey)
	req.Header.Set("Content-Type", "application/json")

	resp, err := g.httpClient.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 300 {
		return "", "", fmt.Errorf("%w: paystack status %d", ErrGatewayRejected, resp.StatusCode)
	}
	var parsed paystackInitResponse
	if err := json.Unmarshal(raw, &parsed); err != nil || !parsed.Status || parsed.Data.AuthorizationURL == "" {
		return "", "", fmt.Errorf("%w: %s", ErrGatewayRejected, parsed.Message)
	}
	ref := parsed.Data.Reference
	if ref == "" {
		ref = reference
	}
	return ref, parsed.Data.AuthorizationURL, nil
}

func (g *PaymentGateways) VerifyPaystackTransaction(reference string) (paidMinor int64, currency, status, userIDMeta, idempMeta string, err error) {
	cfg := g.getConfig()
	if cfg.PaystackSecretKey == "" {
		return 0, "", "", "", "", ErrGatewayUnconfigured
	}
	endpoint := cfg.PaystackAPIBase + "/transaction/verify/" + url.PathEscape(reference)
	req, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		return 0, "", "", "", "", err
	}
	req.Header.Set("Authorization", "Bearer "+cfg.PaystackSecretKey)

	resp, err := g.httpClient.Do(req)
	if err != nil {
		return 0, "", "", "", "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 300 {
		return 0, "", "", "", "", fmt.Errorf("%w: paystack verify %d", ErrGatewayRejected, resp.StatusCode)
	}
	var parsed paystackVerifyResponse
	if err := json.Unmarshal(raw, &parsed); err != nil || !parsed.Status {
		return 0, "", "", "", "", fmt.Errorf("%w: paystack verify decode failed", ErrGatewayRejected)
	}
	if parsed.Data.Metadata != nil {
		if u, ok := parsed.Data.Metadata["user_id"].(string); ok {
			userIDMeta = u
		}
		if k, ok := parsed.Data.Metadata["idempotency_key"].(string); ok {
			idempMeta = k
		}
	}
	return parsed.Data.Amount, strings.ToUpper(strings.TrimSpace(parsed.Data.Currency)), strings.ToLower(strings.TrimSpace(parsed.Data.Status)), userIDMeta, idempMeta, nil
}

// VerifyPaystackSignature checks X-Paystack-Signature using constant-time HMAC-SHA512 over raw payload bytes.
func (g *PaymentGateways) VerifyPaystackSignature(rawBody []byte, sigHeader string) bool {
	cfg := g.getConfig()
	if cfg.PaystackSecretKey == "" || strings.TrimSpace(sigHeader) == "" {
		return false
	}
	mac := hmac.New(sha512.New, []byte(cfg.PaystackSecretKey))
	_, _ = mac.Write(rawBody)
	expected := mac.Sum(nil)
	provided, err := hex.DecodeString(strings.TrimSpace(sigHeader))
	if err != nil || len(provided) != len(expected) {
		return false
	}
	return hmac.Equal(expected, provided)
}

// ---------- PAYPAL ORDERS v2 + WEBHOOK SIGNATURE ----------

func (g *PaymentGateways) getPayPalAccessToken() (string, error) {
	g.mu.Lock()
	cfg := g.cfg
	if g.ppToken != "" && time.Now().Before(g.ppTokenExpAt) {
		tok := g.ppToken
		g.mu.Unlock()
		return tok, nil
	}
	g.mu.Unlock()

	if cfg.PayPalClientID == "" || cfg.PayPalSecret == "" {
		return "", ErrGatewayUnconfigured
	}
	form := url.Values{}
	form.Set("grant_type", "client_credentials")
	req, err := http.NewRequest(http.MethodPost, cfg.PayPalAPIBase+"/v1/oauth2/token", strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.SetBasicAuth(cfg.PayPalClientID, cfg.PayPalSecret)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := g.httpClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
	if resp.StatusCode >= 300 {
		return "", fmt.Errorf("%w: paypal oauth status %d", ErrGatewayRejected, resp.StatusCode)
	}
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || out.AccessToken == "" {
		return "", fmt.Errorf("%w: paypal oauth decode", ErrGatewayRejected)
	}
	ttl := time.Duration(out.ExpiresIn-60) * time.Second
	if ttl < 30*time.Second {
		ttl = 30 * time.Second
	}
	g.mu.Lock()
	g.ppToken = out.AccessToken
	g.ppTokenExpAt = time.Now().Add(ttl)
	g.mu.Unlock()
	return out.AccessToken, nil
}

func (g *PaymentGateways) CreatePayPalOrder(idempotencyKey string, starAmount int64, currency, valueDecimal, returnURL, cancelURL, customID string) (string, string, error) {
	cfg := g.getConfig()
	tok, err := g.getPayPalAccessToken()
	if err != nil {
		return "", "", err
	}
	payload := map[string]any{
		"intent": "CAPTURE",
		"purchase_units": []map[string]any{
			{
				"reference_id": "telefam_stars",
				"custom_id":    customID,
				"description":  fmt.Sprintf("Telefam %d Stars", starAmount),
				"amount": map[string]any{
					"currency_code": currency,
					"value":         valueDecimal,
				},
			},
		},
		"application_context": map[string]any{
			"brand_name":          "Telefam",
			"user_action":         "PAY_NOW",
			"shipping_preference": "NO_SHIPPING",
			"return_url":          returnURL,
			"cancel_url":          cancelURL,
		},
	}
	body, _ := json.Marshal(payload)
	req, err := http.NewRequest(http.MethodPost, cfg.PayPalAPIBase+"/v2/checkout/orders", bytes.NewReader(body))
	if err != nil {
		return "", "", err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("PayPal-Request-Id", idempotencyKey)

	resp, err := g.httpClient.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 300 {
		return "", "", fmt.Errorf("%w: paypal create order %d", ErrGatewayRejected, resp.StatusCode)
	}
	var out struct {
		ID    string `json:"id"`
		Links []struct {
			Href string `json:"href"`
			Rel  string `json:"rel"`
		} `json:"links"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || out.ID == "" {
		return "", "", fmt.Errorf("%w: paypal order missing id", ErrGatewayRejected)
	}
	approveURL := ""
	for _, l := range out.Links {
		if l.Rel == "approve" || l.Rel == "payer-action" {
			approveURL = l.Href
			break
		}
	}
	if approveURL == "" {
		return "", "", fmt.Errorf("%w: paypal missing approve link", ErrGatewayRejected)
	}
	return out.ID, approveURL, nil
}

type paypalOrderPayload struct {
	ID            string `json:"id"`
	Status        string `json:"status"`
	PurchaseUnits []struct {
		CustomID string `json:"custom_id"`
		Amount   struct {
			CurrencyCode string `json:"currency_code"`
			Value        string `json:"value"`
		} `json:"amount"`
		Payments struct {
			Captures []struct {
				ID     string `json:"id"`
				Status string `json:"status"`
				Amount struct {
					CurrencyCode string `json:"currency_code"`
					Value        string `json:"value"`
				} `json:"amount"`
			} `json:"captures"`
		} `json:"payments"`
	} `json:"purchase_units"`
}

func (g *PaymentGateways) VerifyAndCapturePayPalOrder(orderID, captureIdempKey string) (paidMinor int64, currency, status, customID string, err error) {
	cfg := g.getConfig()
	tok, err := g.getPayPalAccessToken()
	if err != nil {
		return 0, "", "", "", err
	}

	req, err := http.NewRequest(http.MethodGet, cfg.PayPalAPIBase+"/v2/checkout/orders/"+url.PathEscape(orderID), nil)
	if err != nil {
		return 0, "", "", "", err
	}
	req.Header.Set("Authorization", "Bearer "+tok)

	resp, err := g.httpClient.Do(req)
	if err != nil {
		return 0, "", "", "", err
	}
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	resp.Body.Close()
	if resp.StatusCode >= 300 {
		return 0, "", "", "", fmt.Errorf("%w: paypal order get %d", ErrGatewayRejected, resp.StatusCode)
	}

	var order paypalOrderPayload
	if err := json.Unmarshal(raw, &order); err != nil {
		return 0, "", "", "", err
	}

	if strings.EqualFold(order.Status, "APPROVED") {
		capReq, err := http.NewRequest(http.MethodPost, cfg.PayPalAPIBase+"/v2/checkout/orders/"+url.PathEscape(orderID)+"/capture", bytes.NewReader([]byte("{}")))
		if err != nil {
			return 0, "", "", "", err
		}
		capReq.Header.Set("Authorization", "Bearer "+tok)
		capReq.Header.Set("Content-Type", "application/json")
		if captureIdempKey != "" {
			capReq.Header.Set("PayPal-Request-Id", "cap_"+captureIdempKey)
		}
		capResp, err := g.httpClient.Do(capReq)
		if err != nil {
			return 0, "", "", "", err
		}
		capRaw, _ := io.ReadAll(io.LimitReader(capResp.Body, 1<<20))
		capResp.Body.Close()
		if capResp.StatusCode >= 300 {
			return 0, "", "", "", fmt.Errorf("%w: paypal capture %d", ErrGatewayRejected, capResp.StatusCode)
		}
		var captured paypalOrderPayload
		if err := json.Unmarshal(capRaw, &captured); err == nil && captured.ID != "" {
			order = captured
		}
	}

	valStr := ""
	curr := ""
	capStatus := strings.ToUpper(strings.TrimSpace(order.Status))
	if len(order.PurchaseUnits) > 0 {
		pu := order.PurchaseUnits[0]
		customID = pu.CustomID
		valStr = pu.Amount.Value
		curr = strings.ToUpper(strings.TrimSpace(pu.Amount.CurrencyCode))
		if len(pu.Payments.Captures) > 0 {
			capItem := pu.Payments.Captures[0]
			if capItem.Amount.Value != "" {
				valStr = capItem.Amount.Value
			}
			if capItem.Amount.CurrencyCode != "" {
				curr = strings.ToUpper(strings.TrimSpace(capItem.Amount.CurrencyCode))
			}
			if capItem.Status != "" {
				capStatus = strings.ToUpper(strings.TrimSpace(capItem.Status))
			}
		}
	}
	minor := ParseGatewayAmountToMinor(valStr, curr)
	return minor, curr, strings.ToLower(capStatus), customID, nil
}

// VerifyPayPalWebhookSignature delegates cryptographic transmission verification to PayPal's official verification endpoint
// while strictly validating the CertURL host to prevent SSRF / domain spoofing.
func (g *PaymentGateways) VerifyPayPalWebhookSignature(headers http.Header, rawBody []byte) (bool, error) {
	cfg := g.getConfig()
	if cfg.PayPalWebhookID == "" {
		return false, ErrGatewayUnconfigured
	}
	certURL := strings.TrimSpace(headers.Get("PAYPAL-CERT-URL"))
	if !isAllowedPayPalCertURL(certURL, cfg.PayPalAPIBase) {
		return false, ErrInvalidSignature
	}
	txID := strings.TrimSpace(headers.Get("PAYPAL-TRANSMISSION-ID"))
	txTime := strings.TrimSpace(headers.Get("PAYPAL-TRANSMISSION-TIME"))
	txSig := strings.TrimSpace(headers.Get("PAYPAL-TRANSMISSION-SIG"))
	authAlgo := strings.TrimSpace(headers.Get("PAYPAL-AUTH-ALGO"))
	if txID == "" || txTime == "" || txSig == "" || authAlgo == "" {
		return false, ErrInvalidSignature
	}

	tok, err := g.getPayPalAccessToken()
	if err != nil {
		return false, err
	}
	var eventObj json.RawMessage = rawBody
	verifyBody, _ := json.Marshal(map[string]any{
		"auth_algo":         authAlgo,
		"cert_url":          certURL,
		"transmission_id":   txID,
		"transmission_sig":  txSig,
		"transmission_time": txTime,
		"webhook_id":        cfg.PayPalWebhookID,
		"webhook_event":     eventObj,
	})
	req, err := http.NewRequest(http.MethodPost, cfg.PayPalAPIBase+"/v1/notifications/verify-webhook-signature", bytes.NewReader(verifyBody))
	if err != nil {
		return false, err
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")

	resp, err := g.httpClient.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 256*1024))
	if resp.StatusCode >= 300 {
		return false, fmt.Errorf("%w: paypal webhook verify %d", ErrInvalidSignature, resp.StatusCode)
	}
	var out struct {
		VerificationStatus string `json:"verification_status"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return false, err
	}
	return strings.EqualFold(out.VerificationStatus, "SUCCESS"), nil
}

func isAllowedPayPalCertURL(rawURL, configuredAPIBase string) bool {
	u, err := url.Parse(rawURL)
	if err != nil || u.Host == "" {
		return false
	}
	host := strings.ToLower(u.Hostname())
	if u.Scheme == "https" && (host == "paypal.com" || strings.HasSuffix(host, ".paypal.com")) {
		return true
	}
	// Allow exact test server host when PAYPAL_API_BASE points to a local httptest server in tests
	if apiURL, err2 := url.Parse(configuredAPIBase); err2 == nil && strings.EqualFold(apiURL.Host, u.Host) {
		return true
	}
	return false
}

func ParseGatewayAmountToMinor(valStr, currency string) int64 {
	f, err := strconv.ParseFloat(strings.TrimSpace(valStr), 64)
	if err != nil || f <= 0 {
		return 0
	}
	factor := int64(100)
	if rule, ok := currencyRulesByCode[strings.ToUpper(strings.TrimSpace(currency))]; ok {
		factor = rule.MinorFactor
	}
	return int64(math.Round(f * float64(factor)))
}
