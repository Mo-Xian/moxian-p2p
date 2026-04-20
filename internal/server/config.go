package server

import (
	"fmt"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

// FileConfig 服务器配置文件 schema
type FileConfig struct {
	Host       string   `yaml:"host"`
	WS         string   `yaml:"ws"`
	UDP        string   `yaml:"udp"`
	UDP2       string   `yaml:"udp2"`
	StunExtras []string `yaml:"stun_extras"`
	Token      string   `yaml:"token"`

	TLSCert string `yaml:"tls_cert"`
	TLSKey  string `yaml:"tls_key"`

	AdminUser string `yaml:"admin_user"`
	AdminPass string `yaml:"admin_pass"`

	RelayLimit string `yaml:"relay_limit"` // per-session 中继限速 如 "10MB"

	// 虚拟网子网 例如 "10.88.0.0/24" 留空则不启用 vIP 自动分配
	VirtualSubnet string `yaml:"virtual_subnet"`
	// vIP 分配映射持久化路径 留空则仅内存（重启丢失）
	VIPStore string `yaml:"vip_store"`
}

// LoadFile 读取 YAML
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

// ParseSize "10MB" -> 字节数
func ParseSize(s string) (int64, error) {
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
