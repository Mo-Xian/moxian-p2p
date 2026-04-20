package protocol

import (
	"encoding/json"
	"fmt"
)

// 信令消息类型
const (
	TypeRegister    = "register"     // client -> server 注册
	TypeRegisterAck = "register_ack" // server -> client 注册回执（含自己的公网地址）
	TypeConnect     = "connect"      // client -> server 请求连接某 peer
	TypePeerInfo    = "peer_info"    // server -> 双方 下发对端地址信息 触发同时打洞
	TypeRelayOpen   = "relay_open"   // client -> server 打洞失败 请求开启中继会话
	TypeRelayReady  = "relay_ready"  // server -> client 中继通道已就绪 (含 relay 端点 + session)
	TypePing        = "ping"
	TypePong        = "pong"
	TypeError       = "error"
	TypeNatUpdate   = "nat_update" // client -> server 补充 NAT 探测结果
)

// Envelope 信令信封
type Envelope struct {
	Type    string          `json:"type"`
	ReqID   string          `json:"req_id,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

// Register 注册请求
type Register struct {
	NodeID  string `json:"node_id"`
	Token   string `json:"token,omitempty"`
	Version string `json:"version,omitempty"`
	// 客户端本地 UDP 端口 用于打洞
	LocalUDPPort int      `json:"local_udp_port"`
	LocalAddrs   []string `json:"local_addrs,omitempty"` // 内网候选地址（ip:port）
	// NAT 探测结果
	NatType     string `json:"nat_type,omitempty"`     // "cone" / "symmetric" / "unknown"
	PublicAddr2 string `json:"public_addr2,omitempty"` // 第二个 STUN 端口观察到的公网地址
	// 虚拟网络
	VirtualIP string `json:"virtual_ip,omitempty"` // 10.88.0.X
	// 节点发现元数据
	Tags        []string `json:"tags,omitempty"`
	Description string   `json:"description,omitempty"`
	// ACL：允许互连的 peer 条件列表（node_id 或 "tag=value"）空=无限制
	AllowPeers []string `json:"allow_peers,omitempty"`
}

// RegisterAck 注册回执
type RegisterAck struct {
	NodeID     string   `json:"node_id"`
	PublicAddr string   `json:"public_addr"` // 服务器看到的该客户端公网 UDP 地址
	ServerTime int64    `json:"server_time"`
	RelayUDP   string   `json:"relay_udp"`   // 中继 UDP 端点（host:port）
	Stun2UDP   string   `json:"stun2_udp"`   // 第二个 STUN 端点（用于 NAT 类型检测）
	StunExtras []string `json:"stun_extras"` // 额外 STUN 端点（非线性预测用）
}

// NatUpdate NAT 补充信息（register 后 客户端做多点 STUN 后上报）
type NatUpdate struct {
	NatType     string   `json:"nat_type"`     // cone/symmetric
	PublicAddr2 string   `json:"public_addr2"` // 第二次 STUN 结果
	Samples     []string `json:"samples"`      // 所有 STUN 端点观察到的 ip:port
}

// Connect 发起连接
type Connect struct {
	PeerID  string `json:"peer_id"`
	Purpose string `json:"purpose,omitempty"` // 如 "tcp-forward"
}

// PeerInfo 下发给双方的对端信息
type PeerInfo struct {
	PeerID     string   `json:"peer_id"`
	PublicAddr string   `json:"public_addr"`
	LocalAddrs []string `json:"local_addrs,omitempty"`
	SessionID  string   `json:"session_id"`
	Role       string   `json:"role"` // "initiator" or "responder"
	// NAT 信息（用于打洞策略）
	NatType     string   `json:"nat_type,omitempty"`
	PublicAddr2 string   `json:"public_addr2,omitempty"` // 对端从另一 STUN 端口观察到的地址
	NatSamples  []string `json:"nat_samples,omitempty"`  // 对端所有 STUN 观察地址（用于端口预测）
}

// 虚拟网络相关
const (
	TypeNetworkUpdate = "network_update" // server -> all 节点变化广播
)

// NetworkNode 节点摘要（用于路由表 + 发现）
type NetworkNode struct {
	NodeID      string   `json:"node_id"`
	VirtualIP   string   `json:"virtual_ip,omitempty"`
	NatType     string   `json:"nat_type,omitempty"`
	Tags        []string `json:"tags,omitempty"`
	Description string   `json:"description,omitempty"`
	OnlineSince int64    `json:"online_since,omitempty"` // unix ts
}

// NetworkUpdate 全量路由表
type NetworkUpdate struct {
	Nodes []NetworkNode `json:"nodes"`
}

// RelayOpen 开启中继
type RelayOpen struct {
	PeerID    string `json:"peer_id"`
	SessionID string `json:"session_id"`
}

// RelayReady 中继就绪
type RelayReady struct {
	RelayAddr string `json:"relay_addr"` // host:port UDP
	SessionID string `json:"session_id"`
	Token     string `json:"token"` // 客户端需在首个 UDP 包携带此 token 绑定会话
}

// ErrorMsg 错误
type ErrorMsg struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Pack 打包信封
func Pack(typ, reqID string, v any) ([]byte, error) {
	raw, err := json.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("marshal payload: %w", err)
	}
	return json.Marshal(Envelope{Type: typ, ReqID: reqID, Payload: raw})
}

// Unpack 解析到目标结构
func Unpack(raw []byte, target any) (*Envelope, error) {
	var env Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, err
	}
	if target != nil && len(env.Payload) > 0 {
		if err := json.Unmarshal(env.Payload, target); err != nil {
			return &env, err
		}
	}
	return &env, nil
}
