package auth

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"net/http"
	"net/mail"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"telefam.company/backend/internal/audit"
	"telefam.company/backend/internal/contacts"
	"telefam.company/backend/internal/errors"
	"telefam.company/backend/internal/feeds"
	"telefam.company/backend/internal/fingerprint"
	"telefam.company/backend/internal/mailer"
	"telefam.company/backend/internal/media"
	"telefam.company/backend/internal/posts"
	"telefam.company/backend/internal/ratelimit"
	"telefam.company/backend/internal/security"
)

type RequestOtpPayload struct {
	Email string `json:"email"`
}

type RequestOtpResponse struct {
	Success         bool  `json:"success"`
	CooldownSeconds int64 `json:"cooldown_seconds"`
}

type VerifyOtpPayload struct {
	Email string `json:"email"`
	Otp   string `json:"otp"`
}

type VerifyLinkPayload struct {
	Token string `json:"token"`
}

type RefreshTokenPayload struct {
	RefreshToken string `json:"refresh_token"`
}

type OAuthPayload struct {
	Provider      string `json:"provider"`
	IdentityToken string `json:"identity_token"`
	Nonce         string `json:"nonce,omitempty"`
}

type AuthSessionResponse struct {
	Success             bool   `json:"success"`
	UserID              string `json:"user_id"`
	SessionToken        string `json:"session_token"`
	RefreshToken        string `json:"refresh_token"`
	AccessExpiresInSec  int64  `json:"access_expires_in_seconds"`
	RefreshExpiresInSec int64  `json:"refresh_expires_in_seconds"`
	Email               string `json:"email"`
	SuggestedFullName   string `json:"suggested_full_name"`
	SuggestedUsername   string `json:"suggested_username"`
	ProfileCompleted    bool   `json:"profile_completed"`
}

type ProfileSetupPayload struct {
	FullName      string `json:"full_name"`
	Username      string `json:"username"`
	PhoneNumber   string `json:"phone_number"`
	CountryISO    string `json:"country_iso"`
	Gender        string `json:"gender"`
	DateOfBirth   string `json:"date_of_birth"`
	Location      string `json:"location"`
	Bio           string `json:"bio"`
	AvatarMediaID string `json:"avatar_media_id,omitempty"`
}

type MediaUploadResponse struct {
	Success           bool                   `json:"success"`
	MediaID           string                 `json:"media_id"`
	Sha256Fingerprint string                 `json:"sha256_fingerprint"`
	DHashFingerprint  string                 `json:"dhash_fingerprint"`
	ContentID         string                 `json:"content_id,omitempty"`
	ThumbnailMediaID  string                 `json:"thumbnail_media_id,omitempty"`
	PerFrameHashes    []string               `json:"per_frame_hashes,omitempty"`
	Renditions        []media.VideoRendition `json:"renditions,omitempty"`
	MimeType          string                 `json:"mime_type"`
	ByteSize          int                    `json:"byte_size"`
}

type AuditEventPayload struct {
	Action   string            `json:"action"`
	Metadata map[string]string `json:"metadata,omitempty"`
}

type SyncOperation struct {
	ClientOpID string `json:"client_op_id"`
	RoomID     string `json:"room_id"`
	Kind       string `json:"kind"`
	Payload    string `json:"payload"`
	CreatedAt  int64  `json:"created_at"`
}

type SyncBatchRequest struct {
	Operations []SyncOperation `json:"operations"`
}

type SyncBatchResponse struct {
	Success       bool     `json:"success"`
	AckedOpIDs    []string `json:"acked_op_ids"`
	ServerEpochMs int64    `json:"server_epoch_ms"`
}

type ProfileBackgroundItem struct {
	MediaID           string `json:"media_id"`
	URL               string `json:"url"`
	Sha256Fingerprint string `json:"sha256_fingerprint"`
	DHashFingerprint  string `json:"dhash_fingerprint"`
	Width             int    `json:"width"`
	Height            int    `json:"height"`
	ByteSize          int    `json:"byte_size"`
	CreatedAtMs       int64  `json:"created_at_ms"`
}

type ProfileUpdatePayload struct {
	FullName      string `json:"full_name"`
	Username      string `json:"username"`
	PhoneNumber   string `json:"phone_number"`
	CountryISO    string `json:"country_iso"`
	Gender        string `json:"gender"`
	DateOfBirth   string `json:"date_of_birth"`
	Location      string `json:"location"`
	Bio           string `json:"bio"`
	AvatarMediaID string `json:"avatar_media_id,omitempty"`
}

type BackgroundOrderPayload struct {
	MediaIDs []string `json:"media_ids"`
}

type storedProfile struct {
	UserID              string
	FullName            string
	Username            string
	PhoneE164           string
	CountryISO          string
	Gender              string
	DateOfBirth         string
	Location            string
	Bio                 string
	AvatarMediaID       string
	UsernameChangedAtMs int64
}

type ContactActionPayload struct {
	TargetUserID string `json:"target_user_id"`
}

type ContactSyncActionPayload struct {
	ClientOpID   string `json:"client_op_id"`
	TargetUserID string `json:"target_user_id"`
	Action       string `json:"action"` // "follow" or "unfollow"
	CreatedAtMs  int64  `json:"created_at_ms"`
}

type ContactSyncBatchRequest struct {
	Actions []ContactSyncActionPayload `json:"actions"`
}

const usernameChangeCooldownMs int64 = 14 * 24 * 60 * 60 * 1000
const maxProfileBackgrounds = 5

type Handler struct {
	fp          *fingerprint.Service
	limiter     *ratelimit.Limiter
	otp         *OtpService
	tokens      *TokenService
	audit       *audit.Logger
	mailer      *mailer.ResendClient
	contacts    *contacts.Service
	posts       *posts.Service
	feeds       *feeds.Engine
	mu          sync.Mutex
	profiles    map[string]storedProfile
	usernames   map[string]string
	mediaMeta   map[string]*media.ProcessedMedia
	backgrounds map[string][]ProfileBackgroundItem
	ackedOps    map[string]struct{}
}

func NewHandler(
	fp *fingerprint.Service,
	limiter *ratelimit.Limiter,
	otp *OtpService,
	tokens *TokenService,
	auditLogger *audit.Logger,
	resendMailer *mailer.ResendClient,
	contactsSvc *contacts.Service,
) *Handler {
	if contactsSvc == nil {
		contactsSvc = contacts.NewService()
	}
	return &Handler{
		fp:          fp,
		limiter:     limiter,
		otp:         otp,
		tokens:      tokens,
		audit:       auditLogger,
		mailer:      resendMailer,
		contacts:    contactsSvc,
		posts:       posts.NewService(),
		feeds:       feeds.NewEngine(),
		profiles:    make(map[string]storedProfile),
		usernames:   make(map[string]string),
		mediaMeta:   make(map[string]*media.ProcessedMedia),
		backgrounds: make(map[string][]ProfileBackgroundItem),
		ackedOps:    make(map[string]struct{}),
	}
}

func (h *Handler) SetPostsService(svc *posts.Service) {
	if svc != nil {
		h.posts = svc
	}
}

