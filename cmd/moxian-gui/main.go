// moxian-gui 是一个 Windows 系统托盘 GUI 程序
// 取代 moxian-client.exe 黑控制台体验 右键托盘图标操作 启停
//
// 使用:
//   1. moxian-gui.exe 和 client.yaml + wintun.dll 放在同一目录
//   2. 右键 exe → 以管理员身份运行（TUN 需要）
//   3. 托盘出现图标 右键菜单 → 启动
//
// 构建: go build -ldflags "-H=windowsgui" -o moxian-gui.exe ./cmd/moxian-gui
package main

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/png"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"

	"github.com/getlantern/systray"

	"github.com/cp12064/moxian-p2p/internal/client"
)

var (
	cancelFn context.CancelFunc
	running  atomic.Bool

	menuState *systray.MenuItem
	menuStart *systray.MenuItem
	menuStop  *systray.MenuItem
)

func main() {
	// GUI 进程无 stdout 把 log 重定向到文件
	logFile, err := os.OpenFile(logPath(), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err == nil {
		log.SetOutput(&stateSniffer{out: logFile})
		log.SetFlags(log.Ldate | log.Ltime)
	}
	log.Printf("[gui] moxian-gui started (workdir=%s)", workDir())
	systray.Run(onReady, onExit)
}

func workDir() string {
	if exe, err := os.Executable(); err == nil {
		return filepath.Dir(exe)
	}
	return "."
}

func configPath() string { return filepath.Join(workDir(), "client.yaml") }
func logPath() string    { return filepath.Join(workDir(), "moxian.log") }

func onReady() {
	systray.SetIcon(makeIcon(0x80, 0x80, 0x80))
	systray.SetTitle("moxian-p2p")
	systray.SetTooltip("moxian-p2p 已停止")

	menuState = systray.AddMenuItem("状态: 未启动", "")
	menuState.Disable()
	systray.AddSeparator()
	menuStart = systray.AddMenuItem("启动", "使用 client.yaml 启动 P2P")
	menuStop = systray.AddMenuItem("停止", "断开")
	menuStop.Disable()
	systray.AddSeparator()
	menuCfg := systray.AddMenuItem("编辑配置", "打开 client.yaml")
	menuLog := systray.AddMenuItem("打开日志", "打开 moxian.log")
	menuDir := systray.AddMenuItem("所在目录", "打开 exe 所在目录")
	systray.AddSeparator()
	menuQuit := systray.AddMenuItem("退出", "")

	go func() {
		for {
			select {
			case <-menuStart.ClickedCh:
				go doStart()
			case <-menuStop.ClickedCh:
				go doStop()
			case <-menuCfg.ClickedCh:
				openPath(configPath())
			case <-menuLog.ClickedCh:
				openPath(logPath())
			case <-menuDir.ClickedCh:
				openPath(workDir())
			case <-menuQuit.ClickedCh:
				doStop()
				systray.Quit()
				return
			}
		}
	}()
}

func onExit() {
	if running.Load() && cancelFn != nil {
		cancelFn()
	}
}

func doStart() {
	if running.Load() {
		return
	}
	setState("启动中", 0xFF, 0xD4, 0x79)
	menuStart.Disable()

	cfgPath := configPath()
	if _, err := os.Stat(cfgPath); err != nil {
		log.Printf("[gui] client.yaml 不存在于 %s  请先创建配置文件", cfgPath)
		setState("缺少 client.yaml", 0xFF, 0x40, 0x40)
		menuStart.Enable()
		return
	}
	fc, err := client.LoadFile(cfgPath)
	if err != nil {
		log.Printf("[gui] 配置解析失败: %v", err)
		setState("配置错误", 0xFF, 0x40, 0x40)
		menuStart.Enable()
		return
	}
	var cfg client.Config
	fc.ApplyTo(&cfg)

	c, err := client.New(cfg)
	if err != nil {
		log.Printf("[gui] new client: %v", err)
		setState("启动失败", 0xFF, 0x40, 0x40)
		menuStart.Enable()
		return
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancelFn = cancel
	running.Store(true)
	menuStop.Enable()

	go func() {
		defer func() {
			running.Store(false)
			setState("已停止", 0x80, 0x80, 0x80)
			menuStart.Enable()
			menuStop.Disable()
		}()
		if err := c.Run(ctx); err != nil && ctx.Err() == nil {
			log.Printf("[gui] run exit: %v", err)
		}
	}()
}

func doStop() {
	if !running.Load() {
		return
	}
	log.Printf("[gui] stopping...")
	setState("停止中", 0xFF, 0x9F, 0x7D)
	if cancelFn != nil {
		cancelFn()
	}
}

func setState(text string, r, g, b uint8) {
	systray.SetIcon(makeIcon(r, g, b))
	systray.SetTooltip("moxian-p2p " + text)
	menuState.SetTitle("状态: " + text)
}

func openPath(path string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("cmd", "/c", "start", "", path)
	case "darwin":
		cmd = exec.Command("open", path)
	default:
		cmd = exec.Command("xdg-open", path)
	}
	if cmd != nil {
		_ = cmd.Start()
	}
}

// stateSniffer 监听 log 输出中的关键字 动态更新托盘状态
type stateSniffer struct{ out io.Writer }

func (s *stateSniffer) Write(p []byte) (int, error) {
	line := string(p)
	switch {
	case strings.Contains(line, "assigned vip ="),
		strings.Contains(line, "[tun] device="):
		setState("TUN 就绪", 0xFF, 0xD4, 0x79)
	case strings.Contains(line, "[forward]") && strings.Contains(line, "established"),
		strings.Contains(line, "[responder]") && strings.Contains(line, "established"),
		strings.Contains(line, "[mesh] connected to"),
		strings.Contains(line, "[peerpool] dialed"),
		strings.Contains(line, "[peerpool] registered inbound"):
		setState("已连接", 0x7D, 0xFF, 0xA8)
	}
	return s.out.Write(p)
}

func makeIcon(r, g, b uint8) []byte {
	img := image.NewNRGBA(image.Rect(0, 0, 16, 16))
	for y := 0; y < 16; y++ {
		for x := 0; x < 16; x++ {
			img.Set(x, y, color.NRGBA{R: r, G: g, B: b, A: 0xFF})
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}
