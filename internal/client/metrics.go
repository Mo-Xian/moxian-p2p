package client

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

func (c *Client) registerClientMetrics(mux *http.ServeMux) {
	reg := prometheus.NewRegistry()

	labels := prometheus.Labels{"node_id": c.cfg.NodeID}

	reg.MustRegister(prometheus.NewGaugeFunc(
		prometheus.GaugeOpts{Name: "moxian_client_sessions_active", Help: "活跃会话数", ConstLabels: labels},
		func() float64 { return float64(len(c.stats.Snapshot())) },
	))
	reg.MustRegister(prometheus.NewCounterFunc(
		prometheus.CounterOpts{Name: "moxian_client_tx_bytes_total", Help: "累计发送字节（所有会话求和）", ConstLabels: labels},
		func() float64 {
			var sum int64
			for _, s := range c.stats.Snapshot() {
				sum += s.TxBytes
			}
			return float64(sum)
		},
	))
	reg.MustRegister(prometheus.NewCounterFunc(
		prometheus.CounterOpts{Name: "moxian_client_rx_bytes_total", Help: "累计接收字节（所有会话求和）", ConstLabels: labels},
		func() float64 {
			var sum int64
			for _, s := range c.stats.Snapshot() {
				sum += s.RxBytes
			}
			return float64(sum)
		},
	))

	mux.Handle("/metrics", promhttp.HandlerFor(reg, promhttp.HandlerOpts{}))
}
