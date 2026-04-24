package server

import (
	"database/sql"
	"encoding/json"
	"net/http"
)

// VaultAPI 加密 vault 读写（零知识 服务器只转发不解密）
type VaultAPI struct {
	DB  *sql.DB
	JWT *JWTManager
}

func (v *VaultAPI) Register(mux *http.ServeMux) {
	mux.HandleFunc("/api/vault", v.JWT.AuthMiddleware(v.handleVault))
}

// 兼容 GET / POST 同一端点
//   GET  /api/vault                        → {encrypted_vault, version}
//   POST /api/vault {encrypted_vault, expected_version} → {version}
func (v *VaultAPI) handleVault(w http.ResponseWriter, r *http.Request) {
	c := ClaimsFromCtx(r.Context())
	if c == nil {
		writeErr(w, 401, "no claims")
		return
	}

	switch r.Method {
	case "GET":
		u, err := GetUserByID(v.DB, c.UserID)
		if err != nil {
			writeErr(w, 404, "user not found")
			return
		}
		vault := ""
		if u.EncryptedVault.Valid {
			vault = u.EncryptedVault.String
		}
		writeJSON(w, 200, map[string]any{
			"encrypted_vault": vault,
			"version":         u.VaultVersion,
		})

	case "POST":
		var body struct {
			EncryptedVault  string `json:"encrypted_vault"`
			ExpectedVersion int64  `json:"expected_version"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeErr(w, 400, "bad json")
			return
		}
		// 防意外覆盖：若客户端没传 expected 就按 0 处理
		// 实际用法：客户端登录时记 vault_version 修改后用它作为 CAS
		newVer, err := UpdateVault(v.DB, c.UserID, body.EncryptedVault, body.ExpectedVersion)
		if err != nil {
			writeErr(w, 409, err.Error())
			return
		}
		writeJSON(w, 200, map[string]any{"version": newVer})

	default:
		writeErr(w, 405, "method not allowed")
	}
}
