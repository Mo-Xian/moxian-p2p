package nat

import (
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// Channel 逻辑数据通道 实现 net.PacketConn 供 KCP 使用
// 根据当前模式不同使用不同包头：
//   ModeDirect -> [MagicDataDirect][sid_len][sid][payload]  -> 发到对端公网地址
//   ModeRelay  -> [MagicRelayData ][sid_len][sid][payload]  -> 发到中继服务器
type Channel struct {
	sessionID string
	mux       *Mux

	mode       atomic.Int32 // 0=未就绪 1=direct 2=relay
	directAddr atomic.Pointer[net.UDPAddr]
	relayAddr  atomic.Pointer[net.UDPAddr]

	readMu   sync.Mutex
	readDdl  time.Time
	incoming chan incomingPkt

	// done 关闭表示 Channel 已停止 用于替代 close(incoming)
	// 避免 deliver 与 Close 并发导致 send on closed channel panic
	done   chan struct{}
	closed atomic.Bool

	// 流量统计（payload 字节 不含 KCP/IP/UDP 头）
	rxBytes atomic.Int64
	txBytes atomic.Int64
	rxPkts  atomic.Int64
	txPkts  atomic.Int64
}

// Stats 流量统计快照
type Stats struct {
	RxBytes, TxBytes int64
	RxPkts, TxPkts   int64
	Mode             int32
}

// Stats 返回当前统计
func (c *Channel) Stats() Stats {
	return Stats{
		RxBytes: c.rxBytes.Load(), TxBytes: c.txBytes.Load(),
		RxPkts: c.rxPkts.Load(), TxPkts: c.txPkts.Load(),
		Mode: c.mode.Load(),
	}
}

// SessionID 返回会话 ID
func (c *Channel) SessionID() string { return c.sessionID }

type incomingPkt struct {
	data []byte
	from *net.UDPAddr
}

const (
	ModeUnset  = 0
	ModeDirect = 1
	ModeRelay  = 2
)

func NewChannel(mux *Mux, sessionID string) *Channel {
	c := &Channel{
		sessionID: sessionID,
		mux:       mux,
		incoming:  make(chan incomingPkt, 512),
		done:      make(chan struct{}),
	}
	mux.RegisterChannel(c)
	return c
}

// SetDirect 切换为直连模式
func (c *Channel) SetDirect(addr *net.UDPAddr) {
	c.directAddr.Store(addr)
	c.mode.Store(ModeDirect)
}

// SetRelay 切换为中继模式
func (c *Channel) SetRelay(addr *net.UDPAddr) {
	c.relayAddr.Store(addr)
	c.mode.Store(ModeRelay)
}

// Mode 当前模式
func (c *Channel) Mode() int32 { return c.mode.Load() }

func (c *Channel) deliver(data []byte, from *net.UDPAddr) {
	buf := make([]byte, len(data))
	copy(buf, data)
	// 监听 done 避免 Close 并发时 send on closed channel
	// incoming 永不 close 仅靠 done 通知 ReadFrom 退出
	select {
	case <-c.done:
		return
	case c.incoming <- incomingPkt{data: buf, from: from}:
	default:
		// 丢弃 防止阻塞
	}
}

// ----- net.PacketConn 实现 -----

func (c *Channel) ReadFrom(p []byte) (int, net.Addr, error) {
	if c.closed.Load() {
		return 0, nil, net.ErrClosed
	}
	c.readMu.Lock()
	ddl := c.readDdl
	c.readMu.Unlock()

	var timer *time.Timer
	var timeC <-chan time.Time
	if !ddl.IsZero() {
		d := time.Until(ddl)
		if d <= 0 {
			return 0, nil, timeoutErr{}
		}
		timer = time.NewTimer(d)
		timeC = timer.C
	}
	defer func() {
		if timer != nil {
			timer.Stop()
		}
	}()

	select {
	case pkt := <-c.incoming:
		n := copy(p, pkt.data)
		c.rxBytes.Add(int64(n))
		c.rxPkts.Add(1)
		return n, pkt.from, nil
	case <-c.done:
		return 0, nil, net.ErrClosed
	case <-timeC:
		return 0, nil, timeoutErr{}
	}
}

func (c *Channel) WriteTo(p []byte, _ net.Addr) (int, error) {
	if c.closed.Load() {
		return 0, net.ErrClosed
	}
	mode := c.mode.Load()
	var magic byte
	var to *net.UDPAddr
	switch mode {
	case ModeDirect:
		magic = MagicDataDirect
		to = c.directAddr.Load()
	case ModeRelay:
		magic = MagicRelayData
		to = c.relayAddr.Load()
	default:
		return 0, errors.New("channel not ready")
	}
	if to == nil {
		return 0, errors.New("channel target missing")
	}
	sid := c.sessionID
	pkt := make([]byte, 0, 2+len(sid)+len(p))
	pkt = append(pkt, magic, byte(len(sid)))
	pkt = append(pkt, sid...)
	pkt = append(pkt, p...)
	if err := c.mux.sendRaw(pkt, to); err != nil {
		return 0, err
	}
	c.txBytes.Add(int64(len(p)))
	c.txPkts.Add(1)
	return len(p), nil
}

func (c *Channel) Close() error {
	if !c.closed.CompareAndSwap(false, true) {
		return nil
	}
	c.mux.UnregisterChannel(c.sessionID)
	close(c.done)
	return nil
}

func (c *Channel) LocalAddr() net.Addr { return c.mux.conn.LocalAddr() }

func (c *Channel) SetDeadline(t time.Time) error {
	return c.SetReadDeadline(t)
}

func (c *Channel) SetReadDeadline(t time.Time) error {
	c.readMu.Lock()
	c.readDdl = t
	c.readMu.Unlock()
	return nil
}

func (c *Channel) SetWriteDeadline(t time.Time) error { return nil }

type timeoutErr struct{}

func (timeoutErr) Error() string   { return "i/o timeout" }
func (timeoutErr) Timeout() bool   { return true }
func (timeoutErr) Temporary() bool { return true }
