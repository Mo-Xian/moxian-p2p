package tunnel

import (
	"bufio"
	"crypto/sha256"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"time"

	"github.com/cp12064/moxian-p2p/internal/debug"
	"github.com/cp12064/moxian-p2p/internal/nat"
	kcp "github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"
)

// 派生 AES 密钥（用于 KCP 加密 防止中继服务器/第三方窥探）
func deriveKey(passphrase, sessionID string) []byte {
	sum := sha256.Sum256([]byte(passphrase + "|" + sessionID))
	return sum[:]
}

func newKcpBlock(passphrase, sessionID string) kcp.BlockCrypt {
	key := deriveKey(passphrase, sessionID)
	b, _ := kcp.NewAESBlockCrypt(key)
	return b
}

// 基础 KCP 调参（与 openp2p 类似 无拥塞 低延迟）
func tuneKcp(s *kcp.UDPSession) {
	s.SetMtu(1200)
	s.SetWindowSize(1024, 1024)
	s.SetNoDelay(1, 20, 2, 1)
	s.SetStreamMode(true)
	s.SetACKNoDelay(true)
	s.SetWriteDelay(false)
}

func smuxConfig() *smux.Config {
	c := smux.DefaultConfig()
	c.Version = 2
	c.KeepAliveInterval = 15 * time.Second
	c.KeepAliveTimeout = 60 * time.Second
	c.MaxReceiveBuffer = 16 << 20
	c.MaxStreamBuffer = 2 << 20
	return c
}

// DialInitiator 主动侧：通过 channel 建立 KCP+smux 客户端会话
// kcp 的 raddr 只是形式上的标识，真正的目标由 Channel 内部决定（direct/relay）
func DialInitiator(ch *nat.Channel, passphrase, sessionID string) (*smux.Session, error) {
	kc, err := kcp.NewConn2(nilRemoteAddr{}, newKcpBlock(passphrase, sessionID), 0, 0, ch)
	if err != nil {
		return nil, err
	}
	tuneKcp(kc)
	sess, err := smux.Client(kc, smuxConfig())
	if err != nil {
		kc.Close()
		return nil, err
	}
	return sess, nil
}

// ServeResponder 被动侧：接受 KCP+smux 会话
func ServeResponder(ch *nat.Channel, passphrase, sessionID string) (*smux.Session, error) {
	listener, err := kcp.ServeConn(newKcpBlock(passphrase, sessionID), 0, 0, ch)
	if err != nil {
		return nil, err
	}
	kc, err := listener.AcceptKCP()
	if err != nil {
		listener.Close()
		return nil, err
	}
	tuneKcp(kc)
	sess, err := smux.Server(kc, smuxConfig())
	if err != nil {
		kc.Close()
		return nil, err
	}
	return sess, nil
}

// RunLocalForward 主动侧：在 local 监听 TCP 新连接映射成 smux 流 告知对端 dial target
// smux session 挂掉后 立刻关闭 listener 让外层重建
func RunLocalForward(local, target string, sess *smux.Session) error {
	ln, err := net.Listen("tcp", local)
	if err != nil {
		return err
	}
	defer ln.Close()
	log.Printf("[tunnel] local %s -> peer:%s", local, target)

	// 监视 session 状态，挂了立刻 close listener 触发 Accept 返回
	done := make(chan struct{})
	go func() {
		t := time.NewTicker(2 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-t.C:
				if sess.IsClosed() {
					log.Printf("[tunnel] session closed, tearing down listener %s", local)
					_ = ln.Close()
					return
				}
			}
		}
	}()
	defer close(done)

	for {
		conn, err := ln.Accept()
		if err != nil {
			return err
		}
		go handleLocalConn(conn, target, sess)
	}
}

func handleLocalConn(c net.Conn, target string, sess *smux.Session) {
	defer c.Close()
	stream, err := sess.OpenStream()
	if err != nil {
		log.Printf("[tunnel] open stream: %v", err)
		return
	}
	defer stream.Close()
	header := fmt.Sprintf("DIAL %s\n", target)
	if _, err := stream.Write([]byte(header)); err != nil {
		return
	}
	pipe(c, stream)
}

// StreamHandlers 按 header 分发流
type StreamHandlers struct {
	OnDial func(stream *smux.Stream, target string, reader *bufio.Reader)
	OnTun  func(stream *smux.Stream, reader *bufio.Reader)
}

// ServeIncoming 被动侧：接受 smux 流 按 header 分发
func ServeIncoming(sess *smux.Session, h StreamHandlers) {
	for {
		stream, err := sess.AcceptStream()
		if err != nil {
			log.Printf("[tunnel] accept stream: %v", err)
			return
		}
		go dispatchStream(stream, h)
	}
}

func dispatchStream(stream *smux.Stream, h StreamHandlers) {
	reader := bufio.NewReader(stream)
	line, err := reader.ReadString('\n')
	if err != nil {
		debug.Logf("[tunnel] stream header read err: %v", err)
		stream.Close()
		return
	}
	line = strings.TrimSpace(line)
	debug.Logf("[tunnel] dispatch stream header=%q", line)
	switch {
	case strings.HasPrefix(line, "DIAL "):
		target := strings.TrimPrefix(line, "DIAL ")
		if h.OnDial != nil {
			h.OnDial(stream, target, reader)
			return
		}
	case line == "TUN":
		if h.OnTun != nil {
			h.OnTun(stream, reader)
			return
		}
	}
	stream.Close()
}

// BuildDialHandler 构造 DIAL 处理器（保留原行为）
func BuildDialHandler(allow func(string) bool) func(*smux.Stream, string, *bufio.Reader) {
	return func(stream *smux.Stream, target string, reader *bufio.Reader) {
		defer stream.Close()
		if allow != nil && !allow(target) {
			log.Printf("[tunnel] deny target %s", target)
			return
		}
		remote, err := net.DialTimeout("tcp", target, 10*time.Second)
		if err != nil {
			log.Printf("[tunnel] dial %s: %v", target, err)
			return
		}
		defer remote.Close()
		log.Printf("[tunnel] stream -> %s", target)
		pipeWithPrefix(stream, remote, reader)
	}
}

func pipe(a, b io.ReadWriteCloser) {
	done := make(chan struct{}, 2)
	go func() { io.Copy(a, b); done <- struct{}{} }()
	go func() { io.Copy(b, a); done <- struct{}{} }()
	<-done
}

func pipeWithPrefix(stream io.ReadWriteCloser, remote io.ReadWriteCloser, reader *bufio.Reader) {
	// 将 reader 中剩余 buffer 的数据先转给 remote
	done := make(chan struct{}, 2)
	go func() {
		if n := reader.Buffered(); n > 0 {
			buf, _ := reader.Peek(n)
			_, _ = remote.Write(buf)
			_, _ = reader.Discard(n)
		}
		io.Copy(remote, reader)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(stream, remote)
		done <- struct{}{}
	}()
	<-done
}

// nilRemoteAddr 供 kcp.NewConn2 使用（目标地址由 Channel 内部决定 所以这里传空）
type nilRemoteAddr struct{}

func (nilRemoteAddr) Network() string { return "udp" }
func (nilRemoteAddr) String() string  { return "channel" }
