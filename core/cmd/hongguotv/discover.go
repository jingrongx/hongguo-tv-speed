package main

import (
	"context"
	"math/rand"
	"net/url"
	"sync"
	"time"
)

// The discovery feed replaces the official personal-recommendation stream
// with a locally shuffled pool built from the public category shelves.
// Browsing then draws from the whole library instead of the official feed's
// slowly-changing head, and everything in the viewer's history is excluded,
// so previously watched dramas stop resurfacing. The shuffle is per session:
// every cold start sees a different order.

const (
	discoverSessionTTL   = 2 * time.Hour
	discoverPageSize     = 15
	discoverRefillTarget = 45
	discoverLowWater     = 10
	// Bounds the upstream shelf walks inside a single serve request: the
	// landscape filter can leave a page with no keeper at all, and without a
	// cap the loop would keep pulling pages until the shelves ran dry within
	// one HTTP request.
	discoverServeRefillCap = 8
)

type discoverSession struct {
	mu       sync.Mutex
	created  time.Time
	accessed time.Time

	pool              []Drama
	served            map[string]bool
	categoryOffset    int
	categorySessionID string
	categoryExhausted bool
	refills           int
	aiIndex           int
	aiExhausted       bool
	landscapeOnly     bool
}

type discoverFeed struct {
	mu       sync.Mutex
	sessions map[string]*discoverSession
}

func newDiscoverFeed() *discoverFeed {
	return &discoverFeed{sessions: map[string]*discoverSession{}}
}

func (f *discoverFeed) session(id string) *discoverSession {
	f.mu.Lock()
	defer f.mu.Unlock()
	now := time.Now()
	for key, session := range f.sessions {
		if now.Sub(session.accessed) > discoverSessionTTL {
			delete(f.sessions, key)
		}
	}
	if session, ok := f.sessions[id]; ok {
		session.accessed = now
		return session
	}
	session := &discoverSession{created: now, accessed: now, served: map[string]bool{}}
	f.sessions[id] = session
	return session
}

// The discovery pool is AI-first: AI dramas are overwhelmingly landscape,
// which is what the TV surface wants, and the viewer preference is to skip
// live-action shorts entirely. The general shelves only top the pool up
// after every AI shelf has been walked to its end.
var discoverAIShelves = []map[string]string{
	{"sort": "hot_score", "genre": "ai_series"},
	{"sort": "online_time", "genre": "ai_series"},
	{"sort": "hot_collect", "genre": "ai_series"},
}
var discoverFallbackShelves = []map[string]string{
	{"sort": "hot_score", "genre": "comic_series"},
	{"sort": "hot_score"},
	{"sort": "online_time"},
}

// discoverExclusions lists every series the viewer already has a record for
// (history, favorites, likes — local or cloud synced). Watched dramas never
// come back through the discovery pool.
func (s *server) discoverExclusions() map[string]bool {
	excluded := map[string]bool{}
	var data LibraryData
	if s.accounts != nil {
		data = s.accounts.librarySnapshot()
	} else if s.libraryStore != nil {
		data = s.libraryStore.snapshot()
	}
	for _, entry := range data.Entries {
		if entry.Drama.SeriesID != "" {
			excluded[entry.Drama.SeriesID] = true
		}
	}
	return excluded
}

func (sess *discoverSession) currentShelf() map[string]string {
	if !sess.aiExhausted {
		return discoverAIShelves[sess.aiIndex%len(discoverAIShelves)]
	}
	return discoverFallbackShelves[sess.refills%len(discoverFallbackShelves)]
}

