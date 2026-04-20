package server

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/time/rate"
)

// UDP 服务报文首字节
const (
	MagicStunReq   = 0x01 // [0x01][4 nonce]
	MagicStunResp  = 0x02 // [0x02][4 nonce][1 len][addr]
	MagicRelayBind = 0x10 // [0x10][1 len][sid][1 len][token]
	MagicRelayAck  = 0x12 // [0x12][1 len][sid][1 status] (0=ok,1=fail)
	MagicRelayData = 0x11 // [0x11][1 len][sid][data]
)

// relaySession 中继会话
type relaySession struct {
	id       string
	token    string
	mu       sync.Mutex
	sides    [2]*net.UDPAddr // 0=先到 1=后到
	lastSeen time.Time
	limiter  *rate.Limiter // per-session 限速（nil=不限）
}

// UDPServer 同时提供 STUN 探测和中继
type UDPServer struct {
	conn    *net.UDPConn
	port    int
	mu      sync.Mutex
	pending map[string]string // sessionID -> token (由 signaling 授权)
	active  map[string]*relaySession

	// 计数器（仅中继路径）
	relayBytes atomic.Int64
	relayPkts  atomic.Int64
	stunReqs   atomic.Int64

	// 每 session 限速字节/秒（0=不限）
	sessionLimit int64
}

// SetSessionRelayLimit 配置 per-session 中继限速
func (u *UDPServer) SetSessionRelayLimit(bytesPerSec int64) {
	u.sessionLimit = bytesPerSec
}

// RelayStats 返回中继累计
func (u *UDPServer) RelayStats() (bytes, pkts, stun int64) {
	return u.relayBytes.Load(), u.relayPkts.Load(), u.stunReqs.Load()
}

// NewUDPServer 监听 listenAddr (如 ":7789")
func NewUDPServer(listenAddr string) (*UDPServer, error) {
	addr, err := net.ResolveUDPAddr("udp", listenAddr)
	if err != nil {
		return nil, err
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return nil, err
	}
	return &UDPServer{
		conn:    conn,
		port:    conn.LocalAddr().(*net.UDPAddr).Port,
		pending: make(map[string]string),
		active:  make(map[string]*relaySession),
	}, nil
}

// Port 返回监听端口
func (u *UDPServer) Port() int { return u.port }

// PublicEndpoint 返回对外 host:port
func (u *UDPServer) PublicEndpoint(host string) string {
	return fmt.Sprintf("%s:%d", host, u.port)
}

// AuthorizeSession 由信令层调用 为会话预分配 token
// 若 pending 或 active 中已有则返回既有 token（允许双方分别请求开中继）
func (u *UDPServer) AuthorizeSession(sessionID string) string {
	u.mu.Lock()
	if t, ok := u.pending[sessionID]; ok {
		u.mu.Unlock()
		return t
	}
	if s, ok := u.active[sessionID]; ok {
		u.mu.Unlock()
		return s.token
	}
	tok := make([]byte, 16)
	_, _ = rand.Read(tok)
	tokStr := hex.EncodeToString(tok)
	u.pending[sessionID] = tokStr
	u.mu.Unlock()
	time.AfterFunc(10*time.Minute, func() {
		u.mu.Lock()
		if t, ok := u.pending[sessionID]; ok && t == tokStr {
			delete(u.pending, sessionID)
		}
		u.mu.Unlock()
	})
	return tokStr
}

// Run 启动 UDP 处理循环
func (u *UDPServer) Run() {
	buf := make([]byte, 64*1024)
	go u.gcLoop()
	for {
		n, remote, err := u.conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("[udp] read: %v", err)
			return
		}
		if n < 1 {
			continue
		}
		u.handle(buf[:n], remote)
	}
}

