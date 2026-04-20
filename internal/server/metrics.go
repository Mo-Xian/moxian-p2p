package server

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// RegisterMetrics 在给定 mux 上注册 /metrics 端点
func RegisterMetrics(mux *http.ServeMux, hub *Hub, relay *UDPServer) {
	reg := prometheus.NewRegistry()

	reg.MustRegister(prometheus.NewGaugeFunc(
		prometheus.GaugeOpts{Name: "moxian_nodes_online", Help: "当前在线节点数"},
		func() float64 { return float64(hub.Count()) },
	))
	reg.MustRegister(prometheus.NewCounterFunc(
		prometheus.CounterOpts{Name: "moxian_relay_bytes_total", Help: "中继转发累计字节"},
		func() float64 {
			b, _, _ := relay.RelayStats()
			return float64(b)
		},
	))
	reg.MustRegister(prometheus.NewCounterFunc(
		prometheus.CounterOpts{Name: "moxian_relay_packets_total", Help: "中继转发累计包数"},
		func() float64 {
			_, p, _ := relay.RelayStats()
			return float64(p)
		},
	))
	reg.MustRegister(prometheus.NewCounterFunc(
		prometheus.CounterOpts{Name: "moxian_stun_requests_total", Help: "STUN 请求累计数"},
		func() float64 {
			_, _, s := relay.RelayStats()
			return float64(s)
		},
	))

	mux.Handle("/metrics", promhttp.HandlerFor(reg, promhttp.HandlerOpts{}))
}
