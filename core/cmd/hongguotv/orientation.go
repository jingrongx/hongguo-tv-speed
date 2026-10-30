package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// The public release channel this distribution publishes to. Mirrored the
// same way the APK updater mirrors its manifest.
const orientationRepository = "jingrongx/hongguo-tv-speed"

// The orientation database maps series_id → video shape. It removes the
// per-drama probe (and for vertical dramas the whole download) from the
// playback path: the crawler in this repository measures new dramas on a
// schedule, every release embeds the accumulated snapshot, and running
// installs refresh it from the same public channel the updater uses. No
// account data, no user input, no manual maintenance.

//go:embed data/series-orientation.json
var orientationSnapshot []byte

const (
	orientationRefreshInterval = 6 * time.Hour
	orientationRequestTimeout  = 20 * time.Second
)

type orientationRecord struct {
	// o: "v" vertical, "h" landscape. w/h: measured video dimensions.
	O       string `json:"o"`
	W       int    `json:"w,omitempty"`
	H       int    `json:"h,omitempty"`
	Updated int64  `json:"t,omitempty"`
}

type orientationPayload struct {
	Version int                          `json:"version"`
	Updated string                       `json:"updated,omitempty"`
	Series  map[string]orientationRecord `json:"series"`
}

type orientationDatabase struct {
	mu       sync.Mutex
	dir      string
	records  map[string]orientationRecord
	updated  string
	lastPull time.Time
	pulling  bool
}

var orientationSourcePaths = []string{
	"/main/core/cmd/hongguotv/data/series-orientation.json",
}

func newOrientationDatabase(dir string) *orientationDatabase {
	db := &orientationDatabase{dir: dir, records: map[string]orientationRecord{}}
	db.merge(parseOrientationPayload(orientationSnapshot))
	if online, err := os.ReadFile(filepath.Join(dir, "series-orientation.json")); err == nil {
		db.merge(parseOrientationPayload(online))
	}
	return db
}

func parseOrientationPayload(raw []byte) orientationPayload {
	var payload orientationPayload
	if err := json.Unmarshal(raw, &payload); err != nil || payload.Version != 1 || payload.Series == nil {
		return orientationPayload{Version: 1, Series: map[string]orientationRecord{}}
	}
	return payload
}

func (db *orientationDatabase) merge(payload orientationPayload) int {
	if payload.Series == nil {
		return 0
	}
	db.mu.Lock()
	defer db.mu.Unlock()
	merged := 0
	for id, record := range payload.Series {
		if id == "" || (record.O != "v" && record.O != "h") {
			continue
		}
		if existing, ok := db.records[id]; ok && existing.O == record.O {
			continue
		}
		db.records[id] = record
		merged++
	}
	if payload.Updated != "" && payload.Updated > db.updated {
		db.updated = payload.Updated
	}
	return merged
}

func (db *orientationDatabase) snapshot() orientationPayload {
	db.mu.Lock()
	defer db.mu.Unlock()
	series := make(map[string]orientationRecord, len(db.records))
	for id, record := range db.records {
		series[id] = record
	}
	return orientationPayload{Version: 1, Updated: db.updated, Series: series}
}

// refresh pulls the latest committed snapshot from the public release
// channel. Failures are silent: the embedded copy keeps the feature working.
func (db *orientationDatabase) refresh() {
	db.mu.Lock()
	if db.pulling || time.Since(db.lastPull) < orientationRefreshInterval {
		db.mu.Unlock()
		return
	}
	db.pulling = true
	db.lastPull = time.Now()
	db.mu.Unlock()

	client := &http.Client{Timeout: orientationRequestTimeout}
	var latest []byte
	for _, source := range updateSourceURLs("https://raw.githubusercontent.com/" + orientationRepository + orientationSourcePaths[0]) {
		ctx, cancel := context.WithTimeout(context.Background(), orientationRequestTimeout)
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, source, nil)
		if err != nil {
			cancel()
			continue
		}
		request.Header.Set("Cache-Control", "no-cache")
		response, err := client.Do(request)
		if err != nil {
			cancel()
			continue
		}
		body, readErr := io.ReadAll(io.LimitReader(response.Body, 32<<20))
		response.Body.Close()
		cancel()
		if readErr == nil && response.StatusCode == http.StatusOK {
			latest = body
			break
		}
	}
	if len(latest) == 0 {
		db.mu.Lock()
		db.pulling = false
		db.mu.Unlock()
		return
	}
	payload := parseOrientationPayload(latest)
	if merged := db.merge(payload); merged > 0 && db.dir != "" {
		if body, err := json.Marshal(payload); err == nil {
			if err := os.WriteFile(filepath.Join(db.dir, "series-orientation.json"), body, 0600); err != nil {
				log.Printf("persist orientation refresh: %v", err)
			}
		}
	}
	db.mu.Lock()
	db.pulling = false
	db.mu.Unlock()
}