func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/auth/otp/request", h.handleRequestOtp)
	mux.HandleFunc("POST /v1/auth/otp/verify", h.handleVerifyOtp)
	mux.HandleFunc("POST /v1/auth/verify-link", h.handleVerifyLink)
	mux.HandleFunc("GET /verify", h.handleWebVerifyRedirect)
	mux.HandleFunc("POST /v1/auth/token/refresh", h.handleRefreshToken)
	mux.HandleFunc("POST /v1/auth/oauth/google", h.handleGoogleOAuth)
	mux.HandleFunc("POST /v1/auth/oauth/apple", h.handleAppleOAuth)
	mux.HandleFunc("POST /v1/media/avatar", h.handleAvatarUpload)
	mux.HandleFunc("GET /v1/media/avatar/{id}", h.handleAvatarDownload)
	mux.HandleFunc("POST /v1/media/background", h.handleProfileBackgroundUpload)
	mux.HandleFunc("POST /v1/media/photo", h.handlePostPhotoUpload)
	mux.HandleFunc("POST /v1/media/video", h.handlePostVideoUpload)
	mux.HandleFunc("GET /v1/media/asset/{id}", h.handleAvatarDownload)
	mux.HandleFunc("GET /v1/songs/search", h.handleSearchSongs)
	mux.HandleFunc("GET /v1/hashtags/search", h.handleSearchHashtags)
	mux.HandleFunc("POST /v1/posts", h.handleCreatePost)
	mux.HandleFunc("PATCH /v1/posts/{id}", h.handleUpdatePost)
	mux.HandleFunc("DELETE /v1/posts/{id}", h.handleDeletePost)
	mux.HandleFunc("GET /v1/feeds", h.handleGetFeeds)
	mux.HandleFunc("GET /v1/feeds/search", h.handleSearchFeeds)
	mux.HandleFunc("POST /v1/feeds/interact", h.handleFeedInteract)
	mux.HandleFunc("POST /v1/feeds/interact/batch", h.handleFeedInteractBatch)
	mux.HandleFunc("GET /v1/feeds/{id}/download", h.handleFeedDownload)
	mux.HandleFunc("GET /v1/profile/check-username", h.handleCheckUsername)
	mux.HandleFunc("POST /v1/profile/setup", h.handleProfileSetup)
	mux.HandleFunc("PATCH /v1/profile", h.handleUpdateProfile)
	mux.HandleFunc("POST /v1/profile/update", h.handleUpdateProfile)
	mux.HandleFunc("POST /v1/profile/backgrounds", h.handleAttachProfileBackground)
	mux.HandleFunc("PUT /v1/profile/backgrounds", h.handleReorderProfileBackgrounds)
	mux.HandleFunc("DELETE /v1/profile/backgrounds/{id}", h.handleDeleteProfileBackground)
	mux.HandleFunc("GET /v1/users/{id}/profile", h.handleGetUserProfile)
	mux.HandleFunc("GET /v1/users/{id}/connections", h.handleGetUserConnections)
	mux.HandleFunc("GET /v1/users/{id}/followers", h.handleGetUserFollowers)
	mux.HandleFunc("GET /v1/users/{id}/following", h.handleGetUserFollowing)
	mux.HandleFunc("POST /v1/users/{id}/block", h.handleBlockUser)
	mux.HandleFunc("POST /v1/users/{id}/unblock", h.handleUnblockUser)
	mux.HandleFunc("POST /v1/users/{id}/report", h.handleReportUser)
	mux.HandleFunc("GET /v1/contacts", h.handleListContacts)
	mux.HandleFunc("POST /v1/contacts/follow", h.handleFollowContact)
	mux.HandleFunc("POST /v1/contacts/unfollow", h.handleUnfollowContact)
	mux.HandleFunc("POST /v1/contacts/sync", h.handleSyncContacts)
	mux.HandleFunc("POST /v1/audit/event", h.handleAuditEvent)
	mux.HandleFunc("POST /v1/sync/batch", h.handleSyncBatch)
	mux.HandleFunc("GET /healthz", h.handleHealth)
}

func (h *Handler) handleWebVerifyRedirect(w http.ResponseWriter, r *http.Request) {
	token := strings.TrimSpace(r.URL.Query().Get("token"))
	if token == "" || len(token) > 256 {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "web_verify_missing_token", nil, sanitizer.GenericErrorMessage)
		return
	}
	deepLink := "telefam://verify?token=" + url.QueryEscape(token)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
	w.WriteHeader(http.StatusOK)
	_, _ = fmt.Fprintf(
		w,
		`<!doctype html><html><head><meta charset="utf-8"><title>Telefam Verification</title></head>`+
			`<body style="font-family:Arial,sans-serif;text-align:center;padding:48px;color:#1A1C1E;">`+
			`<h2 style="color:#C80A0F;">Open Telefam</h2>`+
			`<p>Tap the button below to finish verifying your account in the Telefam app.</p>`+
			`<p><a href="%s" style="display:inline-block;padding:12px 24px;background:#C80A0F;color:#FFFFFF;text-decoration:none;border-radius:10px;font-weight:600;">Continue to Telefam</a></p>`+
			`</body></html>`,
		html.EscapeString(deepLink),
	)
}

func (h *Handler) handleRequestOtp(w http.ResponseWriter, r *http.Request) {
	var req RequestOtpPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_otp_req", err, sanitizer.GenericErrorMessage)
		return
	}

	email := strings.ToLower(strings.TrimSpace(req.Email))
	if _, err := mail.ParseAddress(email); err != nil || !strings.Contains(email, ".") {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_email", err, sanitizer.GenericErrorMessage)
		return
	}

	ctx, err := h.fp.BuildContext(r, email)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "fingerprint_check", err, sanitizer.GenericErrorMessage)
		return
	}

	if h.mailer == nil || !h.mailer.IsConfigured() {
		sanitizer.WriteSanitizedError(w, http.StatusServiceUnavailable, "mailer_not_configured", errors.New("RESEND_API_KEY missing"), sanitizer.GenericErrorMessage)
		return
	}

	check := h.limiter.CheckOtpAllowed(ctx.AccountHash, ctx.DeviceFingerprint, ctx.IPFingerprint)
	if !check.Allowed {
		h.audit.Record(ctx.AccountHash, "otp_rate_limited", ctx.DeviceFingerprint, ctx.IPFingerprint, nil)
		sanitizer.WriteSanitizedError(w, http.StatusTooManyRequests, "rate_limited_otp_request", nil, sanitizer.TryLaterMessage)
		return
	}

	issued, err := h.otp.IssueChallenge(email, ctx.AccountHash, ctx.DeviceFingerprint)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusInternalServerError, "issue_otp", err, sanitizer.GenericErrorMessage)
		return
	}

	if err := h.mailer.SendOtpAndVerificationLink(r.Context(), email, issued.OtpCode, issued.VerificationLink); err != nil {
		h.otp.CancelChallenge(ctx.AccountHash)
		h.audit.Record(ctx.AccountHash, "otp_mail_dispatch_failed", ctx.DeviceFingerprint, ctx.IPFingerprint, nil)
		sanitizer.WriteSanitizedError(w, http.StatusBadGateway, "resend_send_otp", err, sanitizer.GenericErrorMessage)
		return
	}

	decision := h.limiter.CommitOtpSent(ctx.AccountHash, ctx.DeviceFingerprint, ctx.IPFingerprint)
	h.audit.Record(ctx.AccountHash, "otp_requested", ctx.DeviceFingerprint, ctx.IPFingerprint, nil)

	writeJSON(w, http.StatusOK, RequestOtpResponse{
		Success:         true,
		CooldownSeconds: decision.CooldownSeconds,
	})
}

func (h *Handler) handleVerifyOtp(w http.ResponseWriter, r *http.Request) {
	var req VerifyOtpPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_verify_otp", err, sanitizer.GenericErrorMessage)
		return
	}

	ctx, err := h.fp.BuildContext(r, req.Email)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "verify_fp", err, sanitizer.GenericErrorMessage)
		return
	}

	if !h.limiter.CheckVerifyAttempt(ctx.AccountHash, ctx.DeviceFingerprint, ctx.IPFingerprint) {
		h.audit.Record(ctx.AccountHash, "otp_verify_rate_limited", ctx.DeviceFingerprint, ctx.IPFingerprint, nil)
		sanitizer.WriteSanitizedError(w, http.StatusTooManyRequests, "verify_rate_limited", nil, sanitizer.TryLaterMessage)
		return
	}

	verifiedEmail, err := h.otp.VerifyCode(ctx.AccountHash, req.Otp)
	if err != nil {
		h.audit.Record(ctx.AccountHash, "otp_verify_failed", ctx.DeviceFingerprint, ctx.IPFingerprint, nil)
		sanitizer.WriteSanitizedError(w, http.StatusUnauthorized, "otp_mismatch", err, sanitizer.GenericErrorMessage)
		return
	}

	h.limiter.ResetAccountCooldown(ctx.AccountHash, ctx.DeviceFingerprint)
	h.issueAuthResponse(w, ctx.AccountHash, verifiedEmail, ctx.DeviceFingerprint, ctx.IPFingerprint)
}

func (h *Handler) handleVerifyLink(w http.ResponseWriter, r *http.Request) {
	var req VerifyLinkPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_verify_link", err, sanitizer.GenericErrorMessage)
		return
	}

	deviceFP, err := h.fp.ExtractDeviceFingerprint(r)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "link_device_fp", err, sanitizer.GenericErrorMessage)
		return
	}
	ipFP := h.fp.ExtractIPFingerprint(r)

	verifiedEmail, accountHash, err := h.otp.VerifyMagicLink(req.Token)
	if err != nil {
		h.audit.Record("", "link_verify_failed", deviceFP, ipFP, nil)
		sanitizer.WriteSanitizedError(w, http.StatusUnauthorized, "link_invalid", err, sanitizer.GenericErrorMessage)
		return
	}

	h.limiter.ResetAccountCooldown(accountHash, deviceFP)
	h.issueAuthResponse(w, accountHash, verifiedEmail, deviceFP, ipFP)
}

