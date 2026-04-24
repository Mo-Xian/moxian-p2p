package server

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// JWTManager 签发和验证 JWT 用于 API 认证
// 秘钥从 bootstrap.yaml 或环境变量读取 启动时固定
type JWTManager struct {
	secret []byte
	ttl    time.Duration
}

// NewJWTManager secret 至少 32 字节 默认 24 小时 token
func NewJWTManager(secret string, ttl time.Duration) *JWTManager {
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	return &JWTManager{secret: []byte(secret), ttl: ttl}
}

// Claims 自定义载荷
type Claims struct {
	UserID  int64 `json:"uid"`
	IsAdmin bool  `json:"adm,omitempty"`
	jwt.RegisteredClaims
}

// Issue 为用户签发 token
func (m *JWTManager) Issue(userID int64, isAdmin bool) (string, error) {
	now := time.Now()
	c := Claims{
		UserID:  userID,
		IsAdmin: isAdmin,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(m.ttl)),
			Issuer:    "moxian-p2p",
		},
	}
	t := jwt.NewWithClaims(jwt.SigningMethodHS256, c)
	return t.SignedString(m.secret)
}

// Verify 验证 token 返回 claims
func (m *JWTManager) Verify(token string) (*Claims, error) {
	var c Claims
	_, err := jwt.ParseWithClaims(token, &c, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return m.secret, nil
	})
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// ---- HTTP 中间件 ----

type ctxKey string

const ctxKeyClaims ctxKey = "moxian_claims"

// AuthMiddleware 从 Authorization: Bearer xxx 提取 JWT 挂到 context
func (m *JWTManager) AuthMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		auth := r.Header.Get("Authorization")
		if !strings.HasPrefix(auth, "Bearer ") {
			writeErr(w, 401, "missing bearer token")
			return
		}
		tok := strings.TrimPrefix(auth, "Bearer ")
		claims, err := m.Verify(tok)
		if err != nil {
			writeErr(w, 401, "invalid token: "+err.Error())
			return
		}
		ctx := context.WithValue(r.Context(), ctxKeyClaims, claims)
		next(w, r.WithContext(ctx))
	}
}

// AdminOnly 装饰器 要求 is_admin=true
func AdminOnly(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c := ClaimsFromCtx(r.Context())
		if c == nil || !c.IsAdmin {
			writeErr(w, 403, "admin only")
			return
		}
		next(w, r)
	}
}

// ClaimsFromCtx 从 request context 拿 claims
func ClaimsFromCtx(ctx context.Context) *Claims {
	v := ctx.Value(ctxKeyClaims)
	if v == nil {
		return nil
	}
	if c, ok := v.(*Claims); ok {
		return c
	}
	return nil
}
