//go:build linux && !android

package client

import (
	"fmt"
	"log"
	"os/exec"

	"github.com/songgao/water"
)

type linuxTun struct {
	*water.Interface
}

func (t *linuxTun) Name() string { return t.Interface.Name() }

func openTunDevice(vip, subnet, preferred string) (tunDevice, error) {
	cfg := water.Config{DeviceType: water.TUN}
	if preferred != "" {
		cfg.Name = preferred
	}
	ifce, err := water.New(cfg)
	if err != nil {
		return nil, fmt.Errorf("create tun (需要 root 或 CAP_NET_ADMIN): %w", err)
	}
	if subnet == "" {
		subnet = "24"
	}
	if err := runSh("ip", "addr", "add", fmt.Sprintf("%s/%s", vip, subnet), "dev", ifce.Name()); err != nil {
		ifce.Close()
		return nil, err
	}
	if err := runSh("ip", "link", "set", "dev", ifce.Name(), "up"); err != nil {
		ifce.Close()
		return nil, err
	}
	if err := runSh("ip", "link", "set", "dev", ifce.Name(), "mtu", "1400"); err != nil {
		log.Printf("[tun] set mtu: %v", err)
	}
	return &linuxTun{Interface: ifce}, nil
}

func runSh(name string, args ...string) error {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %v: %w (%s)", name, args, err, out)
	}
	return nil
}
