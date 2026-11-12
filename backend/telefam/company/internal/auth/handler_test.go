package auth

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"

	"telefam.company/backend/internal/audit"
	"telefam.company/backend/internal/contacts"
	"telefam.company/backend/internal/feeds"
	"telefam.company/backend/internal/fingerprint"
	"telefam.company/backend/internal/mailer"
	"telefam.company/backend/internal/ratelimit"
)

func TestOtpSendVerifyAndRateLimitFlow(t *testing.T) {
	var sentHTML string
	var sentTo []string
	var authHeader string

	resendServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader = r.Header.Get("Authorization")
		var payload struct {
			To   []string `json:"to"`
			HTML string   `json:"html"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatalf("failed to decode resend payload: %v", err)
		}
		sentTo = payload.To
		sentHTML = payload.HTML
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"id":"test_msg_1"}`))
	}))
	defer resendServer.Close()

	secret := []byte("0123456789abcdef0123456789abcdef")
	fp := fingerprint.NewService(secret)
	limiter := ratelimit.NewLimiter()
	otpSvc := NewOtpService(secret, "https://telefam.company")
	tokens := NewTokenService(secret)
	aud := audit.NewLogger()
	resendClient := mailer.NewResendClient("test_key_only", "Telefam <onboarding@resend.dev>", resendServer.URL)
	contactsSvc := contacts.NewService()
	h := NewHandler(fp, limiter, otpSvc, tokens, aud, resendClient, contactsSvc)

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	deviceFP := "android_device_fp_1234567890"
	reqBody := bytes.NewBufferString(`{"email":"colloh185@gmail.com"}`)
	req := httptest.NewRequest(http.MethodPost, "/v1/auth/otp/request", reqBody)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Telefam-Device-FP", deviceFP)
	req.RemoteAddr = "203.0.113.10:1234"
	rec := httptest.NewRecorder()

	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d: %s", rec.Code, rec.Body.String())
	}
	if authHeader != "Bearer test_key_only" {
		t.Fatalf("unexpected authorization header: %q", authHeader)
	}
	if len(sentTo) != 1 || sentTo[0] != "colloh185@gmail.com" {
		t.Fatalf("unexpected recipients: %v", sentTo)
	}

	codeMatch := regexp.MustCompile(`>([0-9]{6})<`).FindStringSubmatch(sentHTML)
	if len(codeMatch) != 2 {
		t.Fatalf("expected 6-digit OTP inside email HTML, got: %s", sentHTML)
	}
	otpCode := codeMatch[1]

	// Immediate second request from same device/email must trigger sanitized 429 cooldown
	req2 := httptest.NewRequest(http.MethodPost, "/v1/auth/otp/request", bytes.NewBufferString(`{"email":"colloh185@gmail.com"}`))
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("X-Telefam-Device-FP", deviceFP)
	req2.RemoteAddr = "203.0.113.10:1234"
	rec2 := httptest.NewRecorder()
	mux.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 429 during cooldown, got %d", rec2.Code)
	}
	if strings.Contains(rec2.Body.String(), "cooldown") {
		t.Fatalf("error message leaked internal details: %s", rec2.Body.String())
	}

	// Verify issued OTP
	verifyReq := httptest.NewRequest(
		http.MethodPost,
		"/v1/auth/otp/verify",
		bytes.NewBufferString(`{"email":"colloh185@gmail.com","otp":"`+otpCode+`"}`),
	)
	verifyReq.Header.Set("Content-Type", "application/json")
	verifyReq.Header.Set("X-Telefam-Device-FP", deviceFP)
	verifyReq.RemoteAddr = "203.0.113.10:1234"
	verifyRec := httptest.NewRecorder()
	mux.ServeHTTP(verifyRec, verifyReq)
	if verifyRec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK verifying OTP, got %d: %s", verifyRec.Code, verifyRec.Body.String())
	}

	var session AuthSessionResponse
	if err := json.NewDecoder(verifyRec.Body).Decode(&session); err != nil {
		t.Fatalf("decode session failed: %v", err)
	}
	if !session.Success || session.SessionToken == "" || session.RefreshToken == "" {
		t.Fatalf("expected valid session pair, got: %+v", session)
	}
}

