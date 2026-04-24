package client

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/cp12064/moxian-p2p/internal/nat"
	"github.com/cp12064/moxian-p2p/internal/protocol"
	"github.com/cp12064/moxian-p2p/internal/tunnel"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

// Config 客户端配置
type Config struct {
	NodeID     string
	Token      string
	ServerURL  string // ws://host:7788/ws 或 wss://
	ServerUDP  string // host:7789 (同一 host)
	Passphrase string // 对称加密 双方必须一致

	// 主动侧
	Forwards []ForwardRule
	// 被动侧
	AllowTargets []string // 空=允许任意；否则仅允许列表中的 host:port

	// TUN 虚拟局域网
	VirtualIP string // 如 10.88.0.2 或 "auto"
	TunDev    string // Linux: tun_name，Windows: 组件 ID
	TunSubnet string // 路由子网掩码位（默认 24）
	EnableTun bool
	// Android 专用 由 VpnService 传入的已打开 TUN fd
	// 非 0 时 startTun 复用此 fd 不自己创建网卡
	AndroidTunFD int32

	// 节点发现元数据
	Tags        []string
	Description string
	ListOnly    bool // 仅查询一次节点列表后退出

	// UPnP / Mesh
	EnableUPnP bool
	EnableMesh bool

	// NAT 采样次数（越多越准 越慢）
	NatSamples int

	// 统计 HTTP 地址（空=不暴露），如 127.0.0.1:7800
	StatsAddr     string
	StatsInterval time.Duration // 定期日志间隔（0=关闭）

	// ACL：允许主动向这些 peer 发起连接/允许接受这些 peer 的连接
	// 留空=无限制；条目可为 node_id 或 "tag=value"
	AllowPeers []string

	// 限速：客户端总出站字节/秒（0=不限）
	RateLimit int64

	// 跳过 wss TLS 证书校验（自签证书 家用 VPS 常用）
	InsecureTLS bool
}

// ForwardRule 本地端口 -> 远程节点的目标 host:port
type ForwardRule struct {
	Local  string // 0.0.0.0:13389
	Peer   string // 对端 node_id
	Target string // 对端要 dial 的 host:port  127.0.0.1:3389
}

// Client 客户端主控
type Client struct {
	cfg Config
	mux *nat.Mux

	wsMu       sync.Mutex
	ws         *websocket.Conn
	publicAddr string
	relayAddr  string
	natType    string // 自身 NAT 类型

	pending sync.Map // key -> chan ...（发起方等待 peer_info / relay_ready）

	// 路由表 / TUN
	routeMu sync.RWMutex
	routes  map[string]string // virtualIP -> peerNodeID
	tun     *tunRuntime       // 仅在 EnableTun 时赋值 (internal/client/tun*.go)

	listDone chan struct{}

	upnp        *nat.UPnPClient
	upnpMapping *nat.UPnPMapping

	pool  *peerPool
	stats *channelStats
	mesh  *meshManager

	// TUN 启动懒加载 首次 register 收到 AssignedVIP 后再起
	tunOnce       sync.Once
	effectiveVIP  string
}

func New(cfg Config) (*Client, error) {
	if cfg.NodeID == "" {
		return nil, errors.New("node_id required")
	}
	if cfg.Passphrase == "" && !cfg.ListOnly {
		return nil, errors.New("passphrase required (must match both peers)")
	}
	m, err := nat.NewMux(0)
	if err != nil {
		return nil, err
	}
	c := &Client{
		cfg:      cfg,
		mux:      m,
		routes:   make(map[string]string),
		listDone: make(chan struct{}, 1),
		stats:    newChannelStats(),
	}
	c.pool = newPeerPool(c)
	if cfg.EnableMesh {
		c.mesh = newMeshManager(c)
	}
	if cfg.RateLimit > 0 {
		m.SetEgressLimit(cfg.RateLimit)
	}
	return c, nil
}

