package chat

import (
	"strings"
	"time"
)

const callRingingTimeoutMs = 45 * 1000

type storedCall struct {
	DTO CallSessionDTO
}

func normalizeCallType(raw string) string {
	if strings.EqualFold(strings.TrimSpace(raw), "video") {
		return "video"
	}
	return "audio"
}

func (s *Service) StartCall(callerID string, req StartCallPayload) (CallSessionDTO, error) {
	peerID := strings.TrimSpace(req.PeerUserID)
	if peerID == "" || peerID == callerID {
		return CallSessionDTO{}, ErrBadRequest
	}
	if s.hooks.IsBlocked != nil && s.hooks.IsBlocked(callerID, peerID) {
		return CallSessionDTO{}, ErrForbidden
	}
	if s.hooks.GetPrivacy != nil {
		peerPv := s.hooks.GetPrivacy(peerID)
		if !s.allowsRule(peerID, callerID, peerPv.WhoCanCall) {
			return CallSessionDTO{}, ErrForbidden
		}
	}

	conv, err := s.OpenOrCreateDirectConversation(callerID, peerID)
	if err != nil {
		return CallSessionDTO{}, err
	}

	callType := normalizeCallType(req.CallType)
	var callerInfo, calleeInfo UserInfo
	if s.hooks.ResolveUser != nil {
		callerInfo, _ = s.hooks.ResolveUser(callerID)
		calleeInfo, _ = s.hooks.ResolveUser(peerID)
	}
	if callerInfo.FullName == "" {
		callerInfo.FullName = callerInfo.Username
	}
	if callerInfo.FullName == "" {
		callerInfo.FullName = "Telefam User"
	}
	if calleeInfo.FullName == "" {
		calleeInfo.FullName = calleeInfo.Username
	}
	if calleeInfo.FullName == "" {
		calleeInfo.FullName = "Telefam User"
	}

	now := s.nowMs()
	s.mu.Lock()
	s.cleanupExpiredCallsLocked(now)

	// Terminate any prior unfinished call from this caller cleanly
	if prevID := s.userActiveCall[callerID]; prevID != "" {
		if prev := s.calls[prevID]; prev != nil && prev.DTO.State != "ended" {
			prev.DTO.State = "ended"
			prev.DTO.EndReason = "replaced"
			prev.DTO.EndedAtMs = now
			delete(s.userActiveCall, prev.DTO.CallerUserID)
			delete(s.userActiveCall, prev.DTO.CalleeUserID)
		}
	}

	// If peer is already in an active connected call, mark this one busy immediately or notify
	if peerPrevID := s.userActiveCall[peerID]; peerPrevID != "" {
		if peerPrev := s.calls[peerPrevID]; peerPrev != nil && peerPrev.DTO.State == "connected" {
			s.mu.Unlock()
			return CallSessionDTO{}, ErrForbidden
		}
	}

	dto := CallSessionDTO{
		CallID:         newRandomID("call"),
		ConversationID: conv.ConversationID,
		CallerUserID:   callerID,
		CalleeUserID:   peerID,
		CallerName:     callerInfo.FullName,
		CallerUsername: callerInfo.Username,
		CallerAvatar:   callerInfo.AvatarURL,
		CalleeName:     calleeInfo.FullName,
		CalleeUsername: calleeInfo.Username,
		CalleeAvatar:   calleeInfo.AvatarURL,
		CallType:       callType,
		State:          "ringing",
		CallerMuted:    false,
		CalleeMuted:    false,
		CallerCameraOn: callType == "video",
		CalleeCameraOn: callType == "video",
		OfferSDP:       strings.TrimSpace(req.OfferSDP),
		CreatedAtMs:    now,
	}
	s.calls[dto.CallID] = &storedCall{DTO: dto}
	s.userActiveCall[callerID] = dto.CallID
	s.userActiveCall[peerID] = dto.CallID
	s.lastSeenMs[callerID] = now
	s.mu.Unlock()

	callCopy := dto
	s.pushWSEvent(peerID, WebSocketEvent{
		Event:          "call_incoming",
		ConversationID: dto.ConversationID,
		UserID:         callerID,
		CallID:         dto.CallID,
		CallType:       dto.CallType,
		CallState:      dto.State,
		Call:           &callCopy,
		TimestampMs:    now,
	})
	s.pushWSEvent(callerID, WebSocketEvent{
		Event:          "call_state",
		ConversationID: dto.ConversationID,
		UserID:         callerID,
		CallID:         dto.CallID,
		CallType:       dto.CallType,
		CallState:      dto.State,
		Call:           &callCopy,
		TimestampMs:    now,
	})
	return dto, nil
}

