package server

import (
	"embed"
	"io/fs"
	"net/http"
	"strings"
)

//go:embed web
var webFS embed.FS

// WebPanel 提供 / 和 /web/* 路径 单页应用
type WebPanel struct{}

func (w *WebPanel) Register(mux *http.ServeMux) {
	sub, _ := fs.Sub(webFS, "web")
	fileServer := http.FileServer(http.FS(sub))

	// 根路径返回 index.html
	mux.HandleFunc("/", func(rw http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" || r.URL.Path == "/index.html" {
			f, err := sub.Open("index.html")
			if err != nil {
				http.NotFound(rw, r); return
			}
			defer f.Close()
			rw.Header().Set("Content-Type", "text/html; charset=utf-8")
			stat, _ := f.Stat()
			http.ServeContent(rw, r, "index.html", stat.ModTime(), f.(interface {
				Seek(offset int64, whence int) (int64, error)
				Read(p []byte) (int, error)
			}))
			return
		}
		// 其他路径降级 404（不抢其他 handler 的路由）
		http.NotFound(rw, r)
	})

	// /web/app.js /web/style.css 等
	mux.Handle("/web/", http.StripPrefix("/web/", http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		// 防路径穿越
		if strings.Contains(r.URL.Path, "..") {
			http.Error(rw, "bad path", 400); return
		}
		fileServer.ServeHTTP(rw, r)
	})))
}
