// moxian-gui 是一个 Windows 系统托盘 GUI 程序
// 取代 moxian-client.exe 黑控制台体验 右键托盘图标操作 启停
//
// 使用:
//   1. moxian-gui.exe 和 client.yaml + wintun.dll 放在同一目录
//   2. 右键 exe → 以管理员身份运行（TUN 需要）
//   3. 托盘出现图标 右键菜单 → 启动
//
// 构建: go build -ldflags "-H=windowsgui" -o moxian-gui.exe ./cmd/moxian-gui

//go:build windows

package main

import (
	"bytes"
	"context"
	"encoding/binary"
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

func sanitizeHostname() string {
	h, _ := os.Hostname()
	h = strings.ReplaceAll(h, ".", "-")
	h = strings.ReplaceAll(h, " ", "-")
	if h == "" {
		return "host"
	}
	return h
}

// 检查 client.yaml 是否可以自动启动
// 只看 v2 字段（v2_server + v2_email + v2_password）—— 启动时去登录拉配置
func hasAutoStart() bool {
	fc, err := client.LoadFile(configPath())
	if err != nil {
		return false
	}
	return fc.V2Server != "" && fc.V2Email != "" && fc.V2Password != ""
}

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

	// 配置就绪 自动启动 用户不用再点"启动"
	if hasAutoStart() {
		log.Printf("[gui] 配置就绪 自动启动")
		go doStart()
	}
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

	// 唯一启动路径：v2 登录 → 拉 P2P 配置
	if fc.V2Server == "" || fc.V2Email == "" || fc.V2Password == "" {
		log.Printf("[gui] 缺少登录信息（v2_server/v2_email/v2_password）请编辑配置")
		setState("缺少登录信息", 0xFF, 0x40, 0x40)
		menuStart.Enable()
		return
	}
	log.Printf("[gui] 登录: %s as %s", fc.V2Server, fc.V2Email)
	setState("登录中", 0xFF, 0xD4, 0x79)
	ac := client.NewAuthClient(fc.V2Server, fc.V2InsecureTLS)
	if _, err := ac.Login(fc.V2Email, fc.V2Password); err != nil {
		log.Printf("[gui] 登录失败: %v", err)
		setState("登录失败", 0xFF, 0x40, 0x40)
		menuStart.Enable()
		return
	}
	nodeID := fc.V2Node
	if nodeID == "" {
		nodeID = "win-" + sanitizeHostname()
	}
	v2cfg, err := ac.FetchConfig(nodeID)
	if err != nil {
		log.Printf("[gui] 拉配置失败: %v", err)
		setState("拉配置失败", 0xFF, 0x40, 0x40)
		menuStart.Enable()
		return
	}
	cfg.NodeID = v2cfg.NodeID
	cfg.ServerURL = v2cfg.ServerWS
	cfg.ServerUDP = v2cfg.ServerUDP
	cfg.Passphrase = v2cfg.Pass
	cfg.VirtualIP = v2cfg.VirtualIP
	cfg.EnableTun = v2cfg.VirtualIP != ""
	cfg.EnableMesh = v2cfg.Mesh
	cfg.InsecureTLS = fc.V2InsecureTLS
	log.Printf("[gui] 配置已拉取 node=%s vip=%s", cfg.NodeID, cfg.VirtualIP)

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

// makeIcon 生成 Windows 托盘用的 ICO 图标（DIB/BMP 格式 非 PNG）
// 结构：ICONDIR (6) + ICONDIRENTRY (16) + BITMAPINFOHEADER (40) + XOR 像素 + AND 掩码
// 为什么不用 PNG-in-ICO：
//   PNG 内嵌需要 Vista+ 且 shell 对 16x16 PNG 图标渲染不稳定（部分主题/DPI 下透明不显示）
//   DIB 是最原始格式 所有 Windows 版本稳定渲染 优先选它
func makeIcon(r, g, b uint8) []byte {
	const w, h = 16, 16
	const xorSize = w * h * 4      // 32bpp BGRA = 1024
	const andRowBytes = 4          // 16 位宽 -> 2 字节 对齐到 4
	const andSize = andRowBytes * h // 64
	const bmpSize = 40 + xorSize + andSize

	var buf bytes.Buffer
	// ICONDIR (6 bytes)
	_ = binary.Write(&buf, binary.LittleEndian, uint16(0)) // Reserved
	_ = binary.Write(&buf, binary.LittleEndian, uint16(1)) // Type 1=ICO
	_ = binary.Write(&buf, binary.LittleEndian, uint16(1)) // 1 个图像
	// ICONDIRENTRY (16 bytes)
	buf.WriteByte(w)
	buf.WriteByte(h)
	buf.WriteByte(0) // 无调色板
	buf.WriteByte(0) // Reserved
	_ = binary.Write(&buf, binary.LittleEndian, uint16(1))        // Color planes
	_ = binary.Write(&buf, binary.LittleEndian, uint16(32))       // bits per pixel
	_ = binary.Write(&buf, binary.LittleEndian, uint32(bmpSize))  // 数据大小
	_ = binary.Write(&buf, binary.LittleEndian, uint32(6+16))     // 数据偏移

	// BITMAPINFOHEADER (40 bytes)
	_ = binary.Write(&buf, binary.LittleEndian, uint32(40))  // biSize
	_ = binary.Write(&buf, binary.LittleEndian, int32(w))    // biWidth
	_ = binary.Write(&buf, binary.LittleEndian, int32(h*2))  // biHeight = XOR + AND 双倍高度
	_ = binary.Write(&buf, binary.LittleEndian, uint16(1))   // biPlanes
	_ = binary.Write(&buf, binary.LittleEndian, uint16(32))  // biBitCount
	_ = binary.Write(&buf, binary.LittleEndian, uint32(0))   // biCompression = BI_RGB
	_ = binary.Write(&buf, binary.LittleEndian, uint32(0))   // biSizeImage
	_ = binary.Write(&buf, binary.LittleEndian, int32(0))    // biXPelsPerMeter
	_ = binary.Write(&buf, binary.LittleEndian, int32(0))    // biYPelsPerMeter
	_ = binary.Write(&buf, binary.LittleEndian, uint32(0))   // biClrUsed
	_ = binary.Write(&buf, binary.LittleEndian, uint32(0))   // biClrImportant

	// XOR 像素数据 BGRA 自底向上（DIB 惯例）
	row := make([]byte, w*4)
	for x := 0; x < w; x++ {
		row[x*4+0] = b
		row[x*4+1] = g
		row[x*4+2] = r
		row[x*4+3] = 0xFF
	}
	for y := 0; y < h; y++ {
		buf.Write(row)
	}

	// AND 掩码 全 0 表示"完全不透明 像素取自 XOR"
	buf.Write(make([]byte, andSize))
	return buf.Bytes()
}