// Run 阻塞运行（进程生命周期）
func (c *Client) Run(ctx context.Context) error {
	defer c.mux.Close()
	defer func() {
		if c.upnp != nil {
			c.upnp.Close()
		}
	}()

	// STUN 探测公网地址
	pub, err := c.mux.Stun(c.cfg.ServerUDP, 5*time.Second)
	if err != nil {
		return fmt.Errorf("stun: %w", err)
	}
	c.publicAddr = pub.String()
	log.Printf("[client] public udp addr = %s (local port=%d)", c.publicAddr, c.mux.LocalPort())

	// UPnP 尝试（一次性 失败不阻塞）
	if c.cfg.EnableUPnP {
		if upClient, err := nat.DiscoverUPnP(ctx); err == nil {
			c.upnp = upClient
			if m, err := upClient.RequestMapping(uint16(c.mux.LocalPort()), "moxian-p2p", 1800); err == nil {
				c.upnpMapping = m
				log.Printf("[upnp] mapped %d -> %s", m.InternalPort, m.Endpoint())
			} else {
				log.Printf("[upnp] mapping failed: %v", err)
			}
		} else {
			log.Printf("[upnp] discovery failed: %v", err)
		}
	}

	if !c.cfg.ListOnly {
		// 独立于 WS 的长期任务
		if c.cfg.StatsAddr != "" {
			c.startStatsServer(c.cfg.StatsAddr)
		}
		if c.cfg.StatsInterval > 0 {
			go c.startStatsLogger(ctx, c.cfg.StatsInterval)
		}
		// TUN 启动挪到首次注册成功后（见 runOneSession）
		// 因为 AssignedVIP 要等 server 分配才知道
		for _, r := range c.cfg.Forwards {
			rule := r
			go c.runForward(ctx, rule)
		}
	}

	// 进入信令重连循环
	return c.wsConnectLoop(ctx)
}

// wsConnectLoop 维持 WS 连接 断开指数退避重连
func (c *Client) wsConnectLoop(ctx context.Context) error {
	backoff := time.Second
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		err := c.runOneSession(ctx)
		if c.cfg.ListOnly {
			return err
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		log.Printf("[ws] disconnected: %v (retry in %v)", err, backoff)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(backoff):
		}
		if backoff < 60*time.Second {
			backoff *= 2
		}
	}
}

// wsDialer 构造 ws Dialer 若启用 InsecureTLS 则跳过证书校验
func (c *Client) wsDialer() *websocket.Dialer {
	d := *websocket.DefaultDialer
	if c.cfg.InsecureTLS {
		d.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	}
	return &d
}

