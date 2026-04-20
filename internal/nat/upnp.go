package nat

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"github.com/huin/goupnp/dcps/internetgateway2"
)

// UPnPMapping 一个活跃的 UPnP 端口映射
type UPnPMapping struct {
	ExternalIP   string // 网关对外 IP
	ExternalPort uint16 // 公网端口
	InternalPort uint16 // 本地 UDP 端口
	LeaseSecs   uint32 // 租期秒数
}

// Endpoint 返回 ExternalIP:ExternalPort
func (m *UPnPMapping) Endpoint() string {
	return fmt.Sprintf("%s:%d", m.ExternalIP, m.ExternalPort)
}

// UPnPClient UPnP 客户端封装
type UPnPClient struct {
	ctx     context.Context
	cancel  context.CancelFunc
	wan     wanClient
	mapping *UPnPMapping
	mu      sync.Mutex
}

// 统一 WANIPConnection / WANPPPConnection 接口
type wanClient interface {
	GetExternalIPAddressCtx(ctx context.Context) (string, error)
	AddPortMappingCtx(ctx context.Context, NewRemoteHost string, NewExternalPort uint16, NewProtocol string,
		NewInternalPort uint16, NewInternalClient string, NewEnabled bool,
		NewPortMappingDescription string, NewLeaseDuration uint32) error
	DeletePortMappingCtx(ctx context.Context, NewRemoteHost string, NewExternalPort uint16, NewProtocol string) error
}

// DiscoverUPnP 查找支持 UPnP 的网关
func DiscoverUPnP(ctx context.Context) (*UPnPClient, error) {
	dctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	// 优先 WANIPConnection1，其次 PPP
	if cs, _, err := internetgateway2.NewWANIPConnection1ClientsCtx(dctx); err == nil && len(cs) > 0 {
		c := cs[0]
		rt, rc := context.WithCancel(ctx)
		return &UPnPClient{ctx: rt, cancel: rc, wan: &wanIP1{c: c}}, nil
	}
	if cs, _, err := internetgateway2.NewWANPPPConnection1ClientsCtx(dctx); err == nil && len(cs) > 0 {
		c := cs[0]
		rt, rc := context.WithCancel(ctx)
		return &UPnPClient{ctx: rt, cancel: rc, wan: &wanPPP1{c: c}}, nil
	}
	return nil, errors.New("no UPnP gateway found")
}

// RequestMapping 请求 UDP 端口映射（返回公网 host:port）
func (u *UPnPClient) RequestMapping(internalPort uint16, desc string, leaseSecs uint32) (*UPnPMapping, error) {
	ctx, cancel := context.WithTimeout(u.ctx, 5*time.Second)
	defer cancel()
	extIP, err := u.wan.GetExternalIPAddressCtx(ctx)
	if err != nil {
		return nil, fmt.Errorf("get external ip: %w", err)
	}
	internalIP, err := primaryIPv4()
	if err != nil {
		return nil, err
	}
	// 先尝试同端口，冲突则递增
	extPort := internalPort
	for i := 0; i < 5; i++ {
		err := u.wan.AddPortMappingCtx(ctx, "", extPort, "UDP",
			internalPort, internalIP, true, desc, leaseSecs)
		if err == nil {
			m := &UPnPMapping{ExternalIP: extIP, ExternalPort: extPort, InternalPort: internalPort, LeaseSecs: leaseSecs}
			u.mu.Lock()
			u.mapping = m
			u.mu.Unlock()
			go u.renewLoop()
			return m, nil
		}
		extPort++
	}
	return nil, errors.New("add port mapping failed after retries")
}

// renewLoop 提前 ⅔ 租期续约
func (u *UPnPClient) renewLoop() {
	u.mu.Lock()
	m := u.mapping
	u.mu.Unlock()
	if m == nil || m.LeaseSecs == 0 {
		return
	}
	interval := time.Duration(m.LeaseSecs) * 2 / 3 * time.Second
	if interval < 30*time.Second {
		interval = 30 * time.Second
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-u.ctx.Done():
			return
		case <-t.C:
			ctx, cancel := context.WithTimeout(u.ctx, 5*time.Second)
			_ = u.wan.AddPortMappingCtx(ctx, "", m.ExternalPort, "UDP",
				m.InternalPort, func() string { ip, _ := primaryIPv4(); return ip }(), true, "moxian-p2p", m.LeaseSecs)
			cancel()
		}
	}
}

// Close 关闭 + 删除映射
func (u *UPnPClient) Close() {
	u.cancel()
	u.mu.Lock()
	m := u.mapping
	u.mu.Unlock()
	if m == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := u.wan.DeletePortMappingCtx(ctx, "", m.ExternalPort, "UDP"); err != nil {
		log.Printf("[upnp] delete mapping: %v", err)
	}
}

func primaryIPv4() (string, error) {
	// 通过 UDP 连接一个外部地址触发路由决定 不实际发包
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "", err
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).IP.String(), nil
}

// ----- 适配两种 WAN 接口 -----

type wanIP1 struct{ c *internetgateway2.WANIPConnection1 }

func (w *wanIP1) GetExternalIPAddressCtx(ctx context.Context) (string, error) {
	return w.c.GetExternalIPAddressCtx(ctx)
}
func (w *wanIP1) AddPortMappingCtx(ctx context.Context, h string, ep uint16, proto string, ip uint16, ic string, en bool, d string, ls uint32) error {
	return w.c.AddPortMappingCtx(ctx, h, ep, proto, ip, ic, en, d, ls)
}
func (w *wanIP1) DeletePortMappingCtx(ctx context.Context, h string, ep uint16, proto string) error {
	return w.c.DeletePortMappingCtx(ctx, h, ep, proto)
}

type wanPPP1 struct{ c *internetgateway2.WANPPPConnection1 }

func (w *wanPPP1) GetExternalIPAddressCtx(ctx context.Context) (string, error) {
	return w.c.GetExternalIPAddressCtx(ctx)
}
func (w *wanPPP1) AddPortMappingCtx(ctx context.Context, h string, ep uint16, proto string, ip uint16, ic string, en bool, d string, ls uint32) error {
	return w.c.AddPortMappingCtx(ctx, h, ep, proto, ip, ic, en, d, ls)
}
func (w *wanPPP1) DeletePortMappingCtx(ctx context.Context, h string, ep uint16, proto string) error {
	return w.c.DeletePortMappingCtx(ctx, h, ep, proto)
}