func (h *Handler) handleRefreshToken(w http.ResponseWriter, r *http.Request) {
	var req RefreshTokenPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_refresh", err, sanitizer.GenericErrorMessage)
		return
	}
	deviceFP, err := h.fp.ExtractDeviceFingerprint(r)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "refresh_fp", err, sanitizer.GenericErrorMessage)
		return
	}
	ipFP := h.fp.ExtractIPFingerprint(r)

	pair, userID, familyID, err := h.tokens.RotateRefreshToken(req.RefreshToken, deviceFP)
	if err != nil {
		action := "token_refresh_failed"
		if errors.Is(err, ErrTokenReuseAttack) {
			action = "token_reuse_attack_revoked"
		}
		h.audit.Record(userID, action, deviceFP, ipFP, map[string]string{"family": familyID})
		sanitizer.WriteSanitizedError(w, http.StatusUnauthorized, action, err, sanitizer.GenericErrorMessage)
		return
	}

	h.audit.Record(userID, "token_rotated", deviceFP, ipFP, map[string]string{"family": familyID})
	writeJSON(w, http.StatusOK, AuthSessionResponse{
		Success:             true,
		SessionToken:        pair.AccessToken,
		RefreshToken:        pair.RefreshToken,
		AccessExpiresInSec:  pair.AccessExpiresInSec,
		RefreshExpiresInSec: pair.RefreshExpiresInSec,
	})
}

func (h *Handler) handleGoogleOAuth(w http.ResponseWriter, r *http.Request) {
	h.handleExternalOAuth(w, r, "google")
}

func (h *Handler) handleAppleOAuth(w http.ResponseWriter, r *http.Request) {
	h.handleExternalOAuth(w, r, "apple")
}

func (h *Handler) handleExternalOAuth(w http.ResponseWriter, r *http.Request, expectedProvider string) {
	var req OAuthPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_oauth", err, sanitizer.GenericErrorMessage)
		return
	}
	if req.Provider != expectedProvider || strings.TrimSpace(req.IdentityToken) == "" {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "oauth_payload", errors.New("missing token"), sanitizer.GenericErrorMessage)
		return
	}
	deviceFP, err := h.fp.ExtractDeviceFingerprint(r)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "oauth_fp", err, sanitizer.GenericErrorMessage)
		return
	}
	ipFP := h.fp.ExtractIPFingerprint(r)
	userID := h.fp.HashAccount(expectedProvider + ":" + req.IdentityToken[:min(16, len(req.IdentityToken))])
	h.issueAuthResponse(w, userID, "", deviceFP, ipFP)
}

func (h *Handler) handleAvatarUpload(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}

	cropX, _ := strconv.ParseFloat(r.URL.Query().Get("cx"), 64)
	cropY, _ := strconv.ParseFloat(r.URL.Query().Get("cy"), 64)
	cropScale, _ := strconv.ParseFloat(r.URL.Query().Get("scale"), 64)

	processed, err := media.SanitizeCropAndCompressAvatar(r.Body, cropX, cropY, cropScale)
	if err != nil {
		h.audit.Record(claims.UserID, "media_upload_rejected", deviceFP, ipFP, nil)
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "avatar_process", err, sanitizer.GenericErrorMessage)
		return
	}

	h.mu.Lock()
	h.mediaMeta[processed.MediaID] = processed
	h.mu.Unlock()

	h.audit.Record(claims.UserID, "media_avatar_uploaded", deviceFP, ipFP, map[string]string{
		"media_id": processed.MediaID,
		"sha256":   processed.Sha256Fingerprint,
		"dhash":    processed.DHashFingerprint,
	})

	writeJSON(w, http.StatusOK, MediaUploadResponse{
		Success:           true,
		MediaID:           processed.MediaID,
		Sha256Fingerprint: processed.Sha256Fingerprint,
		DHashFingerprint:  processed.DHashFingerprint,
		MimeType:          processed.MimeType,
		ByteSize:          len(processed.EncodedBytes),
	})
}

func (h *Handler) handleCheckUsername(w http.ResponseWriter, r *http.Request) {
	username, err := security.SanitizeUsername(r.URL.Query().Get("u"))
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"available": false})
		return
	}
	h.mu.Lock()
	_, taken := h.usernames[username]
	h.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"available": !taken})
}

func (h *Handler) handleProfileSetup(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}

	var req ProfileSetupPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_profile", err, sanitizer.GenericErrorMessage)
		return
	}

	fullName := security.SanitizeText(req.FullName, 64, false)
	username, err := security.SanitizeUsername(req.Username)
	if err != nil || fullName == "" {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_profile_identity", err, sanitizer.GenericErrorMessage)
		return
	}

	phoneE164, countryISO, err := security.SanitizePhoneE164(req.PhoneNumber, req.CountryISO)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_phone", err, sanitizer.GenericErrorMessage)
		return
	}

	gender, err := security.ValidateGender(req.Gender)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_gender", err, sanitizer.GenericErrorMessage)
		return
	}

	if _, err := security.ValidateDateOfBirthAtLeast13(req.DateOfBirth, time.Now()); err != nil {
		h.audit.Record(claims.UserID, "profile_age_rejected", deviceFP, ipFP, nil)
		sanitizer.WriteSanitizedError(w, http.StatusUnprocessableEntity, "under_13", err, "You must be at least 13 years old.")
		return
	}

	location := security.SanitizeText(req.Location, 80, false)
	if location == "" {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "missing_location", nil, sanitizer.GenericErrorMessage)
		return
	}
	bio := security.SanitizeText(req.Bio, 150, true)

	avatarID := security.SanitizeText(req.AvatarMediaID, 40, false)
	nowMs := time.Now().UnixMilli()
	h.mu.Lock()
	if owner, taken := h.usernames[username]; taken && owner != claims.UserID {
		h.mu.Unlock()
		sanitizer.WriteSanitizedError(w, http.StatusConflict, "username_taken", nil, "This username is already taken.")
		return
	}
	if prev, hasPrev := h.profiles[claims.UserID]; hasPrev && prev.Username != "" && !strings.EqualFold(prev.Username, username) {
		delete(h.usernames, prev.Username)
	}
	h.usernames[username] = claims.UserID
	h.profiles[claims.UserID] = storedProfile{
		UserID:              claims.UserID,
		FullName:            fullName,
		Username:            username,
		PhoneE164:           phoneE164,
		CountryISO:          countryISO,
		Gender:              gender,
		DateOfBirth:         req.DateOfBirth,
		Location:            location,
		Bio:                 bio,
		AvatarMediaID:       avatarID,
		UsernameChangedAtMs: nowMs,
	}
	h.mu.Unlock()

	h.contacts.RegisterOrUpdateUser(contacts.DirectoryUser{
		UserID:        claims.UserID,
		FullName:      fullName,
		Username:      username,
		Country:       location,
		CountryISO:    countryISO,
		AvatarMediaID: avatarID,
		CreatedAt:     time.Now().UTC(),
	}, deviceFP)

	h.audit.Record(claims.UserID, "profile_completed", deviceFP, ipFP, map[string]string{
		"username": username,
		"country":  countryISO,
	})

	writeJSON(w, http.StatusOK, map[string]any{"success": true})
}

func (h *Handler) handleProfileBackgroundUpload(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}

	h.mu.Lock()
	currentCount := len(h.backgrounds[claims.UserID])
	h.mu.Unlock()
	if currentCount >= maxProfileBackgrounds {
		sanitizer.WriteSanitizedError(w, http.StatusConflict, "background_limit_reached", nil, "You can upload up to 5 background photos.")
		return
	}

	processed, err := media.SanitizeCompressAndFingerprintProfileBackground(r.Body)
	if err != nil {
		h.audit.Record(claims.UserID, "profile_background_rejected", deviceFP, ipFP, nil)
		status := http.StatusBadRequest
		if errors.Is(err, media.ErrPayloadTooLarge) {
			status = http.StatusRequestEntityTooLarge
		}
		sanitizer.WriteSanitizedError(w, status, "profile_background_process", err, sanitizer.GenericErrorMessage)
		return
	}

	bgItem := ProfileBackgroundItem{
		MediaID:           processed.MediaID,
		URL:               "/v1/media/asset/" + processed.MediaID,
		Sha256Fingerprint: processed.Sha256Fingerprint,
		DHashFingerprint:  processed.DHashFingerprint,
		Width:             processed.Width,
		Height:            processed.Height,
		ByteSize:          len(processed.EncodedBytes),
		CreatedAtMs:       time.Now().UnixMilli(),
	}

	h.mu.Lock()
	if len(h.backgrounds[claims.UserID]) >= maxProfileBackgrounds {
		h.mu.Unlock()
		sanitizer.WriteSanitizedError(w, http.StatusConflict, "background_limit_reached", nil, "You can upload up to 5 background photos.")
		return
	}
	h.mediaMeta[processed.MediaID] = processed
	h.backgrounds[claims.UserID] = append(h.backgrounds[claims.UserID], bgItem)
	updatedList := append([]ProfileBackgroundItem(nil), h.backgrounds[claims.UserID]...)
	h.mu.Unlock()

	h.audit.Record(claims.UserID, "profile_background_uploaded", deviceFP, ipFP, map[string]string{
		"media_id": processed.MediaID,
		"sha256":   processed.Sha256Fingerprint,
		"dhash":    processed.DHashFingerprint,
	})

	writeJSON(w, http.StatusOK, map[string]any{
		"success":            true,
		"media_id":           processed.MediaID,
		"sha256_fingerprint": processed.Sha256Fingerprint,
		"dhash_fingerprint":  processed.DHashFingerprint,
		"background":         bgItem,
		"backgrounds":        updatedList,
	})
}

