//go:build android

package client

import (
	"errors"
	"fmt"

	"golang.zx2c4.com/wireguard/tun"
)

// androidTun 包装 wireguard/tun 从外部 fd 构造的 TUN 设备
// Android 下 VpnService 给我们的 fd 就是内核 TUN fd
type androidTun struct {
	dev  tun.Device
	name string
}

func (t *androidTun) Read(p []byte) (int, error) {
	bufs := [][]byte{p}
	sizes := make([]int, 1)
	_, err := t.dev.Read(bufs, sizes, 0)
	if err != nil {
		return 0, err
	}
	return sizes[0], nil
}

func (t *androidTun) Write(p []byte) (int, error) {
	_, err := t.dev.Write([][]byte{p}, 0)
	if err != nil {
		return 0, err
	}
	return len(p), nil
}

func (t *androidTun) Close() error { return t.dev.Close() }
func (t *androidTun) Name() string { return t.name }

// openTunDeviceFromFD 从 VpnService 传入的 fd 创建 tun 设备
// Android 独有 其他平台无此入口
func openTunDeviceFromFD(fd int) (tunDevice, error) {
	dev, name, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err != nil {
		return nil, fmt.Errorf("wrap tun fd %d: %w", fd, err)
	}
	return &androidTun{dev: dev, name: name}, nil
}

// openTunDevice Android 下不应主动创建 TUN（无权限）必须走 VpnService
func openTunDevice(vip, subnet, preferred string) (tunDevice, error) {
	return nil, errors.New("android must provide TUN fd via VpnService (AndroidTunFD > 0)")
}
