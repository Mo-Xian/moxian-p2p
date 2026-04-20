package client

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"log"
	"net"
	"sync"

	"github.com/cp12064/moxian-p2p/internal/debug"
	"github.com/xtaci/smux"
)

// tunDevice 抽象 TUN 设备（跨平台实现）
type tunDevice interface {
	Read([]byte) (int, error)
	Write([]byte) (int, error)
	Close() error
	Name() string
}

// tunRuntime TUN 运行时
type tunRuntime struct {
	dev    tunDevice
	pool   *peerPool
	client *Client

	// 对端写流（接收到的）与对端读流（自己发起的）统一使用 peerpool 的 TunStream
	mu     sync.Mutex
	closed bool
}

// startTun 启动 TUN（client.go 调用）
// vip 是决议后的实际 vIP（可能来自 server 分配）
// Android 模式下 cfg.AndroidTunFD > 0 直接从 fd 构造设备（VpnService 打开的）
func (c *Client) startTun(ctx context.Context, vip string) error {
	if vip == "" {
		return errors.New("virtual_ip required for tun mode")
	}
	var (
		dev tunDevice
		err error
	)
	if c.cfg.AndroidTunFD > 0 {
		dev, err = openTunDeviceFromFD(int(c.cfg.AndroidTunFD))
	} else {
		dev, err = openTunDevice(vip, c.cfg.TunSubnet, c.cfg.TunDev)
	}
	if err != nil {
		return err
	}
	rt := &tunRuntime{dev: dev, pool: c.pool, client: c}
	c.tun = rt
	log.Printf("[tun] device=%s vip=%s", dev.Name(), vip)
	go rt.readLoop(ctx)
	return nil
}

// readLoop 从 TUN 读 IP 包 根据目的 IP 路由到对端
func (rt *tunRuntime) readLoop(ctx context.Context) {
	buf := make([]byte, 2048)
	var readCount, ipv4Count, sendCount int64
	for {
		if ctx.Err() != nil {
			return
		}
		n, err := rt.dev.Read(buf)
		if err != nil {
			if rt.closed {
				return
			}
			log.Printf("[tun] read: %v", err)
			return
		}
		readCount++
		if readCount <= 5 || readCount%100 == 0 {
			debug.Logf("[tun] dev.Read #%d n=%d first=0x%02x", readCount, n, buf[0])
		}
		if n < 20 {
			continue
		}
		// 仅处理 IPv4
		if buf[0]>>4 != 4 {
			continue
		}
		ipv4Count++
		dstIP := net.IPv4(buf[16], buf[17], buf[18], buf[19]).String()
		srcIP := net.IPv4(buf[12], buf[13], buf[14], buf[15]).String()
		peerID := rt.client.lookupPeerByVIP(dstIP)
		if ipv4Count <= 5 {
			debug.Logf("[tun] IPv4 #%d src=%s dst=%s len=%d peer=%q", ipv4Count, srcIP, dstIP, n, peerID)
		}
		if peerID == "" {
			continue
		}
		sendCount++
		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		go rt.sendTo(ctx, peerID, pkt)
	}
}

// sendTo 发送 IP 包到对端
// 关键：hdr + pkt 必须一次 Write 过去，避免多个 goroutine 并发写 stream 时帧错位
func (rt *tunRuntime) sendTo(ctx context.Context, peerID string, pkt []byte) {
	stream, err := rt.pool.TunStream(ctx, peerID)
	if err != nil {
		log.Printf("[tun] get stream %s: %v", peerID, err)
		rt.pool.Remove(peerID)
		return
	}
	buf := make([]byte, 2+len(pkt))
	binary.BigEndian.PutUint16(buf[:2], uint16(len(pkt)))
	copy(buf[2:], pkt)
	if _, err := stream.Write(buf); err != nil {
		log.Printf("[tun] write to %s: %v", peerID, err)
		rt.pool.Remove(peerID)
		return
	}
}

// handleIncomingTunStream 被动侧收到 "TUN\n" 流后处理 IP 包
func (c *Client) handleIncomingTunStream(stream *smux.Stream, reader *bufio.Reader) {
	debug.Logf("[tun] incoming TUN stream opened")
	defer func() {
		debug.Logf("[tun] incoming TUN stream closed")
		stream.Close()
	}()
	if c.tun == nil {
		log.Printf("[tun] peer opened TUN stream but local TUN not enabled")
		return
	}
	var count int
	for {
		var hdr [2]byte
		if _, err := io.ReadFull(reader, hdr[:]); err != nil {
			debug.Logf("[tun] read hdr after %d pkts: %v", count, err)
			return
		}
		n := binary.BigEndian.Uint16(hdr[:])
		if n == 0 || n > 4096 {
			log.Printf("[tun] bad pkt size %d (stream corrupted?)", n)
			return
		}
		pkt := make([]byte, n)
		if _, err := io.ReadFull(reader, pkt); err != nil {
			debug.Logf("[tun] read pkt body: %v", err)
			return
		}
		count++
		if count <= 5 || count%100 == 0 {
			srcIP := "?"
			dstIP := "?"
			if n >= 20 && pkt[0]>>4 == 4 {
				srcIP = net.IPv4(pkt[12], pkt[13], pkt[14], pkt[15]).String()
				dstIP = net.IPv4(pkt[16], pkt[17], pkt[18], pkt[19]).String()
			}
			debug.Logf("[tun] recv pkt #%d len=%d src=%s dst=%s first=0x%02x", count, n, srcIP, dstIP, pkt[0])
		}
		if _, err := c.tun.dev.Write(pkt); err != nil {
			log.Printf("[tun] write device fail: %v (pkt len=%d first=0x%02x)", err, n, pkt[0])
			return
		}
	}
}
