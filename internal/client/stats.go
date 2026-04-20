package client

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/cp12064/moxian-p2p/internal/nat"
)

// channelStats 活跃 channel 的统计注册
type channelStats struct {
	mu       sync.RWMutex
	channels map[string]*nat.Channel // sessionID -> Channel
	labels   map[string]string       // sessionID -> peerID
}

func newChannelStats() *channelStats {
	return &channelStats{
		channels: make(map[string]*nat.Channel),
		labels:   make(map[string]string),
	}
}

func (s *channelStats) Add(ch *nat.Channel, peerID string) {
	s.mu.Lock()
	s.channels[ch.SessionID()] = ch
	s.labels[ch.SessionID()] = peerID
	s.mu.Unlock()
}

func (s *channelStats) Remove(sessionID string) {
	s.mu.Lock()
	delete(s.channels, sessionID)
	delete(s.labels, sessionID)
	s.mu.Unlock()
}

// Snapshot 返回所有 session 统计
type SessionStat struct {
	SessionID string `json:"session_id"`
	PeerID    string `json:"peer_id"`
	Mode      string `json:"mode"` // direct / relay / unset
	RxBytes   int64  `json:"rx_bytes"`
	TxBytes   int64  `json:"tx_bytes"`
	RxPkts    int64  `json:"rx_pkts"`
	TxPkts    int64  `json:"tx_pkts"`
}

func modeName(m int32) string {
	switch m {
	case 1:
		return "direct"
	case 2:
		return "relay"
	default:
		return "unset"
	}
}

func (s *channelStats) Snapshot() []SessionStat {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]SessionStat, 0, len(s.channels))
	for sid, ch := range s.channels {
		st := ch.Stats()
		out = append(out, SessionStat{
			SessionID: sid,
			PeerID:    s.labels[sid],
			Mode:      modeName(st.Mode),
			RxBytes:   st.RxBytes, TxBytes: st.TxBytes,
			RxPkts: st.RxPkts, TxPkts: st.TxPkts,
		})
	}
	return out
}

// startStatsServer 启动本地 HTTP 统计接口 + Prometheus /metrics
func (c *Client) startStatsServer(addr string) {
	mux := http.NewServeMux()
	mux.HandleFunc("/stats", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"node_id":  c.cfg.NodeID,
			"sessions": c.stats.Snapshot(),
		})
	})
	c.registerClientMetrics(mux)
	go func() {
		log.Printf("[stats] local HTTP listen %s (GET /stats /metrics)", addr)
		if err := http.ListenAndServe(addr, mux); err != nil {
			log.Printf("[stats] http: %v", err)
		}
	}()
}

// startStatsLogger 定期打印统计
func (c *Client) startStatsLogger(ctx context.Context, interval time.Duration) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			snap := c.stats.Snapshot()
			if len(snap) == 0 {
				continue
			}
			for _, s := range snap {
				log.Printf("[stats] %s peer=%s mode=%s tx=%s rx=%s pkts=%d/%d",
					shortSID(s.SessionID), s.PeerID, s.Mode,
					humanBytes(s.TxBytes), humanBytes(s.RxBytes),
					s.TxPkts, s.RxPkts)
			}
		}
	}
}

func shortSID(s string) string {
	if len(s) > 8 {
		return s[:8]
	}
	return s
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmtBytes(n, "B")
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	units := "KMGT"
	return fmtBytes(n/div, string(units[exp])+"iB")
}

func fmtBytes(n int64, unit string) string {
	return formatInt(n) + unit
}

func formatInt(n int64) string {
	// 简单保留整数
	if n == 0 {
		return "0"
	}
	buf := [24]byte{}
	i := len(buf)
	neg := n < 0
	if neg {
		n = -n
	}
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
