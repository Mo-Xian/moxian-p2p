package server

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"

	"github.com/cp12064/moxian-p2p/internal/protocol"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin:     func(r *http.Request) bool { return true },
}

// Signaling 信令服务器
type Signaling struct {
	Hub        *Hub
	Relay      *UDPServer // 用于告知客户端中继地址
	PublicHost string     // 中继/STUN 对外 host
	AuthToken  string     // 可选全局 token
	Stun2Port  int        // 第二个 STUN 端口（用于 NAT 类型检测）
	StunExtras []int      // 额外 STUN 端口（非线性端口预测用）
}

// Handle 处理一个 WebSocket 连接
func (s *Signaling) Handle(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("upgrade: %v", err)
		return
	}
	defer conn.Close()

	conn.SetReadLimit(64 * 1024)
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	})

	sess, err := s.handleRegister(conn, r)
	if err != nil {
		log.Printf("register: %v", err)
		_ = writeError(conn, "register_failed", err.Error())
		return
	}
	defer func() {
		s.Hub.Remove(sess.NodeID, sess)
		s.broadcastNetwork()
	}()
	log.Printf("[hub] node %s online from %s (total=%d)", sess.NodeID, sess.PublicAddr, s.Hub.Count())

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	go s.pingLoop(ctx, sess)

	for {
		_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		_, raw, err := conn.ReadMessage()
		if err != nil {
			log.Printf("[hub] node %s disconnect: %v", sess.NodeID, err)
			return
		}
		if err := s.dispatch(sess, raw); err != nil {
			log.Printf("[hub] dispatch %s: %v", sess.NodeID, err)
		}
	}
}

func (s *Signaling) handleRegister(conn *websocket.Conn, r *http.Request) (*Session, error) {
	_ = conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	_, raw, err := conn.ReadMessage()
	if err != nil {
		return nil, err
	}
	var reg protocol.Register
	env, err := protocol.Unpack(raw, &reg)
	if err != nil {
		return nil, err
	}
	if env.Type != protocol.TypeRegister {
		return nil, errString("first message must be register")
	}
	if s.AuthToken != "" && reg.Token != s.AuthToken {
		return nil, errString("invalid token")
	}
	if reg.NodeID == "" {
		return nil, errString("empty node_id")
	}

	// 约定：reg.LocalAddrs[0] = STUN 查到的公网地址，其余为内网候选
	if len(reg.LocalAddrs) == 0 {
		return nil, errString("missing public_addr in local_addrs[0]")
	}
	sess := &Session{
		NodeID:      reg.NodeID,
		Conn:        conn,
		PublicAddr:  reg.LocalAddrs[0],
		LocalAddrs:  append([]string(nil), reg.LocalAddrs[1:]...),
		NatType:     reg.NatType,
		VirtualIP:   reg.VirtualIP,
		Tags:        append([]string(nil), reg.Tags...),
		Description: reg.Description,
		AllowPeers:  append([]string(nil), reg.AllowPeers...),
		OnlineSince: time.Now().Unix(),
	}
	if old := s.Hub.Put(sess); old != nil {
		_ = old.Conn.Close()
	}

	ack := protocol.RegisterAck{
		NodeID:     sess.NodeID,
		PublicAddr: sess.PublicAddr,
		ServerTime: time.Now().Unix(),
		RelayUDP:   s.Relay.PublicEndpoint(s.PublicHost),
		Stun2UDP:   fmt.Sprintf("%s:%d", s.PublicHost, s.Stun2Port),
	}
	for _, p := range s.StunExtras {
		ack.StunExtras = append(ack.StunExtras, fmt.Sprintf("%s:%d", s.PublicHost, p))
	}
	out, _ := protocol.Pack(protocol.TypeRegisterAck, env.ReqID, ack)
	if err := sess.Send(out); err != nil {
		return nil, err
	}
	// 注册完成后广播网络变化（供 TUN 模式同步路由表）
	s.broadcastNetwork()
	return sess, nil
}

// broadcastNetwork 广播全量节点列表（用于 TUN 路由 + 节点发现）
func (s *Signaling) broadcastNetwork() {
	snap := s.Hub.Snapshot()
	nodes := make([]protocol.NetworkNode, 0, len(snap))
	for _, sess := range snap {
		nodes = append(nodes, protocol.NetworkNode{
			NodeID:      sess.NodeID,
			VirtualIP:   sess.VirtualIP,
			NatType:     sess.NatType,
			Tags:        sess.Tags,
			Description: sess.Description,
			OnlineSince: sess.OnlineSince,
		})
	}
	out, _ := protocol.Pack(protocol.TypeNetworkUpdate, "", protocol.NetworkUpdate{Nodes: nodes})
	s.Hub.Broadcast(out)
}