// runOneSession 完成一次 连接→注册→NAT 采样→读循环
func (c *Client) runOneSession(ctx context.Context) error {
	u, err := url.Parse(c.cfg.ServerURL)
	if err != nil {
		return err
	}
	ws, _, err := c.wsDialer().DialContext(ctx, u.String(), nil)
	if err != nil {
		return fmt.Errorf("ws dial: %w", err)
	}
	defer ws.Close()

	c.wsMu.Lock()
	c.ws = ws
	c.wsMu.Unlock()
	defer func() {
		c.wsMu.Lock()
		c.ws = nil
		c.wsMu.Unlock()
	}()

	// 候选地址：STUN 公网[0] > UPnP > 内网
	localAddrs := []string{c.publicAddr}
	if c.upnpMapping != nil {
		localAddrs = append(localAddrs, c.upnpMapping.Endpoint())
	}
	localAddrs = append(localAddrs, collectLocalAddrs(c.mux.LocalPort())...)

	regMsg, _ := protocol.Pack(protocol.TypeRegister, "", protocol.Register{
		NodeID:       c.cfg.NodeID,
		Token:        c.cfg.Token,
		Version:      "0.5.0",
		LocalUDPPort: c.mux.LocalPort(),
		LocalAddrs:   localAddrs,
		VirtualIP:    c.cfg.VirtualIP,
		Tags:         c.cfg.Tags,
		Description:  c.cfg.Description,
		AllowPeers:   c.cfg.AllowPeers,
	})
	if err := c.wsWrite(regMsg); err != nil {
		return err
	}
	_, raw, err := ws.ReadMessage()
	if err != nil {
		return err
	}
	var ack protocol.RegisterAck
	env, err := protocol.Unpack(raw, &ack)
	if err != nil {
		return err
	}
	if env.Type != protocol.TypeRegisterAck {
		return fmt.Errorf("unexpected first response: %s", env.Type)
	}
	c.relayAddr = ack.RelayUDP
	log.Printf("[client] registered id=%s public=%s relay=%s", ack.NodeID, ack.PublicAddr, ack.RelayUDP)

	// vIP 决议：优先 server 分配结果 否则用本地 yaml 值（兼容老 server）
	if ack.AssignedVIP != "" {
		c.effectiveVIP = ack.AssignedVIP
	} else if c.cfg.VirtualIP != "" && c.cfg.VirtualIP != "auto" {
		c.effectiveVIP = c.cfg.VirtualIP
	}
	if c.effectiveVIP != "" && c.effectiveVIP != c.cfg.VirtualIP {
		log.Printf("[client] assigned vip = %s", c.effectiveVIP)
	}

	// 懒加载 TUN：只有注册后知道了真实 vIP 才能起网卡
	if c.cfg.EnableTun && c.effectiveVIP != "" {
		c.tunOnce.Do(func() {
			if err := c.startTun(ctx, c.effectiveVIP); err != nil {
				log.Printf("[tun] start failed: %v", err)
			}
		})
	}

	// NAT 采样（重连时每次都做，保持新鲜）
	samples := []string{c.publicAddr}
	for _, ep := range append([]string{ack.Stun2UDP}, ack.StunExtras...) {
		if ep == "" {
			continue
		}
		if pub, err := c.mux.Stun(ep, 3*time.Second); err == nil {
			samples = append(samples, pub.String())
		}
	}
	natType := "cone"
	for _, s := range samples[1:] {
		if s != samples[0] {
			natType = "symmetric"
			break
		}
	}
	c.natType = natType
	pub2 := ""
	if len(samples) > 1 {
		pub2 = samples[1]
	}
	log.Printf("[client] nat_type=%s samples=%d", natType, len(samples))
	nu, _ := protocol.Pack(protocol.TypeNatUpdate, "", protocol.NatUpdate{
		NatType: natType, PublicAddr2: pub2, Samples: samples,
	})
	_ = c.wsWrite(nu)

	// -list 模式：等首次 NetworkUpdate 后返回
	if c.cfg.ListOnly {
		errCh := make(chan error, 1)
		go func() { errCh <- c.readLoop(ctx) }()
		select {
		case <-c.listDone:
			return nil
		case <-time.After(5 * time.Second):
			log.Printf("[client] list: no network update in 5s")
			return nil
		case err := <-errCh:
			return err
		case <-ctx.Done():
			return ctx.Err()
		}
	}

	return c.readLoop(ctx)
}

