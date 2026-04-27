package server

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// ReleaseAPI APP 版本分发
//
// 端点：
//   POST   /api/admin/release/upload     (admin) multipart: tag + file=apk
//   POST   /api/admin/release/promote    (admin) {tag} 设为 latest
//   GET    /api/admin/release/list       (admin) 全列表
//   DELETE /api/admin/release            (admin) ?tag=xxx
//   GET    /api/release/latest           (公开) {tag, apk_url, sha256, size, notes}
//   GET    /releases/<tag>/<filename>    (公开) 二进制下载
//
// 数据布局：
//   <Dir>/manifest.json
//   <Dir>/<tag>/<filename>
type ReleaseAPI struct {
	JWT *JWTManager
	Dir string
	// CIToken 非空时启用 /api/release/ci-upload  CI 流水线用此 token 直传 APK
	// 不需要 admin JWT 走单独 token 验证 适合无人值守上传
	CIToken string

	mu       sync.Mutex
	manifest *releaseManifest
}

type releaseEntry struct {
	Tag        string `json:"tag"`
	Filename   string `json:"filename"`
	Size       int64  `json:"size"`
	SHA256     string `json:"sha256"`
	Notes      string `json:"notes"`
	UploadedAt int64  `json:"uploaded_at"`
}

type releaseManifest struct {
	Latest   string         `json:"latest"`
	Releases []releaseEntry `json:"releases"`
}

func (a *ReleaseAPI) Register(mux *http.ServeMux) {
	if a.Dir == "" {
		return
	}
	if err := os.MkdirAll(a.Dir, 0o755); err != nil {
		// 启动时报错但不阻塞 让请求时再失败
		fmt.Printf("[release] mkdir %s: %v\n", a.Dir, err)
	}
	mux.HandleFunc("/api/admin/release/upload", a.JWT.AuthMiddleware(AdminOnly(a.handleUpload)))
	mux.HandleFunc("/api/admin/release/promote", a.JWT.AuthMiddleware(AdminOnly(a.handlePromote)))
	mux.HandleFunc("/api/admin/release/list", a.JWT.AuthMiddleware(AdminOnly(a.handleList)))
	mux.HandleFunc("/api/admin/release", a.JWT.AuthMiddleware(AdminOnly(a.handleDelete))) // DELETE
	mux.HandleFunc("/api/release/latest", a.handleLatest)
	mux.HandleFunc("/releases/", a.handleDownload)
	if a.CIToken != "" {
		mux.HandleFunc("/api/release/ci-upload", a.handleCIUpload)
	}
}

// ---- manifest 读写 ----

func (a *ReleaseAPI) manifestPath() string { return filepath.Join(a.Dir, "manifest.json") }

// loadManifestLocked 调用前必须持有 a.mu
func (a *ReleaseAPI) loadManifestLocked() *releaseManifest {
	if a.manifest != nil {
		return a.manifest
	}
	m := &releaseManifest{}
	data, err := os.ReadFile(a.manifestPath())
	if err == nil {
		_ = json.Unmarshal(data, m)
	}
	a.manifest = m
	return m
}

func (a *ReleaseAPI) saveManifestLocked() error {
	data, _ := json.MarshalIndent(a.manifest, "", "  ")
	tmp := a.manifestPath() + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, a.manifestPath())
}

// ---- handlers ----

// POST /api/admin/release/upload (multipart)
//   form: tag (必填), notes (可选), file=apk (必填)
func (a *ReleaseAPI) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeErr(w, 405, "method not allowed")
		return
	}
	if err := r.ParseMultipartForm(64 << 20); err != nil {
		writeErr(w, 400, "parse multipart: "+err.Error())
		return
	}
	tag := strings.TrimSpace(r.FormValue("tag"))
	notes := r.FormValue("notes")
	if tag == "" {
		writeErr(w, 400, "tag 必填")
		return
	}
	// 简单合法性 不允许路径分隔/空格
	if strings.ContainsAny(tag, "/\\ \t\r\n") {
		writeErr(w, 400, "tag 含非法字符")
		return
	}

	file, hdr, err := r.FormFile("file")
	if err != nil {
		writeErr(w, 400, "file 必填")
		return
	}
	defer file.Close()

	// 落盘 + 算 sha256
	subdir := filepath.Join(a.Dir, tag)
	if err := os.MkdirAll(subdir, 0o755); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	dst := filepath.Join(subdir, hdr.Filename)
	out, err := os.Create(dst)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer out.Close()
	hasher := sha256.New()
	written, err := io.Copy(io.MultiWriter(out, hasher), file)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	sum := hex.EncodeToString(hasher.Sum(nil))

	// 更新 manifest
	a.mu.Lock()
	m := a.loadManifestLocked()
	// 替换或追加
	now := time.Now().Unix()
	entry := releaseEntry{
		Tag: tag, Filename: hdr.Filename, Size: written,
		SHA256: sum, Notes: notes, UploadedAt: now,
	}
	replaced := false
	for i, e := range m.Releases {
		if e.Tag == tag {
			m.Releases[i] = entry
			replaced = true
			break
		}
	}
	if !replaced {
		m.Releases = append(m.Releases, entry)
	}
	// 默认把新上传的设为 latest（管理员后续可改）
	m.Latest = tag
	if err := a.saveManifestLocked(); err != nil {
		a.mu.Unlock()
		writeErr(w, 500, err.Error())
		return
	}
	a.mu.Unlock()

	writeJSON(w, 200, map[string]any{
		"tag": tag, "filename": hdr.Filename, "sha256": sum, "size": written,
	})
}

