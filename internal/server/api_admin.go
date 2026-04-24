package server

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"time"
)

// AdminAPI 管理员专用 端点（要求 JWT claim 中 is_admin=true）
type AdminAPI struct {
	DB  *sql.DB
	JWT *JWTManager
}

func (a *AdminAPI) Register(mux *http.ServeMux) {
	mux.HandleFunc("/api/admin/invites", a.JWT.AuthMiddleware(AdminOnly(a.handleInvites)))
	mux.HandleFunc("/api/admin/users", a.JWT.AuthMiddleware(AdminOnly(a.handleUsers)))
}

// GET /api/admin/invites → 列表
// POST /api/admin/invites {ttl_hours} → 生成新的
func (a *AdminAPI) handleInvites(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case "GET":
		list, err := ListInvites(a.DB)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		out := make([]map[string]any, 0, len(list))
		for _, inv := range list {
			m := map[string]any{
				"code":       inv.Code,
				"expires_at": inv.ExpiresAt.Unix(),
				"created_at": inv.CreatedAt.Unix(),
				"created_by": inv.CreatedBy,
			}
			if inv.UsedBy.Valid {
				m["used_by"] = inv.UsedBy.Int64
				m["used_at"] = inv.UsedAt.Int64
			}
			out = append(out, m)
		}
		writeJSON(w, 200, map[string]any{"invites": out})

	case "POST":
		claims := ClaimsFromCtx(r.Context())
		var body struct {
			TTLHours int `json:"ttl_hours"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body.TTLHours <= 0 {
			body.TTLHours = 24
		}
		inv, err := CreateInvite(a.DB, claims.UserID, time.Duration(body.TTLHours)*time.Hour)
		if err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		writeJSON(w, 201, map[string]any{
			"code":       inv.Code,
			"expires_at": inv.ExpiresAt.Unix(),
		})

	default:
		writeErr(w, 405, "method not allowed")
	}
}

// GET /api/admin/users → 用户列表
func (a *AdminAPI) handleUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeErr(w, 405, "method not allowed")
		return
	}
	rows, err := a.DB.Query(`
		SELECT id, email, username, is_admin, vault_version, created_at
		FROM users ORDER BY created_at DESC`)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	defer rows.Close()
	out := make([]map[string]any, 0)
	for rows.Next() {
		var id int64
		var email, username string
		var isAdmin int
		var vver, created int64
		if err := rows.Scan(&id, &email, &username, &isAdmin, &vver, &created); err != nil {
			continue
		}
		out = append(out, map[string]any{
			"id":            id,
			"email":         email,
			"username":      username,
			"is_admin":      isAdmin == 1,
			"vault_version": vver,
			"created_at":    created,
		})
	}
	writeJSON(w, 200, map[string]any{"users": out})
}