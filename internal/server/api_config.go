package server

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strings"
)

// ConfigAPI 给客户端下发 moxian-p2p 配置（node_id / pass / peers）
// 也提供节点注册/更新
type ConfigAPI struct {
	DB        *sql.DB
	JWT       *JWTManager
	Hub       *Hub   // 查节点在线状态
	ServerWS  string // wss://... 公开的 ws 端点
	ServerUDP string // host:port 公开的 udp 端点
}

func (c *ConfigAPI) Register(mux *http.ServeMux) {
	mux.HandleFunc("/api/config", c.JWT.AuthMiddleware(c.handleConfig))
	mux.HandleFunc("/api/nodes", c.JWT.AuthMiddleware(c.handleNodes))
}

// GET /api/config?node=phone
//   → {node_id, virtual_ip, pass, server_ws, server_udp, allow_peers, tags}
// 若未提供 node 参数 返回所有节点列表
func (c *ConfigAPI) handleConfig(w http.ResponseWriter, r *http.Request) {
	claims := ClaimsFromCtx(r.Context())
	if claims == nil {
		writeErr(w, 401, "no claims")
		return
	}
	if r.Method != "GET" {
		writeErr(w, 405, "method not allowed")
		return
	}

	nodeParam := r.URL.Query().Get("node")
	if nodeParam == "" {
		// 列表模式
		nodes, err := ListNodesByUser(c.DB, claims.UserID)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		out := make([]map[string]any, 0, len(nodes))
		for _, n := range nodes {
			out = append(out, map[string]any{
				"node_id":    n.NodeID,
				"virtual_ip": n.VirtualIP,
				"tags":       n.Tags,
			})
		}
		writeJSON(w, 200, map[string]any{"nodes": out})
		return
	}

	// 具体节点 config
	nodes, err := ListNodesByUser(c.DB, claims.UserID)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	var self *Node
	peers := make([]string, 0)
	for i, n := range nodes {
		if n.NodeID == nodeParam {
			self = &nodes[i]
		} else {
			peers = append(peers, n.NodeID)
		}
	}
	if self == nil {
		writeErr(w, 404, "node not found; 先 POST /api/nodes 注册此设备")
		return
	}

	pass, err := GetMeshPassphrase(c.DB, claims.UserID)
	if err != nil {
		writeErr(w, 500, "get mesh key: "+err.Error())
		return
	}

	writeJSON(w, 200, map[string]any{
		"node_id":     self.NodeID,
		"virtual_ip":  self.VirtualIP,
		"pass":        pass,
		"server_ws":   c.ServerWS,
		"server_udp":  c.ServerUDP,
		"allow_peers": peers,
		"tags":        self.Tags,
		"mesh":        true,
	})
}

// POST /api/nodes {node_id, tags, description}
// GET  /api/nodes → 所有节点
func (c *ConfigAPI) handleNodes(w http.ResponseWriter, r *http.Request) {
	claims := ClaimsFromCtx(r.Context())
	if claims == nil {
		writeErr(w, 401, "no claims")
		return
	}

	switch r.Method {
	case "GET":
		nodes, err := ListNodesByUser(c.DB, claims.UserID)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		// 标记在线状态（信令 WS 连接中）
		online := map[string]int64{}
		if c.Hub != nil {
			for _, s := range c.Hub.Snapshot() {
				online[s.NodeID] = s.OnlineSince
			}
		}
		out := make([]map[string]any, 0, len(nodes))
		for _, n := range nodes {
			m := map[string]any{
				"node_id":     n.NodeID,
				"virtual_ip":  n.VirtualIP,
				"tags":        n.Tags,
				"description": n.Description,
				"online":      false,
			}
			if t, ok := online[n.NodeID]; ok {
				m["online"] = true
				m["online_since"] = t
			}
			out = append(out, m)
		}
		writeJSON(w, 200, map[string]any{"nodes": out})

	case "POST":
		var body struct {
			NodeID      string   `json:"node_id"`
			Tags        []string `json:"tags"`
			Description string   `json:"description"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeErr(w, 400, "bad json")
			return
		}
		body.NodeID = strings.TrimSpace(body.NodeID)
		if body.NodeID == "" {
			writeErr(w, 400, "node_id 必填")
			return
		}
		n, err := RegisterNode(c.DB, claims.UserID, body.NodeID, body.Tags, body.Description)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		writeJSON(w, 201, map[string]any{
			"node_id":    n.NodeID,
			"virtual_ip": n.VirtualIP,
		})

	case "DELETE":
		nodeParam := r.URL.Query().Get("node")
		if nodeParam == "" {
			writeErr(w, 400, "node 必填")
			return
		}
		if err := DeleteNode(c.DB, claims.UserID, nodeParam); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true})

	default:
		writeErr(w, 405, "method not allowed")
	}
}