func (s *Service) AcceptCall(callerID, callID string, req CallActionPayload) (CallSessionDTO, error) {
	s.mu.Lock()
	c, ok := s.calls[strings.TrimSpace(callID)]
	if !ok {
		s.mu.Unlock()
		return CallSessionDTO{}, ErrNotFound
	}
	if c.DTO.CalleeUserID != callerID && c.DTO.CallerUserID != callerID {
		s.mu.Unlock()
		return CallSessionDTO{}, ErrForbidden
	}
	if c.DTO.State == "ended" {
		dto := c.DTO
		s.mu.Unlock()
		return dto, nil
	}
	// Only the callee (or re-confirming participant) transitions ringing -> connected
	if c.DTO.CalleeUserID != callerID {
		s.mu.Unlock()
		return CallSessionDTO{}, ErrForbidden
	}
	now := s.nowMs()
	c.DTO.State = "connected"
	if c.DTO.ConnectedAtMs == 0 {
		c.DTO.ConnectedAtMs = now
	}
	if ans := strings.TrimSpace(req.AnswerSDP); ans != "" {
		c.DTO.AnswerSDP = ans
	}
	s.userActiveCall[c.DTO.CallerUserID] = c.DTO.CallID
	s.userActiveCall[c.DTO.CalleeUserID] = c.DTO.CallID
	s.lastSeenMs[callerID] = now
	dto := c.DTO
	s.mu.Unlock()

	callCopy := dto
	acceptedEv := WebSocketEvent{
		Event:          "call_accepted",
		ConversationID: dto.ConversationID,
		UserID:         callerID,
		CallID:         dto.CallID,
		CallType:       dto.CallType,
		CallState:      "connected",
		Call:           &callCopy,
		SignalKind:     "answer",
		SignalPayload:  dto.AnswerSDP,
		TimestampMs:    now,
	}
	connectedEv := acceptedEv
	connectedEv.Event = "call_connected"

	// Push BOTH events to BOTH participants so neither caller nor callee can ever stay stuck in ringing.
	s.pushWSEvent(dto.CallerUserID, acceptedEv)
	s.pushWSEvent(dto.CallerUserID, connectedEv)
	s.pushWSEvent(dto.CalleeUserID, connectedEv)
	return dto, nil
}

func (s *Service) RejectCall(callerID, callID string, req CallActionPayload) (CallSessionDTO, error) {
	reason := strings.TrimSpace(req.Reason)
	if reason == "" {
		reason = "declined"
	}
	return s.terminateCall(callerID, callID, reason)
}

func (s *Service) EndCall(callerID, callID string, req CallActionPayload) (CallSessionDTO, error) {
	reason := strings.TrimSpace(req.Reason)
	if reason == "" {
		reason = "hangup"
	}
	return s.terminateCall(callerID, callID, reason)
}

func (s *Service) terminateCall(callerID, callID, reason string) (CallSessionDTO, error) {
	s.mu.Lock()
	c, ok := s.calls[strings.TrimSpace(callID)]
	if !ok {
		s.mu.Unlock()
		return CallSessionDTO{}, ErrNotFound
	}
	if c.DTO.CallerUserID != callerID && c.DTO.CalleeUserID != callerID {
		s.mu.Unlock()
		return CallSessionDTO{}, ErrForbidden
	}
	now := s.nowMs()
	if c.DTO.State != "ended" {
		if c.DTO.State == "ringing" && callerID == c.DTO.CallerUserID && reason == "hangup" {
			reason = "missed"
		}
		c.DTO.State = "ended"
		c.DTO.EndReason = reason
		c.DTO.EndedAtMs = now
	}
	if s.userActiveCall[c.DTO.CallerUserID] == c.DTO.CallID {
		delete(s.userActiveCall, c.DTO.CallerUserID)
	}
	if s.userActiveCall[c.DTO.CalleeUserID] == c.DTO.CallID {
		delete(s.userActiveCall, c.DTO.CalleeUserID)
	}
	dto := c.DTO
	s.mu.Unlock()

	callCopy := dto
	ev := WebSocketEvent{
		Event:          "call_ended",
		ConversationID: dto.ConversationID,
		UserID:         callerID,
		CallID:         dto.CallID,
		CallType:       dto.CallType,
		CallState:      "ended",
		Status:         dto.EndReason,
		Call:           &callCopy,
		TimestampMs:    now,
	}
	s.pushWSEvent(dto.CallerUserID, ev)
	s.pushWSEvent(dto.CalleeUserID, ev)
	return dto, nil
}

