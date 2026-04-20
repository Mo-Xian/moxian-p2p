package main

import (
	"flag"
	"log"
	"net/http"
	"os"
	"strings"

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
	configFile := flag.String("config", "", "YAML 配置文件路径（CLI flag 优先）")
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

	sig := &server.Signaling{
		Hub:        server.NewHub(),
		Relay:      udp,
		PublicHost: *publicHost,
		AuthToken:  *token,
		Stun2Port:  stun2.Port(),
		StunExtras: extraPorts,
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", sig.Handle)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	})
	if *adminUser != "" && *adminPass != "" {
		admin := &server.AdminPanel{Hub: sig.Hub, Relay: udp, User: *adminUser, Pass: *adminPass}
		admin.Register(mux)
		log.Printf("[admin] panel enabled at /admin (user=%s)", *adminUser)
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
