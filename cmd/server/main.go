package main

import (
	"crypto/rand"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/cp12064/moxian-p2p/internal/server"
)

func main() {
	wsAddr := flag.String("ws", ":7788", "WebSocket 信令监听地址")
	udpAddr := flag.String("udp", ":7789", "UDP STUN/中继 监听地址")
	udp2Addr := flag.String("udp2", ":7790", "第二个 UDP STUN 端口（用于 NAT 类型检测）")
	stunExtra := flag.String("stun-extra", "", "额外 STUN 端口列表 例 :7791,:7792,:7793（用于非线性端口预测 留空=关闭）")
	publicHost := flag.String("host", "", "对外公网域名或IP（客户端访问中继时使用）")
	token := flag.String("token", "", "全局认证 token（可选）")
	tlsCert := flag.String("tls-cert", "", "TLS 证书文件路径（开启则启用 wss）")
	tlsKey := flag.String("tls-key", "", "TLS 私钥文件路径")
	adminUser := flag.String("admin-user", "", "Web 管理面板 Basic Auth 用户名（留空=关闭面板）")
	adminPass := flag.String("admin-pass", "", "Web 管理面板密码")
	relayLimit := flag.String("relay-limit", "", "中继每 session 限速 例 10MB/s（空=不限）")
	virtualSubnet := flag.String("virtual-subnet", "", "虚拟网 CIDR 例 10.88.0.0/24（留空=关闭 vIP 自动分配）")
	vipStore := flag.String("vip-store", "", "vIP 分配持久化文件 例 /var/lib/moxian/vip.json")
	configFile := flag.String("config", "", "YAML 配置文件路径（CLI flag 优先）")
	// ---- v2 新增 ----
	dbPath := flag.String("db", "moxian.db", "SQLite 数据库路径")
	releaseDir := flag.String("release-dir", "", "APK release 文件目录（留空=db 同级目录下 releases/）")
	releaseCIToken := flag.String("release-ci-token", "", "CI 流水线上传 APK 用的 token（留空=禁用 ci-upload 端点）也可用环境变量 MOXIAN_RELEASE_CI_TOKEN")
	jwtSecret := flag.String("jwt-secret", "", "JWT 签名密钥（32+ 字节 留空则随机生成 重启后所有 token 失效）")
	jwtTTL := flag.Duration("jwt-ttl", 24*time.Hour, "JWT 有效期")
	flag.Parse()

	// 合并配置文件
	if *configFile != "" {
		fc, err := server.LoadFile(*configFile)
		if err != nil {
			log.Fatalf("load config: %v", err)
		}
		if *publicHost == "" {
			*publicHost = fc.Host
		}
		if *wsAddr == ":7788" && fc.WS != "" {
			*wsAddr = fc.WS
		}
		if *udpAddr == ":7789" && fc.UDP != "" {
			*udpAddr = fc.UDP
		}
		if *udp2Addr == ":7790" && fc.UDP2 != "" {
			*udp2Addr = fc.UDP2
		}
		if *stunExtra == "" && len(fc.StunExtras) > 0 {
			*stunExtra = strings.Join(fc.StunExtras, ",")
		}
		if *token == "" {
			*token = fc.Token
		}
		if *tlsCert == "" {
			*tlsCert = fc.TLSCert
		}
		if *tlsKey == "" {
			*tlsKey = fc.TLSKey
		}
		if *adminUser == "" {
			*adminUser = fc.AdminUser
		}
		if *adminPass == "" {
			*adminPass = fc.AdminPass
		}
		if *relayLimit == "" {
			*relayLimit = fc.RelayLimit
		}
		if *virtualSubnet == "" {
			*virtualSubnet = fc.VirtualSubnet
		}
		if *vipStore == "" {
			*vipStore = fc.VIPStore
		}
	}

	if *publicHost == "" {
		log.Fatal("-host 必填，例如 -host=your.vps.com 或公网 IP")
	}

	udp, err := server.NewUDPServer(*udpAddr)
	if err != nil {
		log.Fatalf("udp listen: %v", err)
	}
	if *relayLimit != "" {
		bps, err := server.ParseSize(*relayLimit)
		if err != nil {
			log.Fatalf("parse -relay-limit: %v", err)
		}
		udp.SetSessionRelayLimit(bps)
		log.Printf("[udp] relay per-session limit: %d bytes/s", bps)
	}
	go udp.Run()
	log.Printf("[udp] listen %s (port=%d public=%s)", *udpAddr, udp.Port(), udp.PublicEndpoint(*publicHost))

	stun2, err := server.NewStunOnly(*udp2Addr)
	if err != nil {
		log.Fatalf("udp2 listen: %v", err)
	}
	go stun2.Run()
	log.Printf("[stun2] listen %s (port=%d)", *udp2Addr, stun2.Port())

	var extraPorts []int
	if *stunExtra != "" {
		for _, e := range strings.Split(*stunExtra, ",") {
			e = strings.TrimSpace(e)
			if e == "" {
				continue
			}
			s, err := server.NewStunOnly(e)
			if err != nil {
				log.Fatalf("stun-extra %s: %v", e, err)
			}
			go s.Run()
			extraPorts = append(extraPorts, s.Port())
			log.Printf("[stun-extra] listen %s (port=%d)", e, s.Port())
		}
	}

	var allocator *server.VIPAllocator
	if *virtualSubnet != "" {
		a, err := server.NewVIPAllocator(*virtualSubnet, *vipStore)
		if err != nil {
			log.Fatalf("vip allocator: %v", err)
		}
		allocator = a
		store := *vipStore
		if store == "" {
			store = "(memory)"
		}
		log.Printf("[vip] allocator enabled subnet=%s store=%s", *virtualSubnet, store)
	}

	sig := &server.Signaling{
		Hub:        server.NewHub(),
		Relay:      udp,
		PublicHost: *publicHost,
		AuthToken:  *token,
		Stun2Port:  stun2.Port(),
		StunExtras: extraPorts,
		Allocator:  allocator,
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", sig.Handle)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	})
	// ---- v2 API 初始化 ----
	db, err := server.OpenDB(*dbPath)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	defer db.Close()

	secret := *jwtSecret
	if secret == "" {
		b := make([]byte, 32)
		rand.Read(b)
		secret = hex.EncodeToString(b)
		log.Printf("[jwt] 未提供 -jwt-secret 随机生成（重启后所有登录会失效）")
	}
	jwtMgr := server.NewJWTManager(secret, *jwtTTL)

	// v2 API 注册
	publicWS := fmt.Sprintf("ws://%s%s/ws", *publicHost, *wsAddr)
	if *tlsCert != "" {
		publicWS = fmt.Sprintf("wss://%s%s/ws", *publicHost, *wsAddr)
	}
	publicUDP := fmt.Sprintf("%s%s", *publicHost, *udpAddr)

	(&server.AuthAPI{DB: db, JWT: jwtMgr}).Register(mux)
	(&server.VaultAPI{DB: db, JWT: jwtMgr}).Register(mux)
	(&server.ConfigAPI{DB: db, JWT: jwtMgr, ServerWS: publicWS, ServerUDP: publicUDP}).Register(mux)
	(&server.AdminAPI{DB: db, JWT: jwtMgr}).Register(mux)

	relDir := *releaseDir
	if relDir == "" {
		relDir = filepath.Join(filepath.Dir(*dbPath), "releases")
	}
	ciTok := *releaseCIToken
	if ciTok == "" {
		ciTok = os.Getenv("MOXIAN_RELEASE_CI_TOKEN")
	}
	(&server.ReleaseAPI{JWT: jwtMgr, Dir: relDir, CIToken: ciTok}).Register(mux)
	log.Printf("[release] dir=%s ci-upload=%v", relDir, ciTok != "")

	(&server.WebPanel{}).Register(mux)
	log.Printf("[v2] auth / vault / config / admin / release API enabled")
	log.Printf("[v2] web panel at http(s)://%s%s/", *publicHost, *wsAddr)

	// ---- 老的 admin 面板 兼容保留（用 env MOXIAN_ADMIN_LEGACY=1 启用）----
	if os.Getenv("MOXIAN_ADMIN_LEGACY") == "1" && *adminUser != "" && *adminPass != "" {
		admin := &server.AdminPanel{Hub: sig.Hub, Relay: udp, User: *adminUser, Pass: *adminPass}
		admin.Register(mux)
		log.Printf("[admin] legacy panel enabled at /admin (user=%s)", *adminUser)
	}
	server.RegisterMetrics(mux, sig.Hub, udp)
	log.Printf("[metrics] Prometheus endpoint /metrics")

	if *tlsCert != "" && *tlsKey != "" {
		log.Printf("[wss] listen %s (wss://%s:PORT/ws)", *wsAddr, *publicHost)
		if err := http.ListenAndServeTLS(*wsAddr, *tlsCert, *tlsKey, mux); err != nil {
			log.Printf("https: %v", err)
			os.Exit(1)
		}
		return
	}
	log.Printf("[ws] listen %s  (ws://%s:PORT/ws)", *wsAddr, *publicHost)
	if err := http.ListenAndServe(*wsAddr, mux); err != nil {
		log.Printf("http: %v", err)
		os.Exit(1)
	}
}