func (db *orientationDatabase) startRefreshLoop(ctx context.Context) {
	go func() {
		db.refresh()
		ticker := time.NewTicker(orientationRefreshInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				db.refresh()
			}
		}
	}()
}

func (s *server) orientation(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if s.orientationDB == nil {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "data": orientationPayload{Version: 1, Series: map[string]orientationRecord{}}})
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "data": s.orientationDB.snapshot()})
}

// --- Crawler ---

type orientationCrawlOptions struct {
	OutPath     string
	MaxNew      int
	MaxPages    int
	Concurrency int
	Timeout     time.Duration
}

// crawlOrientations enumerates the public category shelves, resolves every
// unknown drama's first episode through the cheap Range path (an MP4 header
// fetch, not a download) and records its dimensions. Fully automatic: the
// scheduled workflow runs it against the official endpoints and commits the
// merged database.
func crawlOrientations(ctx context.Context, client *hongguoClient, options orientationCrawlOptions) (orientationPayload, error) {
	result := orientationPayload{Version: 1, Series: map[string]orientationRecord{}}
	if body, err := os.ReadFile(options.OutPath); err == nil {
		result = parseOrientationPayload(body)
	} else {
		result = parseOrientationPayload(orientationSnapshot)
	}
	if options.MaxNew <= 0 {
		options.MaxNew = 400
	}
	if options.MaxPages <= 0 {
		options.MaxPages = 60
	}
	// The upstream answers request bursts with empty 200 bodies — the same
	// behavior that makes bursty TV playback stall. Two workers with a
	// per-item pause keep the crawl under that threshold.
	if options.Concurrency <= 0 {
		options.Concurrency = 2
	}
	if options.Timeout <= 0 {
		options.Timeout = 60 * time.Second
	}

	// A mix of shelves keeps the pool growing from both the hot lists and
	// the newest arrivals instead of re-walking the same head every day.
	shelves := []map[string]string{
		{"sort": "hot_score", "genre": "ai_series"},
		{"sort": "online_time", "genre": "ai_series"},
		{"sort": "hot_score", "genre": "comic_series"},
		{"sort": "hot_score"},
		{"sort": "hot_score", "online_time": "days_7"},
	}
	type candidate struct{ id, title string }
	var pending []candidate
	seen := map[string]bool{}
	for id := range result.Series {
		seen[id] = true
	}
	offset := 0
	for page := 0; page < options.MaxPages && len(pending) < options.MaxNew; page++ {
		shelf := shelves[page%len(shelves)]
		request := CategoryRequest{Offset: offset, Filters: map[string]string{"sort": shelf["sort"]}, Shown: nil}
		if value := shelf["online_time"]; value != "" {
			request.Filters["online_time"] = value
		}
		pageCtx, cancel := context.WithTimeout(ctx, 45*time.Second)
		list, err := client.Category(pageCtx, request)
		cancel()
		if err != nil {
			return result, fmt.Errorf("枚举分类失败（offset %d）: %w", offset, err)
		}
		added := 0
		for _, drama := range list.Items {
			if drama.SeriesID == "" || seen[drama.SeriesID] {
				continue
			}
			seen[drama.SeriesID] = true
			pending = append(pending, candidate{id: drama.SeriesID, title: drama.Title})
			added++
			if len(pending) >= options.MaxNew {
				break
			}
		}
		log.Printf("[orientation-crawl] page %d (%v): %d 条，新增待测 %d", page, shelf, len(list.Items), added)
		if !list.HasMore || len(list.Items) == 0 {
			break
		}
		offset = list.NextOffset
		if offset <= 0 {
			break
		}
	}

	if len(pending) == 0 {
		log.Printf("[orientation-crawl] 没有需要测量的新短剧（库内 %d 条）", len(result.Series))
		return result, nil
	}

	work := make(chan candidate)
	var workMu sync.Mutex
	const maxThrottleStrikes = 8
	consecutiveThrottled := 0
	throttleAborted := false
	workers := options.Concurrency
	if workers > len(pending) {
		workers = len(pending)
	}
	var wg sync.WaitGroup
	measured := 0
	failed := 0
	measure := func(item candidate) (orientationRecord, error) {
		record, err := measureOrientation(ctx, client, item.id, options.Timeout)
		if err == nil {
			return record, nil
		}
		// One paced retry: most failures are the empty-body burst guard, not
		// a dead drama. A short backoff is usually enough for this IP.
		select {
		case <-ctx.Done():
			return record, err
		case <-time.After(4 * time.Second):
		}
		return measureOrientation(ctx, client, item.id, options.Timeout)
	}
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for item := range work {
				if ctx.Err() != nil {
					continue
				}
				workMu.Lock()
				aborted := throttleAborted
				strikes := consecutiveThrottled
				workMu.Unlock()
				if aborted {
					continue
				}
				// Pace each item and watch for a sustained throttle: the
				// upstream answers bursts with empty 200 bodies, and hammering
				// a burned IP only makes it worse. Give up for this run after
				// repeated empty responses — the next scheduled run continues
				// where the committed file left off.
				if strikes >= maxThrottleStrikes {
					workMu.Lock()
					throttleAborted = true
					workMu.Unlock()
					continue
				}
				select {
				case <-ctx.Done():
				case <-time.After(time.Duration(800+rand.Intn(1200)) * time.Millisecond):
				}
				record, err := measure(item)
				workMu.Lock()
				if err != nil {
					failed++
					message := err.Error()
					if strings.Contains(message, "response is empty") || strings.Contains(message, "empty after") {
						consecutiveThrottled++
						if consecutiveThrottled == maxThrottleStrikes {
							log.Printf("[orientation-crawl] 连续 %d 次空响应，疑似上游限流，本次运行提前收尾", consecutiveThrottled)
						}
					} else {
						consecutiveThrottled = 0
					}
					log.Printf("[orientation-crawl] %s 失败: %v", item.id, err)
				} else {
					result.Series[item.id] = record
					measured++
					consecutiveThrottled = 0
				}
				workMu.Unlock()
			}
		}()
	}
	for _, item := range pending {
		workMu.Lock()
		aborted := throttleAborted
		workMu.Unlock()
		if aborted {
			break
		}
		select {
		case <-ctx.Done():
		case work <- item:
		}
		if ctx.Err() != nil {
			break
		}
	}
	close(work)
	wg.Wait()
	now := time.Now().UTC()
	result.Updated = now.Format("2006-01-02T15:04:05Z")
	log.Printf("[orientation-crawl] 完成：新测 %d，失败 %d，库内共 %d 条", measured, failed, len(result.Series))
	return result, nil
}

