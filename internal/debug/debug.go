// Package debug 提供运行期可切换的 verbose 日志开关
// 用于追踪 TUN/tunnel 数据路径 默认关闭 通过 client -v 或 Enable(true) 开启
package debug

import (
	"log"
	"sync/atomic"
)

var enabled atomic.Bool

// Enable 设置开关
func Enable(on bool) { enabled.Store(on) }

// On 返回是否开启
func On() bool { return enabled.Load() }

// Logf 仅在启用时打印
func Logf(format string, args ...any) {
	if !enabled.Load() {
		return
	}
	log.Printf(format, args...)
}