func TestContactsRelationshipsPaginationAndRateLimit(t *testing.T) {
	secret := []byte("0123456789abcdef0123456789abcdef")
	fp := fingerprint.NewService(secret)
	limiter := ratelimit.NewLimiter()
	otpSvc := NewOtpService(secret, "https://telefam.company")
	tokens := NewTokenService(secret)
	aud := audit.NewLogger()
	contactsSvc := contacts.NewService()
	h := NewHandler(fp, limiter, otpSvc, tokens, aud, nil, contactsSvc)

	deviceA := "android_device_a_123456"
	deviceFP, _ := fp.ExtractDeviceFingerprint(&http.Request{Header: http.Header{"X-Telefam-Device-FP": []string{deviceA}}})
	pairA, _, _ := tokens.IssueInitialPair("user_a", "a@telefam.company", deviceFP)

	contactsSvc.RegisterOrUpdateUser(contacts.DirectoryUser{UserID: "user_a", FullName: "Alpha User", Username: "alpha", Country: "Kenya", CountryISO: "KE"}, deviceFP)
	contactsSvc.RegisterOrUpdateUser(contacts.DirectoryUser{UserID: "user_b", FullName: "Amara Okafor", Username: "amara_reads", Country: "Nigeria", CountryISO: "NG"}, "dev_b")
	contactsSvc.RegisterOrUpdateUser(contacts.DirectoryUser{UserID: "user_c", FullName: "Brian Kiprop", Username: "brian_k", Country: "Kenya", CountryISO: "KE"}, "dev_c")

	// User B follows User A -> User A should see FOLLOW_BACK
	contactsSvc.SetFollowing("user_b", "user_a", "dev_b", true)

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	listReq := httptest.NewRequest(http.MethodGet, "/v1/contacts?filter=all&page=1&page_size=1", nil)
	listReq.Header.Set("Authorization", "Bearer "+pairA.AccessToken)
	listReq.Header.Set("X-Telefam-Device-FP", deviceA)
	listRec := httptest.NewRecorder()
	mux.ServeHTTP(listRec, listReq)
	if listRec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK listing contacts, got %d", listRec.Code)
	}
	var pageResp contacts.ContactsPageResponse
	_ = json.NewDecoder(listRec.Body).Decode(&pageResp)
	if !pageResp.HasMore || pageResp.TotalCount != 2 || pageResp.Items[0].RelationshipState != contacts.RelationFollowBack {
		t.Fatalf("unexpected contacts page: %+v", pageResp)
	}

	// Follow user_b -> mutual follow yields FRIENDS
	followReq := httptest.NewRequest(http.MethodPost, "/v1/contacts/follow", bytes.NewBufferString(`{"target_user_id":"user_b"}`))
	followReq.Header.Set("Authorization", "Bearer "+pairA.AccessToken)
	followReq.Header.Set("X-Telefam-Device-FP", deviceA)
	followRec := httptest.NewRecorder()
	mux.ServeHTTP(followRec, followReq)
	var mutResp contacts.FollowMutationResponse
	_ = json.NewDecoder(followRec.Body).Decode(&mutResp)
	if mutResp.RelationshipState != contacts.RelationFriends {
		t.Fatalf("expected FRIENDS after mutual follow, got %s", mutResp.RelationshipState)
	}
}