func (s *Signaling) dispatch(sess *Session, raw []byte) error {
	var env protocol.Envelope
	if err := json.Unmarshal(raw, &env); err != nil {
		return err
	}
	switch env.Type {
	case protocol.TypeConnect:
		return s.handleConnect(sess, &env)
	case protocol.TypeRelayOpen:
		return s.handleRelayOpen(sess, &env)
	case protocol.TypeNatUpdate:
		return s.handleNatUpdate(sess, &env)
	case protocol.TypePong:
		return nil
	default:
		return writeError(sess.Conn, "unknown_type", env.Type)
	}
}

func (s *Signaling) handleConnect(initiator *Session, env *protocol.Envelope) error {
	var req protocol.Connect
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		return err
	}
	peer := s.Hub.Get(req.PeerID)
	if peer == nil {
		return writeError(initiator.Conn, "peer_offline", req.PeerID)
	}
	// ACL 双向检查
	if !aclAllows(initiator.AllowPeers, peer) {
		log.Printf("[acl] %s -> %s denied by initiator rules", initiator.NodeID, peer.NodeID)
		return writeError(initiator.Conn, "acl_denied", "your allow_peers does not permit "+peer.NodeID)
	}
	if !aclAllows(peer.AllowPeers, initiator) {
		log.Printf("[acl] %s -> %s denied by peer rules", initiator.NodeID, peer.NodeID)
		return writeError(initiator.Conn, "acl_denied", peer.NodeID+" does not allow you")
	}
	sessionID := uuid.NewString()

	toInit := protocol.PeerInfo{
		PeerID: peer.NodeID, PublicAddr: peer.PublicAddr, LocalAddrs: peer.LocalAddrs,
		SessionID: sessionID, Role: "initiator",
		NatType: peer.NatType, PublicAddr2: peer.PublicAddr2, NatSamples: peer.NatSamples,
	}
	toResp := protocol.PeerInfo{
		PeerID: initiator.NodeID, PublicAddr: initiator.PublicAddr, LocalAddrs: initiator.LocalAddrs,
		SessionID: sessionID, Role: "responder",
		NatType: initiator.NatType, PublicAddr2: initiator.PublicAddr2, NatSamples: initiator.NatSamples,
	}
	a, _ := protocol.Pack(protocol.TypePeerInfo, env.ReqID, toInit)
	b, _ := protocol.Pack(protocol.TypePeerInfo, "", toResp)
	if err := initiator.Send(a); err != nil {
		return err
	}
	if err := peer.Send(b); err != nil {
		return err
	}
	log.Printf("[hub] connect %s -> %s session=%s", initiator.NodeID, peer.NodeID, sessionID)
	return nil
}

func (s *Signaling) handleNatUpdate(sess *Session, env *protocol.Envelope) error {
	var nu protocol.NatUpdate
	if err := json.Unmarshal(env.Payload, &nu); err != nil {
		return err
	}
	sess.NatType = nu.NatType
	sess.PublicAddr2 = nu.PublicAddr2
	sess.NatSamples = append([]string(nil), nu.Samples...)
	log.Printf("[hub] node %s nat=%s samples=%d", sess.NodeID, nu.NatType, len(nu.Samples))
	s.broadcastNetwork()
	return nil
}

func (s *Signaling) handleRelayOpen(sess *Session, env *protocol.Envelope) error {
	var req protocol.RelayOpen
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		return err
	}
	token := s.Relay.AuthorizeSession(req.SessionID)
	ready := protocol.RelayReady{
		RelayAddr: s.Relay.PublicEndpoint(s.PublicHost),
		SessionID: req.SessionID,
		Token:     token,
	}
	out, _ := protocol.Pack(protocol.TypeRelayReady, env.ReqID, ready)
	return sess.Send(out)
}

func (s *Signaling) pingLoop(ctx context.Context, sess *Session) {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			out, _ := protocol.Pack(protocol.TypePing, "", map[string]int64{"t": time.Now().Unix()})
			if err := sess.Send(out); err != nil {
				return
			}
		}
	}
}

func writeError(conn *websocket.Conn, code, msg string) error {
	out, _ := protocol.Pack(protocol.TypeError, "", protocol.ErrorMsg{Code: code, Message: msg})
	return conn.WriteMessage(websocket.TextMessage, out)
}

type strError string

func (e strError) Error() string { return string(e) }
func errString(s string) error   { return strError(s) }

