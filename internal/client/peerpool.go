package client

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"log"

	"github.com/cp12064/moxian-p2p/internal/protocol"
	"github.com/cp12064/moxian-p2p/internal/tunnel"
	"github.com/google/uuid"
	"github.com/xtaci/smux"
)

// peerLink 到某对端的长连接（smux session）+ TUN 专用流
type peerLink struct {
	peerID    string
	sess      *smux.Session
	tunStream *smux.Stream
	mu        sync.Mutex
}

// peerPool 管理到各对端的 smux 会话（TUN / 复用用）
type peerPool struct {
	c       *Client
	mu      sync.Mutex
	links   map[string]*peerLink
	dialing map[string]chan struct{}
}

func newPeerPool(c *Client) *peerPool {
	return &peerPool{c: c, links: make(map[string]*peerLink), dialing: make(map[string]chan struct{})}
}

// GetOrDial 获取或建立到 peerID 的 smux 会话
func (p *peerPool) GetOrDial(ctx context.Context, peerID string) (*peerLink, error) {
	p.mu.Lock()
	if lk, ok := p.links[peerID]; ok {
		p.mu.Unlock()
		return lk, nil
	}
	// 合并并发 dial
	if ch, ok := p.dialing[peerID]; ok {
		p.mu.Unlock()
		select {
		case <-ch:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		p.mu.Lock()
		lk := p.links[peerID]
		p.mu.Unlock()
		if lk == nil {
			return nil, errors.New("peer dial failed")
		}
		return lk, nil
	}
	waitCh := make(chan struct{})
	p.dialing[peerID] = waitCh
	p.mu.Unlock()

	lk, err := p.dial(ctx, peerID)
	p.mu.Lock()
	delete(p.dialing, peerID)
	if err == nil {
		p.links[peerID] = lk
	}
	close(waitCh)
	p.mu.Unlock()
	return lk, err
}

func (p *peerPool) dial(ctx context.Context, peerID string) (*peerLink, error) {
	c := p.c
	reqID := uuid.NewString()
	piCh := make(chan protocol.PeerInfo, 1)
	c.pending.Store("req:"+reqID, piCh)
	defer c.pending.Delete("req:" + reqID)

	connectMsg, _ := protocol.Pack(protocol.TypeConnect, reqID, protocol.Connect{
		PeerID:  peerID,
		Purpose: "tun",
	})
	if err := c.wsWrite(connectMsg); err != nil {
		return nil, err
	}
	var pi protocol.PeerInfo
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case pi0, ok := <-piCh:
		if !ok {
			return nil, errors.New("peer unreachable")
		}
		pi = pi0
	case <-time.After(10 * time.Second):
		return nil, errors.New("peer_info timeout")
	}

	ch, _, err := c.establishChannel(ctx, pi, "initiator")
	if err != nil {
		return nil, err
	}
	sess, err := tunnel.DialInitiator(ch, c.cfg.Passphrase, pi.SessionID)
	if err != nil {
		ch.Close()
		return nil, fmt.Errorf("smux client: %w", err)
	}
	lk := &peerLink{peerID: peerID, sess: sess}
	// 即使作为 initiator 也要接受对端主动开的流（关键：TUN 回包走对端 OpenStream）
	go tunnel.ServeIncoming(sess, tunnel.StreamHandlers{
		OnDial: tunnel.BuildDialHandler(c.buildAllow()),
		OnTun:  c.handleIncomingTunStream,
	})
	log.Printf("[peerpool] dialed %s (initiator)", peerID)
	return lk, nil
}

// RegisterInbound 将 responder 建立的 session 注册进 pool
// 之后本端要给 peerID 发包时可以复用（比如 TUN 回包通过 OpenStream）
func (p *peerPool) RegisterInbound(peerID string, sess *smux.Session) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if lk, ok := p.links[peerID]; ok && lk.sess != nil && !lk.sess.IsClosed() {
		return // 已有活跃连接 不覆盖
	}
	p.links[peerID] = &peerLink{peerID: peerID, sess: sess}
	log.Printf("[peerpool] registered inbound %s", peerID)
}

// Close 关闭链接
func (p *peerPool) Close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, lk := range p.links {
		if lk.tunStream != nil {
			lk.tunStream.Close()
		}
		if lk.sess != nil {
			lk.sess.Close()
		}
	}
}

// Remove 移除失效 link
func (p *peerPool) Remove(peerID string) {
	p.mu.Lock()
	lk, ok := p.links[peerID]
	if ok {
		delete(p.links, peerID)
	}
	p.mu.Unlock()
	if lk != nil {
		if lk.tunStream != nil {
			lk.tunStream.Close()
		}
		if lk.sess != nil {
			lk.sess.Close()
		}
	}
}

// TunStream 获取或建立到 peer 的 TUN 流
func (p *peerPool) TunStream(ctx context.Context, peerID string) (*smux.Stream, error) {
	lk, err := p.GetOrDial(ctx, peerID)
	if err != nil {
		return nil, err
	}
	lk.mu.Lock()
	defer lk.mu.Unlock()
	if lk.tunStream != nil {
		return lk.tunStream, nil
	}
	stream, err := lk.sess.OpenStream()
	if err != nil {
		return nil, err
	}
	if _, err := stream.Write([]byte("TUN\n")); err != nil {
		stream.Close()
		return nil, err
	}
	lk.tunStream = stream
	return stream, nil
}