func (h *Handler) handleAttachProfileBackground(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var payload struct {
		MediaID string `json:"media_id"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&payload); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_attach_bg", err, sanitizer.GenericErrorMessage)
		return
	}
	mediaID := security.SanitizeText(payload.MediaID, 48, false)
	if mediaID == "" {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "missing_bg_media_id", nil, sanitizer.GenericErrorMessage)
		return
	}

	h.mu.Lock()
	m, exists := h.mediaMeta[mediaID]
	if !exists || m == nil {
		h.mu.Unlock()
		sanitizer.WriteSanitizedError(w, http.StatusNotFound, "bg_media_not_found", nil, "Background image not found.")
		return
	}
	existing := h.backgrounds[claims.UserID]
	for _, item := range existing {
		if item.MediaID == mediaID {
			snapshot := append([]ProfileBackgroundItem(nil), existing...)
			h.mu.Unlock()
			writeJSON(w, http.StatusOK, map[string]any{"success": true, "backgrounds": snapshot})
			return
		}
	}
	if len(existing) >= maxProfileBackgrounds {
		h.mu.Unlock()
		sanitizer.WriteSanitizedError(w, http.StatusConflict, "background_limit_reached", nil, "You can upload up to 5 background photos.")
		return
	}
	bgItem := ProfileBackgroundItem{
		MediaID:           m.MediaID,
		URL:               "/v1/media/asset/" + m.MediaID,
		Sha256Fingerprint: m.Sha256Fingerprint,
		DHashFingerprint:  m.DHashFingerprint,
		Width:             m.Width,
		Height:            m.Height,
		ByteSize:          len(m.EncodedBytes),
		CreatedAtMs:       time.Now().UnixMilli(),
	}
	h.backgrounds[claims.UserID] = append(existing, bgItem)
	updatedList := append([]ProfileBackgroundItem(nil), h.backgrounds[claims.UserID]...)
	h.mu.Unlock()

	h.audit.Record(claims.UserID, "profile_background_attached", deviceFP, ipFP, map[string]string{
		"media_id": mediaID,
		"sha256":   m.Sha256Fingerprint,
	})
	writeJSON(w, http.StatusOK, map[string]any{"success": true, "backgrounds": updatedList})
}

func (h *Handler) handleReorderProfileBackgrounds(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var payload BackgroundOrderPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&payload); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_reorder_bg", err, sanitizer.GenericErrorMessage)
		return
	}
	h.mu.Lock()
	cur := h.backgrounds[claims.UserID]
	byID := make(map[string]ProfileBackgroundItem, len(cur))
	for _, b := range cur {
		byID[b.MediaID] = b
	}
	reordered := make([]ProfileBackgroundItem, 0, len(cur))
	seen := make(map[string]struct{}, len(cur))
	for _, rawID := range payload.MediaIDs {
		id := security.SanitizeText(rawID, 48, false)
		if b, found := byID[id]; found {
			if _, dup := seen[id]; !dup {
				seen[id] = struct{}{}
				reordered = append(reordered, b)
			}
		}
	}
	for _, b := range cur {
		if _, dup := seen[b.MediaID]; !dup {
			reordered = append(reordered, b)
		}
	}
	h.backgrounds[claims.UserID] = reordered
	updatedList := append([]ProfileBackgroundItem(nil), reordered...)
	h.mu.Unlock()

	h.audit.Record(claims.UserID, "profile_backgrounds_reordered", deviceFP, ipFP, map[string]string{"count": strconv.Itoa(len(updatedList))})
	writeJSON(w, http.StatusOK, map[string]any{"success": true, "backgrounds": updatedList})
}

func (h *Handler) handleDeleteProfileBackground(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	targetMediaID := security.SanitizeText(r.PathValue("id"), 48, false)
	if targetMediaID == "" {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "missing_bg_id", nil, sanitizer.GenericErrorMessage)
		return
	}

	h.mu.Lock()
	cur := h.backgrounds[claims.UserID]
	next := make([]ProfileBackgroundItem, 0, len(cur))
	removed := false
	for _, item := range cur {
		if item.MediaID == targetMediaID {
			removed = true
			continue
		}
		next = append(next, item)
	}
	if !removed {
		h.mu.Unlock()
		sanitizer.WriteSanitizedError(w, http.StatusNotFound, "bg_not_found", nil, "Background photo not found.")
		return
	}
	h.backgrounds[claims.UserID] = next
	h.mu.Unlock()

	h.audit.Record(claims.UserID, "profile_background_deleted", deviceFP, ipFP, map[string]string{"media_id": targetMediaID})
	writeJSON(w, http.StatusOK, map[string]any{"success": true, "backgrounds": next})
}

func (h *Handler) handleUpdateProfile(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req ProfileUpdatePayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_profile_update", err, sanitizer.GenericErrorMessage)
		return
	}

	fullName := security.SanitizeText(req.FullName, 64, false)
	if fullName == "" {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_full_name", nil, "Full name is required.")
		return
	}
	username, err := security.SanitizeUsername(req.Username)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_username", err, "Invalid username format.")
		return
	}

	location := security.SanitizeText(req.Location, 80, false)
	if location == "" {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "missing_location", nil, "Location is required.")
		return
	}
	bio := security.SanitizeText(req.Bio, 150, true)

	countryISO := strings.ToUpper(security.SanitizeText(req.CountryISO, 2, false))
	var phoneE164 string
	if strings.TrimSpace(req.PhoneNumber) != "" {
		phoneE164, countryISO, err = security.SanitizePhoneE164(req.PhoneNumber, countryISO)
		if err != nil {
			sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_phone", err, "Invalid phone number.")
			return
		}
	}

	var gender string
	if strings.TrimSpace(req.Gender) != "" {
		gender, err = security.ValidateGender(req.Gender)
		if err != nil {
			sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_gender", err, "Invalid gender.")
			return
		}
	}

	dob := strings.TrimSpace(req.DateOfBirth)
	if dob != "" {
		if _, err := security.ValidateDateOfBirthAtLeast13(dob, time.Now()); err != nil {
			h.audit.Record(claims.UserID, "profile_age_rejected", deviceFP, ipFP, nil)
			sanitizer.WriteSanitizedError(w, http.StatusUnprocessableEntity, "under_13", err, "You must be at least 13 years old.")
			return
		}
	}

	avatarID := security.SanitizeText(req.AvatarMediaID, 40, false)
	nowMs := time.Now().UnixMilli()

	h.mu.Lock()
	prev, hasPrev := h.profiles[claims.UserID]
	dirUser, hasDirUser, _, _ := h.contacts.GetUserRelationshipForViewer(claims.UserID, claims.UserID)
	oldUsername := prev.Username
	if oldUsername == "" && hasDirUser {
		oldUsername = dirUser.Username
	}
	if phoneE164 == "" {
		phoneE164 = prev.PhoneE164
	}
	if countryISO == "" {
		if prev.CountryISO != "" {
			countryISO = prev.CountryISO
		} else {
			countryISO = dirUser.CountryISO
		}
	}
	if gender == "" {
		gender = prev.Gender
	}
	if dob == "" {
		dob = prev.DateOfBirth
	}
	if avatarID == "" {
		if prev.AvatarMediaID != "" {
			avatarID = prev.AvatarMediaID
		} else {
			avatarID = dirUser.AvatarMediaID
		}
	}

	usernameChangedAt := prev.UsernameChangedAtMs
	isChangingUsername := oldUsername != "" && !strings.EqualFold(oldUsername, username)
	if isChangingUsername {
		if owner, taken := h.usernames[username]; taken && owner != claims.UserID {
			h.mu.Unlock()
			sanitizer.WriteSanitizedError(w, http.StatusConflict, "username_taken", nil, "This username is already taken.")
			return
		}
		if usernameChangedAt > 0 && (nowMs-usernameChangedAt) < usernameChangeCooldownMs {
			nextMs := usernameChangedAt + usernameChangeCooldownMs
			h.mu.Unlock()
			writeJSON(w, http.StatusTooManyRequests, map[string]any{
				"success":                 false,
				"error":                   "Username can only be changed once every 2 weeks.",
				"username_changed_at_ms":  usernameChangedAt,
				"next_username_change_ms": nextMs,
			})
			return
		}
		delete(h.usernames, oldUsername)
		usernameChangedAt = nowMs
	} else if usernameChangedAt == 0 {
		usernameChangedAt = nowMs
	}
	if owner, taken := h.usernames[username]; taken && owner != claims.UserID {
		h.mu.Unlock()
		sanitizer.WriteSanitizedError(w, http.StatusConflict, "username_taken", nil, "This username is already taken.")
		return
	}
	h.usernames[username] = claims.UserID

	updated := storedProfile{
		UserID:              claims.UserID,
		FullName:            fullName,
		Username:            username,
		PhoneE164:           phoneE164,
		CountryISO:          countryISO,
		Gender:              gender,
		DateOfBirth:         dob,
		Location:            location,
		Bio:                 bio,
		AvatarMediaID:       avatarID,
		UsernameChangedAtMs: usernameChangedAt,
	}
	if !hasPrev && updated.CountryISO == "" {
		updated.CountryISO = "KE"
	}
	h.profiles[claims.UserID] = updated
	h.mu.Unlock()

	h.contacts.RegisterOrUpdateUser(contacts.DirectoryUser{
		UserID:        claims.UserID,
		FullName:      fullName,
		Username:      username,
		Country:       location,
		CountryISO:    updated.CountryISO,
		AvatarMediaID: avatarID,
		CreatedAt:     time.Now().UTC(),
	}, deviceFP)

	h.audit.Record(claims.UserID, "profile_updated", deviceFP, ipFP, map[string]string{
		"username": username,
		"country":  updated.CountryISO,
	})

	nextChangeMs := int64(0)
	if updated.UsernameChangedAtMs > 0 {
		nextChangeMs = updated.UsernameChangedAtMs + usernameChangeCooldownMs
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"success":                 true,
		"username":                updated.Username,
		"full_name":               updated.FullName,
		"username_changed_at_ms":  updated.UsernameChangedAtMs,
		"next_username_change_ms": nextChangeMs,
	})
}

type UserProfileVideoItem struct {
	PostID       string `json:"post_id"`
	ThumbnailURL string `json:"thumbnail_url"`
	VideoURL     string `json:"video_url"`
	ViewsCount   int64  `json:"views_count"`
	DurationMs   int64  `json:"duration_ms"`
	Caption      string `json:"caption"`
	CreatedAtMs  int64  `json:"created_at_ms"`
}

type UserProfileResponse struct {
	Success              bool                    `json:"success"`
	UserID               string                  `json:"user_id"`
	FullName             string                  `json:"full_name"`
	Username             string                  `json:"username"`
	AvatarURL            string                  `json:"avatar_url"`
	Bio                  string                  `json:"bio"`
	Location             string                  `json:"location"`
	CountryISO           string                  `json:"country_iso"`
	Gender               string                  `json:"gender,omitempty"`
	DateOfBirth          string                  `json:"date_of_birth,omitempty"`
	PhoneNumber          string                  `json:"phone_number,omitempty"`
	UsernameChangedAtMs  int64                   `json:"username_changed_at_ms"`
	NextUsernameChangeMs int64                   `json:"next_username_change_ms"`
	BackgroundURLs       []string                `json:"background_urls"`
	Backgrounds          []ProfileBackgroundItem `json:"backgrounds"`
	FollowingCount       int64                   `json:"following_count"`
	FollowersCount       int64                   `json:"followers_count"`
	LikesCount           int64                   `json:"likes_count"`
	IsFollowing          bool                    `json:"is_following"`
	FollowsYou           bool                    `json:"follows_you"`
	IsOwner              bool                    `json:"is_owner"`
	IsBlocked            bool                    `json:"is_blocked"`
	RiskScore            float64                 `json:"risk_score"`
	RelationshipState    string                  `json:"relationship_state"`
	Videos               []UserProfileVideoItem  `json:"videos"`
	Posts                []feeds.FeedItem        `json:"posts"`
	SavedPosts           []feeds.FeedItem        `json:"saved_posts,omitempty"`
	RepostedPosts        []feeds.FeedItem        `json:"reposted_posts,omitempty"`
	LockedPosts          []feeds.FeedItem        `json:"locked_posts,omitempty"`
}

type UserReportPayload struct {
	Reason  string `json:"reason"`
	Details string `json:"details,omitempty"`
}

func (h *Handler) handleGetUserProfile(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	targetID := security.SanitizeText(r.PathValue("id"), 128, false)
	if strings.EqualFold(targetID, "me") {
		targetID = claims.UserID
	}
	if targetID == "" {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_user_id", nil, sanitizer.GenericErrorMessage)
		return
	}

	u, exists, isFollowing, followsYou := h.contacts.GetUserRelationshipForViewer(claims.UserID, targetID)
	if !exists {
		h.mu.Lock()
		sp, spOk := h.profiles[targetID]
		h.mu.Unlock()
		if spOk {
			u = contacts.DirectoryUser{
				UserID:        sp.UserID,
				FullName:      sp.FullName,
				Username:      sp.Username,
				Country:       sp.Location,
				CountryISO:    sp.CountryISO,
				AvatarMediaID: sp.AvatarMediaID,
				CreatedAt:     time.Now().UTC(),
			}
			exists = true
		}
	}

	if !exists {
		sanitizer.WriteSanitizedError(w, http.StatusNotFound, "user_not_found", nil, "User profile not found")
		return
	}

	followingCount, followersCount := h.contacts.GetUserCounts(targetID)
	isOwner := (claims.UserID == targetID)
	isBlocked := !isOwner && h.feeds.IsBlocked(claims.UserID, targetID)

	avatarURL := ""
	if u.AvatarMediaID != "" {
		avatarURL = "/v1/media/avatar/" + u.AvatarMediaID
	}

	var sp storedProfile
	var bgItems []ProfileBackgroundItem
	h.mu.Lock()
	sp = h.profiles[targetID]
	bgItems = append([]ProfileBackgroundItem(nil), h.backgrounds[targetID]...)
	h.mu.Unlock()

	bgURLs := make([]string, 0, len(bgItems))
	for _, b := range bgItems {
		bgURLs = append(bgURLs, b.URL)
	}

	authorPosts := h.posts.ListAuthorPosts(targetID)
	authorSummary := feeds.AuthorSummary{
		UserID:      u.UserID,
		Username:    u.Username,
		FullName:    u.FullName,
		AvatarURL:   avatarURL,
		IsFollowing: isFollowing,
		FollowsYou:  followsYou,
	}
	feedItems, lockedItems, likesCount := h.feeds.BuildAuthorFeedItems(claims.UserID, authorPosts, authorSummary)

	var savedItems []feeds.FeedItem
	var repostedItems []feeds.FeedItem
	if isOwner {
		allPosts := h.posts.ListActivePosts()
		savedItems, repostedItems = h.feeds.BuildOwnerSavedAndRepostedItems(claims.UserID, allPosts, func(uid string) feeds.AuthorSummary {
			return h.resolveAuthorSummary(claims.UserID, uid)
		})
	}

	videoItems := make([]UserProfileVideoItem, 0, len(feedItems))
	for _, fi := range feedItems {
		if fi.Mode == posts.ModeVideo {
			videoItems = append(videoItems, UserProfileVideoItem{
				PostID:       fi.PostID,
				ThumbnailURL: fi.ThumbnailURL,
				VideoURL:     fi.VideoURL,
				ViewsCount:   fi.ViewsCount,
				DurationMs:   fi.DurationMs,
				Caption:      fi.Caption,
				CreatedAtMs:  fi.CreatedAtMs,
			})
		}
	}

	var nextChangeMs int64
	if sp.UsernameChangedAtMs > 0 {
		nextChangeMs = sp.UsernameChangedAtMs + usernameChangeCooldownMs
	}

	h.audit.Record(claims.UserID, "user_profile_viewed", deviceFP, ipFP, map[string]string{
		"target_user_id": targetID,
	})

	resp := UserProfileResponse{
		Success:              true,
		UserID:               u.UserID,
		FullName:             u.FullName,
		Username:             u.Username,
		AvatarURL:            avatarURL,
		Bio:                  sp.Bio,
		Location:             u.Country,
		CountryISO:           u.CountryISO,
		BackgroundURLs:       bgURLs,
		Backgrounds:          bgItems,
		FollowingCount:       followingCount,
		FollowersCount:       followersCount,
		LikesCount:           likesCount,
		IsFollowing:          isFollowing,
		FollowsYou:           followsYou,
		IsOwner:              isOwner,
		IsBlocked:            isBlocked,
		RiskScore:            h.contacts.GetRiskScore(targetID),
		RelationshipState:    string(contacts.ComputeRelationship(isFollowing, followsYou)),
		Videos:               videoItems,
		Posts:                feedItems,
	}
	if isOwner {
		resp.Gender = sp.Gender
		resp.DateOfBirth = sp.DateOfBirth
		resp.PhoneNumber = sp.PhoneE164
		resp.UsernameChangedAtMs = sp.UsernameChangedAtMs
		resp.NextUsernameChangeMs = nextChangeMs
		resp.SavedPosts = savedItems
		resp.RepostedPosts = repostedItems
		resp.LockedPosts = lockedItems
	}

	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) handleGetUserConnections(w http.ResponseWriter, r *http.Request) {
	tab := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("type")))
	h.writeUserConnectionsResponse(w, r, tab == "followers")
}

func (h *Handler) handleGetUserFollowers(w http.ResponseWriter, r *http.Request) {
	h.writeUserConnectionsResponse(w, r, true)
}

func (h *Handler) handleGetUserFollowing(w http.ResponseWriter, r *http.Request) {
	h.writeUserConnectionsResponse(w, r, false)
}

func (h *Handler) writeUserConnectionsResponse(w http.ResponseWriter, r *http.Request, isFollowers bool) {
	claims, _, _, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	targetID := security.SanitizeText(r.PathValue("id"), 128, false)
	if strings.EqualFold(targetID, "me") {
		targetID = claims.UserID
	}
	if targetID == "" {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_user_id", nil, sanitizer.GenericErrorMessage)
		return
	}
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	pageSize, _ := strconv.Atoi(r.URL.Query().Get("page_size"))
	resp := h.contacts.ListUserConnectionsPage(targetID, isFollowers, claims.UserID, page, pageSize, func(uid string) bool {
		return h.feeds.IsBlocked(claims.UserID, uid)
	})
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) handleBlockUser(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	targetID := security.SanitizeText(r.PathValue("id"), 128, false)
	if targetID == "" || targetID == claims.UserID {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_block_target", nil, sanitizer.GenericErrorMessage)
		return
	}
	h.feeds.BlockUser(claims.UserID, targetID)
	h.contacts.SetFollowing(claims.UserID, targetID, deviceFP, false)
	h.contacts.AddRiskScore(targetID, 5.0)
	h.audit.Record(claims.UserID, "user_blocked", deviceFP, ipFP, map[string]string{
		"target_user_id": targetID,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"success":    true,
		"user_id":    targetID,
		"is_blocked": true,
	})
}

func (h *Handler) handleUnblockUser(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	targetID := security.SanitizeText(r.PathValue("id"), 128, false)
	if targetID == "" || targetID == claims.UserID {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_unblock_target", nil, sanitizer.GenericErrorMessage)
		return
	}
	h.feeds.UnblockUser(claims.UserID, targetID)
	h.audit.Record(claims.UserID, "user_unblocked", deviceFP, ipFP, map[string]string{
		"target_user_id": targetID,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"success":    true,
		"user_id":    targetID,
		"is_blocked": false,
	})
}

func (h *Handler) handleReportUser(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	targetID := security.SanitizeText(r.PathValue("id"), 128, false)
	if targetID == "" || targetID == claims.UserID {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_report_target", nil, sanitizer.GenericErrorMessage)
		return
	}
	var payload UserReportPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&payload); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_user_report", err, sanitizer.GenericErrorMessage)
		return
	}
	reason := security.SanitizeText(payload.Reason, 80, false)
	if reason == "" {
		reason = "Other"
	}
	details := security.SanitizeText(payload.Details, 400, true)
	h.feeds.RecordUserReport(claims.UserID, targetID, reason, details)
	risk := h.contacts.AddRiskScore(targetID, 15.0)
	h.audit.Record(claims.UserID, "user_reported", deviceFP, ipFP, map[string]string{
		"target_user_id": targetID,
		"reason":         reason,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"success":          true,
		"reported_user_id": targetID,
		"reason":           reason,
		"risk_score":       risk,
	})
}

func (h *Handler) handleAvatarDownload(w http.ResponseWriter, r *http.Request) {
	mediaID := security.SanitizeText(r.PathValue("id"), 48, false)
	if mediaID == "" {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	quality := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("quality")))

	h.mu.Lock()
	item, ok := h.mediaMeta[mediaID]
	h.mu.Unlock()
	if !ok || len(item.EncodedBytes) == 0 {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	payload := item.EncodedBytes
	mimeType := item.MimeType
	if quality != "" && len(item.Renditions) > 0 {
		for _, rd := range item.Renditions {
			if strings.EqualFold(rd.QualityLabel, quality) && len(rd.EncodedBytes) > 0 {
				payload = rd.EncodedBytes
				break
			}
		}
	}

	w.Header().Set("Content-Type", mimeType)
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	// ServeContent supports RFC 7233 Range requests (206 Partial Content) for adaptive chunked streaming & preloading.
	http.ServeContent(w, r, mediaID, time.Unix(1_700_000_000, 0), bytes.NewReader(payload))
}

func (h *Handler) handleListContacts(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	filter := contacts.FilterTab(strings.ToLower(strings.TrimSpace(r.URL.Query().Get("filter"))))
	query := r.URL.Query().Get("q")
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	pageSize, _ := strconv.Atoi(r.URL.Query().Get("page_size"))

	resp := h.contacts.ListContacts(claims.UserID, filter, query, page, pageSize)
	h.audit.Record(claims.UserID, "contacts_list", deviceFP, ipFP, map[string]string{
		"filter": string(filter),
		"page":   strconv.Itoa(resp.Page),
	})
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) handleFollowContact(w http.ResponseWriter, r *http.Request) {
	h.handleFollowMutation(w, r, true)
}

func (h *Handler) handleUnfollowContact(w http.ResponseWriter, r *http.Request) {
	h.handleFollowMutation(w, r, false)
}

func (h *Handler) handleFollowMutation(w http.ResponseWriter, r *http.Request, wantFollow bool) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req ContactActionPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_contact_action", err, sanitizer.GenericErrorMessage)
		return
	}
	targetID := security.SanitizeText(req.TargetUserID, 128, false)
	if targetID == "" || targetID == claims.UserID {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "invalid_target_contact", nil, sanitizer.GenericErrorMessage)
		return
	}
	if !h.contacts.CheckActionRateLimit(claims.UserID, deviceFP, ipFP) {
		h.audit.Record(claims.UserID, "contact_action_rate_limited", deviceFP, ipFP, map[string]string{
			"risk_score": fmt.Sprintf("%.1f", h.contacts.GetRiskScore(claims.UserID)),
		})
		sanitizer.WriteSanitizedError(w, http.StatusTooManyRequests, "contact_rate_limited", nil, "Try again later")
		return
	}

	res, updated := h.contacts.SetFollowing(claims.UserID, targetID, deviceFP, wantFollow)
	if !updated {
		sanitizer.WriteSanitizedError(w, http.StatusNotFound, "contact_not_found", nil, sanitizer.GenericErrorMessage)
		return
	}
	actionName := "contact_unfollow"
	if wantFollow {
		actionName = "contact_follow"
	}
	h.audit.Record(claims.UserID, actionName, deviceFP, ipFP, map[string]string{
		"target_user_id": targetID,
		"relationship":   string(res.RelationshipState),
		"risk_score":     fmt.Sprintf("%.1f", res.CallerRiskScore),
	})
	writeJSON(w, http.StatusOK, res)
}

func (h *Handler) handleSyncContacts(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req ContactSyncBatchRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<18)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_contacts_sync", err, sanitizer.GenericErrorMessage)
		return
	}

	acked := make([]string, 0, len(req.Actions))
	for _, op := range req.Actions {
		if strings.TrimSpace(op.ClientOpID) == "" {
			continue
		}
		if !h.contacts.CheckActionRateLimit(claims.UserID, deviceFP, ipFP) {
			h.audit.Record(claims.UserID, "contact_sync_rate_limited", deviceFP, ipFP, nil)
			sanitizer.WriteSanitizedError(w, http.StatusTooManyRequests, "contact_sync_rate_limited", nil, "Try again later")
			return
		}
		wantFollow := strings.EqualFold(strings.TrimSpace(op.Action), "follow")
		if _, updated := h.contacts.SetFollowing(claims.UserID, strings.TrimSpace(op.TargetUserID), deviceFP, wantFollow); updated {
			acked = append(acked, op.ClientOpID)
		}
	}

	h.audit.Record(claims.UserID, "contacts_offline_synced", deviceFP, ipFP, map[string]string{
		"count":      strconv.Itoa(len(acked)),
		"risk_score": fmt.Sprintf("%.1f", h.contacts.GetRiskScore(claims.UserID)),
	})
	writeJSON(w, http.StatusOK, SyncBatchResponse{
		Success:       true,
		AckedOpIDs:    acked,
		ServerEpochMs: time.Now().UnixMilli(),
	})
}

func (h *Handler) handleAuditEvent(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req AuditEventPayload
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_audit", err, sanitizer.GenericErrorMessage)
		return
	}
	action := security.SanitizeText(req.Action, 64, false)
	if action == "" {
		action = "client_event"
	}
	h.audit.Record(claims.UserID, action, deviceFP, ipFP, req.Metadata)
	writeJSON(w, http.StatusOK, map[string]bool{"success": true})
}

func (h *Handler) handleSyncBatch(w http.ResponseWriter, r *http.Request) {
	var req SyncBatchRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_sync", err, sanitizer.GenericErrorMessage)
		return
	}

	h.mu.Lock()
	acked := make([]string, 0, len(req.Operations))
	for _, op := range req.Operations {
		if strings.TrimSpace(op.ClientOpID) == "" {
			continue
		}
		h.ackedOps[op.ClientOpID] = struct{}{}
		acked = append(acked, op.ClientOpID)
	}
	h.mu.Unlock()

	writeJSON(w, http.StatusOK, SyncBatchResponse{
		Success:       true,
		AckedOpIDs:    acked,
		ServerEpochMs: time.Now().UnixMilli(),
	})
}

func (h *Handler) handlePostPhotoUpload(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}

	processed, err := media.SanitizeCompressAndFingerprintPostPhoto(r.Body)
	if err != nil {
		h.audit.Record(claims.UserID, "post_photo_rejected", deviceFP, ipFP, nil)
		status := http.StatusBadRequest
		if errors.Is(err, media.ErrPayloadTooLarge) {
			status = http.StatusRequestEntityTooLarge
		}
		sanitizer.WriteSanitizedError(w, status, "post_photo_process", err, sanitizer.GenericErrorMessage)
		return
	}

	h.mu.Lock()
	h.mediaMeta[processed.MediaID] = processed
	h.mu.Unlock()

	h.audit.Record(claims.UserID, "post_photo_uploaded", deviceFP, ipFP, map[string]string{
		"media_id":   processed.MediaID,
		"content_id": processed.ContentID,
		"sha256":     processed.Sha256Fingerprint,
		"dhash":      processed.DHashFingerprint,
	})

	writeJSON(w, http.StatusOK, MediaUploadResponse{
		Success:           true,
		MediaID:           processed.MediaID,
		Sha256Fingerprint: processed.Sha256Fingerprint,
		DHashFingerprint:  processed.DHashFingerprint,
		ContentID:         processed.ContentID,
		MimeType:          processed.MimeType,
		ByteSize:          len(processed.EncodedBytes),
	})
}

func (h *Handler) handlePostVideoUpload(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, media.MaxPostVideoBytes+media.MaxPostThumbnailBytes+(1<<20))

	durationMs, _ := strconv.ParseInt(r.URL.Query().Get("duration_ms"), 10, 64)
	trimStartMs, _ := strconv.ParseInt(r.URL.Query().Get("trim_start_ms"), 10, 64)
	trimEndMs, _ := strconv.ParseInt(r.URL.Query().Get("trim_end_ms"), 10, 64)
	width, _ := strconv.Atoi(r.URL.Query().Get("width"))
	height, _ := strconv.Atoi(r.URL.Query().Get("height"))

	frameHeader := r.Header.Get("X-Telefam-Frame-Hashes")
	var clientFrameHashes []string
	if strings.TrimSpace(frameHeader) != "" {
		for _, tok := range strings.Split(frameHeader, ",") {
			if clean := strings.TrimSpace(tok); clean != "" {
				clientFrameHashes = append(clientFrameHashes, clean)
			}
		}
	}

	var processed *media.ProcessedMedia
	var thumbProcessed *media.ProcessedMedia
	var err error

	contentType := r.Header.Get("Content-Type")
	if strings.HasPrefix(strings.ToLower(contentType), "multipart/form-data") {
		if mr, mErr := r.MultipartReader(); mErr == nil {
			for {
				part, pErr := mr.NextPart()
				if pErr != nil {
					break
				}
				switch part.FormName() {
				case "video":
					processed, err = media.AnalyzeVideoAndFingerprintFrames(
						r.Context(), part, clientFrameHashes, durationMs, trimStartMs, trimEndMs, width, height,
					)
				case "thumbnail":
					if tp, tErr := media.SanitizePostThumbnail(part); tErr == nil {
						thumbProcessed = tp
					}
				}
				_ = part.Close()
				if err != nil {
					break
				}
			}
		} else {
			err = mErr
		}
	} else {
		processed, err = media.AnalyzeVideoAndFingerprintFrames(
			r.Context(), r.Body, clientFrameHashes, durationMs, trimStartMs, trimEndMs, width, height,
		)
	}

	if err != nil || processed == nil {
		h.audit.Record(claims.UserID, "post_video_rejected", deviceFP, ipFP, nil)
		status := http.StatusBadRequest
		if errors.Is(err, media.ErrPayloadTooLarge) {
			status = http.StatusRequestEntityTooLarge
		}
		sanitizer.WriteSanitizedError(w, status, "post_video_process", err, sanitizer.GenericErrorMessage)
		return
	}

	h.mu.Lock()
	h.mediaMeta[processed.MediaID] = processed
	if thumbProcessed != nil {
		h.mediaMeta[thumbProcessed.MediaID] = thumbProcessed
		processed.ThumbnailMediaID = thumbProcessed.MediaID
	}
	h.mu.Unlock()

	h.audit.Record(claims.UserID, "post_video_uploaded", deviceFP, ipFP, map[string]string{
		"media_id":   processed.MediaID,
		"content_id": processed.ContentID,
		"frames":     strconv.Itoa(len(processed.PerFrameHashes)),
		"renditions": strconv.Itoa(len(processed.Renditions)),
	})

	writeJSON(w, http.StatusOK, MediaUploadResponse{
		Success:           true,
		MediaID:           processed.MediaID,
		Sha256Fingerprint: processed.Sha256Fingerprint,
		DHashFingerprint:  processed.DHashFingerprint,
		ContentID:         processed.ContentID,
		ThumbnailMediaID:  processed.ThumbnailMediaID,
		PerFrameHashes:    processed.PerFrameHashes,
		Renditions:        processed.Renditions,
		MimeType:          processed.MimeType,
		ByteSize:          len(processed.EncodedBytes),
	})
}

func (h *Handler) handleSearchSongs(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	q := r.URL.Query().Get("q")
	tracks, err := h.posts.SearchSongs(r.Context(), q)
	if err != nil && len(tracks) == 0 {
		sanitizer.WriteSanitizedError(w, http.StatusBadGateway, "songs_search_upstream", err, sanitizer.GenericErrorMessage)
		return
	}
	h.audit.Record(claims.UserID, "songs_search", deviceFP, ipFP, map[string]string{"count": strconv.Itoa(len(tracks))})
	writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"items":   tracks,
	})
}

func (h *Handler) handleSearchHashtags(w http.ResponseWriter, r *http.Request) {
	claims, _, _, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	_ = claims
	q := r.URL.Query().Get("q")
	tags := h.posts.SearchHashtags(q)
	writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"items":   tags,
	})
}

func (h *Handler) handleCreatePost(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req posts.CreatePostRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<18)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_create_post", err, sanitizer.GenericErrorMessage)
		return
	}

	var renditions []media.VideoRendition
	if req.Mode == posts.ModeVideo && req.VideoMediaID != "" {
		h.mu.Lock()
		if vMeta, exists := h.mediaMeta[req.VideoMediaID]; exists {
			renditions = vMeta.Renditions
			if req.ContentID == "" {
				req.ContentID = vMeta.ContentID
			}
			if req.ThumbnailMediaID == "" {
				req.ThumbnailMediaID = vMeta.ThumbnailMediaID
			}
		}
		h.mu.Unlock()
	}

	created, err := h.posts.CreatePost(claims.UserID, req, renditions)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "create_post", err, sanitizer.GenericErrorMessage)
		return
	}

	h.audit.Record(claims.UserID, "post_created", deviceFP, ipFP, map[string]string{
		"post_id":    created.PostID,
		"mode":       string(created.Mode),
		"content_id": created.ContentID,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"post":    created,
	})
}

func (h *Handler) handleUpdatePost(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	postID := security.SanitizeText(r.PathValue("id"), 80, false)
	var req posts.UpdatePostRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<18)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_update_post", err, sanitizer.GenericErrorMessage)
		return
	}
	updated, err := h.posts.UpdatePost(claims.UserID, postID, req)
	if err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, posts.ErrPostNotFound) {
			status = http.StatusNotFound
		} else if errors.Is(err, posts.ErrNotPostOwner) {
			status = http.StatusForbidden
		}
		sanitizer.WriteSanitizedError(w, status, "update_post", err, sanitizer.GenericErrorMessage)
		return
	}
	h.audit.Record(claims.UserID, "post_updated", deviceFP, ipFP, map[string]string{"post_id": postID})
	writeJSON(w, http.StatusOK, map[string]any{
		"success": true,
		"post":    updated,
	})
}

func (h *Handler) handleDeletePost(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	postID := security.SanitizeText(r.PathValue("id"), 80, false)
	if err := h.posts.DeletePost(claims.UserID, postID); err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, posts.ErrPostNotFound) {
			status = http.StatusNotFound
		} else if errors.Is(err, posts.ErrNotPostOwner) {
			status = http.StatusForbidden
		}
		sanitizer.WriteSanitizedError(w, status, "delete_post", err, sanitizer.GenericErrorMessage)
		return
	}
	h.audit.Record(claims.UserID, "post_deleted", deviceFP, ipFP, map[string]string{"post_id": postID})
	writeJSON(w, http.StatusOK, map[string]bool{"success": true})
}

func (h *Handler) handleGetFeeds(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	tab := r.URL.Query().Get("tab")
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	pageSize, _ := strconv.Atoi(r.URL.Query().Get("page_size"))

	allPosts := h.posts.ListActivePosts()
	resp := h.feeds.GetFeed(claims.UserID, tab, page, pageSize, allPosts, func(uid string) feeds.AuthorSummary {
		return h.resolveAuthorSummary(claims.UserID, uid)
	})
	h.audit.Record(claims.UserID, "feeds_viewed", deviceFP, ipFP, map[string]string{
		"tab":   resp.Tab,
		"page":  strconv.Itoa(resp.Page),
		"count": strconv.Itoa(len(resp.Items)),
	})
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) handleSearchFeeds(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	q := r.URL.Query().Get("q")
	shortcut := r.URL.Query().Get("shortcut")
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	pageSize, _ := strconv.Atoi(r.URL.Query().Get("page_size"))

	allPosts := h.posts.ListActivePosts()
	resp := h.feeds.Search(claims.UserID, q, shortcut, page, pageSize, allPosts, func(uid string) feeds.AuthorSummary {
		return h.resolveAuthorSummary(claims.UserID, uid)
	})
	h.audit.Record(claims.UserID, "feeds_searched", deviceFP, ipFP, map[string]string{
		"shortcut": resp.Shortcut,
		"results":  strconv.Itoa(len(resp.Items)),
	})
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) handleFeedInteract(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req feeds.InteractionRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_feed_interact", err, sanitizer.GenericErrorMessage)
		return
	}
	post, _ := h.posts.GetPost(req.PostID)
	res := h.feeds.RecordInteraction(claims.UserID, post, req)
	h.audit.Record(claims.UserID, "feed_"+res.Action, deviceFP, ipFP, map[string]string{
		"post_id": res.PostID,
	})
	writeJSON(w, http.StatusOK, res)
}

func (h *Handler) handleFeedInteractBatch(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	var req struct {
		Operations []feeds.InteractionRequest `json:"operations"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<18)).Decode(&req); err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusBadRequest, "decode_feed_interact_batch", err, sanitizer.GenericErrorMessage)
		return
	}
	acked := make([]string, 0, len(req.Operations))
	for _, op := range req.Operations {
		if strings.TrimSpace(op.ClientOpID) == "" {
			continue
		}
		post, _ := h.posts.GetPost(op.PostID)
		h.feeds.RecordInteraction(claims.UserID, post, op)
		acked = append(acked, op.ClientOpID)
	}
	h.audit.Record(claims.UserID, "feed_batch_synced", deviceFP, ipFP, map[string]string{
		"count": strconv.Itoa(len(acked)),
	})
	writeJSON(w, http.StatusOK, SyncBatchResponse{
		Success:       true,
		AckedOpIDs:    acked,
		ServerEpochMs: time.Now().UnixMilli(),
	})
}