func (u *UDPServer) handle(pkt []byte, remote *net.UDPAddr) {
	switch pkt[0] {
	case MagicStunReq:
		if len(pkt) < 5 {
			return
		}
		u.stunReqs.Add(1)
		nonce := pkt[1:5]
		addrStr := remote.String()
		out := make([]byte, 0, 6+len(addrStr))
		out = append(out, MagicStunResp)
		out = append(out, nonce...)
		out = append(out, byte(len(addrStr)))
		out = append(out, addrStr...)
		_, _ = u.conn.WriteToUDP(out, remote)
	case MagicRelayBind:
		u.handleBind(pkt, remote)
	case MagicRelayData:
		u.handleData(pkt, remote)
	default:
		// 忽略未知
	}
}

func (u *UDPServer) handleBind(pkt []byte, remote *net.UDPAddr) {
	sid, token, ok := parseLenPair(pkt[1:])
	if !ok {
		u.bindAck(remote, "", 1)
		return
	}
	u.mu.Lock()
	expect, pendingOK := u.pending[sid]
	sess, activeOK := u.active[sid]
	if !activeOK && pendingOK && expect == token {
		sess = &relaySession{id: sid, token: token, lastSeen: time.Now()}
		if u.sessionLimit > 0 {
			burst := int(u.sessionLimit / 10)
			if burst < 4096 {
				burst = 4096
			}
			sess.limiter = rate.NewLimiter(rate.Limit(u.sessionLimit), burst)
		}
		u.active[sid] = sess
		delete(u.pending, sid)
	}
	u.mu.Unlock()
	if sess == nil || sess.token != token {
		u.bindAck(remote, sid, 1)
		return
	}
	sess.mu.Lock()
	switch {
	case sess.sides[0] == nil:
		sess.sides[0] = remote
	case sess.sides[1] == nil && !udpAddrEqual(sess.sides[0], remote):
		sess.sides[1] = remote
	}
	sess.lastSeen = time.Now()
	sess.mu.Unlock()
	u.bindAck(remote, sid, 0)
}

func (u *UDPServer) handleData(pkt []byte, remote *net.UDPAddr) {
	if len(pkt) < 2 {
		return
	}
	slen := int(pkt[1])
	if len(pkt) < 2+slen {
		return
	}
	sid := string(pkt[2 : 2+slen])
	u.mu.Lock()
	sess := u.active[sid]
	u.mu.Unlock()
	if sess == nil {
		return
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	sess.lastSeen = time.Now()
	var target *net.UDPAddr
	switch {
	case udpAddrEqual(sess.sides[0], remote):
		target = sess.sides[1]
	case udpAddrEqual(sess.sides[1], remote):
		target = sess.sides[0]
	}
	if target == nil {
		return
	}
	// 限速 + 转发
	if sess.limiter != nil {
		_ = sess.limiter.WaitN(context.Background(), len(pkt))
	}
	n, _ := u.conn.WriteToUDP(pkt, target)
	if n > 0 {
		u.relayBytes.Add(int64(n))
		u.relayPkts.Add(1)
	}
}

func (u *UDPServer) bindAck(remote *net.UDPAddr, sid string, status byte) {
	out := make([]byte, 0, 3+len(sid))
	out = append(out, MagicRelayAck, byte(len(sid)))
	out = append(out, sid...)
	out = append(out, status)
	_, _ = u.conn.WriteToUDP(out, remote)
}

func (u *UDPServer) gcLoop() {
	t := time.NewTicker(2 * time.Minute)
	for range t.C {
		cutoff := time.Now().Add(-10 * time.Minute)
		u.mu.Lock()
		for sid, s := range u.active {
			if s.lastSeen.Before(cutoff) {
				delete(u.active, sid)
			}
		}
		u.mu.Unlock()
	}
}

func parseLenPair(b []byte) (a, c string, ok bool) {
	if len(b) < 1 {
		return
	}
	la := int(b[0])
	if len(b) < 1+la+1 {
		return
	}
	a = string(b[1 : 1+la])
	off := 1 + la
	lc := int(b[off])
	if len(b) < off+1+lc {
		return
	}
	c = string(b[off+1 : off+1+lc])
	ok = true
	return
}

func udpAddrEqual(a, b *net.UDPAddr) bool {
	if a == nil || b == nil {
		return false
	}
	return a.IP.Equal(b.IP) && a.Port == b.Port
}
