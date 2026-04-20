//go:build darwin

package client

import (
	"fmt"
	"os/exec"

	"github.com/songgao/water"
)

type darwinTun struct {
	*water.Interface
}

func (t *darwinTun) Name() string { return t.Interface.Name() }

func openTunDevice(vip, subnet, preferred string) (tunDevice, error) {
	cfg := water.Config{DeviceType: water.TUN}
	ifce, err := water.New(cfg)
	if err != nil {
		return nil, fmt.Errorf("create tun: %w", err)
	}
	// macOS utun 点对点 且需要指定对端 这里用自身占位
	if err := runSh("ifconfig", ifce.Name(), "inet", vip, vip, "up"); err != nil {
		ifce.Close()
		return nil, err
	}
	return &darwinTun{Interface: ifce}, nil
}

func runSh(name string, args ...string) error {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %v: %w (%s)", name, args, err, out)
	}
	return nil
}
