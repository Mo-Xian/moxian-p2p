// Package mobile 提供给 Android 通过 gomobile bind 调用的 Go 门面 API
//
// 用法（Kotlin 侧）:
//   val client = Mobile.newClient(yamlStr, logSink)
//   client.start(tunFd)       // tunFd 从 VpnService.Builder.establish().detachFd() 拿
//   ...
//   client.stop()
//
// gomobile 限制:
//   - 导出类型必须是命名 struct 或 interface
//   - 方法参数/返回值必须是基本类型、[]byte、string、error、interface 或已导出 struct 指针
//   - 不能用 channel / reflect / 复杂类型导出
package mobile

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/cp12064/moxian-p2p/internal/client"
	"github.com/cp12064/moxian-p2p/internal/debug"
	"gopkg.in/yaml.v3"
)

// LogSink Kotlin 实现此接口 接收 Go 侧所有日志
// gomobile 会把 Go interface 转成 Java interface
type LogSink interface {
	Log(line string)
}

// Client 是 gomobile 导出的 client 门面
type Client struct {
	cfg     client.Config
	sink    LogSink
	mu      sync.Mutex
	inner   *client.Client
	cancel  context.CancelFunc
	running atomic.Bool
}

// NewClient 从 yaml 字符串创建 client（不启动）
// sink 可为 nil 则不重定向日志
func NewClient(yamlConfig string, sink LogSink) (*Client, error) {
	if strings.TrimSpace(yamlConfig) == "" {
		return nil, errors.New("empty yaml config")
	}
	var fc client.FileConfig
	if err := yaml.Unmarshal([]byte(yamlConfig), &fc); err != nil {
		return nil, fmt.Errorf("parse yaml: %w", err)
	}
	var cfg client.Config
	fc.ApplyTo(&cfg)
	// Android 语义固定:
	cfg.EnableTun = true
	if cfg.VirtualIP == "" {
		cfg.VirtualIP = "auto"
	}
	// Android 默认开 debug 日志 方便排障
	debug.Enable(true)

	if sink != nil {
		log.SetOutput(&sinkWriter{sink: sink})
		log.SetFlags(log.Ldate | log.Ltime)
	}

	return &Client{cfg: cfg, sink: sink}, nil
}

// Start 启动 client
// tunFd 从 VpnService.Builder.establish().detachFd() 得到
// 非阻塞 后台运行
func (c *Client) Start(tunFd int32) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.running.Load() {
		return errors.New("already running")
	}
	if tunFd <= 0 {
		return fmt.Errorf("invalid tun fd: %d", tunFd)
	}
	c.cfg.AndroidTunFD = tunFd

	ic, err := client.New(c.cfg)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	c.inner = ic
	c.cancel = cancel
	c.running.Store(true)

	go func() {
		defer c.running.Store(false)
		if err := ic.Run(ctx); err != nil && ctx.Err() == nil {
			log.Printf("[mobile] run exited: %v", err)
		} else {
			log.Printf("[mobile] run stopped")
		}
	}()
	return nil
}

// Stop 停止 client 幂等
func (c *Client) Stop() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.cancel != nil {
		c.cancel()
	}
	c.running.Store(false)
}

// IsRunning 是否运行中
func (c *Client) IsRunning() bool { return c.running.Load() }

// Version 获取 Go 端版本字符串（方便 Kotlin 侧展示）
func Version() string { return "0.6.0-mobile" }

// PrepareVip 做一次短连接 STUN + WS 注册 返回 server 分配的 vIP
// 用于 Android 建 VpnService.Builder 前拿到真实 vIP
// 由于同一 node_id 在 server 持久化 后续 Start 注册会拿到同一 vIP
//
// 阻塞调用 建议在 Kotlin 协程 IO 线程上跑
func PrepareVip(yamlConfig string) (string, error) {
	if strings.TrimSpace(yamlConfig) == "" {
		return "", errors.New("empty yaml")
	}
	var fc client.FileConfig
	if err := yaml.Unmarshal([]byte(yamlConfig), &fc); err != nil {
		return "", fmt.Errorf("parse yaml: %w", err)
	}
	var cfg client.Config
	fc.ApplyTo(&cfg)
	// 仅做 probe 不启动其他功能
	cfg.EnableTun = false
	cfg.EnableMesh = false
	cfg.Forwards = nil
	if cfg.VirtualIP == "" {
		cfg.VirtualIP = "auto"
	}

	tmp, err := client.New(cfg)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return tmp.PeekVIP(ctx)
}

// sinkWriter 把 log 包的输出转发给 Kotlin 的 LogSink
type sinkWriter struct{ sink LogSink }

func (w *sinkWriter) Write(p []byte) (int, error) {
	if w.sink == nil {
		return len(p), nil
	}
	s := string(p)
	// 去掉尾部换行 一行一次回调
	s = strings.TrimRight(s, "\r\n")
	if s != "" {
		// 防止 sink 的实现 panic 影响写操作
		func() {
			defer func() { recover() }()
			w.sink.Log(s)
		}()
	}
	return len(p), nil
}
