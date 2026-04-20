//go:build windows

package client

import (
	"fmt"
	"net"
	"os/exec"
	"strings"

	"golang.zx2c4.com/wireguard/tun"
)

// windowsTun 基于 Wintun 的 TUN 设备
// 需要 wintun.dll 与 exe 同目录（或位于 PATH 中）
// 下载 https://www.wintun.net/
type windowsTun struct {
	dev  tun.Device
	name string
}

func (w *windowsTun) Read(p []byte) (int, error) {
	bufs := [][]byte{p}
	sizes := make([]int, 1)
	_, err := w.dev.Read(bufs, sizes, 0)
	if err != nil {
		return 0, err
	}
	return sizes[0], nil
}

func (w *windowsTun) Write(p []byte) (int, error) {
	// wireguard/tun 的 Write 需要一个 slice-of-slices，offset 之前可有预留字节 这里用 0
	bufs := [][]byte{p}
	_, err := w.dev.Write(bufs, 0)
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (w *windowsTun) Close() error { return w.dev.Close() }
func (w *windowsTun) Name() string { return w.name }

func openTunDevice(vip, subnet, preferred string) (tunDevice, error) {
	name := preferred
	if name == "" {
		name = "moxian"
	}
	dev, err := tun.CreateTUN(name, 1400)
	if err != nil {
		return nil, fmt.Errorf("create wintun: %w (确认 wintun.dll 与 exe 同目录，并以管理员权限运行)", err)
	}
	actualName, _ := dev.Name()
	if subnet == "" {
		subnet = "24"
	}
	mask := cidrToNetmask(subnet)
	// netsh interface ipv4 set address name="moxian" static 10.88.0.2 255.255.255.0
	cmd := exec.Command("netsh", "interface", "ipv4", "set", "address",
		fmt.Sprintf("name=%s", actualName),
		"static", vip, mask)
	out, err := cmd.CombinedOutput()
	if err != nil {
		dev.Close()
		return nil, fmt.Errorf("netsh set address: %w (%s)", err, strings.TrimSpace(string(out)))
	}
	return &windowsTun{dev: dev, name: actualName}, nil
}

func cidrToNetmask(cidr string) string {
	var n int
	_, _ = fmt.Sscanf(cidr, "%d", &n)
	if n <= 0 || n > 32 {
		n = 24
	}
	m := net.CIDRMask(n, 32)
	return fmt.Sprintf("%d.%d.%d.%d", m[0], m[1], m[2], m[3])
}
