package nat

import (
	"context"
	"crypto/rand"
	"errors"
	"log"
	"net"
	"sync"
	"time"
)

// PunchResult 打洞结果
type PunchResult struct {
	Addr *net.UDPAddr
}

type punchCollector struct {
	sid       string
	nonce     []byte
	mux       *Mux
	pongAddr  chan *net.UDPAddr
	pingAddrs chan *net.UDPAddr
	once      sync.Once
}

func (p *punchCollector) OnPunchPing(from *net.UDPAddr, nonce []byte) {
	// 对端发来 ping 我们回 pong
	_ = p.mux.SendPunchPong(p.sid, nonce, from)
	select {
	case p.pingAddrs <- from:
	default:
	}
}

func (p *punchCollector) OnPunchPong(from *net.UDPAddr, nonce []byte) {
	// 只接受 nonce 匹配的 pong
	if len(nonce) != len(p.nonce) {
		return
	}
	for i := range nonce {
		if nonce[i] != p.nonce[i] {
			return
		}
	}
	p.once.Do(func() {
		p.pongAddr <- from
	})
}

// Punch 向候选地址发送打洞包
// candidates 顺序：优先公网地址 后跟内网地址
func Punch(ctx context.Context, mux *Mux, sessionID string, candidates []string, timeout time.Duration) (*PunchResult, error) {
	if len(candidates) == 0 {
		return nil, errors.New("no candidates")
	}
	nonce := make([]byte, 4)
	_, _ = rand.Read(nonce)
	coll := &punchCollector{
		sid:       sessionID,
		nonce:     nonce,
		mux:       mux,
		pongAddr:  make(chan *net.UDPAddr, 1),
		pingAddrs: make(chan *net.UDPAddr, 16),
	}
	mux.RegisterPunchHook(sessionID, coll)
	defer mux.UnregisterPunchHook(sessionID)

	// 解析候选地址
	addrs := make([]*net.UDPAddr, 0, len(candidates))
	for _, s := range candidates {
		a, err := net.ResolveUDPAddr("udp", s)
		if err != nil {
			continue
		}
		addrs = append(addrs, a)
	}
	if len(addrs) == 0 {
		return nil, errors.New("all candidates unresolved")
	}

	// 每 300ms 向全部候选地址广播一次打洞 ping
	pingCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		t := time.NewTicker(300 * time.Millisecond)
		defer t.Stop()
		// 立即发一次
		for _, a := range addrs {
			_ = mux.SendPunchPing(sessionID, nonce, a)
		}
		for {
			select {
			case <-pingCtx.Done():
				return
			case <-t.C:
				for _, a := range addrs {
					_ = mux.SendPunchPing(sessionID, nonce, a)
				}
			}
		}
	}()

	deadline := time.NewTimer(timeout)
	defer deadline.Stop()

	// 收到任何 pong 立即成功；
	// 若只收到对端 ping（即收到 ping 但还没收到自己发的 pong 的回应），保留作为候选，宽限期结束后也认为成功
	var fallback *net.UDPAddr
	graceTimer := time.NewTimer(timeout + time.Second) // 兜底
	defer graceTimer.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case a := <-coll.pongAddr:
			log.Printf("[punch] session=%s direct via pong from %s", sessionID, a)
			return &PunchResult{Addr: a}, nil
		case a := <-coll.pingAddrs:
			if fallback == nil {
				fallback = a
			}
		case <-deadline.C:
			if fallback != nil {
				log.Printf("[punch] session=%s direct via ping-only from %s", sessionID, fallback)
				return &PunchResult{Addr: fallback}, nil
			}
			return nil, errors.New("punch timeout")
		case <-graceTimer.C:
			return nil, errors.New("punch grace timeout")
		}
	}
}
