package client

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// FileConfig 客户端 yaml 文件 schema（v2 唯一模式）
//
// 字段分两类:
//  1. 登录凭据（必填）—— 启动时去 server 登录拉真实 P2P 配置
//  2. 行为开关（可选）—— 本地决定的运行参数 server 不下发
type FileConfig struct {
	// ---- 登录凭据 ----
	// Email + Password 完整登录 适用 CLI / GUI（用户在 yaml 写明文密码）
	// JWT 直传 适用 Android（Kotlin 已登录 不持久化明文密码）
	// 两种模式二选一 优先 JWT
	Server      string `yaml:"server"`        // 如 https://1.2.3.4:7788
	Email       string `yaml:"email"`
	Password    string `yaml:"password"`
	JWT         string `yaml:"jwt"`           // 已登录的 token 跳过 email+password 登录
	Node        string `yaml:"node"`          // 节点名 留空则用 fallback
	InsecureTLS bool   `yaml:"insecure_tls"`  // 自签证书跳过验证

	// ---- 行为开关 ----
	Tags        []string `yaml:"tags"`
	Description string   `yaml:"description"`

	AllowTargets []string        `yaml:"allow_targets"`
	AllowPeers   []string        `yaml:"allow_peers"`
	Forwards     []ForwardConfig `yaml:"forwards"`

	TunSubnet string `yaml:"tun_subnet"`
	TunDev    string `yaml:"tun_dev"`

	UPnP bool `yaml:"upnp"`
	Mesh bool `yaml:"mesh"`

	StatsAddr     string        `yaml:"stats_http"`
	StatsInterval time.Duration `yaml:"stats_log"`

	RateLimit string `yaml:"rate_limit"` // 如 "10MB" / "1.5MB"
	Verbose   bool   `yaml:"verbose"`
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

// ApplyTo 把行为开关合并进 Config（非空才覆盖 CLI 优先）
// 不处理凭据字段（NodeID/ServerURL/ServerUDP/Passphrase/VirtualIP）
// 凭据由 FetchAndApply 通过 v2 登录拉取
func (fc *FileConfig) ApplyTo(cfg *Config) {
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

// FetchAndApply 用 fc 里的登录凭据调 server 拉 P2P 配置 填进 cfg
// 优先 JWT 模式（fc.JWT 非空）次之 Email+Password 模式
// node 取 fc.Node，否则 fallbackNode
// 调用方应在 ApplyTo 之后调用（行为开关已就绪）
func (fc *FileConfig) FetchAndApply(cfg *Config, fallbackNode string) error {
	if fc.Server == "" {
		return errors.New("server 必填")
	}
	ac := NewAuthClient(fc.Server, fc.InsecureTLS)
	if fc.JWT != "" {
		ac.JWT = fc.JWT
	} else {
		if fc.Email == "" || fc.Password == "" {
			return errors.New("缺少凭据：jwt 或 (email + password) 至少提供一组")
		}
		if _, err := ac.Login(fc.Email, fc.Password); err != nil {
			return fmt.Errorf("login: %w", err)
		}
	}
	node := fc.Node
	if node == "" {
		node = fallbackNode
	}
	if node == "" {
		return errors.New("node 必填（fallbackNode 也未提供）")
	}
	v2cfg, err := ac.FetchConfig(node)
	if err != nil {
		return fmt.Errorf("fetch config: %w", err)
	}
	cfg.NodeID = v2cfg.NodeID
	cfg.ServerURL = v2cfg.ServerWS
	cfg.ServerUDP = v2cfg.ServerUDP
	cfg.Passphrase = v2cfg.Pass
	cfg.VirtualIP = v2cfg.VirtualIP
	cfg.EnableTun = v2cfg.VirtualIP != ""
	if v2cfg.Mesh {
		cfg.EnableMesh = true
	}
	cfg.InsecureTLS = fc.InsecureTLS
	return nil
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
