package client

import (
	"fmt"
	"os"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// FileConfig 配置文件 schema（与 Config 一一对应 yaml tag 驱动）
type FileConfig struct {
	NodeID      string   `yaml:"node_id"`
	Token       string   `yaml:"token"`
	ServerURL   string   `yaml:"server"`
	ServerUDP   string   `yaml:"server_udp"`
	Passphrase  string   `yaml:"pass"`
	Tags        []string `yaml:"tags"`
	Description string   `yaml:"description"`

	AllowTargets []string        `yaml:"allow_targets"`
	AllowPeers   []string        `yaml:"allow_peers"`
	Forwards     []ForwardConfig `yaml:"forwards"`

	VirtualIP string `yaml:"virtual_ip"`
	TunSubnet string `yaml:"tun_subnet"`
	TunDev    string `yaml:"tun_dev"`

	UPnP bool `yaml:"upnp"`
	Mesh bool `yaml:"mesh"`

	StatsAddr     string        `yaml:"stats_http"`
	StatsInterval time.Duration `yaml:"stats_log"`

	RateLimit   string `yaml:"rate_limit"` // 如 "10MB" / "1.5MB"
	Verbose     bool   `yaml:"verbose"`
	InsecureTLS bool   `yaml:"insecure_tls"` // 自签证书跳过验证
}

// ForwardConfig 端口转发条目
type ForwardConfig struct {
	Local  string `yaml:"local"`
	Peer   string `yaml:"peer"`
	Target string `yaml:"target"`
}

// LoadFile 从 YAML 文件读取
func LoadFile(path string) (*FileConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var fc FileConfig
	if err := yaml.Unmarshal(data, &fc); err != nil {
		return nil, fmt.Errorf("parse yaml: %w", err)
	}
	return &fc, nil
}

// ApplyTo 将配置文件字段合并到 Config（CLI 优先 非空字段才覆盖）
func (fc *FileConfig) ApplyTo(cfg *Config) {
	if cfg.NodeID == "" {
		cfg.NodeID = fc.NodeID
	}
	if cfg.Token == "" {
		cfg.Token = fc.Token
	}
	if cfg.ServerURL == "" {
		cfg.ServerURL = fc.ServerURL
	}
	if cfg.ServerUDP == "" {
		cfg.ServerUDP = fc.ServerUDP
	}
	if cfg.Passphrase == "" {
		cfg.Passphrase = fc.Passphrase
	}
	if len(cfg.Tags) == 0 {
		cfg.Tags = fc.Tags
	}
	if cfg.Description == "" {
		cfg.Description = fc.Description
	}
	if len(cfg.AllowTargets) == 0 {
		cfg.AllowTargets = fc.AllowTargets
	}
	if len(cfg.AllowPeers) == 0 {
		cfg.AllowPeers = fc.AllowPeers
	}
	if len(cfg.Forwards) == 0 {
		for _, f := range fc.Forwards {
			cfg.Forwards = append(cfg.Forwards, ForwardRule{Local: f.Local, Peer: f.Peer, Target: f.Target})
		}
	}
	if cfg.VirtualIP == "" {
		cfg.VirtualIP = fc.VirtualIP
		cfg.EnableTun = fc.VirtualIP != ""
	}
	if cfg.TunSubnet == "" || cfg.TunSubnet == "24" {
		if fc.TunSubnet != "" {
			cfg.TunSubnet = fc.TunSubnet
		}
	}
	if cfg.TunDev == "" {
		cfg.TunDev = fc.TunDev
	}
	if !cfg.EnableUPnP {
		cfg.EnableUPnP = fc.UPnP
	}
	if !cfg.EnableMesh {
		cfg.EnableMesh = fc.Mesh
	}
	if cfg.StatsAddr == "" {
		cfg.StatsAddr = fc.StatsAddr
	}
	if cfg.StatsInterval == 0 {
		cfg.StatsInterval = fc.StatsInterval
	}
	if cfg.RateLimit == 0 && fc.RateLimit != "" {
		if v, err := parseSize(fc.RateLimit); err == nil {
			cfg.RateLimit = v
		}
	}
	if !cfg.InsecureTLS {
		cfg.InsecureTLS = fc.InsecureTLS
	}
}

// parseSize 解析 "10MB" / "1.5KB" -> 字节数
func parseSize(s string) (int64, error) {
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
	s = strings.TrimSpace(s)
	var f float64
	if _, err := fmt.Sscanf(s, "%f", &f); err != nil {
		return 0, err
	}
	return int64(f * float64(mult)), nil
}
