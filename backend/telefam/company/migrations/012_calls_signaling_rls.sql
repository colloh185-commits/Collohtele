-- Telefam 1-on-1 Audio & Video Call Signaling Sessions with Strict PostgreSQL RLS

CREATE TABLE IF NOT EXISTS telefam_chat_call_sessions (
    call_id            TEXT PRIMARY KEY,
    conversation_id    TEXT NOT NULL REFERENCES telefam_chat_conversations(conversation_id) ON DELETE CASCADE,
    caller_user_id     TEXT NOT NULL,
    callee_user_id     TEXT NOT NULL,
    call_type          TEXT NOT NULL CHECK (call_type IN ('audio', 'video')),
    state              TEXT NOT NULL CHECK (state IN ('ringing', 'connecting', 'connected', 'ended')),
    end_reason         TEXT NOT NULL DEFAULT '',
    offer_sdp          TEXT NOT NULL DEFAULT '',
    answer_sdp         TEXT NOT NULL DEFAULT '',
    caller_muted       BOOLEAN NOT NULL DEFAULT FALSE,
    callee_muted       BOOLEAN NOT NULL DEFAULT FALSE,
    caller_camera_on   BOOLEAN NOT NULL DEFAULT TRUE,
    callee_camera_on   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at_ms      BIGINT NOT NULL,
    ringing_at_ms      BIGINT NOT NULL,
    connected_at_ms    BIGINT NOT NULL DEFAULT 0,
    ended_at_ms        BIGINT NOT NULL DEFAULT 0,
    CHECK (caller_user_id <> callee_user_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_calls_participants_state
    ON telefam_chat_call_sessions (caller_user_id, callee_user_id, state, created_at_ms DESC);

CREATE TABLE IF NOT EXISTS telefam_chat_call_events (
    event_id       BIGSERIAL PRIMARY KEY,
    call_id        TEXT NOT NULL REFERENCES telefam_chat_call_sessions(call_id) ON DELETE CASCADE,
    sender_user_id TEXT NOT NULL,
    target_user_id TEXT NOT NULL,
    signal_kind    TEXT NOT NULL,
    created_at_ms  BIGINT NOT NULL
);

ALTER TABLE telefam_chat_call_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE telefam_chat_call_sessions FORCE ROW LEVEL SECURITY;

ALTER TABLE telefam_chat_call_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE telefam_chat_call_events FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS chat_calls_participant_select ON telefam_chat_call_sessions;
CREATE POLICY chat_calls_participant_select ON telefam_chat_call_sessions
    FOR SELECT
    USING (
        COALESCE(NULLIF(current_setting('app.current_user_id', true), ''), NULLIF(current_setting('telefam.current_user_id', true), '')) IN (caller_user_id, callee_user_id)
    );

DROP POLICY IF EXISTS chat_calls_caller_insert ON telefam_chat_call_sessions;
CREATE POLICY chat_calls_caller_insert ON telefam_chat_call_sessions
    FOR INSERT
    WITH CHECK (
        COALESCE(NULLIF(current_setting('app.current_user_id', true), ''), NULLIF(current_setting('telefam.current_user_id', true), '')) = caller_user_id
    );

DROP POLICY IF EXISTS chat_calls_participant_update ON telefam_chat_call_sessions;
CREATE POLICY chat_calls_participant_update ON telefam_chat_call_sessions
    FOR UPDATE
    USING (
        COALESCE(NULLIF(current_setting('app.current_user_id', true), ''), NULLIF(current_setting('telefam.current_user_id', true), '')) IN (caller_user_id, callee_user_id)
    )
    WITH CHECK (
        COALESCE(NULLIF(current_setting('app.current_user_id', true), ''), NULLIF(current_setting('telefam.current_user_id', true), '')) IN (caller_user_id, callee_user_id)
    );

DROP POLICY IF EXISTS chat_call_events_participant_all ON telefam_chat_call_events;
CREATE POLICY chat_call_events_participant_all ON telefam_chat_call_events
    FOR ALL
    USING (
        COALESCE(NULLIF(current_setting('app.current_user_id', true), ''), NULLIF(current_setting('telefam.current_user_id', true), '')) IN (sender_user_id, target_user_id)
    )
    WITH CHECK (
        COALESCE(NULLIF(current_setting('app.current_user_id', true), ''), NULLIF(current_setting('telefam.current_user_id', true), '')) = sender_user_id
    );
