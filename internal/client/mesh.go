package client

import (
	"context"
	"log"
	"sync"
	"time"

	"github.com/cp12064/moxian-p2p/internal/protocol"
)

// meshManager 根据节点列表自动维护到所有 peer 的 smux 会话
type meshManager struct {
	c          *Client
	mu         sync.Mutex
	active     map[string]struct{} // 正在维护的 peerID
	onlineSince map[string]int64   // peerID -> 上次看到的 OnlineSince 用于检测对端重启
}

func newMeshManager(c *Client) *meshManager {
	return &meshManager{c: c, active: make(map[string]struct{}), onlineSince: make(map[string]int64)}
}

// onNetworkUpdate 收到路由表后调用 拓扑变更触发自动连接
func (m *meshManager) onNetworkUpdate(nodes []protocol.NetworkNode) {
	self := m.c.cfg.NodeID
	for _, n := range nodes {
		if n.NodeID == self {
			continue
		}
		// 只向 id 大于自己的节点发起 避免双向同时 dial
		if n.NodeID <= self {
			continue
		}
		m.mu.Lock()
		prev := m.onlineSince[n.NodeID]
		m.onlineSince[n.NodeID] = n.OnlineSince
		_, active := m.active[n.NodeID]
		m.mu.Unlock()

		// 对端重启（OnlineSince 变化）：立刻关掉旧 session 让 maintain 重新 dial
		if active && prev != 0 && n.OnlineSince != 0 && prev != n.OnlineSince {
			log.Printf("[mesh] %s re-registered (online_since %d→%d), force reconnect",
				n.NodeID, prev, n.OnlineSince)
			m.c.pool.Remove(n.NodeID)
			continue
		}

		if active {
			continue
		}
		m.mu.Lock()
		m.active[n.NodeID] = struct{}{}
		m.mu.Unlock()
		go m.maintain(n.NodeID)
	}
}

// maintain 保持与 peerID 的 smux 会话可用 断开后 5~30s 退避重试
func (m *meshManager) maintain(peerID string) {
	ctx := context.Background()
	backoff := 5 * time.Second
	for {
		lk, err := m.c.pool.GetOrDial(ctx, peerID)
		if err != nil {
			log.Printf("[mesh] dial %s: %v (retry in %v)", peerID, err, backoff)
			time.Sleep(backoff)
			if backoff < 60*time.Second {
				backoff *= 2
			}
			continue
		}
		log.Printf("[mesh] connected to %s", peerID)
		backoff = 5 * time.Second
		// 轮询直到会话关闭
		for !lk.sess.IsClosed() {
			time.Sleep(5 * time.Second)
		}
		log.Printf("[mesh] disconnected from %s, reconnect in %v", peerID, backoff)
		m.c.pool.Remove(peerID)
		time.Sleep(backoff)
	}
}
