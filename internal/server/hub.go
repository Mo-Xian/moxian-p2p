package server

import (
	"sync"

	"github.com/gorilla/websocket"
)

// Session 一个已注册的节点会话
type Session struct {
	NodeID      string
	Conn        *websocket.Conn
	PublicAddr  string   // 公网 UDP 地址（客户端上报）
	LocalAddrs  []string // 内网候选
	NatType     string   // cone/symmetric/unknown
	PublicAddr2 string   // 第二 STUN 端口观察地址
	NatSamples  []string // 所有 STUN 端点观察样本
	VirtualIP   string   // 虚拟 IP（TUN 模式）
	Tags        []string // 标签
	Description string   // 描述
	AllowPeers  []string // ACL 规则 空=无限制
	OnlineSince int64    // 上线时间（unix）
	writeMu     sync.Mutex
}

func (s *Session) Send(data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.Conn.WriteMessage(websocket.TextMessage, data)
}

// Hub 节点注册表
type Hub struct {
	mu       sync.RWMutex
	sessions map[string]*Session
}

func NewHub() *Hub {
	return &Hub{sessions: make(map[string]*Session)}
}

func (h *Hub) Put(s *Session) (old *Session) {
	h.mu.Lock()
	defer h.mu.Unlock()
	old = h.sessions[s.NodeID]
	h.sessions[s.NodeID] = s
	return old
}

func (h *Hub) Remove(nodeID string, expect *Session) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if cur, ok := h.sessions[nodeID]; ok && cur == expect {
		delete(h.sessions, nodeID)
	}
}

func (h *Hub) Get(nodeID string) *Session {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return h.sessions[nodeID]
}

func (h *Hub) Count() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.sessions)
}

// Snapshot 返回所有会话快照（用于面板/广播）
func (h *Hub) Snapshot() []*Session {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]*Session, 0, len(h.sessions))
	for _, s := range h.sessions {
		out = append(out, s)
	}
	return out
}

// Broadcast 向所有会话广播
func (h *Hub) Broadcast(data []byte) {
	snap := h.Snapshot()
	for _, s := range snap {
		_ = s.Send(data)
	}
}