func (sess *discoverSession) refill(ctx context.Context, client *hongguoClient, db *orientationDatabase) {
	if sess.categoryExhausted {
		return
	}
	shelf := sess.currentShelf()
	request := CategoryRequest{
		Offset:    sess.categoryOffset,
		SessionID: sess.categorySessionID,
		Filters:   map[string]string{"sort": shelf["sort"]},
	}
	if value := shelf["online_time"]; value != "" {
		request.Filters["online_time"] = value
	}
	if value := shelf["genre"]; value != "" {
		request.Filters["genre"] = value
	}
	list, err := client.Category(ctx, request)
	sess.refills++
	if err != nil {
		// A failed shelf is not fatal: serve what the pool already has.
		return
	}
	fresh := make([]Drama, 0, len(list.Items))
	for _, drama := range list.Items {
		if drama.SeriesID == "" || drama.EpisodeCount < 1 {
			continue
		}
		fresh = append(fresh, drama)
	}
	rand.Shuffle(len(fresh), func(i, j int) { fresh[i], fresh[j] = fresh[j], fresh[i] })
	if db != nil && sess.landscapeOnly {
		// The landscape preference shapes the pool server-side. The AI
		// shelves are the landscape home, so only dramas measured as vertical
		// are dropped there; the general shelves are presumed vertical, and
		// only dramas the shared database has measured as landscape may pass —
		// unknowns would hand the probing cost straight back to the skip
		// path, which is what made switching dramas feel endless.
		kept := fresh[:0]
		for _, drama := range fresh {
			verdict := db.verdict(drama.SeriesID)
			if sess.aiExhausted {
				if verdict != "h" {
					continue
				}
			} else if verdict == "v" {
				continue
			}
			kept = append(kept, drama)
		}
		fresh = kept
	}
	sess.pool = append(sess.pool, fresh...)
	sess.categoryOffset = list.NextOffset
	sess.categorySessionID = list.SessionID
	if !list.HasMore || list.NextOffset <= 0 {
		// This shelf ran dry. Within the AI block rotate to the next AI
		// shelf; once every AI shelf is exhausted, fall back to the general
		// shelves so the pool never truly starves.
		if !sess.aiExhausted {
			sess.aiIndex++
			if sess.aiIndex >= len(discoverAIShelves) {
				sess.aiExhausted = true
				sess.categoryOffset = 0
				sess.categorySessionID = ""
			}
		} else {
			sess.categoryExhausted = true
		}
	}
}

// serve pops up to pageSize unexcluded dramas from the pool, refilling from
// the category shelves as needed.
func (s *server) discoverServe(ctx context.Context, sess *discoverSession, shown []string, limit int) ([]Drama, bool) {
	if limit <= 0 {
		limit = discoverPageSize
	}
	client := s.hongguo
	sess.mu.Lock()
	defer sess.mu.Unlock()
	excluded := s.discoverExclusions()
	shownSet := map[string]bool{}
	for _, id := range shown {
		shownSet[id] = true
	}
	items := make([]Drama, 0, limit)
	usedRefills := 0
	for len(items) < limit {
		if len(sess.pool) < discoverLowWater && !sess.categoryExhausted && usedRefills < discoverServeRefillCap {
			sess.refill(ctx, client, s.orientationDB)
			usedRefills++
		}
		if len(sess.pool) == 0 {
			break
		}
		drama := sess.pool[0]
		sess.pool = sess.pool[1:]
		if sess.served[drama.SeriesID] || excluded[drama.SeriesID] || shownSet[drama.SeriesID] {
			continue
		}
		sess.served[drama.SeriesID] = true
		items = append(items, drama)
	}
	remaining := len(sess.pool) >= discoverLowWater || !sess.categoryExhausted
	return items, remaining
}

// discoverFeed answers /api/v1/lists/recommend?shuffle=1 with the same
// pagination contract the official feed uses (items/next_offset/has_more/
// session_id), so the existing pager drives it unchanged.
func (s *server) discoverFeedHandler(ctx context.Context, q url.Values) (ListResult, error) {
	sessionID := q.Get("session_id")
	if sessionID == "" || len(sessionID) > 64 {
		sessionID = randomDiscoverSessionID()
	}
	sess := s.discover.session(sessionID)
	// The landscape-only preference travels with every page request so a
	// settings toggle takes effect without abandoning the session.
	sess.landscapeOnly = q.Get("landscape") == "1"
	shown := splitQueryList(q.Get("shown"))
	items, remaining := s.discoverServe(ctx, sess, shown, discoverPageSize)
	out := ListResult{CategoryResult: CategoryResult{
		Items:      items,
		HasMore:    remaining,
		NextOffset: len(items),
		SessionID:  sessionID,
	}}
	return out, nil
}

func randomDiscoverSessionID() string {
	// Match the official session id shape loosely; only uniqueness matters
	// locally. Hex keeps it URL-safe through the pager cursor.
	const chars = "0123456789abcdef"
	buffer := make([]byte, 16)
	for i := range buffer {
		buffer[i] = chars[rand.Intn(len(chars))]
	}
	return string(buffer)
}