func (h *Handler) handleFeedDownload(w http.ResponseWriter, r *http.Request) {
	claims, deviceFP, ipFP, ok := h.requireAuth(w, r)
	if !ok {
		return
	}
	postID := security.SanitizeText(r.PathValue("id"), 80, false)
	post, exists := h.posts.GetPost(postID)
	if !exists || post.VideoMediaID == "" {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	h.mu.Lock()
	item, hasMedia := h.mediaMeta[post.VideoMediaID]
	h.mu.Unlock()
	if !hasMedia || len(item.EncodedBytes) == 0 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	h.audit.Record(claims.UserID, "feed_video_downloaded", deviceFP, ipFP, map[string]string{"post_id": postID})
	w.Header().Set("Content-Type", item.MimeType)
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="telefam_%s.mp4"`, postID))
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, postID+".mp4", time.Unix(post.CreatedAtMs/1000, 0), bytes.NewReader(item.EncodedBytes))
}

func (h *Handler) resolveAuthorSummary(viewerID, authorID string) feeds.AuthorSummary {
	if u, ok, isFollowing, followsYou := h.contacts.GetUserRelationshipForViewer(viewerID, authorID); ok {
		avatar := ""
		if u.AvatarMediaID != "" {
			avatar = "/v1/media/avatar/" + u.AvatarMediaID
		}
		return feeds.AuthorSummary{
			UserID:      u.UserID,
			Username:    u.Username,
			FullName:    u.FullName,
			AvatarURL:   avatar,
			IsFollowing: isFollowing,
			FollowsYou:  followsYou,
		}
	}
	h.mu.Lock()
	p, ok := h.profiles[authorID]
	h.mu.Unlock()
	if ok {
		avatar := ""
		if p.AvatarMediaID != "" {
			avatar = "/v1/media/avatar/" + p.AvatarMediaID
		}
		return feeds.AuthorSummary{
			UserID:    p.UserID,
			Username:  p.Username,
			FullName:  p.FullName,
			AvatarURL: avatar,
		}
	}
	return feeds.AuthorSummary{
		UserID:   authorID,
		Username: "member",
		FullName: "Telefam Member",
	}
}

func (h *Handler) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (h *Handler) issueAuthResponse(w http.ResponseWriter, userID, email, deviceFP, ipFP string) {
	pair, _, err := h.tokens.IssueInitialPair(userID, email, deviceFP)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusInternalServerError, "issue_tokens", err, sanitizer.GenericErrorMessage)
		return
	}
	fullName, username := security.AutofillFromEmail(email)
	h.mu.Lock()
	_, profileDone := h.profiles[userID]
	h.mu.Unlock()

	h.audit.Record(userID, "auth_session_issued", deviceFP, ipFP, nil)

	writeJSON(w, http.StatusOK, AuthSessionResponse{
		Success:             true,
		UserID:              userID,
		SessionToken:        pair.AccessToken,
		RefreshToken:        pair.RefreshToken,
		AccessExpiresInSec:  pair.AccessExpiresInSec,
		RefreshExpiresInSec: pair.RefreshExpiresInSec,
		Email:               email,
		SuggestedFullName:   fullName,
		SuggestedUsername:   username,
		ProfileCompleted:    profileDone,
	})
}

func (h *Handler) requireAuth(w http.ResponseWriter, r *http.Request) (AccessClaims, string, string, bool) {
	deviceFP, err := h.fp.ExtractDeviceFingerprint(r)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusUnauthorized, "auth_missing_fp", err, sanitizer.GenericErrorMessage)
		return AccessClaims{}, "", "", false
	}
	ipFP := h.fp.ExtractIPFingerprint(r)
	authHeader := strings.TrimSpace(r.Header.Get("Authorization"))
	token := strings.TrimPrefix(authHeader, "Bearer ")
	claims, err := h.tokens.VerifyAccessToken(token, deviceFP)
	if err != nil {
		sanitizer.WriteSanitizedError(w, http.StatusUnauthorized, "auth_invalid_token", err, sanitizer.GenericErrorMessage)
		return AccessClaims{}, "", "", false
	}
	return claims, deviceFP, ipFP, true
}

func writeJSON(w http.ResponseWriter, statusCode int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(body)
}
