package nat

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/time/rate"
)

// 客户端 UDP 首字节定义（与 server 端一致）
const (
	MagicStunReq    = 0x01
	MagicStunResp   = 0x02
	MagicRelayBind  = 0x10
	MagicRelayData  = 0x11
	MagicRelayAck   = 0x12
	MagicPunchPing  = 0x20 // [0x20][1 len][sid][4 nonce]
	MagicPunchPong  = 0x21 // [0x21][1 len][sid][4 nonce]
	MagicDataDirect = 0x30 // [0x30][1 len][sid][payload]
)

// Mux 复用单个 UDP socket：STUN/打洞/中继/数据
type Mux struct {
	conn *net.UDPConn

	mu          sync.RWMutex
	channels    map[string]*Channel
	stunWaiters map[string]chan *net.UDPAddr // nonce(hex) -> reply
	relayAcks   map[string]chan byte         // sessionID -> status
	punchHooks  map[string]PunchHook         // sessionID -> callback

	egress atomic.Pointer[rate.Limiter] // 数据出站限速（nil=不限）
}

// SetEgressLimit 设置出站字节/秒（仅对数据包生效）
func (m *Mux) SetEgressLimit(bytesPerSec int64) {
	if bytesPerSec <= 0 {
		m.egress.Store(nil)
		return
	}
	burst := int(bytesPerSec / 10)
	if burst < 4096 {
		burst = 4096
	}
	m.egress.Store(rate.NewLimiter(rate.Limit(bytesPerSec), burst))
}

// PunchHook 收到对端打洞包时触发
type PunchHook interface {
	OnPunchPing(from *net.UDPAddr, nonce []byte)
	OnPunchPong(from *net.UDPAddr, nonce []byte)
}

// NewMux 在 0 端口（随机）或指定端口监听
func NewMux(localPort int) (*Mux, error) {
	addr := &net.UDPAddr{IP: net.IPv4zero, Port: localPort}
	conn, err := net.ListenUDP("udp4", addr)
	if err != nil {
		return nil, err
	}
	m := &Mux{
		conn:        conn,
		channels:    make(map[string]*Channel),
		stunWaiters: make(map[string]chan *net.UDPAddr),
		relayAcks:   make(map[string]chan byte),
		punchHooks:  make(map[string]PunchHook),
	}
	go m.readLoop()
	return m, nil
}

// LocalPort 本地端口
func (m *Mux) LocalPort() int { return m.conn.LocalAddr().(*net.UDPAddr).Port }

// Close 关闭
func (m *Mux) Close() error { return m.conn.Close() }

// sendRaw 发送原始包（数据包受出站限速约束）
func (m *Mux) sendRaw(pkt []byte, to *net.UDPAddr) error {
	if len(pkt) > 0 {
		if lim := m.egress.Load(); lim != nil {
			switch pkt[0] {
			case MagicDataDirect, MagicRelayData:
				_ = lim.WaitN(context.Background(), len(pkt))
			}
		}
	}
	_, err := m.conn.WriteToUDP(pkt, to)
	return err
}

func (m *Mux) readLoop() {
	buf := make([]byte, 64*1024)
	for {
		n, from, err := m.conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("[mux] read: %v", err)
			return
		}
		if n < 1 {
			continue
		}
		// 复制，交给下游
		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		m.dispatch(pkt, from)
	}
}

func (m *Mux) dispatch(pkt []byte, from *net.UDPAddr) {
	switch pkt[0] {
	case MagicStunResp:
		if len(pkt) < 5 {
			return
		}
		nonce := hex.EncodeToString(pkt[1:5])
		m.mu.Lock()
		ch, ok := m.stunWaiters[nonce]
		if ok {
			delete(m.stunWaiters, nonce)
		}
		m.mu.Unlock()
		if !ok || len(pkt) < 6 {
			return
		}
		alen := int(pkt[5])
		if len(pkt) < 6+alen {
			return
		}
		addrStr := string(pkt[6 : 6+alen])
		a, err := net.ResolveUDPAddr("udp", addrStr)
		if err == nil {
			ch <- a
		}
	case MagicRelayAck:
		if len(pkt) < 2 {
			return
		}
		slen := int(pkt[1])
		if len(pkt) < 2+slen+1 {
			return
		}
		sid := string(pkt[2 : 2+slen])
		status := pkt[2+slen]
		m.mu.Lock()
		ch, ok := m.relayAcks[sid]
		if ok {
			delete(m.relayAcks, sid)
		}
		m.mu.Unlock()
		if ok {
			ch <- status
		}
	case MagicPunchPing, MagicPunchPong:
		if len(pkt) < 2 {
			return
		}
		slen := int(pkt[1])
		if len(pkt) < 2+slen+4 {
			return
		}
		sid := string(pkt[2 : 2+slen])
		nonce := pkt[2+slen : 2+slen+4]
		m.mu.RLock()
		hook := m.punchHooks[sid]
		m.mu.RUnlock()
		if hook != nil {
			if pkt[0] == MagicPunchPing {
				hook.OnPunchPing(from, nonce)
			} else {
				hook.OnPunchPong(from, nonce)
			}
		}
	case MagicDataDirect, MagicRelayData:
		if len(pkt) < 2 {
			return
		}
		slen := int(pkt[1])
		if len(pkt) < 2+slen {
			return
		}
		sid := string(pkt[2 : 2+slen])
		data := pkt[2+slen:]
		m.mu.RLock()
		ch := m.channels[sid]
		m.mu.RUnlock()
		if ch != nil {
			ch.deliver(data, from)
		}
	}
}

