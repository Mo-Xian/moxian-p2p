package server

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
)

// AuthAPI 用户认证相关端点
type AuthAPI struct {
	DB  *sql.DB
	JWT *JWTManager
}

// Register 挂载路由
func (a *AuthAPI) Register(mux *http.ServeMux) {
	mux.HandleFunc("/api/auth/prelogin", a.handlePrelogin)
	mux.HandleFunc("/api/auth/register", a.handleRegister)
	mux.HandleFunc("/api/auth/login", a.handleLogin)
	mux.HandleFunc("/api/auth/me", a.JWT.AuthMiddleware(a.handleMe))
}

// ---- Prelogin ----
// 客户端拿 KDF 参数 以便派生 masterKey 再算 pwdHash
// 不暴露用户是否存在（固定返回 600k 迭代）避免账号枚举
//   POST /api/auth/prelogin {email}
//   → {kdf_iterations: 600000}
type preloginReq struct {
	Email string `json:"email"`
}
type preloginResp struct {
	KDFIterations int `json:"kdf_iterations"`
}

func (a *AuthAPI) handlePrelogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeErr(w, 405, "method not allowed")
		return
	}
	var req preloginReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, 400, "bad json")
		return
	}
	iters := 600_000
	// 如果用户存在 返回其真实 iterations（不同老用户可能迁移过）
	if u, err := GetUserByEmail(a.DB, req.Email); err == nil {
		iters = u.KDFIterations
	}
	writeJSON(w, 200, preloginResp{KDFIterations: iters})
}

// ---- Register ----
//   POST /api/auth/register
//   {email, username, password_hash, kdf_iterations, invite_code}
//   → {user_id}
type registerReq struct {
	Email         string `json:"email"`
	Username      string `json:"username"`
	PasswordHash  string `json:"password_hash"`
	KDFIterations int    `json:"kdf_iterations"`
	InviteCode    string `json:"invite_code"`
}

func (a *AuthAPI) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeErr(w, 405, "method not allowed")
		return
	}
	var req registerReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, 400, "bad json")
		return
	}

	// 简单校验
	req.Email = strings.TrimSpace(req.Email)
	req.Username = strings.TrimSpace(req.Username)
	req.InviteCode = strings.ToUpper(strings.TrimSpace(req.InviteCode))
	if req.Email == "" || req.Username == "" {
		writeErr(w, 400, "email 和 username 必填")
		return
	}
	if !strings.Contains(req.Email, "@") {
		writeErr(w, 400, "email 格式不对")
		return
	}
	if err := ValidatePasswordHashFormat(req.PasswordHash); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if req.KDFIterations < 100_000 {
		req.KDFIterations = 600_000
	}

	u, err := CreateUser(a.DB, req.Email, req.Username, req.PasswordHash, req.KDFIterations, req.InviteCode)
	if errors.Is(err, ErrUserExists) {
		writeErr(w, 409, "该 email 或 username 已被占用")
		return
	}
	if errors.Is(err, ErrInvalidInvite) || (err != nil && strings.Contains(err.Error(), "邀请码")) {
		writeErr(w, 403, err.Error())
		return
	}
	if err != nil {
		writeErr(w, 500, "内部错误: "+err.Error())
		return
	}

	writeJSON(w, 201, map[string]any{
		"user_id":   u.ID,
		"username":  u.Username,
		"is_admin":  u.IsAdmin,
		"created":   u.CreatedAt.Unix(),
	})
}

// ---- Login ----
//   POST /api/auth/login {email, password_hash}
//   → {jwt, user_id, username, is_admin, encrypted_vault, vault_version, kdf_iterations}
type loginReq struct {
	Email        string `json:"email"`
	PasswordHash string `json:"password_hash"`
}

type loginResp struct {
	JWT            string `json:"jwt"`
	UserID         int64  `json:"user_id"`
	Username       string `json:"username"`
	IsAdmin        bool   `json:"is_admin"`
	KDFIterations  int    `json:"kdf_iterations"`
	EncryptedVault string `json:"encrypted_vault,omitempty"`
	VaultVersion   int64  `json:"vault_version"`
}

func (a *AuthAPI) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeErr(w, 405, "method not allowed")
		return
	}
	var req loginReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, 400, "bad json")
		return
	}

	u, err := GetUserByEmail(a.DB, req.Email)
	if errors.Is(err, ErrUserNotFound) {
		// 故意延迟 + 通用错误 避免账号枚举
		time.Sleep(200 * time.Millisecond)
		writeErr(w, 401, "邮箱或密码错误")
		return
	}
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}

	if !VerifyPassword(req.PasswordHash, u.PasswordHash) {
		time.Sleep(200 * time.Millisecond)
		writeErr(w, 401, "邮箱或密码错误")
		return
	}

	tok, err := a.JWT.Issue(u.ID, u.IsAdmin)
	if err != nil {
		writeErr(w, 500, "issue token: "+err.Error())
		return
	}

	resp := loginResp{
		JWT:           tok,
		UserID:        u.ID,
		Username:      u.Username,
		IsAdmin:       u.IsAdmin,
		KDFIterations: u.KDFIterations,
		VaultVersion:  u.VaultVersion,
	}
	if u.EncryptedVault.Valid {
		resp.EncryptedVault = u.EncryptedVault.String
	}
	writeJSON(w, 200, resp)
}

// ---- Me ----
//   GET /api/auth/me (Bearer)
//   → {user_id, email, username, is_admin}
func (a *AuthAPI) handleMe(w http.ResponseWriter, r *http.Request) {
	c := ClaimsFromCtx(r.Context())
	if c == nil {
		writeErr(w, 401, "no claims")
		return
	}
	u, err := GetUserByID(a.DB, c.UserID)
	if err != nil {
		writeErr(w, 404, "user not found")
		return
	}
	writeJSON(w, 200, map[string]any{
		"user_id":  u.ID,
		"email":    u.Email,
		"username": u.Username,
		"is_admin": u.IsAdmin,
	})
}

// ---- 通用 helpers ----
func writeJSON(w http.ResponseWriter, code int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(body)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}
