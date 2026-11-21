package auth

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"strings"

	sanitizer "telefam.company/backend/internal/errors"
	"telefam.company/backend/internal/security"
	"telefam.company/backend/internal/stars"
)

func (h *Handler) ensureStarsService() *stars.Service {
	h.mu.Lock()
	if h.stars == nil {
		h.stars = stars.NewService(stars.HostHooks{
			ResolveUser: func(userID string) (stars.UserProfileView, bool) {
				h.mu.Lock()
				prof, hasProf := h.profiles[userID]
				email := h.userEmails[userID]
				h.mu.Unlock()
				if hasProf {
					avatar := ""
					if prof.AvatarMediaID != "" {
						avatar = "/v1/media/avatar/" + prof.AvatarMediaID
					}
					return stars.UserProfileView{
						UserID:     prof.UserID,
						Email:      email,
						FullName:   prof.FullName,
						Username:   prof.Username,
						AvatarURL:  avatar,
						CountryISO: prof.CountryISO,
					}, true
				}
				du, _, ok := h.contacts.GetUserForViewer("", userID)
				if ok {
					avatar := ""
					if du.AvatarMediaID != "" {
						avatar = "/v1/media/avatar/" + du.AvatarMediaID
					}
					return stars.UserProfileView{
						UserID:     du.UserID,
						Email:      email,
						FullName:   du.FullName,
						Username:   du.Username,
						AvatarURL:  avatar,
						CountryISO: du.CountryISO,
					}, true
				}
				return stars.UserProfileView{}, false
			},
			IsBlocked: func(a, b string) bool {
				return h.feeds.IsBlocked(a, b) || h.feeds.IsBlocked(b, a)
			},
			ChatService: h.ensureChatService,
			AuditRecord: func(userID, event, deviceFP, ipFP string, meta map[string]string) {
				h.audit.Record(userID, event, deviceFP, ipFP, meta)
			},
			PublicBase: strings.TrimSpace(os.Getenv("TELEFAM_PUBLIC_BASE_URL")),
		})
	}
	svc := h.stars
	h.mu.Unlock()
	return svc
}

func (h *Handler) handleGetStarsSummary(w http.ResponseWriter, r *http.Request) {
	claims, _, _, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	country := security.SanitizeText(r.URL.Query().Get("country"), 4, false)
	if country == "" {
		country = security.SanitizeText(r.URL.Query().Get("country_iso"), 4, false)
	}
	resp := h.ensureStarsService().GetSummary(claims.UserID, country)
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) handleCreateStarCheckout(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req stars.CreateStarCheckoutRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_star_checkout", err, sanitizer.GenericErrorMessage)
		return
	}
	if headerKey := strings.TrimSpace(r.Header.Get("Idempotency-Key")); headerKey != "" && strings.TrimSpace(req.IdempotencyKey) == "" {
		req.IdempotencyKey = headerKey
	}
	resp, err := h.ensureStarsService().CreateCheckout(claims.UserID, deviceFP, ipFP, req)
	if err != nil {
		writeStarsErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) handleVerifyStarPayment(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req stars.VerifyStarPaymentRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8*1024)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_star_verify", err, sanitizer.GenericErrorMessage)
		return
	}
	if headerKey := strings.TrimSpace(r.Header.Get("Idempotency-Key")); headerKey != "" && strings.TrimSpace(req.IdempotencyKey) == "" {
		req.IdempotencyKey = headerKey
	}
	resp, err := h.ensureStarsService().VerifyPayment(claims.UserID, deviceFP, ipFP, req)
	if err != nil {
		writeStarsErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) handleSendStars(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req stars.SendStarGiftRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64*1024)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_star_send", err, sanitizer.GenericErrorMessage)
		return
	}
	if headerKey := strings.TrimSpace(r.Header.Get("Idempotency-Key")); headerKey != "" && strings.TrimSpace(req.IdempotencyKey) == "" {
		req.IdempotencyKey = headerKey
	}
	resp, err := h.ensureStarsService().SendStars(claims.UserID, deviceFP, ipFP, req)
	if err != nil {
		writeStarsErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) handlePaystackWebhook(w http.ResponseWriter, r *http.Request) {
	rawBody, err := io.ReadAll(io.LimitReader(r.Body, 2<<20))
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "read_paystack_webhook", err, sanitizer.GenericErrorMessage)
		return
	}
	sig := r.Header.Get("X-Paystack-Signature")
	if err := h.ensureStarsService().HandlePaystackWebhook(rawBody, sig); err != nil {
		writeStarsErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"received": true})
}