// Stun 查询公网地址（向 server UDP 发送 STUN_REQ）
func (m *Mux) Stun(serverAddr string, timeout time.Duration) (*net.UDPAddr, error) {
	server, err := net.ResolveUDPAddr("udp", serverAddr)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, 4)
	_, _ = rand.Read(nonce)
	key := hex.EncodeToString(nonce)

	reply := make(chan *net.UDPAddr, 1)
	m.mu.Lock()
	m.stunWaiters[key] = reply
	m.mu.Unlock()
	defer func() {
		m.mu.Lock()
		delete(m.stunWaiters, key)
		m.mu.Unlock()
	}()

	pkt := append([]byte{MagicStunReq}, nonce...)
	// 发 3 次抗丢包
	for i := 0; i < 3; i++ {
		_ = m.sendRaw(pkt, server)
		select {
		case a := <-reply:
			return a, nil
		case <-time.After(timeout / 3):
		}
	}
	return nil, errors.New("stun timeout")
}

// RelayBind 向中继服务器绑定会话
func (m *Mux) RelayBind(relayAddr, sessionID, token string, timeout time.Duration) error {
	server, err := net.ResolveUDPAddr("udp", relayAddr)
	if err != nil {
		return err
	}
	ackCh := make(chan byte, 1)
	m.mu.Lock()
	m.relayAcks[sessionID] = ackCh
	m.mu.Unlock()
	defer func() {
		m.mu.Lock()
		delete(m.relayAcks, sessionID)
		m.mu.Unlock()
	}()

	pkt := buildRelayBind(sessionID, token)
	for i := 0; i < 5; i++ {
		_ = m.sendRaw(pkt, server)
		select {
		case status := <-ackCh:
			if status == 0 {
				return nil
			}
			return fmt.Errorf("relay bind rejected (status=%d)", status)
		case <-time.After(timeout / 5):
		}
	}
	return errors.New("relay bind timeout")
}

// SendPunchPing 发送打洞包
func (m *Mux) SendPunchPing(sid string, nonce []byte, to *net.UDPAddr) error {
	return m.sendRaw(buildPunch(MagicPunchPing, sid, nonce), to)
}

func (m *Mux) SendPunchPong(sid string, nonce []byte, to *net.UDPAddr) error {
	return m.sendRaw(buildPunch(MagicPunchPong, sid, nonce), to)
}

// RegisterPunchHook 注册打洞回调
func (m *Mux) RegisterPunchHook(sid string, h PunchHook) {
	m.mu.Lock()
	m.punchHooks[sid] = h
	m.mu.Unlock()
}

func (m *Mux) UnregisterPunchHook(sid string) {
	m.mu.Lock()
	delete(m.punchHooks, sid)
	m.mu.Unlock()
}

// RegisterChannel 绑定一个数据通道
func (m *Mux) RegisterChannel(ch *Channel) {
	m.mu.Lock()
	m.channels[ch.sessionID] = ch
	m.mu.Unlock()
}

func (m *Mux) UnregisterChannel(sid string) {
	m.mu.Lock()
	delete(m.channels, sid)
	m.mu.Unlock()
}

func buildRelayBind(sid, token string) []byte {
	b := make([]byte, 0, 3+len(sid)+len(token))
	b = append(b, MagicRelayBind, byte(len(sid)))
	b = append(b, sid...)
	b = append(b, byte(len(token)))
	b = append(b, token...)
	return b
}

func buildPunch(magic byte, sid string, nonce []byte) []byte {
	b := make([]byte, 0, 2+len(sid)+len(nonce))
	b = append(b, magic, byte(len(sid)))
	b = append(b, sid...)
	b = append(b, nonce...)
	return b
}