// PeekVIP 做一次短连接 STUN+register 取回 server 分配的 vIP 然后立刻断开
// 用于 Android 建 VpnService.Builder 前预先知道 vIP
// 注意: 调用后 client 的 UDP mux 已关闭 不可再使用
func (c *Client) PeekVIP(ctx context.Context) (string, error) {
	defer c.mux.Close()

	pub, err := c.mux.Stun(c.cfg.ServerUDP, 5*time.Second)
	if err != nil {
		return "", fmt.Errorf("stun: %w", err)
	}
	c.publicAddr = pub.String()

	u, err := url.Parse(c.cfg.ServerURL)
	if err != nil {
		return "", err
	}
	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	ws, _, err := c.wsDialer().DialContext(dialCtx, u.String(), nil)
	if err != nil {
		return "", fmt.Errorf("ws dial: %w", err)
	}
	defer ws.Close()

	localAddrs := []string{c.publicAddr}
	localAddrs = append(localAddrs, collectLocalAddrs(c.mux.LocalPort())...)
	reg := protocol.Register{
		NodeID:       c.cfg.NodeID,
		Token:        c.cfg.Token,
		Version:      "probe",
		LocalUDPPort: c.mux.LocalPort(),
		LocalAddrs:   localAddrs,
		VirtualIP:    c.cfg.VirtualIP,
		Tags:         c.cfg.Tags,
		Description:  c.cfg.Description,
	}
	regMsg, _ := protocol.Pack(protocol.TypeRegister, "", reg)
	_ = ws.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if err := ws.WriteMessage(websocket.TextMessage, regMsg); err != nil {
		return "", err
	}
	_ = ws.SetReadDeadline(time.Now().Add(10 * time.Second))
	_, raw, err := ws.ReadMessage()
	if err != nil {
		return "", err
	}
	var ack protocol.RegisterAck
	env, err := protocol.Unpack(raw, &ack)
	if err != nil {
		return "", err
	}
	if env.Type == protocol.TypeError {
		var emsg protocol.ErrorMsg
		_ = json.Unmarshal(env.Payload, &emsg)
		return "", fmt.Errorf("server rejected: %s %s", emsg.Code, emsg.Message)
	}
	if env.Type != protocol.TypeRegisterAck {
		return "", fmt.Errorf("unexpected response type: %s", env.Type)
	}
	if ack.AssignedVIP == "" {
		return "", errors.New("server did not assign vip (server 未配 virtual_subnet?)")
	}
	return ack.AssignedVIP, nil
}

func (c *Client) readLoop(ctx context.Context) error {
	for {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		_, raw, err := c.ws.ReadMessage()
		if err != nil {
			return err
		}
		var env protocol.Envelope
		if err := json.Unmarshal(raw, &env); err != nil {
			continue
		}
		switch env.Type {
		case protocol.TypePing:
			pong, _ := protocol.Pack(protocol.TypePong, env.ReqID, map[string]int64{"t": time.Now().Unix()})
			_ = c.wsWrite(pong)
		case protocol.TypePeerInfo:
			var pi protocol.PeerInfo
			if err := json.Unmarshal(env.Payload, &pi); err != nil {
				continue
			}
			go c.handlePeerInfo(ctx, pi, env.ReqID)
		case protocol.TypeNetworkUpdate:
			var nu protocol.NetworkUpdate
			if err := json.Unmarshal(env.Payload, &nu); err != nil {
				continue
			}
			c.applyNetworkUpdate(nu)
		case protocol.TypeRelayReady:
			var rr protocol.RelayReady
			if err := json.Unmarshal(env.Payload, &rr); err != nil {
				continue
			}
			if ch, ok := c.pending.Load("relay:" + rr.SessionID); ok {
				select {
				case ch.(chan protocol.RelayReady) <- rr:
				default:
				}
			}
		case protocol.TypeError:
			var e protocol.ErrorMsg
			_ = json.Unmarshal(env.Payload, &e)
			log.Printf("[client] server error: %s - %s (req=%s)", e.Code, e.Message, env.ReqID)
			if ch, ok := c.pending.Load("req:" + env.ReqID); ok {
				close(ch.(chan protocol.PeerInfo))
				c.pending.Delete("req:" + env.ReqID)
			}
		}
	}
}

// ----- 主动侧 -----