// POST /api/release/ci-upload (multipart)
//   Header: X-Release-Token: <CI 配置的 token>
//   form: tag, notes, file=apk
// 与 admin/upload 等价 但用 token 验证 适合 CI 无人值守
func (a *ReleaseAPI) handleCIUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeErr(w, 405, "method not allowed")
		return
	}
	got := r.Header.Get("X-Release-Token")
	if a.CIToken == "" || got == "" || got != a.CIToken {
		writeErr(w, 403, "invalid X-Release-Token")
		return
	}
	a.handleUpload(w, r)
}

// POST /api/admin/release/promote {tag}
func (a *ReleaseAPI) handlePromote(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeErr(w, 405, "method not allowed")
		return
	}
	var body struct {
		Tag string `json:"tag"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	if body.Tag == "" {
		writeErr(w, 400, "tag 必填")
		return
	}
	a.mu.Lock()
	m := a.loadManifestLocked()
	defer a.mu.Unlock()
	found := false
	for _, e := range m.Releases {
		if e.Tag == body.Tag {
			found = true
			break
		}
	}
	if !found {
		writeErr(w, 404, "tag 不存在")
		return
	}
	m.Latest = body.Tag
	if err := a.saveManifestLocked(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "latest": body.Tag})
}

// GET /api/admin/release/list
func (a *ReleaseAPI) handleList(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeErr(w, 405, "method not allowed")
		return
	}
	a.mu.Lock()
	m := a.loadManifestLocked()
	defer a.mu.Unlock()
	// 按 uploaded_at 倒序
	entries := make([]releaseEntry, len(m.Releases))
	copy(entries, m.Releases)
	sort.Slice(entries, func(i, j int) bool {
		return entries[i].UploadedAt > entries[j].UploadedAt
	})
	writeJSON(w, 200, map[string]any{
		"latest":   m.Latest,
		"releases": entries,
	})
}

// DELETE /api/admin/release?tag=xxx
func (a *ReleaseAPI) handleDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != "DELETE" {
		writeErr(w, 405, "method not allowed")
		return
	}
	tag := r.URL.Query().Get("tag")
	if tag == "" {
		writeErr(w, 400, "tag 必填")
		return
	}
	a.mu.Lock()
	m := a.loadManifestLocked()
	defer a.mu.Unlock()
	idx := -1
	for i, e := range m.Releases {
		if e.Tag == tag {
			idx = i
			break
		}
	}
	if idx < 0 {
		writeErr(w, 404, "tag 不存在")
		return
	}
	m.Releases = append(m.Releases[:idx], m.Releases[idx+1:]...)
	if m.Latest == tag {
		m.Latest = ""
		if len(m.Releases) > 0 {
			// 取最近一条作为 latest
			latest := m.Releases[0]
			for _, e := range m.Releases {
				if e.UploadedAt > latest.UploadedAt {
					latest = e
				}
			}
			m.Latest = latest.Tag
		}
	}
	// 删除文件
	_ = os.RemoveAll(filepath.Join(a.Dir, tag))
	if err := a.saveManifestLocked(); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "latest": m.Latest})
}

// GET /api/release/latest 公开 不需要登录
func (a *ReleaseAPI) handleLatest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeErr(w, 405, "method not allowed")
		return
	}
	a.mu.Lock()
	m := a.loadManifestLocked()
	defer a.mu.Unlock()
	if m.Latest == "" {
		writeErr(w, 404, "no release")
		return
	}
	var entry *releaseEntry
	for i := range m.Releases {
		if m.Releases[i].Tag == m.Latest {
			entry = &m.Releases[i]
			break
		}
	}
	if entry == nil {
		writeErr(w, 500, "manifest inconsistent")
		return
	}
	// apk_url 用相对路径 客户端拼自己的 base URL 兼容反向代理
	apkURL := fmt.Sprintf("/releases/%s/%s", entry.Tag, entry.Filename)
	writeJSON(w, 200, map[string]any{
		"tag":         entry.Tag,
		"filename":    entry.Filename,
		"size":        entry.Size,
		"sha256":      entry.SHA256,
		"notes":       entry.Notes,
		"apk_url":     apkURL,
		"uploaded_at": entry.UploadedAt,
	})
}

// GET /releases/<tag>/<filename>  公开静态文件
func (a *ReleaseAPI) handleDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" && r.Method != "HEAD" {
		writeErr(w, 405, "method not allowed")
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/releases/")
	rest = filepath.ToSlash(filepath.Clean(rest))
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		writeErr(w, 400, "invalid path")
		return
	}
	tag, filename := parts[0], parts[1]
	if strings.Contains(filename, "/") || strings.HasPrefix(filename, ".") {
		writeErr(w, 400, "invalid filename")
		return
	}

	full := filepath.Join(a.Dir, tag, filename)
	// 防越权
	if !strings.HasPrefix(filepath.Clean(full), filepath.Clean(a.Dir)+string(filepath.Separator)) {
		writeErr(w, 400, "invalid path")
		return
	}
	st, err := os.Stat(full)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			writeErr(w, 404, "not found")
			return
		}
		writeErr(w, 500, err.Error())
		return
	}
	if st.IsDir() {
		writeErr(w, 404, "not found")
		return
	}

	// APK 用 application/vnd.android.package-archive
	if strings.HasSuffix(filename, ".apk") {
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	}
	http.ServeFile(w, r, full)
}
