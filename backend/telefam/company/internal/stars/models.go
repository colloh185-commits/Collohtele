package stars

type StarPackageDTO struct {
	StarAmount     int64  `json:"star_amount"`
	IsPopular      bool   `json:"is_popular"`
	LocalCurrency  string `json:"local_currency"`
	CurrencySymbol string `json:"currency_symbol"`
	LocalMinorUnit int64  `json:"local_minor_unit"`
	LocalDecimals  int    `json:"local_decimals"`
	LocalDisplay   string `json:"local_display"`
}

type StarWalletDTO struct {
	UserID           string `json:"user_id"`
	AvailableBalance int64  `json:"available_balance"`
	TotalReceived    int64  `json:"total_received"`
	TotalSent        int64  `json:"total_sent"`
	TotalPurchased   int64  `json:"total_purchased"`
	UpdatedAtMs      int64  `json:"updated_at_ms"`
}

type StarSenderSummaryDTO struct {
	SenderUserID   string `json:"sender_user_id"`
	SenderName     string `json:"sender_name"`
	SenderUsername string `json:"sender_username"`
	SenderAvatar   string `json:"sender_avatar,omitempty"`
	TotalStars     int64  `json:"total_stars"`
	GiftsCount     int    `json:"gifts_count"`
	LastGiftedAtMs int64  `json:"last_gifted_at_ms"`
}

type StarTransactionDTO struct {
	TxID           string `json:"tx_id"`
	TxType         string `json:"tx_type"` // purchase | gift
	Direction      string `json:"direction"` // received | sent | purchased
	SenderUserID   string `json:"sender_user_id,omitempty"`
	SenderName     string `json:"sender_name,omitempty"`
	SenderUsername string `json:"sender_username,omitempty"`
	SenderAvatar   string `json:"sender_avatar,omitempty"`
	RecipientID    string `json:"recipient_user_id,omitempty"`
	RecipientName  string `json:"recipient_name,omitempty"`
	ConversationID string `json:"conversation_id,omitempty"`
	MessageID      string `json:"message_id,omitempty"`
	StarAmount     int64  `json:"star_amount"`
	Currency       string `json:"currency,omitempty"`
	AmountMinor    int64  `json:"amount_minor,omitempty"`
	Provider       string `json:"provider,omitempty"`
	Status         string `json:"status"`
	CreatedAtMs    int64  `json:"created_at_ms"`
}

type StarPaymentIntentDTO struct {
	IntentID          string `json:"intent_id"`
	IdempotencyKey    string `json:"idempotency_key"`
	Provider          string `json:"provider"`
	ProviderReference string `json:"provider_reference"`
	StarAmount        int64  `json:"star_amount"`
	Currency          string `json:"currency"`
	ExpectedMinor     int64  `json:"expected_amount_minor"`
	VerifiedMinor     int64  `json:"verified_amount_minor"`
	AmountDisplay     string `json:"amount_display"`
	CheckoutURL       string `json:"checkout_url,omitempty"`
	Status            string `json:"status"` // pending | completed | failed | expired
	Credited          bool   `json:"credited"`
	SignatureVerified bool   `json:"signature_verified"`
	CreatedAtMs       int64  `json:"created_at_ms"`
	CompletedAtMs     int64  `json:"completed_at_ms,omitempty"`
}

type StarRateLimitStatusDTO struct {
	HourlySentStars       int64 `json:"hourly_sent_stars"`
	HourlySentLimit       int64 `json:"hourly_sent_limit"`
	DailySentStars        int64 `json:"daily_sent_stars"`
	DailySentLimit        int64 `json:"daily_sent_limit"`
	DailyPurchasedStars   int64 `json:"daily_purchased_stars"`
	DailyPurchasedLimit   int64 `json:"daily_purchased_limit"`
}

type StarSummaryResponse struct {
	Success         bool                   `json:"success"`
	CountryISO      string                 `json:"country_iso"`
	Currency        string                 `json:"currency"`
	CurrencySymbol  string                 `json:"currency_symbol"`
	Wallet          StarWalletDTO          `json:"wallet"`
	Packages        []StarPackageDTO       `json:"packages"`
	Senders         []StarSenderSummaryDTO `json:"senders"`
	Transactions    []StarTransactionDTO   `json:"transactions"`
	PendingIntents  []StarPaymentIntentDTO `json:"pending_intents"`
	RateLimitStatus StarRateLimitStatusDTO `json:"rate_limit_status"`
	ServerEpochMs   int64                  `json:"server_epoch_ms"`
}

type CreateStarCheckoutRequest struct {
	IdempotencyKey string `json:"idempotency_key"`
	StarAmount     int64  `json:"star_amount"`
	Provider       string `json:"provider"` // paystack | paypal
	CountryISO     string `json:"country_iso,omitempty"`
	ReturnURL      string `json:"return_url,omitempty"`
	CancelURL      string `json:"cancel_url,omitempty"`
}

type CreateStarCheckoutResponse struct {
	Success       bool                 `json:"success"`
	Replayed      bool                 `json:"replayed"`
	Intent        StarPaymentIntentDTO `json:"intent"`
	Wallet        StarWalletDTO        `json:"wallet"`
	ServerEpochMs int64                `json:"server_epoch_ms"`
}

type VerifyStarPaymentRequest struct {
	IdempotencyKey    string `json:"idempotency_key,omitempty"`
	ProviderReference string `json:"provider_reference,omitempty"`
}

type VerifyStarPaymentResponse struct {
	Success       bool                 `json:"success"`
	Intent        StarPaymentIntentDTO `json:"intent"`
	Wallet        StarWalletDTO        `json:"wallet"`
	ServerEpochMs int64                `json:"server_epoch_ms"`
}

type SendStarGiftRequest struct {
	IdempotencyKey   string `json:"idempotency_key"`
	RecipientUserID  string `json:"recipient_user_id"`
	ConversationID   string `json:"conversation_id,omitempty"`
	StarAmount       int64  `json:"star_amount"`
	CiphertextB64    string `json:"ciphertext_b64,omitempty"`
	SenderCipherB64  string `json:"sender_ciphertext_b64,omitempty"`
	InsufficientHint bool   `json:"-"`
}

type SendStarGiftResponse struct {
	Success              bool               `json:"success"`
	Replayed             bool               `json:"replayed"`
	InsufficientBalance  bool               `json:"insufficient_balance,omitempty"`
	RequiredStarAmount   int64              `json:"required_star_amount,omitempty"`
	AvailableBalance     int64              `json:"available_balance"`
	Wallet               StarWalletDTO      `json:"wallet"`
	Transaction          *StarTransactionDTO `json:"transaction,omitempty"`
	Message              any                `json:"message,omitempty"`
	Conversation         any                `json:"conversation,omitempty"`
	ServerEpochMs        int64              `json:"server_epoch_ms"`
}
