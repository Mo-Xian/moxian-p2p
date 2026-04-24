package server

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

// Argon2id 参数 OWASP 推荐值（2024）
const (
	argonTime    = 3
	argonMemory  = 64 * 1024 // 64 MB
	argonThreads = 4
	argonKeyLen  = 32
	argonSaltLen = 16
)

// HashPassword Argon2id 哈希密码（接收客户端传来的 PBKDF2 哈希 服务器再哈一层防数据库泄漏）
// 返回格式: $argon2id$v=19$m=65536,t=3,p=4$<salt_b64>$<hash_b64>
func HashPassword(clientPwdHash string) (string, error) {
	salt := make([]byte, argonSaltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	hash := argon2.IDKey([]byte(clientPwdHash), salt, argonTime, argonMemory, argonThreads, argonKeyLen)
	encoded := fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, argonMemory, argonTime, argonThreads,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(hash),
	)
	return encoded, nil
}

// VerifyPassword 验证密码
func VerifyPassword(clientPwdHash, encoded string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 {
		return false
	}
	if parts[1] != "argon2id" {
		return false
	}
	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil || version != argon2.Version {
		return false
	}
	var memory uint32
	var time uint32
	var threads uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &memory, &time, &threads); err != nil {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false
	}
	expected, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return false
	}
	actual := argon2.IDKey([]byte(clientPwdHash), salt, time, memory, threads, uint32(len(expected)))
	return subtle.ConstantTimeCompare(expected, actual) == 1
}

// GenerateInviteCode 生成 8 字符邀请码 简短好记（大写 + 数字 无易混字符）
func GenerateInviteCode() string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 去掉 0/O/1/I/l
	b := make([]byte, 8)
	rand.Read(b)
	out := make([]byte, 8)
	for i, v := range b {
		out[i] = alphabet[int(v)%len(alphabet)]
	}
	return string(out)
}

// ValidatePasswordStrength 简单强度校验（最少 6 位 如用户要求）
func ValidatePasswordHashFormat(hash string) error {
	// 客户端传来的 PBKDF2 hash 应为 base64 的 32 字节 = 44 字符
	if len(hash) < 40 || len(hash) > 60 {
		return errors.New("password_hash 长度异常")
	}
	if _, err := base64.StdEncoding.DecodeString(hash); err != nil {
		return errors.New("password_hash 非 base64")
	}
	return nil
}
