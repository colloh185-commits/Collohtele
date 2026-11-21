-- Telefam Stars Wallet, Idempotent Payment Intents (Paystack & PayPal), Webhook Deduplication & AML Ledger
-- Strict PostgreSQL Row-Level Security (RLS) enforced on all user-scoped tables.

CREATE TABLE IF NOT EXISTS telefam_star_wallets (
    user_id            TEXT PRIMARY KEY,
    available_balance  BIGINT NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    total_received     BIGINT NOT NULL DEFAULT 0 CHECK (total_received >= 0),
    total_sent         BIGINT NOT NULL DEFAULT 0 CHECK (total_sent >= 0),
    total_purchased    BIGINT NOT NULL DEFAULT 0 CHECK (total_purchased >= 0),
    updated_at_ms      BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS telefam_star_payment_intents (
    intent_id               TEXT PRIMARY KEY,
    user_id                 TEXT NOT NULL,
    idempotency_key         TEXT NOT NULL,
    provider                TEXT NOT NULL CHECK (provider IN ('paystack', 'paypal')),
    provider_reference      TEXT NOT NULL UNIQUE,
    star_amount             BIGINT NOT NULL CHECK (star_amount > 0),
    currency                CHAR(3) NOT NULL,
    expected_amount_minor   BIGINT NOT NULL CHECK (expected_amount_minor > 0),
    verified_amount_minor   BIGINT NOT NULL DEFAULT 0,
    checkout_url            TEXT NOT NULL DEFAULT '',
    status                  TEXT NOT NULL CHECK (status IN ('pending', 'completed', 'failed', 'expired')),
    credited                BOOLEAN NOT NULL DEFAULT FALSE,
    signature_verified      BOOLEAN NOT NULL DEFAULT FALSE,
    webhook_event_id        TEXT NOT NULL DEFAULT '',
    created_at_ms           BIGINT NOT NULL,
    completed_at_ms         BIGINT NOT NULL DEFAULT 0,
    UNIQUE (user_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_star_intents_user_time
    ON telefam_star_payment_intents (user_id, created_at_ms DESC);

CREATE TABLE IF NOT EXISTS telefam_star_webhook_events (
    provider            TEXT NOT NULL CHECK (provider IN ('paystack', 'paypal')),
    event_id            TEXT NOT NULL,
    signature_verified  BOOLEAN NOT NULL,
    payload_sha256      CHAR(64) NOT NULL,
    received_at_ms      BIGINT NOT NULL,
    PRIMARY KEY (provider, event_id)
);

CREATE TABLE IF NOT EXISTS telefam_star_transactions (
    tx_id               TEXT PRIMARY KEY,
    idempotency_key     TEXT NOT NULL,
    tx_type             TEXT NOT NULL CHECK (tx_type IN ('purchase', 'gift')),
    sender_user_id      TEXT NOT NULL DEFAULT '',
    recipient_user_id   TEXT NOT NULL,
    conversation_id     TEXT NOT NULL DEFAULT '',
    message_id          TEXT NOT NULL DEFAULT '',
    star_amount         BIGINT NOT NULL CHECK (star_amount > 0),
    currency            CHAR(3) NOT NULL DEFAULT '',
    amount_minor        BIGINT NOT NULL DEFAULT 0,
    provider            TEXT NOT NULL DEFAULT '',
    aml_flag_reason     TEXT NOT NULL DEFAULT '',
    created_at_ms       BIGINT NOT NULL,
    UNIQUE (sender_user_id, tx_type, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_star_tx_recipient_time
    ON telefam_star_transactions (recipient_user_id, created_at_ms DESC);

CREATE INDEX IF NOT EXISTS idx_star_tx_sender_time
    ON telefam_star_transactions (sender_user_id, created_at_ms DESC);

CREATE TABLE IF NOT EXISTS telefam_star_aml_events (
    event_id      TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL,
    device_fp     TEXT NOT NULL DEFAULT '',
    ip_fp         TEXT NOT NULL DEFAULT '',
    reason        TEXT NOT NULL,
    star_amount   BIGINT NOT NULL DEFAULT 0,
    created_at_ms BIGINT NOT NULL
);

-- Extend chat message types to allow verified star_gift entries
ALTER TABLE telefam_chat_messages
    DROP CONSTRAINT IF EXISTS telefam_chat_messages_message_type_check;
ALTER TABLE telefam_chat_messages
    ADD CONSTRAINT telefam_chat_messages_message_type_check
    CHECK (message_type IN ('text', 'sticker', 'gif', 'clip', 'voice', 'voice_once', 'star_gift'));

ALTER TABLE telefam_chat_messages
    ADD COLUMN IF NOT EXISTS star_gift_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE telefam_chat_messages
    ADD COLUMN IF NOT EXISTS star_gift_tx_id TEXT NOT NULL DEFAULT '';
ALTER TABLE telefam_chat_messages
    ADD COLUMN IF NOT EXISTS star_gift_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- ============================================================================
-- STRICT POSTGRESQL ROW-LEVEL SECURITY (RLS)
-- ============================================================================
ALTER TABLE telefam_star_wallets         ENABLE ROW LEVEL SECURITY;
ALTER TABLE telefam_star_wallets         FORCE ROW LEVEL SECURITY;

ALTER TABLE telefam_star_payment_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE telefam_star_payment_intents FORCE ROW LEVEL SECURITY;

ALTER TABLE telefam_star_webhook_events  ENABLE ROW LEVEL SECURITY;
ALTER TABLE telefam_star_webhook_events  FORCE ROW LEVEL SECURITY;

ALTER TABLE telefam_star_transactions    ENABLE ROW LEVEL SECURITY;
ALTER TABLE telefam_star_transactions    FORCE ROW LEVEL SECURITY;

ALTER TABLE telefam_star_aml_events      ENABLE ROW LEVEL SECURITY;
ALTER TABLE telefam_star_aml_events      FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS star_wallets_owner_select ON telefam_star_wallets;
CREATE POLICY star_wallets_owner_select ON telefam_star_wallets
    FOR SELECT
    USING (
        user_id = COALESCE(NULLIF(current_setting('app.current_user_id', true), ''), NULLIF(current_setting('telefam.current_user_id', true), ''))
    );

DROP POLICY IF EXISTS star_intents_owner_select ON telefam_star_payment_intents;
CREATE POLICY star_intents_owner_select ON telefam_star_payment_intents
    FOR SELECT
    USING (
        user_id = COALESCE(NULLIF(current_setting('app.current_user_id', true), ''), NULLIF(current_setting('telefam.current_user_id', true), ''))
    );

DROP POLICY IF EXISTS star_transactions_participant_select ON telefam_star_transactions;
CREATE POLICY star_transactions_participant_select ON telefam_star_transactions
    FOR SELECT
    USING (
        COALESCE(NULLIF(current_setting('app.current_user_id', true), ''), NULLIF(current_setting('telefam.current_user_id', true), ''))
        IN (sender_user_id, recipient_user_id)
    );

-- Deny direct user access to webhooks and AML logs (backend service-role only)
DROP POLICY IF EXISTS star_webhooks_deny_clients ON telefam_star_webhook_events;
CREATE POLICY star_webhooks_deny_clients ON telefam_star_webhook_events
    FOR ALL
    USING (FALSE)
    WITH CHECK (FALSE);

DROP POLICY IF EXISTS star_aml_deny_clients ON telefam_star_aml_events;
CREATE POLICY star_aml_deny_clients ON telefam_star_aml_events
    FOR ALL
    USING (FALSE)
    WITH CHECK (FALSE);
