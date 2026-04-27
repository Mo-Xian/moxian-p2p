package server

import (
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

// User DB 模型
type User struct {
	ID             int64
	Email          string
	Username       string
	PasswordHash   string
	KDFIterations  int
	IsAdmin        bool
	EncryptedVault sql.NullString
	VaultVersion   int64
	CreatedAt      time.Time
	UpdatedAt      time.Time
}

// Node 用户的一个设备
type Node struct {
	ID          int64
	UserID      int64
	NodeID      string // user 内部唯一 如 "phone"
	VirtualIP   string
	Tags        []string
	Description string
	CreatedAt   time.Time
}

// Invite 邀请码
type Invite struct {
	Code      string
	CreatedBy int64
	UsedBy    sql.NullInt64
	UsedAt    sql.NullInt64
	ExpiresAt time.Time
	CreatedAt time.Time
}

// ---- User CRUD ----

var ErrUserExists = errors.New("user already exists")
var ErrUserNotFound = errors.New("user not found")
var ErrInvalidInvite = errors.New("邀请码无效或已过期")

// CreateUser 创建用户
// isFirstUser = 是否自动升管理员（首次注册时 true）
// inviteCode = 必填 除首次注册外
func CreateUser(db *sql.DB, email, username, clientPwdHash string, kdfIter int, inviteCode string) (*User, error) {
	if kdfIter < 100_000 {
		kdfIter = 600_000
	}
	email = strings.ToLower(strings.TrimSpace(email))
	username = strings.TrimSpace(username)
	if email == "" || username == "" {
		return nil, errors.New("email 和 username 不能为空")
	}

	tx, err := db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	// 检查是否已有用户
	var total int
	if err := tx.QueryRow("SELECT COUNT(*) FROM users").Scan(&total); err != nil {
		return nil, err
	}
	isFirst := total == 0

	// 非首次注册必须有有效邀请码
	var inviterID int64 = 0
	if !isFirst {
		var exp int64
		var usedBy sql.NullInt64
		err := tx.QueryRow(
			"SELECT created_by, expires_at, used_by FROM invites WHERE code = ?",
			strings.ToUpper(inviteCode),
		).Scan(&inviterID, &exp, &usedBy)
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrInvalidInvite
		}
		if err != nil {
			return nil, err
		}
		if usedBy.Valid {
			return nil, errors.New("邀请码已使用")
		}
		if time.Unix(exp, 0).Before(time.Now()) {
			return nil, errors.New("邀请码已过期")
		}
	}

	// 哈希密码
	pwdHashed, err := HashPassword(clientPwdHash)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	res, err := tx.Exec(`
		INSERT INTO users (email, username, password_hash, kdf_iterations, is_admin, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		email, username, pwdHashed, kdfIter, boolToInt(isFirst), now.Unix(), now.Unix(),
	)
	if err != nil {
		// sqlite 唯一约束错误信息含 "UNIQUE constraint"
		if strings.Contains(err.Error(), "UNIQUE") {
			return nil, ErrUserExists
		}
		return nil, err
	}
	userID, _ := res.LastInsertId()

	// 标记邀请码已用
	if !isFirst {
		_, _ = tx.Exec(
			"UPDATE invites SET used_by = ?, used_at = ? WHERE code = ?",
			userID, now.Unix(), strings.ToUpper(inviteCode),
		)
	}

	// 生成 P2P mesh passphrase（32 字节 base64）
	pass := make([]byte, 32)
	rand.Read(pass)
	passB64 := base64.RawStdEncoding.EncodeToString(pass)
	if _, err := tx.Exec(
		"INSERT INTO user_mesh_keys (user_id, passphrase) VALUES (?, ?)",
		userID, passB64,
	); err != nil {
		return nil, err
	}

	if err := tx.Commit(); err != nil {
		return nil, err
	}

	return &User{
		ID:            userID,
		Email:         email,
		Username:      username,
		PasswordHash:  pwdHashed,
		KDFIterations: kdfIter,
		IsAdmin:       isFirst,
		CreatedAt:     now,
		UpdatedAt:     now,
	}, nil
}

// GetUserByEmail 按 email 查用户
func GetUserByEmail(db *sql.DB, email string) (*User, error) {
	row := db.QueryRow(`
		SELECT id, email, username, password_hash, kdf_iterations, is_admin,
		       encrypted_vault, vault_version, created_at, updated_at
		FROM users WHERE email = ? COLLATE NOCASE`,
		strings.ToLower(email),
	)
	return scanUser(row)
}

// GetUserByID 按 id 查用户
func GetUserByID(db *sql.DB, id int64) (*User, error) {
	row := db.QueryRow(`
		SELECT id, email, username, password_hash, kdf_iterations, is_admin,
		       encrypted_vault, vault_version, created_at, updated_at
		FROM users WHERE id = ?`, id,
	)
	return scanUser(row)
}

func scanUser(row *sql.Row) (*User, error) {
	var u User
	var createdAt, updatedAt int64
	var isAdmin int
	err := row.Scan(&u.ID, &u.Email, &u.Username, &u.PasswordHash, &u.KDFIterations,
		&isAdmin, &u.EncryptedVault, &u.VaultVersion, &createdAt, &updatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, err
	}
	u.IsAdmin = isAdmin == 1
	u.CreatedAt = time.Unix(createdAt, 0)
	u.UpdatedAt = time.Unix(updatedAt, 0)
	return &u, nil
}

// UpdateVault 保存加密 vault（乐观并发控制）
func UpdateVault(db *sql.DB, userID int64, encryptedVault string, expectedVersion int64) (int64, error) {
	res, err := db.Exec(`
		UPDATE users SET encrypted_vault = ?, vault_version = vault_version + 1, updated_at = ?
		WHERE id = ? AND vault_version = ?`,
		encryptedVault, time.Now().Unix(), userID, expectedVersion,
	)
	if err != nil {
		return 0, err
	}
	rows, _ := res.RowsAffected()
	if rows == 0 {
		return 0, errors.New("vault 版本冲突 请先拉取最新版本再覆盖")
	}
	var v int64
	err = db.QueryRow("SELECT vault_version FROM users WHERE id = ?", userID).Scan(&v)
	return v, err
}

// GetMeshPassphrase 读取用户的 P2P mesh 共享密码
func GetMeshPassphrase(db *sql.DB, userID int64) (string, error) {
	var p string
	err := db.QueryRow("SELECT passphrase FROM user_mesh_keys WHERE user_id = ?", userID).Scan(&p)
	return p, err
}

// ResetUserPassword 管理员重置用户主密码
// 因为 vault 用旧 masterKey 加密 重置后 vault 必须清空 用户重登后从空 vault 开始
// P2P mesh passphrase 保留 不影响 P2P 功能
func ResetUserPassword(db *sql.DB, userID int64, clientPwdHash string, kdfIter int) error {
	pwdHashed, err := HashPassword(clientPwdHash)
	if err != nil {
		return err
	}
	res, err := db.Exec(`
		UPDATE users
		SET password_hash = ?, kdf_iterations = ?,
		    encrypted_vault = NULL, vault_version = 0,
		    updated_at = ?
		WHERE id = ?`,
		pwdHashed, kdfIter, time.Now().Unix(), userID,
	)
	if err != nil {
		return err
	}
	rows, _ := res.RowsAffected()
	if rows == 0 {
		return errors.New("用户不存在")
	}
	return nil
}

// ---- Invite CRUD ----

// CreateInvite 管理员生成邀请码 有效期默认 24 小时
func CreateInvite(db *sql.DB, createdBy int64, ttl time.Duration) (*Invite, error) {
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	now := time.Now()
	expires := now.Add(ttl)
	for tries := 0; tries < 5; tries++ {
		code := GenerateInviteCode()
		_, err := db.Exec(
			"INSERT INTO invites (code, created_by, expires_at, created_at) VALUES (?, ?, ?, ?)",
			code, createdBy, expires.Unix(), now.Unix(),
		)
		if err == nil {
			return &Invite{
				Code:      code,
				CreatedBy: createdBy,
				ExpiresAt: expires,
				CreatedAt: now,
			}, nil
		}
		if !strings.Contains(err.Error(), "UNIQUE") {
			return nil, err
		}
	}
	return nil, errors.New("couldn't generate unique invite after 5 tries")
}

// ListInvites 列出管理员创建的邀请码
func ListInvites(db *sql.DB) ([]Invite, error) {
	rows, err := db.Query(`
		SELECT code, created_by, used_by, used_at, expires_at, created_at
		FROM invites ORDER BY created_at DESC LIMIT 100`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Invite
	for rows.Next() {
		var inv Invite
		var exp, cr int64
		if err := rows.Scan(&inv.Code, &inv.CreatedBy, &inv.UsedBy, &inv.UsedAt, &exp, &cr); err != nil {
			return nil, err
		}
		inv.ExpiresAt = time.Unix(exp, 0)
		inv.CreatedAt = time.Unix(cr, 0)
		out = append(out, inv)
	}
	return out, nil
}

// ---- Node CRUD ----

// RegisterNode 注册新节点 自动分配 vIP
func RegisterNode(db *sql.DB, userID int64, nodeID string, tags []string, desc string) (*Node, error) {
	// 先看看是否已存在
	row := db.QueryRow(
		"SELECT id, virtual_ip FROM nodes WHERE user_id = ? AND node_id = ?",
		userID, nodeID,
	)
	var id int64
	var vip string
	if err := row.Scan(&id, &vip); err == nil {
		tagsJSON, _ := json.Marshal(tags)
		db.Exec("UPDATE nodes SET tags = ?, description = ? WHERE id = ?", string(tagsJSON), desc, id)
		return &Node{ID: id, UserID: userID, NodeID: nodeID, VirtualIP: vip, Tags: tags, Description: desc}, nil
	}

	// 分配 vIP：简单策略 10.88.0.2 ~ 10.88.255.254
	// 查询该用户已用 IP 拿下一个
	rows, _ := db.Query("SELECT virtual_ip FROM nodes WHERE user_id = ?", userID)
	used := map[string]bool{}
	for rows.Next() {
		var v string
		rows.Scan(&v)
		used[v] = true
	}
	rows.Close()

	var chosen string
	// 按用户 ID 偏移 每个用户从 10.88.<uid%256>.2 起
	subnet := int(userID % 256)
	if subnet == 0 {
		subnet = 1
	}
	for host := 2; host < 255; host++ {
		candidate := fmt.Sprintf("10.88.%d.%d", subnet, host)
		if !used[candidate] {
			chosen = candidate
			break
		}
	}
	if chosen == "" {
		return nil, errors.New("该用户子网已满（扩展到 /16 建议后续实现）")
	}

	tagsJSON, _ := json.Marshal(tags)
	now := time.Now()
	res, err := db.Exec(`
		INSERT INTO nodes (user_id, node_id, virtual_ip, tags, description, created_at)
		VALUES (?, ?, ?, ?, ?, ?)`,
		userID, nodeID, chosen, string(tagsJSON), desc, now.Unix(),
	)
	if err != nil {
		return nil, err
	}
	insID, _ := res.LastInsertId()
	return &Node{
		ID: insID, UserID: userID, NodeID: nodeID, VirtualIP: chosen,
		Tags: tags, Description: desc, CreatedAt: now,
	}, nil
}

// ListNodesByUser 列出用户所有节点
func ListNodesByUser(db *sql.DB, userID int64) ([]Node, error) {
	rows, err := db.Query(`
		SELECT id, user_id, node_id, virtual_ip, tags, description, created_at
		FROM nodes WHERE user_id = ?`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Node
	for rows.Next() {
		var n Node
		var tagsStr sql.NullString
		var created int64
		if err := rows.Scan(&n.ID, &n.UserID, &n.NodeID, &n.VirtualIP, &tagsStr, &n.Description, &created); err != nil {
			return nil, err
		}
		if tagsStr.Valid {
			_ = json.Unmarshal([]byte(tagsStr.String), &n.Tags)
		}
		n.CreatedAt = time.Unix(created, 0)
		out = append(out, n)
	}
	return out, nil
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
