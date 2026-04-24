package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/cp12064/moxian-p2p/internal/client"
	"github.com/cp12064/moxian-p2p/internal/debug"
)

type forwardFlag []client.ForwardRule

func (f *forwardFlag) String() string { return fmt.Sprint(*f) }
func (f *forwardFlag) Set(v string) error {
	// 格式 LOCAL=PEER=TARGET
	parts := strings.SplitN(v, "=", 3)
	if len(parts) != 3 {
		return fmt.Errorf("bad forward rule %q (expected LOCAL=PEER=TARGET)", v)
	}
	*f = append(*f, client.ForwardRule{Local: parts[0], Peer: parts[1], Target: parts[2]})
	return nil
}

func main() {
	var (
		nodeID     = flag.String("id", "", "本节点 ID")
		server     = flag.String("server", "", "信令 WS 地址 例 ws://host:7788/ws")
		udpAddr    = flag.String("udp", "", "服务器 UDP 地址 例 host:7789")
		pass       = flag.String("pass", "", "会话对称加密口令（双方必须一致）")
		token      = flag.String("token", "", "服务器认证 token（可选）")
		allowStr   = flag.String("allow", "", "被动侧允许的 dial 目标列表 逗号分隔；留空=任意")
		virtualIP = flag.String("vip", "", "虚拟局域网 IP (如 10.88.0.2) 开启则启用 TUN")
		tunSubnet = flag.String("vnet", "24", "虚拟局域网子网位数")
		tunDev    = flag.String("tun-dev", "", "TUN 设备名（Linux 留空自动）")
		tagsStr   = flag.String("tags", "", "节点标签 逗号分隔 例 group=home,role=server")
		desc      = flag.String("desc", "", "节点描述（展示用）")
		list      = flag.Bool("list", false, "仅查询在线节点列表后退出")
		upnp      = flag.Bool("upnp", false, "启用 UPnP 自动开端口（家用路由器辅助打洞）")
		mesh      = flag.Bool("mesh", false, "启用 Mesh 自动组网（与所有 peer 建立 P2P 隧道）")
		statsHttp  = flag.String("stats-http", "", "暴露 HTTP /stats 接口的监听地址 例 127.0.0.1:7800")
		statsLog   = flag.Duration("stats-log", 0, "定期打印流量统计 例 30s 默认关闭")
		verbose    = flag.Bool("v", false, "打印 TUN/tunnel 追踪日志 排障用")
		allowPeers = flag.String("allow-peers", "", "ACL：允许互连的 peer 列表 逗号分隔 可为 node_id 或 tag=val；空=无限制")
		rateLimit  = flag.String("rate-limit", "", "客户端总出站限速 例 10MB / 1.5MB（空=不限）")
		configFile = flag.String("config", "", "YAML 配置文件路径（CLI flag 优先）")
		// v2 登录模式
		loginSrv   = flag.String("login", "", "v2 模式：moxian-server HTTPS 地址 例 https://vps.example.com:7788")
		loginEmail = flag.String("email", "", "v2 模式：登录邮箱")
		loginPwd   = flag.String("password", "", "v2 模式：主密码（强烈建议用环境变量 MOXIAN_PASSWORD）")
		insecure   = flag.Bool("insecure-tls", false, "v2 模式：跳过 TLS 证书验证（自签证书用）")
		forwards   forwardFlag
	)
	flag.Var(&forwards, "forward", "主动侧端口映射 LOCAL=PEER=TARGET 可多次")
	flag.Parse()

	// v2 模式：用 -login + -email + -password 登录自动拉配置
	if *loginSrv != "" {
		pwd := *loginPwd
		if pwd == "" {
			pwd = os.Getenv("MOXIAN_PASSWORD")
		}
		if *loginEmail == "" || pwd == "" {
			log.Fatal("v2 模式需要 -email 和 -password（或 MOXIAN_PASSWORD 环境变量）")
		}
		ac := client.NewAuthClient(*loginSrv, *insecure)
		if _, err := ac.Login(*loginEmail, pwd); err != nil {
			log.Fatalf("v2 登录失败: %v", err)
		}
		nodeForCfg := *nodeID
		if nodeForCfg == "" {
			nodeForCfg = "cli-" + hostnameOrRandom()
		}
		cfg2, err := ac.FetchConfig(nodeForCfg)
		if err != nil {
			log.Fatalf("v2 拉配置失败: %v", err)
		}
		// 填入 CLI flag（尊重用户已显式指定的值）
		if *nodeID == "" {
			*nodeID = cfg2.NodeID
		}
		if *server == "" {
			*server = cfg2.ServerWS
		}
		if *udpAddr == "" {
			*udpAddr = cfg2.ServerUDP
		}
		if *pass == "" {
			*pass = cfg2.Pass
		}
		if *virtualIP == "" {
			*virtualIP = cfg2.VirtualIP
		}
		if !*mesh {
			*mesh = cfg2.Mesh
		}
		log.Printf("[v2] 已从 server 获取配置 node=%s vip=%s peers=%v", cfg2.NodeID, cfg2.VirtualIP, cfg2.AllowPeers)
	}

	debug.Enable(*verbose)

	cfg := client.Config{
		NodeID:        *nodeID,
		ServerURL:     *server,
		ServerUDP:     *udpAddr,
		Token:         *token,
		Passphrase:    *pass,
		Forwards:      forwards,
		VirtualIP:     *virtualIP,
		TunSubnet:     *tunSubnet,
		TunDev:        *tunDev,
		EnableTun:     *virtualIP != "",
		Description:   *desc,
		ListOnly:      *list,
		EnableUPnP:    *upnp,
		EnableMesh:    *mesh,
		StatsAddr:     *statsHttp,
		StatsInterval: *statsLog,
	}
	if *allowStr != "" {
		cfg.AllowTargets = strings.Split(*allowStr, ",")
	}
	if *tagsStr != "" {
		cfg.Tags = strings.Split(*tagsStr, ",")
	}
	if *allowPeers != "" {
		cfg.AllowPeers = strings.Split(*allowPeers, ",")
	}
	if *rateLimit != "" {
		v, err := parseSizeCLI(*rateLimit)
		if err != nil {
			log.Fatalf("parse -rate-limit: %v", err)
		}
		cfg.RateLimit = v
	}

	// 配置文件（CLI 字段空时填充）
	if *configFile != "" {
		fc, err := client.LoadFile(*configFile)
		if err != nil {
			log.Fatalf("load config: %v", err)
		}
		fc.ApplyTo(&cfg)
	}

	if cfg.NodeID == "" || cfg.ServerURL == "" || cfg.ServerUDP == "" {
		flag.Usage()
		log.Fatal("id/server/udp 必填（命令行或配置文件）")
	}
	if !cfg.ListOnly && cfg.Passphrase == "" {
		flag.Usage()
		log.Fatal("pass 必填（除 -list 模式外）")
	}

	c, err := client.New(cfg)
	if err != nil {
		log.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigCh
		log.Println("shutdown...")
		cancel()
	}()

	if err := c.Run(ctx); err != nil && ctx.Err() == nil {
		log.Fatal(err)
	}
}

func hostnameOrRandom() string {
	h, err := os.Hostname()
	if err == nil && h != "" {
		return strings.ReplaceAll(h, ".", "-")
	}
	return fmt.Sprintf("cli-%d", os.Getpid())
}

// parseSizeCLI 与 client.parseSize 同逻辑 由于在 main 包使用 单独实现
func parseSizeCLI(s string) (int64, error) {
	s = strings.TrimSpace(strings.ToUpper(s))
	mult := int64(1)
	switch {
	case strings.HasSuffix(s, "KB"):
		mult = 1024
		s = strings.TrimSuffix(s, "KB")
	case strings.HasSuffix(s, "MB"):
		mult = 1024 * 1024
		s = strings.TrimSuffix(s, "MB")
	case strings.HasSuffix(s, "GB"):
		mult = 1024 * 1024 * 1024
		s = strings.TrimSuffix(s, "GB")
	case strings.HasSuffix(s, "B"):
		s = strings.TrimSuffix(s, "B")
	}
	var f float64
	if _, err := fmt.Sscanf(strings.TrimSpace(s), "%f", &f); err != nil {
		return 0, err
	}
	return int64(f * float64(mult)), nil
}