func (c *Client) runForward(ctx context.Context, rule ForwardRule) {
	for {
		if err := c.forwardOnce(ctx, rule); err != nil {
			log.Printf("[forward %s->%s] %v, retry in 5s", rule.Local, rule.Peer, err)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}
}

func (c *Client) forwardOnce(ctx context.Context, rule ForwardRule) error {
	// 发起 connect
	reqID := uuid.NewString()
	piCh := make(chan protocol.PeerInfo, 1)
	c.pending.Store("req:"+reqID, piCh)
	defer c.pending.Delete("req:" + reqID)

	connectMsg, _ := protocol.Pack(protocol.TypeConnect, reqID, protocol.Connect{
		PeerID:  rule.Peer,
		Purpose: "tcp-forward",
	})
	if err := c.wsWrite(connectMsg); err != nil {
		return err
	}

	var pi protocol.PeerInfo
	select {
	case <-ctx.Done():
		return ctx.Err()
	case pi0, ok := <-piCh:
		if !ok {
			return errors.New("peer unreachable (maybe offline)")
		}
		pi = pi0
	case <-time.After(10 * time.Second):
		return errors.New("peer_info timeout")
	}
	// 把 piCh 注册到 sid 上 在 handlePeerInfo 里发（其实 handlePeerInfo 只用于被动侧，主动侧 我们直接在这 dispatch）
	// 注意：服务端回给发起方的 peer_info 也会进 readLoop，我们用 req:reqID 路由
	// 但 readLoop 的 peer_info 分支目前直接 handlePeerInfo 了 需要改成先查 req 路由

	ch, ok, err := c.establishChannel(ctx, pi, "initiator")
	if err != nil {
		return err
	}
	_ = ok
	defer ch.Close()
	defer c.stats.Remove(pi.SessionID)

	sess, err := tunnel.DialInitiator(ch, c.cfg.Passphrase, pi.SessionID)
	if err != nil {
		return fmt.Errorf("smux client: %w", err)
	}
	defer sess.Close()
	log.Printf("[forward] session=%s established mode=%d", pi.SessionID, ch.Mode())
	return tunnel.RunLocalForward(rule.Local, rule.Target, sess)
}

// ----- 被动侧 -----

func (c *Client) handlePeerInfo(ctx context.Context, pi protocol.PeerInfo, reqID string) {
	// 优先路由给主动侧等待者
	if reqID != "" {
		if v, ok := c.pending.Load("req:" + reqID); ok {
			v.(chan protocol.PeerInfo) <- pi
			return
		}
	}
	// 否则作为被动侧处理
	ch, _, err := c.establishChannel(ctx, pi, "responder")
	if err != nil {
		log.Printf("[responder] session=%s establish: %v", pi.SessionID, err)
		return
	}
	defer ch.Close()
	defer c.stats.Remove(pi.SessionID)
	sess, err := tunnel.ServeResponder(ch, c.cfg.Passphrase, pi.SessionID)
	if err != nil {
		log.Printf("[responder] smux server: %v", err)
		return
	}
	defer sess.Close()
	log.Printf("[responder] session=%s established mode=%d", pi.SessionID, ch.Mode())
	// 关键：把 session 注册到 pool 本端发 TUN 回包时复用（反向 OpenStream）
	c.pool.RegisterInbound(pi.PeerID, sess)
	defer c.pool.Remove(pi.PeerID)
	tunnel.ServeIncoming(sess, tunnel.StreamHandlers{
		OnDial: tunnel.BuildDialHandler(c.buildAllow()),
		OnTun:  c.handleIncomingTunStream,
	})
}

// establishChannel 打洞 / 中继建立 Channel
func (c *Client) establishChannel(ctx context.Context, pi protocol.PeerInfo, role string) (*nat.Channel, bool, error) {
	ch := nat.NewChannel(c.mux, pi.SessionID)
	c.stats.Add(ch, pi.PeerID)
	candidates := append([]string{pi.PublicAddr}, pi.LocalAddrs...)
	// 对端为对称 NAT 时追加端口预测候选
	if pi.NatType == "symmetric" {
		samples := pi.NatSamples
		if len(samples) == 0 && pi.PublicAddr2 != "" {
			samples = []string{pi.PublicAddr, pi.PublicAddr2}
		}
		candidates = append(candidates, predictPorts(samples, 30)...)
	}
	punchCtx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()
	res, err := nat.Punch(punchCtx, c.mux, pi.SessionID, candidates, 5*time.Second)
	if err == nil {
		ch.SetDirect(res.Addr)
		return ch, true, nil
	}
	log.Printf("[punch] session=%s failed (%v), fallback relay", pi.SessionID, err)
	// 中继兜底
	relayCh := make(chan protocol.RelayReady, 1)
	c.pending.Store("relay:"+pi.SessionID, relayCh)
	defer c.pending.Delete("relay:" + pi.SessionID)

	openMsg, _ := protocol.Pack(protocol.TypeRelayOpen, "", protocol.RelayOpen{
		PeerID:    pi.PeerID,
		SessionID: pi.SessionID,
	})
	if err := c.wsWrite(openMsg); err != nil {
		ch.Close()
		return nil, false, err
	}
	var rr protocol.RelayReady
	select {
	case <-ctx.Done():
		ch.Close()
		return nil, false, ctx.Err()
	case rr = <-relayCh:
	case <-time.After(5 * time.Second):
		ch.Close()
		return nil, false, errors.New("relay ready timeout")
	}
	relayUDP, err := net.ResolveUDPAddr("udp", rr.RelayAddr)
	if err != nil {
		ch.Close()
		return nil, false, err
	}
	if err := c.mux.RelayBind(rr.RelayAddr, rr.SessionID, rr.Token, 5*time.Second); err != nil {
		ch.Close()
		return nil, false, err
	}
	ch.SetRelay(relayUDP)
	return ch, false, nil
}

func (c *Client) buildAllow() func(string) bool {
	if len(c.cfg.AllowTargets) == 0 {
		return nil
	}
	set := make(map[string]struct{}, len(c.cfg.AllowTargets))
	for _, t := range c.cfg.AllowTargets {
		set[t] = struct{}{}
	}
	return func(target string) bool {
		_, ok := set[target]
		return ok
	}
}

// wsWrite 带互斥的 WS 写（未连接返回错误 调用方可重试）
func (c *Client) wsWrite(data []byte) error {
	c.wsMu.Lock()
	defer c.wsMu.Unlock()
	if c.ws == nil {
		return errors.New("signaling not connected")
	}
	return c.ws.WriteMessage(websocket.TextMessage, data)
}

// applyNetworkUpdate 收到节点列表 更新路由表 + 打印发现信息
func (c *Client) applyNetworkUpdate(nu protocol.NetworkUpdate) {
	c.routeMu.Lock()
	c.routes = make(map[string]string, len(nu.Nodes))
	for _, n := range nu.Nodes {
		if n.NodeID == c.cfg.NodeID {
			continue
		}
		if n.VirtualIP != "" {
			c.routes[n.VirtualIP] = n.NodeID
		}
	}
	c.routeMu.Unlock()

	// 打印发现列表
	log.Printf("[discovery] %d node(s) online:", len(nu.Nodes))
	for _, n := range nu.Nodes {
		marker := " "
		if n.NodeID == c.cfg.NodeID {
			marker = "*"
		}
		vip := n.VirtualIP
		if vip == "" {
			vip = "-"
		}
		nat := n.NatType
		if nat == "" {
			nat = "?"
		}
		tags := ""
		if len(n.Tags) > 0 {
			tags = " [" + strings.Join(n.Tags, ",") + "]"
		}
		desc := ""
		if n.Description != "" {
			desc = " " + n.Description
		}
		log.Printf("[discovery]   %s %-16s vip=%-14s nat=%-9s%s%s", marker, n.NodeID, vip, nat, tags, desc)
	}

	// mesh 自动连接
	if c.mesh != nil {
		c.mesh.onNetworkUpdate(nu.Nodes)
	}

	// -list 模式：收到首次列表就退出
	if c.cfg.ListOnly {
		select {
		case c.listDone <- struct{}{}:
		default:
		}
	}
}

// lookupPeerByVIP 根据虚拟 IP 查对端 node_id
func (c *Client) lookupPeerByVIP(vip string) string {
	c.routeMu.RLock()
	defer c.routeMu.RUnlock()
	return c.routes[vip]
}

// predictPorts 根据 NAT 样本列表生成端口候选
// samples[0] 是主 STUN 观察地址（host:port）
// 多个样本可以分析步长分布：
//   - 单一步长稳定 → 线性步进预测
//   - 步长抖动但有界 → 在 [min, max] 范围内密集扫描
//   - 样本 <2 → 回退到单端口
func predictPorts(samples []string, count int) []string {
	if len(samples) == 0 {
		return nil
	}
	host, base, err := net.SplitHostPort(samples[0])
	if err != nil {
		return nil
	}
	var basePort int
	_, _ = fmt.Sscanf(base, "%d", &basePort)
	if basePort == 0 {
		return nil
	}
	// 收集所有端口
	ports := []int{basePort}
	for _, s := range samples[1:] {
		_, p, err := net.SplitHostPort(s)
		if err != nil {
			continue
		}
		var pn int
		_, _ = fmt.Sscanf(p, "%d", &pn)
		if pn > 0 {
			ports = append(ports, pn)
		}
	}
	if len(ports) < 2 {
		return nil
	}
	// 相邻端口差
	strides := make([]int, 0, len(ports)-1)
	for i := 1; i < len(ports); i++ {
		d := ports[i] - ports[i-1]
		if d < 0 {
			d = -d
		}
		if d > 0 {
			strides = append(strides, d)
		}
	}
	if len(strides) == 0 {
		return nil
	}
	// 统计 min/max stride + 均值
	minS, maxS := strides[0], strides[0]
	sum := 0
	for _, s := range strides {
		if s < minS {
			minS = s
		}
		if s > maxS {
			maxS = s
		}
		sum += s
	}
	avg := sum / len(strides)
	out := make([]string, 0, count*2)
	// 线性预测：以平均步长向两侧
	for i := 1; i <= count; i++ {
		if p := basePort + i*avg; p > 0 && p < 65536 {
			out = append(out, fmt.Sprintf("%s:%d", host, p))
		}
		if p := basePort - i*avg; p > 0 && p < 65536 {
			out = append(out, fmt.Sprintf("%s:%d", host, p))
		}
	}
	// 当 stride 抖动大时 额外在 [minS, maxS] 区间密集扫
	if maxS-minS >= 5 && maxS < 300 {
		for step := minS; step <= maxS; step++ {
			if p := basePort + step; p > 0 && p < 65536 {
				out = append(out, fmt.Sprintf("%s:%d", host, p))
			}
			if p := basePort - step; p > 0 && p < 65536 {
				out = append(out, fmt.Sprintf("%s:%d", host, p))
			}
		}
	}
	return dedupStrings(out)
}

func dedupStrings(in []string) []string {
	seen := make(map[string]struct{}, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		if _, ok := seen[s]; ok {
			continue
		}
		seen[s] = struct{}{}
		out = append(out, s)
	}
	return out
}

// collectLocalAddrs 收集本机内网候选地址
func collectLocalAddrs(port int) []string {
	ifaces, err := net.InterfaceAddrs()
	if err != nil {
		return nil
	}
	out := make([]string, 0, 4)
	for _, a := range ifaces {
		var ip net.IP
		switch v := a.(type) {
		case *net.IPNet:
			ip = v.IP
		case *net.IPAddr:
			ip = v.IP
		}
		if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			continue
		}
		ip4 := ip.To4()
		if ip4 == nil {
			continue
		}
		out = append(out, fmt.Sprintf("%s:%d", ip4.String(), port))
	}
	return out
}
