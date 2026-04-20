package server

import (
	"log"
	"net"
)

// StunOnly 只响应 STUN 的简易 UDP 服务（用于 NAT 类型检测的第二端点）
type StunOnly struct {
	conn *net.UDPConn
	port int
}

func NewStunOnly(listenAddr string) (*StunOnly, error) {
	addr, err := net.ResolveUDPAddr("udp", listenAddr)
	if err != nil {
		return nil, err
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return nil, err
	}
	return &StunOnly{conn: conn, port: conn.LocalAddr().(*net.UDPAddr).Port}, nil
}

func (s *StunOnly) Port() int { return s.port }

func (s *StunOnly) Run() {
	buf := make([]byte, 2048)
	for {
		n, remote, err := s.conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("[stun2] read: %v", err)
			return
		}
		if n < 5 || buf[0] != MagicStunReq {
			continue
		}
		nonce := append([]byte(nil), buf[1:5]...)
		addrStr := remote.String()
		out := make([]byte, 0, 6+len(addrStr))
		out = append(out, MagicStunResp)
		out = append(out, nonce...)
		out = append(out, byte(len(addrStr)))
		out = append(out, addrStr...)
		_, _ = s.conn.WriteToUDP(out, remote)
	}
}