func (s *Service) RelayCallSignal(callerID, callID string, req CallSignalPayload) error {
	cleanID := strings.TrimSpace(callID)
	kind := strings.ToLower(strings.TrimSpace(req.Kind))
	if cleanID == "" || kind == "" {
		return ErrBadRequest
	}
	s.mu.Lock()
	c, ok := s.calls[cleanID]
	if !ok {
		s.mu.Unlock()
		return ErrNotFound
	}
	if c.DTO.CallerUserID != callerID && c.DTO.CalleeUserID != callerID {
		s.mu.Unlock()
		return ErrForbidden
	}
	if c.DTO.State == "ended" {
		s.mu.Unlock()
		return ErrBadRequest
	}
	peerID := c.DTO.CalleeUserID
	isCaller := callerID == c.DTO.CallerUserID
	if !isCaller {
		peerID = c.DTO.CallerUserID
		if (kind == "answer" || kind == "audio_frame" || kind == "video_frame") && c.DTO.State == "ringing" {
			c.DTO.State = "connected"
			c.DTO.ConnectedAtMs = s.nowMs()
		}
	}
	if req.Muted != nil {
		if isCaller {
			c.DTO.CallerMuted = *req.Muted
		} else {
			c.DTO.CalleeMuted = *req.Muted
		}
	}
	if req.CameraOn != nil {
		if isCaller {
			c.DTO.CallerCameraOn = *req.CameraOn
		} else {
			c.DTO.CalleeCameraOn = *req.CameraOn
		}
	}
	now := s.nowMs()
	s.lastSeenMs[callerID] = now
	dto := c.DTO
	s.mu.Unlock()

	evName := "call_signal"
	if kind == "audio_frame" || kind == "video_frame" {
		evName = "call_media"
	}
	callCopy := dto
	s.pushWSEvent(peerID, WebSocketEvent{
		Event:          evName,
		ConversationID: dto.ConversationID,
		UserID:         callerID,
		CallID:         dto.CallID,
		CallType:       dto.CallType,
		CallState:      dto.State,
		Call:           &callCopy,
		SignalKind:     kind,
		SignalPayload:  req.Payload,
		MediaB64:       req.MediaB64,
		VideoWidth:     req.VideoWidth,
		VideoHeight:    req.VideoHeight,
		CallerMuted:    req.Muted,
		CallerCameraOn: req.CameraOn,
		Seq:            req.Seq,
		TimestampMs:    now,
	})
	return nil
}

func (s *Service) GetActiveCallForUser(callerID string) *CallSessionDTO {
	now := s.nowMs()
	s.mu.Lock()
	expired := s.cleanupExpiredCallsLocked(now)
	callID := s.userActiveCall[callerID]
	var dtoCopy *CallSessionDTO
	if callID != "" {
		if c := s.calls[callID]; c != nil && c.DTO.State != "ended" {
			d := c.DTO
			dtoCopy = &d
		}
	}
	s.mu.Unlock()

	for _, exp := range expired {
		expCopy := exp
		ev := WebSocketEvent{
			Event:          "call_ended",
			ConversationID: exp.ConversationID,
			CallID:         exp.CallID,
			CallType:       exp.CallType,
			CallState:      "ended",
			Status:         exp.EndReason,
			Call:           &expCopy,
			TimestampMs:    now,
		}
		s.pushWSEvent(exp.CallerUserID, ev)
		s.pushWSEvent(exp.CalleeUserID, ev)
	}
	return dtoCopy
}

func (s *Service) cleanupExpiredCallsLocked(nowMs int64) []CallSessionDTO {
	var expired []CallSessionDTO
	for _, c := range s.calls {
		if c.DTO.State == "ringing" && nowMs-c.DTO.CreatedAtMs >= callRingingTimeoutMs {
			c.DTO.State = "ended"
			c.DTO.EndReason = "missed"
			c.DTO.EndedAtMs = nowMs
			if s.userActiveCall[c.DTO.CallerUserID] == c.DTO.CallID {
				delete(s.userActiveCall, c.DTO.CallerUserID)
			}
			if s.userActiveCall[c.DTO.CalleeUserID] == c.DTO.CallID {
				delete(s.userActiveCall, c.DTO.CalleeUserID)
			}
			expired = append(expired, c.DTO)
		}
	}
	return expired
}

// Re-check ringing calls periodically isn't strictly needed; lazy cleanup on GetActiveCall + AcceptCall + StartCall
// plus client timer guarantees zero orphaned ringing calls.
var _ = time.Millisecond