func (h *Handler) handlePayPalWebhook(w http.ResponseWriter, r *http.Request) {
	rawBody, err := io.ReadAll(io.LimitReader(r.Body, 2<<20))
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "read_paypal_webhook", err, sanitizer.GenericErrorMessage)
		return
	}
	if err := h.ensureStarsService().HandlePayPalWebhook(r.Header, rawBody); err != nil {
		writeStarsErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"received": true})
}

func (h *Handler) handleStarsCallbackPage(w http.ResponseWriter, r *http.Request) {
	status := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("status")))
	ref := strings.TrimSpace(r.URL.Query().Get("reference"))
	if ref == "" {
		ref = strings.TrimSpace(r.URL.Query().Get("token"))
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
	title := "Return to Telefam"
	subtitle := "You can close this window and return to Telefam to complete verification."
	if status == "cancelled" {
		title = "Checkout Cancelled"
		subtitle = "No charge was made. Return to Telefam to choose another method."
	}
	_, _ = io.WriteString(w, `<!doctype html><html><head><meta charset="utf-8"><title>Telefam Stars</title></head><body style="font-family:sans-serif;text-align:center;padding:48px;color:#1A1C1E;"><h2 style="color:#C80A0F;">`+title+`</h2><p>`+subtitle+`</p><p><a href="telefam://stars/callback" style="display:inline-block;padding:12px 24px;background:#C80A0F;color:#FFF;text-decoration:none;border-radius:999px;font-weight:700;">Open Telefam</a></p></body></html>`)
	_ = ref
}

func writeStarsErr(w http.ResponseWriter, err error) {
	var amlErr *stars.AmlError
	switch {
	case errors.As(err, &amlErr):
		w.Header().Set("Retry-After", "60")
		sanitizer.WriteSanitizedError(w, http.StatusTooManyRequests, "stars_aml_limit", err, amlErr.Decision.Message)
	case errors.Is(err, stars.ErrIdempotencyConflict):
		sanitizer.WriteSanitizedError(w, http.StatusConflict, "stars_idempotency_conflict", err, "Idempotency key was already used with different payment parameters.")
	case errors.Is(err, stars.ErrInvalidSignature):
		sanitizer.WriteSanitizedError(w, http.StatusUnauthorized, "stars_invalid_signature", err, "Webhook signature verification failed.")
	case errors.Is(err, stars.ErrAmountMismatch):
		sanitizer.WriteSanitizedError(w, http.StatusUnprocessableEntity, "stars_amount_mismatch", err, "Verified payment amount or currency does not match the package order.")
	case errors.Is(err, stars.ErrPaymentPending):
		sanitizer.WriteSanitizedError(w, http.StatusAccepted, "stars_payment_pending", err, "Payment is still awaiting confirmation from the provider.")
	case errors.Is(err, stars.ErrGatewayUnconfigured):
		sanitizer.WriteSanitizedError(w, http.StatusServiceUnavailable, "stars_gateway_unconfigured", err, "Payment gateway credentials are not configured on the server.")
	case errors.Is(err, stars.ErrGatewayRejected):
		sanitizer.WriteSanitizedError(w, http.StatusBadGateway, "stars_gateway_rejected", err, "Payment provider rejected the transaction request.")
	case errors.Is(err, stars.ErrForbidden):
		sanitizer.WriteSanitizedError(w, http.StatusForbidden, "stars_forbidden", err, "Action not permitted.")
	case errors.Is(err, stars.ErrNotFound):
		sanitizer.WriteSanitizedError(w, http.StatusNotFound, "stars_not_found", err, "Payment or recipient not found.")
	default:
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "stars_bad_request", err, "Invalid star payment or transfer request.")
	}
}