func TestPostMediaSongsHashtagsAndContentID(t *testing.T) {
	itunesMock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"results":[{"trackId":9988,"trackName":"Golden Hour","artistName":"JVKE","collectionName":"this is what ____ feels like","artworkUrl100":"https://example.com/art.jpg","previewUrl":"https://example.com/prev.m4a","trackTimeMillis":209000}]}`))
	}))
	defer itunesMock.Close()

	secret := []byte("0123456789abcdef0123456789abcdef")
	fp := fingerprint.NewService(secret)
	limiter := ratelimit.NewLimiter()
	otpSvc := NewOtpService(secret, "https://telefam.company")
	tokens := NewTokenService(secret)
	aud := audit.NewLogger()
	contactsSvc := contacts.NewService()
	h := NewHandler(fp, limiter, otpSvc, tokens, aud, nil, contactsSvc)

	h.SetPostsService(posts.NewServiceWithSongEndpoints(itunesMock.URL, itunesMock.URL))
	deviceA := "android_device_post_123"
	deviceFP, _ := fp.ExtractDeviceFingerprint(&http.Request{Header: http.Header{"X-Telefam-Device-FP": []string{deviceA}}})
	pairA, _, _ := tokens.IssueInitialPair("author_1", "author@telefam.company", deviceFP)

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	// 1. Search songs via mocked public iTunes API endpoint
	songReq := httptest.NewRequest(http.MethodGet, "/v1/songs/search?q=golden", nil)
	songReq.Header.Set("Authorization", "Bearer "+pairA.AccessToken)
	songReq.Header.Set("X-Telefam-Device-FP", deviceA)
	songRec := httptest.NewRecorder()
	mux.ServeHTTP(songRec, songReq)
	if songRec.Code != http.StatusOK || !strings.Contains(songRec.Body.String(), "Golden Hour") {
		t.Fatalf("expected song search result Golden Hour, got %d: %s", songRec.Code, songRec.Body.String())
	}

	// 2. Upload valid MP4 header + per-frame client dHashes -> verifies deterministic vid_ content_id + renditions
	fakeMp4 := []byte{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0x00, 0x00, 0x00, 0x00, 'm', 'd', 'a', 't', 1, 2, 3, 4}
	vidReq := httptest.NewRequest(http.MethodPost, "/v1/media/video?duration_ms=12000&trim_start_ms=1000&trim_end_ms=9000&width=1280&height=720", bytes.NewReader(fakeMp4))
	vidReq.Header.Set("Authorization", "Bearer "+pairA.AccessToken)
	vidReq.Header.Set("X-Telefam-Device-FP", deviceA)
	vidReq.Header.Set("X-Telefam-Frame-Hashes", "0f0f0f0f0f0f0f0f,a1a1a1a1a1a1a1a1")
	vidRec := httptest.NewRecorder()
	mux.ServeHTTP(vidRec, vidReq)
	if vidRec.Code != http.StatusOK {
		t.Fatalf("expected 200 uploading mp4, got %d: %s", vidRec.Code, vidRec.Body.String())
	}
	var mediaResp MediaUploadResponse
	_ = json.NewDecoder(vidRec.Body).Decode(&mediaResp)
	if !strings.HasPrefix(mediaResp.ContentID, "vid_") || len(mediaResp.Renditions) < 2 {
		t.Fatalf("unexpected video upload response: %+v", mediaResp)
	}

	// 3. Create video post with hashtag -> verify hashtag post_count increments
	postBody := `{"client_op_id":"op_v1","mode":"video","caption":"Weekend trip","video_media_id":"` + mediaResp.MediaID + `","trim_start_ms":1000,"trim_end_ms":9000,"mute_original_audio":true,"content_id":"` + mediaResp.ContentID + `","hashtags":["telefam","travel"]}`
	postReq := httptest.NewRequest(http.MethodPost, "/v1/posts", bytes.NewBufferString(postBody))
	postReq.Header.Set("Authorization", "Bearer "+pairA.AccessToken)
	postReq.Header.Set("X-Telefam-Device-FP", deviceA)
	postRec := httptest.NewRecorder()
	mux.ServeHTTP(postRec, postReq)
	if postRec.Code != http.StatusOK {
		t.Fatalf("expected 200 creating post, got %d: %s", postRec.Code, postRec.Body.String())
	}

	tagReq := httptest.NewRequest(http.MethodGet, "/v1/hashtags/search?q=telefam", nil)
	tagReq.Header.Set("Authorization", "Bearer "+pairA.AccessToken)
	tagReq.Header.Set("X-Telefam-Device-FP", deviceA)
	tagRec := httptest.NewRecorder()
	mux.ServeHTTP(tagRec, tagReq)
	if tagRec.Code != http.StatusOK || !strings.Contains(tagRec.Body.String(), `"post_count":1`) {
		t.Fatalf("expected hashtag post_count 1, got %d: %s", tagRec.Code, tagRec.Body.String())
	}
}

func TestFeedsDynamicRecommendationsRangeStreamingRepostAndOwnerEditDelete(t *testing.T) {
	secret := []byte("0123456789abcdef0123456789abcdef")
	fp := fingerprint.NewService(secret)
	limiter := ratelimit.NewLimiter()
	otpSvc := NewOtpService(secret, "https://telefam.company")
	tokens := NewTokenService(secret)
	aud := audit.NewLogger()
	contactsSvc := contacts.NewService()
	h := NewHandler(fp, limiter, otpSvc, tokens, aud, nil, contactsSvc)

	deviceA := "android_device_feed_1"
	deviceB := "android_device_feed_2"
	fpA, _ := fp.ExtractDeviceFingerprint(&http.Request{Header: http.Header{"X-Telefam-Device-FP": []string{deviceA}}})
	fpB, _ := fp.ExtractDeviceFingerprint(&http.Request{Header: http.Header{"X-Telefam-Device-FP": []string{deviceB}}})

	contactsSvc.RegisterOrUpdateUser(contacts.DirectoryUser{UserID: "creator_cook", Username: "chef_amara", FullName: "Amara"}, fpA)
	contactsSvc.RegisterOrUpdateUser(contacts.DirectoryUser{UserID: "creator_night", Username: "rohan.verma", FullName: "Rohan"}, fpB)

	pairViewer, _, _ := tokens.IssueInitialPair("viewer_1", "viewer@telefam.company", fpA)
	pairCook, _, _ := tokens.IssueInitialPair("creator_cook", "cook@telefam.company", fpA)
	pairNight, _, _ := tokens.IssueInitialPair("creator_night", "night@telefam.company", fpB)

	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	uploadVideo := func(token, dev string) string {
		mp4 := []byte{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0, 'm', 'd', 'a', 't', 1, 2, 3, 4}
		req := httptest.NewRequest(http.MethodPost, "/v1/media/video?duration_ms=10000&width=1080&height=1920", bytes.NewReader(mp4))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("X-Telefam-Device-FP", dev)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		var resp MediaUploadResponse
		_ = json.NewDecoder(rec.Body).Decode(&resp)
		return resp.MediaID
	}

	cookMedia := uploadVideo(pairCook.AccessToken, deviceA)
	nightMedia := uploadVideo(pairNight.AccessToken, deviceB)

	createPost := func(token, dev, body string) string {
		req := httptest.NewRequest(http.MethodPost, "/v1/posts", bytes.NewBufferString(body))
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("X-Telefam-Device-FP", dev)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		var out struct {
			Post struct {
				PostID string `json:"post_id"`
			} `json:"post"`
		}
		_ = json.NewDecoder(rec.Body).Decode(&out)
		return out.Post.PostID
	}

	cookPostID := createPost(pairCook.AccessToken, deviceA, `{"client_op_id":"op_c1","mode":"video","caption":"Quick pasta","video_media_id":"`+cookMedia+`","hashtags":["cooking","pasta"]}`)
	nightPostID := createPost(pairNight.AccessToken, deviceB, `{"client_op_id":"op_n1","mode":"video","caption":"Beautiful nights","video_media_id":"`+nightMedia+`","hashtags":["nightvibes","citylights"]}`)

	// 1. Verify RFC 7233 HTTP 206 Partial Content range streaming works for Media3/AVPlayer preloading
	rangeReq := httptest.NewRequest(http.MethodGet, "/v1/media/asset/"+nightMedia, nil)
	rangeReq.Header.Set("Range", "bytes=0-11")
	rangeRec := httptest.NewRecorder()
	mux.ServeHTTP(rangeRec, rangeReq)
	if rangeRec.Code != http.StatusPartialContent || rangeRec.Body.Len() != 12 {
		t.Fatalf("expected 206 Partial Content 12 bytes, got %d (%d bytes)", rangeRec.Code, rangeRec.Body.Len())
	}

	// 2. Viewer likes nightPostID -> nightPostID ranks first on For You
	interact := func(body string) {
		req := httptest.NewRequest(http.MethodPost, "/v1/feeds/interact", bytes.NewBufferString(body))
		req.Header.Set("Authorization", "Bearer "+pairViewer.AccessToken)
		req.Header.Set("X-Telefam-Device-FP", deviceA)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("interact failed: %d %s", rec.Code, rec.Body.String())
		}
	}
	interact(`{"post_id":"` + nightPostID + `","action":"like"}`)
	interact(`{"post_id":"` + nightPostID + `","action":"watch","watch_ms":9000,"duration_ms":10000}`)

	fetchFeeds := func(tab string) feeds.FeedPageResponse {
		req := httptest.NewRequest(http.MethodGet, "/v1/feeds?tab="+tab, nil)
		req.Header.Set("Authorization", "Bearer "+pairViewer.AccessToken)
		req.Header.Set("X-Telefam-Device-FP", deviceA)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		var resp feeds.FeedPageResponse
		_ = json.NewDecoder(rec.Body).Decode(&resp)
		return resp
	}
	feed1 := fetchFeeds("for_you")
	if len(feed1.Items) != 2 || feed1.Items[0].PostID != nightPostID {
		t.Fatalf("expected nightPostID ranked first after like+watch, got %+v", feed1.Items)
	}

	// 3. Viewer interests dynamically shift: favourite cooking + mark nightPost not_interested
	interact(`{"post_id":"` + nightPostID + `","action":"not_interested"}`)
	interact(`{"post_id":"` + cookPostID + `","action":"favourite"}`)
	feed2 := fetchFeeds("for_you")
	if len(feed2.Items) != 1 || feed2.Items[0].PostID != cookPostID {
		t.Fatalf("expected dynamic interest pivot to cookPostID only, got %+v", feed2.Items)
	}

	// 4. Repost makes cookPostID show as Reposted in feed
	interact(`{"post_id":"` + cookPostID + `","action":"repost"}`)
	feedRepost := fetchFeeds("following")
	if len(feedRepost.Items) != 1 || !feedRepost.Items[0].IsReposted {
		t.Fatalf("expected reposted item in following feed, got %+v", feedRepost.Items)
	}

	// 5. Search shortcut generation from live corpus hashtags
	searchReq := httptest.NewRequest(http.MethodGet, "/v1/feeds/search?q=pasta&shortcut=cooking", nil)
	searchReq.Header.Set("Authorization", "Bearer "+pairViewer.AccessToken)
	searchReq.Header.Set("X-Telefam-Device-FP", deviceA)
	searchRec := httptest.NewRecorder()
	mux.ServeHTTP(searchRec, searchReq)
	var searchResp feeds.FeedSearchResponse
	_ = json.NewDecoder(searchRec.Body).Decode(&searchResp)
	if len(searchResp.Items) != 1 || len(searchResp.Shortcuts) < 2 {
		t.Fatalf("unexpected search response: %+v", searchResp)
	}

	// 6. Owner enforcement: non-owner cannot edit/delete (403); owner can edit & delete (200)
	forbiddenEdit := httptest.NewRequest(http.MethodPatch, "/v1/posts/"+cookPostID, bytes.NewBufferString(`{"caption":"hijacked"}`))
	forbiddenEdit.Header.Set("Authorization", "Bearer "+pairViewer.AccessToken)
	forbiddenEdit.Header.Set("X-Telefam-Device-FP", deviceA)
	forbiddenRec := httptest.NewRecorder()
	mux.ServeHTTP(forbiddenRec, forbiddenEdit)
	if forbiddenRec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden for non-owner edit, got %d", forbiddenRec.Code)
	}

	ownerEdit := httptest.NewRequest(http.MethodPatch, "/v1/posts/"+cookPostID, bytes.NewBufferString(`{"caption":"Updated pasta recipe"}`))
	ownerEdit.Header.Set("Authorization", "Bearer "+pairCook.AccessToken)
	ownerEdit.Header.Set("X-Telefam-Device-FP", deviceA)
	ownerEditRec := httptest.NewRecorder()
	mux.ServeHTTP(ownerEditRec, ownerEdit)
	if ownerEditRec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for owner edit, got %d", ownerEditRec.Code)
	}

	ownerDel := httptest.NewRequest(http.MethodDelete, "/v1/posts/"+cookPostID, nil)
	ownerDel.Header.Set("Authorization", "Bearer "+pairCook.AccessToken)
	ownerDel.Header.Set("X-Telefam-Device-FP", deviceA)
	ownerDelRec := httptest.NewRecorder()
	mux.ServeHTTP(ownerDelRec, ownerDel)
	if ownerDelRec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for owner delete, got %d", ownerDelRec.Code)
	}
}


