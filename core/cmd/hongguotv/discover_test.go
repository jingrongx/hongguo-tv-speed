package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"testing"
)

// discoverStub serves three category pages of 15 playable dramas each and
// remembers the requested offset so the test can assert paging behavior.
func discoverStub(t *testing.T, requested *[]int) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/reading/distribution/category/landpage/v/" {
			t.Errorf("unexpected category path %s", r.URL.Path)
		}
		var body struct {
			Offset int `json:"offset"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		*requested = append(*requested, body.Offset)
		rows := make([]map[string]any, 0, 15)
		for i := 0; i < 15; i++ {
			id := body.Offset + i + 1
			rows = append(rows, map[string]any{
				"series_id":   strconv.Itoa(7700000000000000000 + id),
				"series_name": fmt.Sprintf("剧%d", id),
				"episode_cnt": 12,
			})
		}
		more := body.Offset < 30
		next := body.Offset + 15
		response := map[string]any{
			"code":    0,
			"message": "success",
			"data": map[string]any{
				"video_data":  rows,
				"has_more":    more,
				"next_offset": next,
				"session_id":  "stub-session",
			},
		}
		_ = json.NewEncoder(w).Encode(response)
	}))
}

func newDiscoverTestServer(t *testing.T, watched ...string) (*server, *[]int) {
	t.Helper()
	var requested []int
	upstream := discoverStub(t, &requested)
	client := newHongguoClient()
	client.apiBase = upstream.URL
	client.webBase = upstream.URL
	store, err := newLibraryStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range watched {
		if _, err := store.apply(LibraryChange{Action: "progress", Drama: Drama{SeriesID: id, Title: "看过"}, Episode: 2, VideoID: "7600000000000000001"}); err != nil {
			t.Fatal(err)
		}
	}
	return &server{hongguo: client, libraryStore: store, discover: newDiscoverFeed()}, &requested
}

func TestDiscoverFeedExcludesWatchedAndDoesNotRepeat(t *testing.T) {
	watched := []string{strconv.Itoa(7700000000000000001), strconv.Itoa(7700000000000000030)}
	s, _ := newDiscoverTestServer(t, watched...)
	first, err := s.discoverFeedHandler(context.Background(), url.Values{"session_id": {""}})
	if err != nil {
		t.Fatal(err)
	}
	if first.SessionID == "" || len(first.Items) != discoverPageSize {
		t.Fatalf("first page: session=%q items=%d", first.SessionID, len(first.Items))
	}
	seen := map[string]bool{}
	excluded := map[string]bool{}
	for _, id := range watched {
		excluded[id] = true
	}
	for _, item := range first.Items {
		if excluded[item.SeriesID] {
			t.Fatalf("watched drama %s resurfaced in the discovery feed", item.SeriesID)
		}
		if seen[item.SeriesID] {
			t.Fatalf("duplicate series %s within one session", item.SeriesID)
		}
		seen[item.SeriesID] = true
	}
	// Walk the pool dry through the same session: every page must add only
	// fresh series ids.
	for page := 0; page < 6; page++ {
		next, err := s.discoverFeedHandler(context.Background(), url.Values{"session_id": {first.SessionID}})
		if err != nil {
			t.Fatal(err)
		}
		for _, item := range next.Items {
			if seen[item.SeriesID] {
				t.Fatalf("series %s served twice in one session", item.SeriesID)
			}
			seen[item.SeriesID] = true
		}
		if len(next.Items) == 0 {
			break
		}
	}
	if len(seen) < 30 {
		t.Fatalf("discovery pool drained too early: %d unique dramas", len(seen))
	}
}

func TestDiscoverFeedNewSessionReshufflesAndKeepsExcluding(t *testing.T) {
	watched := strconv.Itoa(7700000000000000002)
	s, _ := newDiscoverTestServer(t, watched)
	first, err := s.discoverFeedHandler(context.Background(), url.Values{})
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.discoverFeedHandler(context.Background(), url.Values{})
	if err != nil {
		t.Fatal(err)
	}
	if first.SessionID == second.SessionID {
		t.Fatal("a fresh request must start a fresh discovery session")
	}
	if len(first.Items) == 0 || len(second.Items) == 0 {
		t.Fatal("empty discovery pages")
	}
	for _, page := range [][]Drama{first.Items, second.Items} {
		for _, item := range page {
			if item.SeriesID == watched {
				t.Fatalf("watched drama %s survived the exclusion", watched)
			}
			if item.EpisodeCount < 1 {
				t.Fatalf("unplayable drama %s entered the pool", item.SeriesID)
			}
		}
	}
	sameOrder := len(first.Items) == len(second.Items)
	if sameOrder {
		for i := range first.Items {
			if first.Items[i].SeriesID != second.Items[i].SeriesID {
				sameOrder = false
				break
			}
		}
	}
	if sameOrder {
		t.Fatal("two independent sessions produced the identical shuffle")
	}
}

func TestDiscoverFeedShownFilterIsHonored(t *testing.T) {
	s, _ := newDiscoverTestServer(t)
	shown := strconv.Itoa(7700000000000000003)
	page, err := s.discoverFeedHandler(context.Background(), url.Values{"shown": {shown}})
	if err != nil {
		t.Fatal(err)
	}
	for _, item := range page.Items {
		if item.SeriesID == shown {
			t.Fatalf("client-shown drama %s was served again", shown)
		}
	}
}

// The landscape preference filters the pool server-side: dramas measured as
// vertical never leave the AI shelves, and the general shelves only pass
// dramas the shared database has measured as landscape. Draining both runs
// keeps the assertions independent of the per-session shuffle.
func TestDiscoverFeedLandscapeFilter(t *testing.T) {
	vertical := strconv.Itoa(7700000000000000001)
	landscape := strconv.Itoa(7700000000000000002)
	db := &orientationDatabase{records: map[string]orientationRecord{
		vertical:  {O: "v", W: 720, H: 1280},
		landscape: {O: "h", W: 1280, H: 720},
	}}
	drain := func(query url.Values) []string {
		s, _ := newDiscoverTestServer(t)
		s.orientationDB = db
		var ids []string
		for call := 0; call < 40; call++ {
			out, err := s.discoverFeedHandler(context.Background(), query)
			if err != nil {
				t.Fatal(err)
			}
			for _, drama := range out.Items {
				ids = append(ids, drama.SeriesID)
			}
			if !out.HasMore {
				break
			}
		}
		return ids
	}
	landscapeFeed := drain(url.Values{"session_id": {"land-test"}, "landscape": {"1"}})
	unfiltered := drain(url.Values{"session_id": {"open-test"}})
	if len(landscapeFeed) == 0 {
		t.Fatal("landscape feed served nothing")
	}
	if len(unfiltered) == 0 {
		t.Fatal("unfiltered feed served nothing")
	}
	for _, id := range landscapeFeed {
		if id == vertical {
			t.Fatal("landscape feed served a drama measured as vertical")
		}
	}
	seenVertical := false
	for _, id := range unfiltered {
		if id == vertical {
			seenVertical = true
		}
	}
	if !seenVertical {
		t.Fatal("unfiltered feed never served the vertical drama")
	}
	seenLandscape := false
	for _, id := range landscapeFeed {
		if id == landscape {
			seenLandscape = true
		}
	}
	if !seenLandscape {
		t.Fatal("landscape feed never served the measured landscape drama")
	}
}

func TestOrientationPayloadParseAndMerge(t *testing.T) {
	base := parseOrientationPayload([]byte(`{"version":1,"updated":"2026-01-01T00:00:00Z","series":{
		"111":{"o":"v","w":720,"h":1280,"t":1},
		"222":{"o":"h","w":1920,"h":1080},
		"333":{"o":"x"},
		"":{"o":"v"}
	}}`))
	if len(base.Series) != 4 {
		t.Fatalf("parse keeps raw rows, got %d", len(base.Series))
	}
	if base.Series["111"].O != "v" || base.Series["222"].O != "h" {
		t.Fatal("orientation verdicts lost in parse")
	}
	db := newOrientationDatabase(t.TempDir())
	if merged := db.merge(base); merged != 2 {
		t.Fatalf("expected 2 merged records, got %d", merged)
	}
	if merged := db.merge(base); merged != 0 {
		t.Fatalf("re-merging the same records must be a no-op, got %d", merged)
	}
	conflict := parseOrientationPayload([]byte(`{"version":1,"series":{"111":{"o":"h","w":1280,"h":720}}}`))
	if merged := db.merge(conflict); merged != 1 {
		t.Fatal("a different verdict must override the stored one")
	}
	snapshot := db.snapshot()
	if snapshot.Series["111"].O != "h" {
		t.Fatal("snapshot must reflect the newest verdict")
	}
	if len(parseOrientationPayload([]byte(`{"version":2,"series":{"444":{"o":"v"}}}`)).Series) != 0 {
		t.Fatal("unknown payload versions must be rejected")
	}
}

func TestPlaybackErrorCodeClassification(t *testing.T) {
	cases := map[string]string{
		fmt.Errorf("内置播放解析失败: %w", errMiniReelVideoModelEmpty).Error(): "VIDEO_MODEL_EMPTY",
		"内置播放解析失败: MiniReel media preparation timed out":               "UPSTREAM_TIMEOUT",
		"按需读取播放准备失败: 上游 HTTP 403":                                      "VIDEO_MODEL_HTTP",
		"内置播放解析失败: read tcp: connection reset by peer":                 "ECONNRESET",
		"内置播放解析失败: dial tcp: connect: connection refused":              "ECONNREFUSED",
		"内置播放服务没有返回地址":                                                 "VIDEO_MODEL_EMPTY",
		"按需读取准备失败: upstream has no strong ETag":                        "",
	}
	for message, expected := range cases {
		if got := playbackErrorCode(fmt.Errorf("%s", message)); got != expected {
			t.Fatalf("playbackErrorCode(%q) = %q, want %q", message, got, expected)
		}
	}
	if got := playbackErrorCode(fmt.Errorf("wrapped: %w", context.DeadlineExceeded)); got != "UPSTREAM_TIMEOUT" {
		t.Fatalf("deadline errors must classify as UPSTREAM_TIMEOUT, got %q", got)
	}
	if got := playbackErrorCode(nil); got != "" {
		t.Fatalf("nil error must classify empty, got %q", got)
	}
}

func TestPlayInfoWritesMachineErrorCode(t *testing.T) {
	recorder := httptest.NewRecorder()
	writeErrorCode(recorder, http.StatusBadGateway, "UPSTREAM_TIMEOUT", "内置播放解析失败: timeout")
	var payload struct {
		OK        bool   `json:"ok"`
		Error     string `json:"error"`
		ErrorCode string `json:"error_code"`
	}
	if err := json.NewDecoder(bytes.NewReader(recorder.Body.Bytes())).Decode(&payload); err != nil {
		t.Fatal(err)
	}
	if payload.OK || payload.ErrorCode != "UPSTREAM_TIMEOUT" || payload.Error == "" {
		t.Fatalf("machine code missing next to the message: %#v", payload)
	}
	plain := httptest.NewRecorder()
	writeError(plain, http.StatusBadRequest, "参数无效")
	if bytes.Contains(plain.Body.Bytes(), []byte("error_code")) {
		t.Fatal("plain errors must not gain an error_code field")
	}
}
