package client

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"golang.org/x/crypto/pbkdf2"
)

// AuthClient v2 认证客户端 用于 CLI 登录 moxian-server 拉取配置
type AuthClient struct {
	ServerURL string
	JWT       string
	HTTP      *http.Client
}

// NewAuthClient base URL 如 https://vps.example.com:7788
func NewAuthClient(baseURL string) *AuthClient {
	return &AuthClient{
		ServerURL: strings.TrimRight(baseURL, "/"),
		HTTP:      &http.Client{Timeout: 15 * time.Second},
	}
}

// Login 执行登录流程：prelogin → 派生 pwdHash → login → 获取 JWT
// 返回 JWT vault blob vault 版本 kdf 迭代数 供后续解密 vault 使用
func (c *AuthClient) Login(email, password string) (string, error) {
	email = strings.ToLower(strings.TrimSpace(email))

	// 1. prelogin
	pre, err := c.postJSON("/api/auth/prelogin", map[string]string{"email": email}, "")
	if err != nil {
		return "", fmt.Errorf("prelogin: %w", err)
	}
	var preResp struct {
		KDFIterations int    `json:"kdf_iterations"`
		Error         string `json:"error,omitempty"`
	}
	if err := json.Unmarshal(pre, &preResp); err != nil {
		return "", fmt.Errorf("prelogin parse: %w", err)
	}
	if preResp.Error != "" {
		return "", errors.New(preResp.Error)
	}
	iter := preResp.KDFIterations
	if iter <= 0 {
		iter = 600_000
	}

	// 2. 派生 pwdHash
	masterKey := pbkdf2.Key([]byte(password), []byte(email), iter, 32, sha256.New)
	pwdHashBytes := pbkdf2.Key(masterKey, []byte(password), 1, 32, sha256.New)
	pwdHash := base64.StdEncoding.EncodeToString(pwdHashBytes)

	// 3. login
	loginResp, err := c.postJSON("/api/auth/login", map[string]string{
		"email":         email,
		"password_hash": pwdHash,
	}, "")
	if err != nil {
		return "", fmt.Errorf("login: %w", err)
	}
	var lr struct {
		JWT      string `json:"jwt"`
		UserID   int64  `json:"user_id"`
		Username string `json:"username"`
		Error    string `json:"error,omitempty"`
	}
	if err := json.Unmarshal(loginResp, &lr); err != nil {
		return "", fmt.Errorf("login parse: %w", err)
	}
	if lr.Error != "" {
		return "", errors.New(lr.Error)
	}
	if lr.JWT == "" {
		return "", errors.New("no jwt")
	}
	c.JWT = lr.JWT
	return lr.JWT, nil
}

// ClientConfig moxian-p2p config 从服务器拉回的字段
type ClientConfig struct {
	NodeID     string   `json:"node_id"`
	VirtualIP  string   `json:"virtual_ip"`
	Pass       string   `json:"pass"`
	ServerWS   string   `json:"server_ws"`
	ServerUDP  string   `json:"server_udp"`
	AllowPeers []string `json:"allow_peers"`
	Tags       []string `json:"tags"`
	Mesh       bool     `json:"mesh"`
	Error      string   `json:"error,omitempty"`
}

// FetchConfig 拉取指定节点的 moxian-p2p config
// 若节点未注册 先 POST /api/nodes 注册
func (c *AuthClient) FetchConfig(nodeID string) (*ClientConfig, error) {
	if nodeID == "" {
		return nil, errors.New("nodeID 必填")
	}
	body, err := c.getJSON("/api/config?node=" + nodeID)
	if err != nil {
		// 可能是 404 节点未注册 先注册再试
		_, _ = c.postJSON("/api/nodes", map[string]string{"node_id": nodeID}, c.JWT)
		body, err = c.getJSON("/api/config?node=" + nodeID)
		if err != nil {
			return nil, err
		}
	}
	var cfg ClientConfig
	if err := json.Unmarshal(body, &cfg); err != nil {
		return nil, err
	}
	if cfg.Error != "" {
		return nil, errors.New(cfg.Error)
	}
	return &cfg, nil
}

func (c *AuthClient) postJSON(path string, body any, bearer string) ([]byte, error) {
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest("POST", c.ServerURL+path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return io.ReadAll(resp.Body)
}

func (c *AuthClient) getJSON(path string) ([]byte, error) {
	req, _ := http.NewRequest("GET", c.ServerURL+path, nil)
	if c.JWT != "" {
		req.Header.Set("Authorization", "Bearer "+c.JWT)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(b))
	}
	return io.ReadAll(resp.Body)
}