// measureOrientation resolves the first episode twice through the Range path
// only: the moov fetch reports the track dimensions without downloading the
// episode. allowStandardFallback=false keeps a broken Range contract from
// silently pulling the whole file.
func measureOrientation(ctx context.Context, client *hongguoClient, seriesID string, timeout time.Duration) (orientationRecord, error) {
	itemCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	detail, err := client.SeriesDetail(itemCtx, seriesID)
	if err != nil {
		return orientationRecord{}, fmt.Errorf("详情: %w", err)
	}
	if len(detail.VidList) == 0 {
		if detail.Vid == "" {
			return orientationRecord{}, errors.New("没有第一集视频")
		}
		detail.VidList = []string{detail.Vid}
	}
	info, err := client.PlayInfoRangeQuality(itemCtx, seriesID, detail.VidList[0], "", false, false)
	if err != nil {
		return orientationRecord{}, fmt.Errorf("播放准备: %w", err)
	}
	if info.VideoWidth <= 0 || info.VideoHeight <= 0 {
		return orientationRecord{}, errors.New("响应没有视频尺寸")
	}
	orientation := "h"
	if info.VideoHeight > info.VideoWidth {
		orientation = "v"
	}
	return orientationRecord{O: orientation, W: info.VideoWidth, H: info.VideoHeight, Updated: time.Now().UnixMilli()}, nil
}

// updateSourceURLs mirrors the APK updater's ordered proxy list for raw
// content: four public GitHub mirrors first, then GitHub itself.
func updateSourceURLs(origin string) []string {
	proxies := []string{
		"https://gh-proxy.com/",
		"https://v4.gh-proxy.org/",
		"https://gh-proxy.org/",
		"https://ghfast.top/",
	}
	result := make([]string, 0, len(proxies)+1)
	for _, proxy := range proxies {
		result = append(result, proxy+origin)
	}
	result = append(result, origin)
	return result
}

// runOrientationCrawl is the -crawl-orientation entry point used by the
// scheduled workflow: measure new dramas, merge them into the committed
// database file and exit. One run touches only anonymous endpoints.
func runOrientationCrawl(outPath string, maxNew, maxPages int) error {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Hour)
	defer cancel()
	if err := ensureMiniReelRoot(); err != nil {
		log.Printf("prepare media cache: %v", err)
	}
	client := newHongguoClient()
	payload, err := crawlOrientations(ctx, client, orientationCrawlOptions{
		OutPath:  outPath,
		MaxNew:   maxNew,
		MaxPages: maxPages,
	})
	if err != nil {
		return err
	}
	body, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		return err
	}
	body = append(body, '\n')
	if err := os.MkdirAll(filepath.Dir(outPath), 0o755); err != nil {
		return err
	}
	return os.WriteFile(outPath, body, 0o644)
}
