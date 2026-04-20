//go:build !android

package client

import "errors"

// openTunDeviceFromFD 非 Android 平台不支持 (仅 Android VpnService 场景才用外部 fd)
func openTunDeviceFromFD(fd int) (tunDevice, error) {
	return nil, errors.New("openTunDeviceFromFD only supported on android")
}
