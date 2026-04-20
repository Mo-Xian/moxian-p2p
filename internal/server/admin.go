package server

import (
	"crypto/subtle"
	_ "embed"
	"encoding/json"
	"net/http"
)

//go:embed admin.html
var adminHTML []byte

// AdminPanel 提供 /admin 页面 + /api/nodes JSON
type AdminPanel struct {
	Hub   *Hub
	Relay *UDPServer
	User  string
	Pass  string
}

// Register 注册路由
func (a *AdminPanel) Register(mux *http.ServeMux) {
	mux.HandleFunc("/admin", a.auth(a.handleIndex))
	mux.HandleFunc("/admin/", a.auth(a.handleIndex))
	mux.HandleFunc("/api/nodes", a.auth(a.handleNodes))
	mux.HandleFunc("/api/stats", a.auth(a.handleStats))
}

func (a *AdminPanel) handleStats(w http.ResponseWriter, r *http.Request) {
	b, p, s := int64(0), int64(0), int64(0)
	if a.Relay != nil {
		b, p, s = a.Relay.RelayStats()
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"relay_bytes": b,
		"relay_pkts":  p,
		"stun_reqs":   s,
		"nodes":       a.Hub.Count(),
	})
}

func (a *AdminPanel) auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		if !ok ||
			subtle.ConstantTimeCompare([]byte(user), []byte(a.User)) != 1 ||
			subtle.ConstantTimeCompare([]byte(pass), []byte(a.Pass)) != 1 {
			w.Header().Set("WWW-Authenticate", `Basic realm="moxian-p2p"`)
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		h(w, r)
	}
}

func (a *AdminPanel) handleIndex(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write(adminHTML)
}

// NodeView 面板展示用节点摘要
type NodeView struct {
	NodeID      string   `json:"node_id"`
	PublicAddr  string   `json:"public_addr"`
	Public2     string   `json:"public2"`
	NatType     string   `json:"nat_type"`
	VirtualIP   string   `json:"virtual_ip"`
	LocalAddrs  []string `json:"local_addrs"`
	Tags        []string `json:"tags,omitempty"`
	Description string   `json:"description,omitempty"`
	OnlineSince int64    `json:"online_since"`
}

func (a *AdminPanel) handleNodes(w http.ResponseWriter, r *http.Request) {
	snap := a.Hub.Snapshot()
	out := make([]NodeView, 0, len(snap))
	for _, s := range snap {
		out = append(out, NodeView{
			NodeID:      s.NodeID,
			PublicAddr:  s.PublicAddr,
			Public2:     s.PublicAddr2,
			NatType:     s.NatType,
			VirtualIP:   s.VirtualIP,
			LocalAddrs:  s.LocalAddrs,
			Tags:        s.Tags,
			Description: s.Description,
			OnlineSince: s.OnlineSince,
		})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"count": len(out),
		"nodes": out,
	})
}
