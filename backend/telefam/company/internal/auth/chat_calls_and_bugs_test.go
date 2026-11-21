package auth

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"telefam.company/backend/internal/chat"
	"telefam.company/backend/internal/contacts"
)

func TestCallsStateTransitionsPrivacyAndMarkUnreadRevert(t *testing.T) {
	h, mux, tokens := newTestMuxAndTokens()
	dfpAlice := "device-call-alice"
	dfpBob := "device-call-bobby"
	alicePair, _, _ := tokens.IssueInitialPair("u_call_alice", "alicecall@telefam.company", dfpAlice)
	bobPair, _, _ := tokens.IssueInitialPair("u_call_bob", "bobcall@telefam.company", dfpBob)

	h.contacts.RegisterOrUpdateUser(contacts.DirectoryUser{
		UserID: "u_call_alice", FullName: "Alice Call", Username: "alicecall",
	}, dfpAlice)
	h.contacts.RegisterOrUpdateUser(contacts.DirectoryUser{
		UserID: "u_call_bob", FullName: "Bob Call", Username: "bobcall",
	}, dfpBob)

	// Open direct chat Alice -> Bob
	openRec := httptest.NewRecorder()
	mux.ServeHTTP(openRec, authReq("POST", "/v1/chat/conversations/direct/u_call_bob", nil, alicePair.AccessToken, dfpAlice))
	if openRec.Code != http.StatusOK {
		t.Fatalf("open direct chat failed: %d %s", openRec.Code, openRec.Body.String())
	}
	var openResp struct {
		Conversation chat.ConversationDTO `json:"conversation"`
	}
	_ = json.Unmarshal(openRec.Body.Bytes(), &openResp)
	convID := openResp.Conversation.ConversationID

	// 1. Alice updates chat privacy -> online_indicators=anyone & typing_indicators=anyone
	privRec := httptest.NewRecorder()
	mux.ServeHTTP(privRec, authReq("PUT", "/v1/chat/privacy", ChatPrivacySettingsDTO{
		MessageRequests:     "anyone",
		WhoCanCall:          "anyone",
		WhoCanScreenshot:    "nobody",
		WhoCanShareChats:    "nobody",
		WhoCanCopyMessages:  "nobody",
		WhoCanDownloadMedia: "anyone",
		ChatThemeID:         "none",
		BubbleColorID:       "cyan",
		ReadReceipts:        "anyone",
		TypingIndicators:    "anyone",
		OnlineIndicators:    "anyone",
	}, alicePair.AccessToken, dfpAlice))
	if privRec.Code != http.StatusOK {
		t.Fatalf("update chat privacy failed: %d %s", privRec.Code, privRec.Body.String())
	}

	// Bob lists conversations and sees Alice's live online + typing permissions
	listRec := httptest.NewRecorder()
	mux.ServeHTTP(listRec, authReq("GET", "/v1/chat/conversations", nil, bobPair.AccessToken, dfpBob))
	if listRec.Code != http.StatusOK {
		t.Fatalf("list conversations failed: %d", listRec.Code)
	}
	var listResp struct {
		Conversations []chat.ConversationDTO `json:"conversations"`
	}
	_ = json.Unmarshal(listRec.Body.Bytes(), &listResp)
	if len(listResp.Conversations) != 1 || !listResp.Conversations[0].Permissions.ShowOnlineIndicator || !listResp.Conversations[0].Permissions.ShowTypingIndicator {
		t.Fatalf("expected Bob to see Alice's updated online & typing indicators live, got %+v", listResp.Conversations)
	}

	// HTTP typing endpoint succeeds cleanly
	typRec := httptest.NewRecorder()
	mux.ServeHTTP(typRec, authReq("POST", "/v1/chat/conversations/"+convID+"/typing", chat.TypingSignalPayload{
		TypingMode: "typing",
	}, alicePair.AccessToken, dfpAlice))
	if typRec.Code != http.StatusOK {
		t.Fatalf("typing endpoint failed: %d %s", typRec.Code, typRec.Body.String())
	}

	// 2. Alice sends a message to Bob, Bob marks seen, then Bob marks conversation unread -> must revert to delivered!
	sendRec := httptest.NewRecorder()
	mux.ServeHTTP(sendRec, authReq("POST", "/v1/chat/conversations/"+convID+"/messages", chat.SendMessagePayload{
		ClientOpID:       "op_mark_unread_1",
		MessageType:      "text",
		CompressionCodec: "zlib",
		CiphertextB64:    "c_cipher",
	}, alicePair.AccessToken, dfpAlice))
	if sendRec.Code != http.StatusOK {
		t.Fatalf("send msg failed: %d %s", sendRec.Code, sendRec.Body.String())
	}
	var sendResp struct {
		Message chat.MessageDTO `json:"message"`
	}
	_ = json.Unmarshal(sendRec.Body.Bytes(), &sendResp)

	seenRec := httptest.NewRecorder()
	mux.ServeHTTP(seenRec, authReq("POST", "/v1/chat/messages/"+sendResp.Message.MessageID+"/receipt", chat.ReceiptPayload{
		Status: "seen",
	}, bobPair.AccessToken, dfpBob))
	if seenRec.Code != http.StatusOK {
		t.Fatalf("seen receipt failed: %d", seenRec.Code)
	}

	unreadRec := httptest.NewRecorder()
	mux.ServeHTTP(unreadRec, authReq("POST", "/v1/chat/conversations/batch-action", chat.BatchConversationActionPayload{
		ConversationIDs: []string{convID},
		Action:          "mark_unread",
	}, bobPair.AccessToken, dfpBob))
	if unreadRec.Code != http.StatusOK {
		t.Fatalf("mark_unread failed: %d %s", unreadRec.Code, unreadRec.Body.String())
	}

	aliceMsgsRec := httptest.NewRecorder()
	mux.ServeHTTP(aliceMsgsRec, authReq("GET", "/v1/chat/conversations/"+convID+"/messages", nil, alicePair.AccessToken, dfpAlice))
	var msgsResp struct {
		Conversation chat.ConversationDTO `json:"conversation"`
		Messages     []chat.MessageDTO    `json:"messages"`
	}
	_ = json.Unmarshal(aliceMsgsRec.Body.Bytes(), &msgsResp)
	if len(msgsResp.Messages) != 1 || msgsResp.Messages[0].DeliveryStatus != "delivered" || msgsResp.Conversation.LastDeliveryStatus != "delivered" {
		t.Fatalf("expected peer mark_unread to revert sender message delivery_status to delivered, got msg=%+v conv=%+v", msgsResp.Messages, msgsResp.Conversation)
	}

	// 3. Video Call full lifecycle: Start (ringing) -> Accept (connected for BOTH) -> Signal -> End
	startCallRec := httptest.NewRecorder()
	mux.ServeHTTP(startCallRec, authReq("POST", "/v1/chat/calls/start", chat.StartCallPayload{
		ConversationID: convID,
		PeerUserID:     "u_call_bob",
		CallType:       "video",
	}, alicePair.AccessToken, dfpAlice))
	if startCallRec.Code != http.StatusOK {
		t.Fatalf("start call failed: %d %s", startCallRec.Code, startCallRec.Body.String())
	}
	var callResp struct {
		Call chat.CallSessionDTO `json:"call"`
	}
	_ = json.Unmarshal(startCallRec.Body.Bytes(), &callResp)
	if callResp.Call.CallID == "" || callResp.Call.State != "ringing" || callResp.Call.CallType != "video" {
		t.Fatalf("unexpected started call dto: %+v", callResp.Call)
	}
	callID := callResp.Call.CallID

	// Bob sees active ringing call via REST active endpoint
	activeBobRec := httptest.NewRecorder()
	mux.ServeHTTP(activeBobRec, authReq("GET", "/v1/chat/calls/active", nil, bobPair.AccessToken, dfpBob))
	var activeResp struct {
		Call *chat.CallSessionDTO `json:"call"`
	}
	_ = json.Unmarshal(activeBobRec.Body.Bytes(), &activeResp)
	if activeResp.Call == nil || activeResp.Call.CallID != callID || activeResp.Call.State != "ringing" {
		t.Fatalf("expected Bob active call ringing, got %+v", activeResp.Call)
	}

	// Bob accepts call -> BOTH caller & callee see state == connected!
	accRec := httptest.NewRecorder()
	mux.ServeHTTP(accRec, authReq("POST", "/v1/chat/calls/"+callID+"/accept", chat.CallActionPayload{}, bobPair.AccessToken, dfpBob))
	if accRec.Code != http.StatusOK {
		t.Fatalf("accept call failed: %d %s", accRec.Code, accRec.Body.String())
	}
	_ = json.Unmarshal(accRec.Body.Bytes(), &callResp)
	if callResp.Call.State != "connected" || callResp.Call.ConnectedAtMs == 0 {
		t.Fatalf("expected call state connected after accept, got %+v", callResp.Call)
	}

	activeAliceRec := httptest.NewRecorder()
	mux.ServeHTTP(activeAliceRec, authReq("GET", "/v1/chat/calls/active", nil, alicePair.AccessToken, dfpAlice))
	_ = json.Unmarshal(activeAliceRec.Body.Bytes(), &activeResp)
	if activeResp.Call == nil || activeResp.Call.State != "connected" {
		t.Fatalf("caller still stuck out of connected state: %+v", activeResp.Call)
	}

	// Relay audio frame & video frame signals
	sigRec := httptest.NewRecorder()
	mux.ServeHTTP(sigRec, authReq("POST", "/v1/chat/calls/"+callID+"/signal", chat.CallSignalPayload{
		Kind:     "audio_frame",
		MediaB64: "AAAA",
	}, alicePair.AccessToken, dfpAlice))
	if sigRec.Code != http.StatusOK {
		t.Fatalf("signal relay failed: %d %s", sigRec.Code, sigRec.Body.String())
	}

	// Alice ends call
	endRec := httptest.NewRecorder()
	mux.ServeHTTP(endRec, authReq("POST", "/v1/chat/calls/"+callID+"/end", chat.CallActionPayload{Reason: "hangup"}, alicePair.AccessToken, dfpAlice))
	if endRec.Code != http.StatusOK {
		t.Fatalf("end call failed: %d %s", endRec.Code, endRec.Body.String())
	}

	// 4. WhoCanCall privacy enforcement
	mux.ServeHTTP(httptest.NewRecorder(), authReq("PUT", "/v1/chat/privacy", ChatPrivacySettingsDTO{
		MessageRequests: "anyone",
		WhoCanCall:      "nobody",
	}, bobPair.AccessToken, dfpBob))
	blockedCallRec := httptest.NewRecorder()
	mux.ServeHTTP(blockedCallRec, authReq("POST", "/v1/chat/calls/start", chat.StartCallPayload{
		ConversationID: convID,
		PeerUserID:     "u_call_bob",
		CallType:       "audio",
	}, alicePair.AccessToken, dfpAlice))
	if blockedCallRec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 when WhoCanCall=nobody, got %d", blockedCallRec.Code)
	}
}
